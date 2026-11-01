package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ConservativeTooltipFallbackTest {
    private static TooltipContext.Decision collection() {
        return TooltipContext.analyze(
                new TooltipContext.Evidence(
                        "ContainerScreen",
                        "Diamond Collection",
                        TooltipContext.Location.CONTAINER,
                        true,
                        "",
                        "minecraft:red_stained_glass_pane",
                        24,
                        90),
                List.of("Diamond VII"));
    }

    private static List<String> recipes() {
        return List.of(
                "Diamond VII",
                "",
                "Rewards:",
                "  Hardened Diamond Helmet Recipe",
                "  Hardened Diamond Chestplate Recipe",
                "+4 SkyBlock XP");
    }

    private static LoreParser.Paragraph whole() {
        return new LoreParser.Paragraph(
                new LoreParser.Block(
                        3, 5, "Hardened Diamond Helmet Recipe Hardened Diamond Chestplate Recipe"),
                "ui",
                "DESCRIPTION",
                "PROSE_CANDIDATE");
    }

    @Test
    void existingAnalysisRemainsWholeAndOnlyVerifiedFallbackPartitionsIt() {
        var source = recipes();
        var context = collection();
        var analysis = LoreParser.analyze(source, context);
        assertTrue(
                analysis.paragraphs().stream()
                        .anyMatch(p -> p.block().text().equals(whole().block().text())));
        var rows =
                LoreParser.independentRecipeRows(
                        source,
                        context,
                        whole(),
                        s ->
                                Set.of(
                                                "Hardened Diamond Helmet Recipe",
                                                "Hardened Diamond Chestplate Recipe")
                                        .contains(s));
        assertEquals(2, rows.size());
        assertEquals(
                new LoreParser.Block(3, 4, "Hardened Diamond Helmet Recipe"),
                rows.getFirst().block());
        assertEquals(
                new LoreParser.Block(4, 5, "Hardened Diamond Chestplate Recipe"),
                rows.getLast().block());
        assertTrue(
                LoreParser.independentRecipeRows(
                                source, context, whole(), s -> !s.contains("Chestplate"))
                        .isEmpty());
    }

    @Test
    void fallbackRequiresCompleteRangeAndSemanticSectionAndSlotEvidence() {
        var context = collection();
        var source = recipes();
        var changed = new ArrayList<>(source);
        changed.set(2, "A description:");
        assertTrue(
                LoreParser.independentRecipeRows(changed, context, whole(), s -> true).isEmpty());
        changed = new ArrayList<>(source);
        changed.set(3, "Grants 10 Mining Speed while holding this");
        assertTrue(
                LoreParser.independentRecipeRows(changed, context, whole(), s -> true).isEmpty());
        var stale =
                new LoreParser.Paragraph(
                        new LoreParser.Block(3, 5, whole().block().text() + " and deals damage."),
                        "ui",
                        "DESCRIPTION",
                        "");
        assertTrue(LoreParser.independentRecipeRows(source, context, stale, s -> true).isEmpty());
        var evidence = new HashMap<>(context.evidence());
        evidence.put("slotMatches", false);
        assertTrue(
                LoreParser.independentRecipeRows(
                                source,
                                new TooltipContext.Decision(
                                        context.kind(), context.reason(), evidence),
                                whole(),
                                s -> true)
                        .isEmpty());
        evidence.put("slotMatches", true);
        evidence.put("containerTitle", "A player's chest");
        assertTrue(
                LoreParser.independentRecipeRows(
                                source,
                                new TooltipContext.Decision(
                                        context.kind(), context.reason(), evidence),
                                whole(),
                                s -> true)
                        .isEmpty());
    }

    @Test
    void partitionsUpdateDiagnosticIndexesAndRejectLostOrOverlappingRows() {
        var source = recipes();
        var analysis = LoreParser.analyze(source, collection());
        int index =
                java.util.stream.IntStream.range(0, analysis.paragraphs().size())
                        .filter(i -> analysis.paragraphs().get(i).block().start() == 3)
                        .findFirst()
                        .orElseThrow();
        var rows =
                LoreParser.independentRecipeRows(
                        source, collection(), analysis.paragraphs().get(index), s -> true);
        var updated = LoreParser.replaceParagraphs(analysis, Map.of(index, rows));
        assertEquals(analysis.paragraphs().size() + 1, updated.paragraphs().size());
        assertEquals(index, updated.lines().get(3).paragraphIndex());
        assertEquals(index + 1, updated.lines().get(4).paragraphIndex());
        assertEquals("RECIPE_LIST_ENTRY", updated.lines().get(4).role());
        assertEquals("TRANSLATE", updated.lines().get(4).action());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LoreParser.replaceParagraphs(
                                analysis, Map.of(index, List.of(rows.getFirst()))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LoreParser.replaceParagraphs(
                                analysis,
                                Map.of(index, List.of(rows.getFirst(), rows.getFirst()))));
    }

    private static TooltipContext.Evidence minion(
            TooltipContext.Location location, boolean matched, String id, String carrier) {
        return new TooltipContext.Evidence(
                "ContainerScreen", "Cobblestone Minion II", location, matched, id, carrier, 40, 90);
    }

    @Test
    void verifiedMinionLockedSlotIsInstructionWhileRealItemEvidenceWins() {
        var lines = List.of("Storage unlocked at tier VIII");
        var decision =
                TooltipContext.analyze(
                        minion(
                                TooltipContext.Location.CONTAINER,
                                true,
                                "",
                                "minecraft:white_stained_glass_pane"),
                        lines);
        assertEquals(TooltipContext.Kind.UI, decision.kind());
        assertEquals("MINION_LOCKED_SLOT_INSTRUCTION", decision.reason());
        var analysis = LoreParser.analyze(lines, decision);
        assertEquals(1, analysis.paragraphs().size());
        assertEquals("DESCRIPTION", analysis.paragraphs().getFirst().role());
        assertEquals("ui", analysis.paragraphs().getFirst().channel());
        assertEquals("Storage unlocked at tier VIII", decision.evidence().get("tooltipTitle"));
        assertEquals("UI", decision.evidence().get("tooltipKind"));
        assertEquals(
                TooltipContext.Kind.ITEM,
                TooltipContext.analyze(
                                minion(
                                        TooltipContext.Location.PLAYER,
                                        true,
                                        "",
                                        "minecraft:white_stained_glass_pane"),
                                lines)
                        .kind());
        assertEquals(
                TooltipContext.Kind.ITEM,
                TooltipContext.analyze(
                                minion(
                                        TooltipContext.Location.CONTAINER,
                                        true,
                                        "GENUINE_ITEM",
                                        "minecraft:white_stained_glass_pane"),
                                lines)
                        .kind());
        assertEquals(
                TooltipContext.Kind.UNKNOWN,
                TooltipContext.analyze(
                                minion(
                                        TooltipContext.Location.CONTAINER,
                                        false,
                                        "",
                                        "minecraft:white_stained_glass_pane"),
                                lines)
                        .kind());
        assertEquals(
                TooltipContext.Kind.UNKNOWN,
                TooltipContext.analyze(
                                minion(
                                        TooltipContext.Location.CONTAINER,
                                        true,
                                        "",
                                        "minecraft:paper"),
                                lines)
                        .kind());
        assertTrue(
                LoreParser.analyze(
                                List.of("Cobblestone"),
                                TooltipContext.analyze(
                                        minion(
                                                TooltipContext.Location.CONTAINER,
                                                true,
                                                "",
                                                "minecraft:white_stained_glass_pane"),
                                        List.of("Cobblestone")))
                        .paragraphs()
                        .isEmpty());
    }
}
