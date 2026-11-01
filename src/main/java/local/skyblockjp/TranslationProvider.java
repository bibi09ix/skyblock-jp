package local.skyblockjp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Provider wire formats and transport only; it owns no queue, cache or dialogue history. */
final class TranslationProvider implements AutoCloseable {
    /** Immutable request context prevents retries or adjacent speakers from sharing mutable fields. */
    record Context(String speaker, String previousDialogue, boolean playerChat, String retryHint) {
        Context {
            previousDialogue = previousDialogue == null ? "" : previousDialogue;
            retryHint = retryHint == null ? "" : retryHint;
        }
    }

    record Reply(String text, String details) {}

    record Response(int status, String body) {}

    @FunctionalInterface
    interface Exchange {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }

    static final class Failure extends Exception {
        final boolean retryable;
        final String details;

        Failure(String message, boolean retryable, String details) {
            super(message);
            this.retryable = retryable;
            this.details = details;
        }
    }

    private final Config config;
    private final HttpClient client;
    private final Exchange exchange;

    TranslationProvider(Config config) {
        this.config = config;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        exchange =
                request -> {
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    return new Response(response.statusCode(), response.body());
                };
    }

    TranslationProvider(Config config, Exchange exchange) {
        this.config = config;
        this.exchange = exchange;
        client = null;
    }

    Reply translate(String text, Context context, BooleanSupplier available) throws Exception {
        if (!available.getAsBoolean()) throw new CancellationException("API translation disabled");
        HttpRequest request = request(text, context);
        if (!available.getAsBoolean()) throw new CancellationException("API translation disabled");
        Response response;
        try {
            response = exchange.send(request);
        } catch (IOException e) {
            throw new Failure("network timeout/error", true, "");
        }
        return parse(response);
    }

    HttpRequest request(String text, Context context) throws Failure {
        if (!config.provider.equals("GROQ")) {
            return HttpRequest.newBuilder(
                            URI.create(
                                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ja&dt=t&q="
                                            + URLEncoder.encode(text, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
        }
        if (config.groqApiKey == null || config.groqApiKey.isBlank())
            throw new Failure("missing Groq key", false, "");
        var body = new JsonObject();
        body.addProperty("model", config.groqModel);
        body.addProperty("temperature", 0.1);
        body.addProperty("max_tokens", 2400);
        if (config.groqModel.startsWith("openai/gpt-oss"))
            body.addProperty("reasoning_effort", "low");
        var system = new JsonObject();
        system.addProperty("role", "system");
        var prompt =
                context.speaker() == null
                        ? TranslationPrompts.ITEM
                        : context.playerChat() ? TranslationPrompts.CHAT : TranslationPrompts.NPC;
        system.addProperty("content", prompt.text() + context.retryHint());
        var user = new JsonObject();
        user.addProperty("role", "user");
        if (context.speaker() == null) {
            user.addProperty("content", TranslationInput.clarify(text));
        } else {
            var dialogue = new JsonObject();
            dialogue.addProperty("speaker", context.speaker());
            dialogue.addProperty("previousDialogue", context.previousDialogue());
            dialogue.addProperty("dialogue", text);
            user.addProperty("content", dialogue.toString());
        }
        var messages = new JsonArray();
        messages.add(system);
        messages.add(user);
        body.add("messages", messages);
        return HttpRequest.newBuilder(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Authorization", "Bearer " + config.groqApiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(25))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private Reply parse(Response response) throws Failure {
        String details = "HTTP " + response.status();
        if (response.status() != 200) {
            details +=
                    "; body="
                            + response.body()
                                    .substring(0, Math.min(4000, response.body().length()));
            throw new Failure(
                    "HTTP " + response.status(),
                    response.status() == 429 || response.status() >= 500,
                    details);
        }
        com.google.gson.JsonElement json;
        try {
            json = JsonParser.parseString(response.body());
        } catch (com.google.gson.JsonParseException invalid) {
            throw malformedResponse(response.status(), "JSON_PARSE");
        }
        try {
            if (config.provider.equals("GROQ")) {
                var choice =
                        json.getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject();
                details += "; finish_reason=" + choice.get("finish_reason");
                var content = choice.getAsJsonObject("message").get("content");
                if (choice.has("finish_reason")
                        && "length".equals(choice.get("finish_reason").getAsString())) {
                    details += "; partial=" + (content == null ? "null" : content.toString());
                    throw new Failure("OUTPUT_TRUNCATED: 出力上限で訳文が途切れました", true, details);
                }
                return new Reply(
                        content == null || content.isJsonNull() ? null : content.getAsString(),
                        details);
            }
            var out = new StringBuilder();
            for (var part : json.getAsJsonArray().get(0).getAsJsonArray()) {
                if (part.isJsonArray() && !part.getAsJsonArray().get(0).isJsonNull())
                    out.append(part.getAsJsonArray().get(0).getAsString());
            }
            return new Reply(out.toString(), details);
        } catch (IllegalStateException
                | IndexOutOfBoundsException
                | UnsupportedOperationException
                | NullPointerException invalid) {
            // Provider payloads and exception messages may contain the submitted conversation.
            // Preserve only HTTP status and a bounded parser category, even if partial parsing
            // already read an untrusted finish_reason.
            throw malformedResponse(response.status(), "RESPONSE_SHAPE");
        }
    }

    private static Failure malformedResponse(int status, String category) {
        return new Failure(
                "INVALID_PROVIDER_RESPONSE", false, "HTTP " + status + "; parse=" + category);
    }

    @Override
    public void close() {
        if (client != null) client.shutdownNow();
    }
}
