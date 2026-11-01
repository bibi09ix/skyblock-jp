package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Additional cases discovered in the 0.7.4 client QA session 091678e8. */
class LiveStatsMenuClassificationRegressionTest {
    private static TooltipContext.Evidence evidence(String title, String itemId, String carrier) {
        return new TooltipContext.Evidence(
                "net.minecraft.client.gui.screens.inventory.ContainerScreen",
                title,
                TooltipContext.Location.CONTAINER,
                true,
                itemId,
                carrier,
                10,
                90);
    }

    private static LoreParser.Analysis analyze(String screen, List<String> lines) {
        return LoreParser.analyze(
                lines,
                TooltipContext.analyze(evidence(screen, "", "minecraft:player_head"), lines));
    }

    private static List<String> texts(LoreParser.Analysis analysis) {
        return analysis.paragraphs().stream().map(p -> p.block().text()).toList();
    }

    @Test
    void liveMiningStatsKeepsEveryNumericStatIncludingNewStatFamilies() {
        var lines =
                List.of(
                        "Mining Stats",
                        "Stats that influence what you can",
                        "break, how quickly you can break it,",
                        "and how many drops you receive",
                        "when mining.",
                        "",
                        " \uE015 Mining Speed 3,292",
                        " \uE016 Mining Spread 150",
                        " \uE01C Pristine 0.7",
                        " \uE053 Mining Fortune 673.12",
                        " \uE053 Ore Fortune 110",
                        " \uE053 Block Fortune 22",
                        " \uE053 Gemstone Fortune 190",
                        "",
                        "Click for details!",
                        "minecraft:stone_pickaxe",
                        "19個のコンポーネント",
                        "Minecraft");
        var analysis = analyze("Stats & Equipment", lines);
        assertEquals(
                List.of(
                        "Mining Stats",
                        "Stats that influence what you can break, how quickly you can break it, and how many drops you receive when mining.",
                        "Click for details!"),
                texts(analysis));
        for (int i = 6; i <= 12; i++) {
            assertEquals("KEEP", analysis.lines().get(i).action(), lines.get(i));
            assertEquals(-1, analysis.lines().get(i).paragraphIndex(), lines.get(i));
        }
    }

    @Test
    void statListEllipsisIsMetadataButAnOrdinaryProseContinuationIsKept() {
        var stats =
                List.of(
                        "Stats & Equipment",
                        "View your equipment, stats,",
                        "achievements, and more!",
                        "",
                        " \uE022 Speed 400",
                        " \uE00D Strength 278.75",
                        " \uE008 Defense 1,092.01",
                        " \uE007 Crit Damage 184%",
                        " \uE02C Crit Chance 117%",
                        " \uE010 Health 3,266.5",
                        " \uE003 Intelligence 1,348.84",
                        " and more...",
                        "",
                        "Also accessible via /stats",
                        "",
                        "Click to view!",
                        "minecraft:player_head");
        var analysis = analyze("SkyBlock Menu", stats);
        assertEquals(
                List.of(
                        "Stats & Equipment",
                        "View your equipment, stats, achievements, and more!",
                        "Also accessible via /stats",
                        "Click to view!"),
                texts(analysis));
        assertEquals("KEEP", analysis.lines().get(11).action());

        var prose =
                List.of("A Curious Bag", "Collect ores, rare drops,", "and more...", "", "RARE");
        var context =
                TooltipContext.analyze(
                        evidence("Storage", "CURIOUS_BAG", "minecraft:player_head"), prose);
        var proseAnalysis = LoreParser.analyze(prose, context);
        assertEquals(List.of("Collect ores, rare drops, and more..."), texts(proseAnalysis));
        assertEquals("JOIN", proseAnalysis.lines().get(2).action());
    }

    @Test
    void nestedStatBreakdownMenusAreRecognizedByTheirStructureAcrossStatFamilies() {
        for (String stat : List.of("Health", "Defense", "Mining Speed", "Charm Chance")) {
            String title = "(1/2) " + stat + " \u279C Flat Bonuses";
            var lines =
                    List.of(
                            "\uE010 SkyBlock Level",
                            "SkyBlock Levels",
                            "",
                            "Value: +1,475\uE010",
                            "",
                            "Stats from leveling up your SkyBlock",
                            "level.",
                            "minecraft:player_head",
                            "15個のコンポーネント",
                            "Minecraft");
            var context =
                    TooltipContext.analyze(evidence(title, "", "minecraft:player_head"), lines);
            assertEquals(TooltipContext.Kind.UI, context.kind(), title);
            var analysis = LoreParser.analyze(lines, context);
            assertTrue(
                    analysis.paragraphs().stream()
                            .anyMatch(p -> p.block().start() == 0 && p.channel().equals("ui")),
                    title);
            assertTrue(
                    texts(analysis).contains("Stats from leveling up your SkyBlock level."), title);
            assertEquals(
                    "KEEP",
                    analysis.lines().get(3).action(),
                    "the numeric Value row must remain original");
        }
    }

