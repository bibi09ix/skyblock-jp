package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TranslationProviderTest {

    @Test
    void malformedSuccessfulResponsesRetainOnlyStatusAndABoundedParseCategory() throws Exception {
        for (String body :
                List.of(
                        "{\"private\":\"PRIVATE_PARSE_CANARY\",\"choices\":[",
                        "{\"private\":\"PRIVATE_PARSE_CANARY\",\"choices\":[]}",
                        "\"PRIVATE_PARSE_CANARY\"",
                        "{\"choices\":[{\"finish_reason\":\"PRIVATE_PARSE_CANARY\",\"message\":{\"content\":[\"PRIVATE_PARSE_CANARY\",\"gsk_fake_parse_secret\"]}}]}")) {
            var calls = new AtomicInteger();
            try (var provider =
                    new TranslationProvider(
                            config(),
                            request -> {
                                calls.incrementAndGet();
                                return new TranslationProvider.Response(200, body);
                            })) {
                var failure =
                        assertThrows(
                                TranslationProvider.Failure.class,
                                () ->
                                        provider.translate(
                                                "PRIVATE_REQUEST_CANARY",
                                                new TranslationProvider.Context(
                                                        "Bob",
                                                        "PRIVATE_PREVIOUS_CANARY",
                                                        false,
                                                        ""),
                                                () -> true));
                assertEquals("INVALID_PROVIDER_RESPONSE", failure.getMessage());
                assertFalse(
                        failure.retryable,
                        "Preserve the previous no-retry policy for malformed HTTP 200 responses");
                assertTrue(
                        List.of("HTTP 200; parse=JSON_PARSE", "HTTP 200; parse=RESPONSE_SHAPE")
                                .contains(failure.details));
                for (String secret :
                        List.of(
                                "PRIVATE_PARSE_CANARY",
                                "PRIVATE_REQUEST_CANARY",
                                "PRIVATE_PREVIOUS_CANARY",
                                "gsk_fake_parse_secret"))
                    assertFalse(failure.details.contains(secret), secret);
                assertEquals(1, calls.get());
            }
        }
        var google = config();
        google.provider = "GOOGLE";
        try (var provider =
                new TranslationProvider(
                        google,
                        request ->
                                new TranslationProvider.Response(
                                        200, "{\"private\":\"PRIVATE_PARSE_CANARY\"}"))) {
            var failure =
                    assertThrows(
                            TranslationProvider.Failure.class,
                            () ->
                                    provider.translate(
                                            "PRIVATE_REQUEST_CANARY",
                                            new TranslationProvider.Context(null, "", false, ""),
                                            () -> true));
            assertEquals("HTTP 200; parse=RESPONSE_SHAPE", failure.details);
            assertEquals("INVALID_PROVIDER_RESPONSE", failure.getMessage());
        }
    }

    private static Config config() {
        var config = new Config();
        config.groqApiKey = "gsk_fake_provider_test_key";
        return config;
    }

    private static String body(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        var bytes = new ByteArrayOutputStream();
        request.bodyPublisher()
                .orElseThrow()
                .subscribe(
                        new Flow.Subscriber<ByteBuffer>() {
                            @Override
                            public void onSubscribe(Flow.Subscription subscription) {
                                subscription.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(ByteBuffer buffer) {
                                byte[] part = new byte[buffer.remaining()];
                                buffer.get(part);
                                bytes.writeBytes(part);
                            }

                            @Override
                            public void onError(Throwable error) {
                                result.completeExceptionally(error);
                            }

                            @Override
                            public void onComplete() {
                                result.complete(bytes.toString(StandardCharsets.UTF_8));
                            }
                        });
        return result.get(2, TimeUnit.SECONDS);
    }

    private static JsonObject payload(HttpRequest request) throws Exception {
        return JsonParser.parseString(body(request)).getAsJsonObject();
    }

    @Test
    void versionedPromptsAreByteForByteEquivalentToThe0712JavaLiterals() {
        assertEquals(
                "5011bfbdca31ac9404bf88ac8e101e9670815e9a0bfcbe95b6de2c93374fca55",
                TranslationFingerprint.sha256(TranslationPrompts.ITEM.text()));
        assertEquals(
                "1d5358f4131afd51e3749f0739d8bfe2a1a5446f6ea94bfd1d2419e3dbb91b84",
                TranslationFingerprint.sha256(TranslationPrompts.NPC.text()));
        assertEquals(
                "28ac6c40481dcf153775533f51f4dc77de5a4299be9b9edcad76fd5bcebf0b3b",
                TranslationFingerprint.sha256(TranslationPrompts.CHAT.text()));
        assertEquals(TranslationPrompts.NPC.text(), NpcTranslation.PROMPT);
        assertEquals(TranslationPrompts.CHAT.text(), NpcTranslation.CHAT_PROMPT);
    }

    @Test
    void itemRequestKeepsModelOptionsHeadersAndCompletePrompt() throws Exception {
        try (var provider =
                new TranslationProvider(
                        config(),
                        request -> {
                            throw new AssertionError("No network");
                        })) {
            var request =
                    provider.request(
                            "Gain <n0/> Health.",
                            new TranslationProvider.Context(null, "", false, " retry"));
            assertEquals(
                    "https://api.groq.com/openai/v1/chat/completions", request.uri().toString());
            assertEquals(
                    "Bearer gsk_fake_provider_test_key",
                    request.headers().firstValue("Authorization").orElseThrow());
            var json = payload(request);
            assertEquals("openai/gpt-oss-120b", json.get("model").getAsString());
            assertEquals("low", json.get("reasoning_effort").getAsString());
            assertEquals(2400, json.get("max_tokens").getAsInt());
            var messages = json.getAsJsonArray("messages");
            assertEquals(
                    TranslationPrompts.ITEM.text() + " retry",
                    messages.get(0).getAsJsonObject().get("content").getAsString());
            assertEquals(
                    TranslationInput.clarify("Gain <n0/> Health."),
                    messages.get(1).getAsJsonObject().get("content").getAsString());
        }
    }

    @Test
    void dialogueContextAndRetryHintsCannotLeakIntoTheNextSpeakerOrItem() throws Exception {
        try (var provider =
                new TranslationProvider(
                        config(),
                        request -> {
                            throw new AssertionError("No network");
                        })) {
            var first =
                    payload(
                            provider.request(
                                    "Hello",
                                    new TranslationProvider.Context(
                                            "Bob", "Earlier", false, " retry NPC")));
            var chat =
                    payload(
                            provider.request(
                                    "Hello",
                                    new TranslationProvider.Context("CHAT", "Other", true, "")));
            var item =
                    payload(
                            provider.request(
                                    "Hello", new TranslationProvider.Context(null, "", false, "")));
            var npcMessages = first.getAsJsonArray("messages");
            assertEquals(
                    TranslationPrompts.NPC.text() + " retry NPC",
                    npcMessages.get(0).getAsJsonObject().get("content").getAsString());
            var npc =
                    JsonParser.parseString(
                                    npcMessages
                                            .get(1)
                                            .getAsJsonObject()
                                            .get("content")
                                            .getAsString())
                            .getAsJsonObject();
            assertEquals("Bob", npc.get("speaker").getAsString());
            assertEquals("Earlier", npc.get("previousDialogue").getAsString());
            assertEquals("Hello", npc.get("dialogue").getAsString());
            assertEquals(
                    TranslationPrompts.CHAT.text(),
                    chat.getAsJsonArray("messages")
                            .get(0)
                            .getAsJsonObject()
                            .get("content")
                            .getAsString());
            assertEquals(
                    TranslationPrompts.ITEM.text(),
                    item.getAsJsonArray("messages")
                            .get(0)
                            .getAsJsonObject()
                            .get("content")
                            .getAsString());
            assertFalse(item.toString().contains("Earlier"));
            assertFalse(chat.toString().contains("retry NPC"));
        }
    }

    @Test
    void googleRequestPreservesTextAndJoinsSegmentedResponses() throws Exception {
        var config = config();
        config.provider = "GOOGLE";
        try (var provider =
                new TranslationProvider(
                        config,
                        request -> {
                            assertEquals("GET", request.method());
                            assertFalse(request.headers().firstValue("Authorization").isPresent());
                            assertTrue(
                                    URLDecoder.decode(
                                                    request.uri().getRawQuery(),
                                                    StandardCharsets.UTF_8)
                                            .endsWith("q=A + 20% & <n0/>"));
                            return new TranslationProvider.Response(
                                    200, "[[[\"前半\",\"a\"],[\"後半\",\"b\"]]]");
                        })) {
            assertEquals(
                    "前半後半",
                    provider.translate(
                                    "A + 20% & <n0/>",
                                    new TranslationProvider.Context(null, "", false, ""),
                                    () -> true)
                            .text());
        }
    }

    @Test
    void cancelledRequestsNeverReachTheTransport() throws Exception {
        var calls = new AtomicInteger();
        var availabilityChecks = new AtomicInteger();
        try (var provider =
                new TranslationProvider(
                        config(),
                        request -> {
                            calls.incrementAndGet();
                            return null;
                        })) {
            assertThrows(
                    CancellationException.class,
                    () ->
                            provider.translate(
                                    "Hello",
                                    new TranslationProvider.Context("Bob", "", false, ""),
                                    () -> availabilityChecks.incrementAndGet() == 1));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void providerFailuresRetainRetryPolicyAndBoundedResponseDetails() throws Exception {
        for (int status : new int[] {401, 429, 500}) {
            try (var provider =
                    new TranslationProvider(
                            config(),
                            request ->
                                    new TranslationProvider.Response(status, "x".repeat(5000)))) {
                var failure =
                        assertThrows(
                                TranslationProvider.Failure.class,
                                () ->
                                        provider.translate(
                                                "Hello",
                                                new TranslationProvider.Context(
                                                        null, "", false, ""),
                                                () -> true));
                assertEquals("HTTP " + status, failure.getMessage());
                assertEquals(status != 401, failure.retryable);
                assertTrue(failure.details.length() < 4050);
            }
        }
        try (var provider =
                new TranslationProvider(
                        config(),
                        request ->
                                new TranslationProvider.Response(
                                        200,
                                        "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"途中\"}}]}"))) {
            var failure =
                    assertThrows(
                            TranslationProvider.Failure.class,
                            () ->
                                    provider.translate(
                                            "Hello",
                                            new TranslationProvider.Context(null, "", false, ""),
                                            () -> true));
            assertTrue(failure.retryable);
            assertTrue(failure.getMessage().startsWith("OUTPUT_TRUNCATED"));
            assertTrue(failure.details.contains("途中"));
        }
        try (var provider =
                new TranslationProvider(
                        config(),
                        request -> {
                            throw new IOException("private details");
                        })) {
            var failure =
                    assertThrows(
                            TranslationProvider.Failure.class,
                            () ->
                                    provider.translate(
                                            "Hello",
                                            new TranslationProvider.Context(null, "", false, ""),
                                            () -> true));
            assertEquals("network timeout/error", failure.getMessage());
            assertFalse(failure.details.contains("private details"));
        }
    }
}
