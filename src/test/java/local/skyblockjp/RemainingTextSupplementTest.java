package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** Frozen authored targets, scoped lookups and production rendering; no API. */
class RemainingTextSupplementTest {
    @Test
    void allReviewedSupplementPairsResolveAndRenderWithTheirAuthoredNamesAndValues()
            throws Exception {
        var dictionary = BundledDictionary.loadBundled();
        assertTrue(dictionary.stats().loaded(), dictionary.stats().error());
        try (var input =
                getClass().getResourceAsStream("/remaining-text-supplements-0.7.10.json")) {
            assertNotNull(input);
            var rows =
                    JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonArray();
            assertEquals(65, rows.size());
            for (var raw : rows) {
                var row = raw.getAsJsonObject();
                String source = row.get("source").getAsString(),
                        target = row.get("target").getAsString();
                String channel = row.get("channel").getAsString(),
                        id = row.get("source_id").getAsString();
                var match =
                        dictionary
                                .lookup(channel, source, "")
                                .orElseThrow(() -> new AssertionError(channel + ": " + source));
                assertEquals(target, match.text(), source);
                assertEquals(row.get("keep_original").getAsBoolean(), match.keepOriginal(), source);
                assertTrue(match.sourceIds().contains(id), source);
                var paragraph =
                        new StyledParagraph(
                                List.of(Component.literal(source).withStyle(ChatFormatting.GRAY)));
                var encoded = paragraph.dictionaryEncoding(match, false);
                assertTrue(
                        encoded.accepted(),
                        source + ": " + encoded.reason() + " " + encoded.details());
                assertEquals(target, paragraph.decode(encoded.text()).getString(), source);
            }
        }
    }
}
