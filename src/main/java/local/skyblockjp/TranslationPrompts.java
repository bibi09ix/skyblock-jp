package local.skyblockjp;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Versioned provider instructions. Segment boundaries do not change the submitted prompt. */
final class TranslationPrompts {
    record Prompt(String id, String text) {}

    static final Prompt ITEM = load("item-v1");
    static final Prompt NPC = load("npc-v1");
    static final Prompt CHAT = load("chat-v1");

    private TranslationPrompts() {}

    private static Prompt load(String id) {
        String path = "/assets/skyblock_jp/prompts/" + id + ".json";
        try (var input = TranslationPrompts.class.getResourceAsStream(path)) {
            if (input == null)
                throw new IllegalStateException("Missing bundled translation prompt: " + id);
            var document =
                    JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!id.equals(document.get("id").getAsString()))
                throw new IllegalStateException("Mismatched bundled translation prompt: " + id);
            var text = new StringBuilder();
            for (var segment : document.getAsJsonArray("segments"))
                text.append(segment.getAsString());
            if (text.isEmpty())
                throw new IllegalStateException("Empty bundled translation prompt: " + id);
            return new Prompt(id, text.toString());
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid bundled translation prompt: " + id, e);
        }
    }
}
