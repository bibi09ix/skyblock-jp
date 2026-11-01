package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@EnabledIfEnvironmentVariable(named = "SKYBLOCK_JP_REVIEW_LIVE", matches = ".+")
class ReviewLiveTest {
    @TempDir Path dir;

    @Test
    void rechecksReviewedProblemsWithApi() throws Exception {
        Config config = Config.load(Path.of(System.getenv("SKYBLOCK_JP_REVIEW_LIVE")));
        List<Map<String, Object>> results = new ArrayList<>();
        try (var stream = getClass().getResourceAsStream("/reviewed-display-fixtures.json");
                var translator =
                        new Translator(
                                config,
                                dir.resolve("cache.json"),
                                LoggerFactory.getLogger("review-live"))) {
            var records =
                    JsonParser.parseString(
                                    new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonArray();
            for (var record : records) {
                var item = record.getAsJsonObject();
                List<String> lines = new ArrayList<>();
                item.getAsJsonArray("originalLines").forEach(v -> lines.add(v.getAsString()));
                for (var block : LoreParser.parse(lines)) {
                    String text = block.text();
                    if (!text.matches(
                            "(?is).*(landing|Permafly|AFK immunity|production by|trades and cookie|60m|bonus up to|every 2,000|per Perfect Gemstone|quadruple|ability mana cost|melee Log).*"))
                        continue;
                    if (!results.isEmpty()) Thread.sleep(9000);
                    var paragraph =
                            new StyledParagraph(
                                    lines.subList(block.start(), block.end()).stream()
                                            .map(s -> (Component) Component.literal(s))
                                            .toList());
                    String translated =
                            translator
                                    .request(paragraph.encoded(), "review=" + lines.getFirst())
                                    .get(120, TimeUnit.SECONDS);
                    assertNotNull(
                            translated,
                            lines.getFirst() + ": " + translator.error(paragraph.encoded()));
                    assertNull(TranslationDiagnostics.validate(paragraph.encoded(), translated));
                    String decoded = paragraph.decode(translated).getString();
                    if (text.contains("landing")) {
                        assertFalse(decoded.contains("着地"));
                        assertTrue(decoded.matches(".*(?:命中|当て).*"));
                    }
                    if (text.startsWith("‣")) assertTrue(decoded.contains("‣"));
                    if (text.contains("60m")) assertTrue(decoded.contains("60分"));
                    results.add(
                            Map.of(
                                    "item",
                                    lines.getFirst(),
                                    "source",
                                    text,
                                    "translation",
                                    decoded));
                    System.out.println("Reviewed " + lines.getFirst() + ": " + decoded);
                }
            }
        } finally {
            Files.writeString(
                    Path.of(System.getenv("SKYBLOCK_JP_REVIEW_OUTPUT")),
                    new GsonBuilder().setPrettyPrinting().create().toJson(results));
        }
        assertEquals(12, results.size());
    }
}
