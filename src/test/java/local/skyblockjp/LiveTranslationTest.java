package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Opt-in: uses the saved provider and consumes its normal API quota. */
@EnabledIfEnvironmentVariable(named = "SKYBLOCK_JP_LIVE_CONFIG", matches = ".+")
class LiveTranslationTest {
    @TempDir Path dir;

    @Test
    void recentProblemItemsTranslateWithRealProvider() throws Exception {
        Config config = Config.load(Path.of(System.getenv("SKYBLOCK_JP_LIVE_CONFIG")));
        try (var stream = getClass().getResourceAsStream("/recent-translation-sources.json");
                var translator =
                        new Translator(
                                config,
                                dir.resolve("cache.json"),
                                LoggerFactory.getLogger("live-test"))) {
            var fixtures =
                    JsonParser.parseString(
                                    new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonArray();
            int paragraphs = 0;
            for (var fixture : fixtures) {
                var record = fixture.getAsJsonObject();
                String item = record.get("item").getAsString(),
                        source = record.get("source").getAsString();
                if (record.has("original")) {
                    String original = record.get("original").getAsString();
                    if (LoreParser.protectedLine(original)) continue;
                    var paragraph =
                            new StyledParagraph(
                                    java.util.List.of(
                                            net.minecraft.network.chat.Component.literal(
                                                    original)));
                    String result =
                            translator
                                    .request(paragraph.encoded(), "live regression: " + item)
                                    .get(100, TimeUnit.SECONDS);
                    assertNotNull(result, item + ": " + translator.error(paragraph.encoded()));
                    assertNull(TranslationDiagnostics.validate(paragraph.encoded(), result), item);
                    assertFalse(paragraph.decode(result).getString().isBlank());
                    paragraphs++;
                    continue;
                }
                if (item.contains("Dragon Horn")) {
                    assertTrue(LoreParser.protectedLine("Renowned (Legendary):"));
                    continue;
                }
                // Same bullet boundaries as LoreParser, retaining complete style pairs.
                for (String part : source.split("(?=<s\\d+>‣ </s\\d+>)")) {
                    if (part.isBlank()) continue;
                    String result =
                            translator
                                    .request(part, "live regression: " + item)
                                    .get(100, TimeUnit.SECONDS);
                    assertNotNull(result, item + ": " + translator.error(part));
                    assertNull(TranslationDiagnostics.validate(part, result), item);
                    paragraphs++;
                }
            }
            System.out.println(
                    "Live regression succeeded: "
                            + paragraphs
                            + " paragraphs; protected metadata excluded.");
        }
    }
}
