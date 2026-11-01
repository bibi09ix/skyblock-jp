package local.skyblockjp;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Structural coverage over all observed original tooltips; location is explicitly assumed. */
class TooltipPipelineTraceReplayTest {
    @Test void all147RecordedTooltipsProduceCompleteNonOverlappingSourceLineTraces() throws Exception {
        var resource = getClass().getResourceAsStream("/tooltip-classification-0.7.3-session.json");
        assertNotNull(resource);
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            var fixture = JsonParser.parseReader(reader).getAsJsonObject();
            assertTrue(fixture.get("provenance").getAsString().contains("assumed CONTAINER"));
            var tooltips = fixture.getAsJsonArray("tooltips");
            assertEquals(147, tooltips.size());
            int checkedLines = 0;
            for (var value : tooltips) {
                var recorded = value.getAsJsonObject();
                var lines = recorded.getAsJsonArray("originalLines").asList().stream().map(x -> x.getAsString()).toList();
                String item = recorded.get("item").getAsString();
                var evidence = new TooltipContext.Evidence("fixture.AssumedContainerScreen", recorded.get("screenTitle").getAsString(),
                    TooltipContext.Location.CONTAINER, true, "", "", 13, 90);
                var context = TooltipContext.analyze(evidence, lines);
                var result = LoreParser.analyze(lines, context);
                assertEquals(lines.size(), result.lines().size(), item);
                boolean[] covered = new boolean[lines.size()];
                for (int p = 0; p < result.paragraphs().size(); p++) {
                    var paragraph = result.paragraphs().get(p);
                    var block = paragraph.block();
                    assertTrue(List.of("item", "ui").contains(paragraph.channel()), item);
                    assertTrue(block.start() >= 0 && block.end() > block.start() && block.end() <= lines.size(), item);
                    String expected = String.join(" ", lines.subList(block.start(), block.end()).stream().map(LoreParser::clean).toList()).replaceAll("\\s+", " ");
                    assertEquals(expected, block.text(), item);
                    for (int i = block.start(); i < block.end(); i++) {
                        assertFalse(covered[i], item + " duplicate source row " + i);
                        covered[i] = true;
                        var trace = result.lines().get(i);
                        assertEquals(p, trace.paragraphIndex(), item + " paragraph membership row " + i);
                        assertTrue(List.of("TRANSLATE", "JOIN").contains(trace.action()), item + " non-prose leaked into paragraph at " + i);
                        assertFalse(List.of("STAT", "STAT_LABEL", "COST_VALUE", "DEBUG", "RARITY", "SEPARATOR", "ITEM_NAME", "PROGRESS", "DURATION_OR_COST").contains(trace.role()),
                            item + " protected role leaked into paragraph: " + trace.role());
                    }
                }
                for (int i = 0; i < lines.size(); i++) {
                    var trace = result.lines().get(i);
                    assertEquals(i, trace.index(), item);
                    assertEquals(LoreParser.clean(lines.get(i)), trace.source(), item);
                    assertFalse(trace.reason().isBlank(), item + " unexplained source row " + i);
                    if (!covered[i]) {
                        assertEquals("KEEP", trace.action(), item + " lost source row " + i);
                        assertEquals(-1, trace.paragraphIndex(), item);
                    }
                }
                checkedLines += lines.size();
            }
            assertTrue(checkedLines > 1_000, "replay should cover the entire observed session, not a few cherry-picked items");
        }
    }
}