    @Test
    void nestedStatMenuDoesNotOverrideRealItemIdentity() {
        var lines =
                List.of(
                        "Health Talisman",
                        "Health: +3",
                        "Increases your survivability.",
                        "COMMON ACCESSORY");
        var context =
                TooltipContext.analyze(
                        evidence(
                                "(1/2) Health \u279C Flat Bonuses",
                                "HEALTH_TALISMAN",
                                "minecraft:player_head"),
                        lines);
        assertEquals(TooltipContext.Kind.ITEM, context.kind());
        var analysis = LoreParser.analyze(lines, context);
        assertEquals("KEEP", analysis.lines().getFirst().action());
        assertEquals(List.of("Increases your survivability."), texts(analysis));
        assertEquals("item", analysis.paragraphs().getFirst().channel());
    }

    @Test
    void newGlyphMetricNamesRemainValuesWithoutMaskingEnglishEffectSentences() {
        var lines =
                List.of(
                        "New Gathering Stats",
                        "\uE055 New Gathering Fortune 12.5",
                        "\uE056 Crystal Spread 40",
                        "",
                        "Gain \uE053 Ore Fortune 110 while mining.",
                        "",
                        "Mining Speed increases by 150 while in water.");
        var analysis = analyze("Stats & Equipment", lines);
        assertEquals("KEEP", analysis.lines().get(1).action());
        assertEquals("KEEP", analysis.lines().get(2).action());
        assertTrue(texts(analysis).contains("Gain \uE053 Ore Fortune 110 while mining."));
        assertTrue(texts(analysis).contains("Mining Speed increases by 150 while in water."));
    }

    @Test
    void romanNumberedSkillTitlesAreUiTextWhileRealEnchantmentRowsStayOriginal() {
        for (var lines :
                List.of(
                        List.of(
                                "Farming XXXVI",
                                "Harvest crops and shear sheep to",
                                "earn Farming EXP!",
                                "",
                                "Progress to Level XXXVII: 39.9%",
                                "758,799.6/1.9M",
                                "",
                                "Level XXXVII Rewards:",
                                "  Farmhand XXXVII",
                                "    Grants +144\u279C148 \uE051 Farming",
                                "    Fortune, which increases your",
                                "    chance for multiple crops.",
                                "  +5\uE010 Health",
                                "  +550,000 Coins",
                                "  +20 SkyBlock XP",
                                "",
                                "Click to view!",
                                "minecraft:golden_hoe"),
                        List.of(
                                "Mining LX",
                                "Dive into deep caves and find rare",
                                "ores and valuable materials to earn",
                                "Mining EXP!",
                                "",
                                "Max Skill level reached!",
                                "163,493,691.5",
                                "",
                                "Click to view!",
                                "minecraft:stone_pickaxe"))) {
            var analysis = analyze("Your Skills", lines);
            var title = analysis.lines().getFirst();
            assertEquals("TRANSLATE", title.action(), lines.getFirst());
            assertEquals("UI_TITLE", title.role(), lines.getFirst());
            assertEquals(lines.getFirst(), analysis.paragraphs().getFirst().block().text());
            assertEquals("ui", analysis.paragraphs().getFirst().channel());
        }

        var gear = List.of("Ancient Helmet", "Growth V", "Grants +75 Health.", "", "RARE HELMET");
        var context =
                TooltipContext.analyze(
                        evidence("Your Skills", "TEST_HELMET", "minecraft:leather_helmet"), gear);
        var analysis = LoreParser.analyze(gear, context);
        assertEquals(TooltipContext.Kind.ITEM, context.kind());
        assertEquals("KEEP", analysis.lines().get(1).action());
        assertEquals("ENCHANTMENT_NAME", analysis.lines().get(1).role());
        assertEquals(List.of("Grants +75 Health."), texts(analysis));
    }

    @Test
    void totalXpAfterMaxSkillStatusIsNotAContinuationOfTheStatusSentence() {
        var lines =
                List.of(
                        "Mining LX",
                        "Dive into deep caves and find rare",
                        "ores and valuable materials to earn",
                        "Mining EXP!",
                        "",
                        "Max Skill level reached!",
                        "                     163,493,691.5",
                        "",
                        "Click to view!",
                        "minecraft:stone_pickaxe");
        var analysis = analyze("Your Skills", lines);
        var xp = analysis.lines().get(6);
        assertEquals("KEEP", xp.action());
        assertEquals(-1, xp.paragraphIndex());
        assertTrue(
                analysis.paragraphs().stream()
                        .noneMatch(p -> p.block().start() <= 6 && p.block().end() > 6));
        assertTrue(texts(analysis).stream().noneMatch(s -> s.contains("163,493,691.5")));

        var prose = List.of("Damage Item", "Deals damage up to", "163,493,691.5", "", "RARE");
        var context =
                TooltipContext.analyze(
                        evidence("Storage", "DAMAGE_ITEM", "minecraft:paper"), prose);
        var proseAnalysis = LoreParser.analyze(prose, context);
        assertEquals(List.of("Deals damage up to 163,493,691.5"), texts(proseAnalysis));
        assertEquals("JOIN", proseAnalysis.lines().get(2).action());
    }
}
