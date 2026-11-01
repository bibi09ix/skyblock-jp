package local.skyblockjp;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The stat breakdown structure must not absorb labels or numeric tables into its prose. */
class TooltipTotalsAndTrailersTest {
    private static LoreParser.Analysis item(String... lines) {
        return LoreParser.analyze(List.of(lines), new TooltipContext.Decision(TooltipContext.Kind.ITEM, "fixture", Map.of()));
    }
    private static List<String> paragraphs(LoreParser.Analysis analysis) {
        return analysis.paragraphs().stream().map(p -> p.block().text()).toList();
    }

    @Test void actualFlatBonusTooltipKeepsStatLabelCountedTrailerAndTotal() {
        var lines = List.of("\uE010 Flat Bonuses", "Health Stat", "All of these are summed together", "into a base amount.", "",
            " +1,475\uE010 SkyBlock Level", " +283\uE010 Pure Shark Scale Chestplate", " +279\uE010 Pure Moogma Leggings",
            " +233\uE010 Pure Shark Scale Helmet", " +229\uE010 Pure Slug Boots", " +200\uE010 Bestiary", " +122\uE010 Farming Skill",
            "  And 15 more...", "", "Adds up to: +3,266.5\uE010 Health", "", "Click to dig deeper!",
            "minecraft:paper", "13個のコンポーネント", "Minecraft");
        var context = TooltipContext.analyze(new TooltipContext.Evidence("fixture.ContainerScreen", "Stats ➜ Health",
            TooltipContext.Location.CONTAINER, true, "", "minecraft:paper", 10, 90), lines);
        assertEquals(TooltipContext.Kind.UI, context.kind());
        var result = LoreParser.analyze(lines, context);
        assertEquals(List.of("\uE010 Flat Bonuses", "All of these are summed together into a base amount.", "Click to dig deeper!"), paragraphs(result));
        assertEquals("STAT_LABEL", result.lines().get(1).role());
        assertEquals("STAT_TYPE_LABEL", result.lines().get(1).reason());
        assertEquals("STAT_TRAILER", result.lines().get(12).role());
        assertEquals("STAT_LIST_TRAILER", result.lines().get(12).reason());
        assertEquals("STAT_TOTAL_ROW", result.lines().get(14).reason());
        assertEquals("Adds up to: +3,266.5\uE010 Health", result.lines().get(14).source());
        for (int index : List.of(1, 5, 6, 7, 8, 9, 10, 11, 12, 14)) {
            assertEquals("KEEP", result.lines().get(index).action(), "row " + index);
            assertEquals(-1, result.lines().get(index).paragraphIndex());
        }
    }

    @Test void countedMoreRequiresImmediateNumericStatContextAndACompleteLabel() {
        var summary = item("Item", "Health: +100", "And 1,500 more...");
        assertTrue(summary.paragraphs().isEmpty());
        assertEquals("STAT_LIST_TRAILER", summary.lines().get(2).reason());

        var normal = item("Item", "Collect ores, rare drops,", "and 15 more...");
        assertEquals(List.of("Collect ores, rare drops, and 15 more..."), paragraphs(normal));
        assertEquals("JOIN", normal.lines().get(2).action());

        var explanation = item("Item", "Health: +100", "And 15 more enemies will attack you.");
        assertEquals(List.of("And 15 more enemies will attack you."), paragraphs(explanation));

        var newParagraph = item("Item", "Health: +100", "", "And 15 more...");
        assertEquals(List.of("And 15 more..."), paragraphs(newParagraph));
    }

    @Test void statSuffixIsLimitedToCompleteKnownStatLabels() {
        for (String name : List.of("Health", "Defense", "Mining Speed", "Charm Chance", "Effective Health")) {
            var result = item("Item", "\uE010 " + name + " Stat", "All of these are summed together.");
            assertEquals("STAT_TYPE_LABEL", result.lines().get(1).reason(), name);
            assertEquals(List.of("All of these are summed together."), paragraphs(result), name);
        }
        var prose = item("Item", "Health Stat increases when you gain levels.");
        assertEquals(List.of("Health Stat increases when you gain levels."), paragraphs(prose));
        var unknown = item("Item", "Curious Reward Stat");
        assertEquals(List.of("Curious Reward Stat"), paragraphs(unknown));
    }

    @Test void explicitTotalsRequireEntireNumericPayloadWithoutEffectsOrConditions() {
        for (String total : List.of("Adds up to: +3,266.5\uE010 Health", "Total: +25% Mining Fortune", "Sum: 12.5s",
                "Combined Total: +100 Defense", "Total Bonuses: +15 New Metric")) {
            var result = item("Item", total);
            assertTrue(result.paragraphs().isEmpty(), total);
            assertEquals("STAT_TOTAL_ROW", result.lines().get(1).reason(), total);
            assertEquals(total, result.lines().get(1).source());
        }
        for (String prose : List.of("Adds up to: +5 Health while you are in water.",
                "Adds up to: +10% extra damage per duplicate,", "This ability adds up to 5 Health per hit.",
                "Total: +100 Defense when you are near enemies.")) {
            assertEquals(List.of(prose), paragraphs(item("Item", prose)), prose);
        }
    }
}
