package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class StructuredLoreBoundaryTest {
    private static List<String> texts(String... lines) {
        return LoreParser.parse(List.of(lines)).stream().map(LoreParser.Block::text).toList();
    }

    @Test
    void scalarCostProgressAndPunctuationGlyphsCannotContaminateDescriptions() {
        for (String value :
                List.of(
                        "Vitality Cost: 50",
                        "Next Upgrade: 250",
                        "Charges: 10/20",
                        "Cooldown: 5 ⸕",
                        "Duration: 30s ⸎",
                        "Value: .5T",
                        "Level 13 → 14"))
            assertEquals(
                    List.of("Grants a useful bonus."),
                    texts("Item", value, "Grants a useful bonus."),
                    value);
    }

    @Test
    void costSectionsEndAtActualProseEvenWhenServerOmitsABlankLine() {
        assertEquals(
                List.of("Grants a useful bonus.", "Click to buy!"),
                texts(
                        "Item",
                        "Cost",
                        "Enchanted Iron x20",
                        "25,000 Coins",
                        "Grants a useful bonus.",
                        "Click to buy!"));
    }

    @Test
    void unknownLabelsAndNamedMetadataCannotSwallowNeighboringDictionaryText() {
        assertEquals(
                List.of("Future Feature:", "Grants a useful bonus."),
                texts("Item", "Future Feature:", "Grants a useful bonus."));
        for (String label :
                List.of(
                        "Extra Bonus: Anti-Toxin",
                        "Orb Buff: Radiant",
                        "Vacuum Bag: Large",
                        "Basic Nozzle Bonus: Spooky"))
            assertEquals(
                    List.of("Grants a useful bonus."),
                    texts("Item", label, "Grants a useful bonus."),
                    label);
    }

    @Test
    void arrowBulletsAndProgressNumbersDoNotMergeAdjacentParagraphs() {
        assertEquals(
                List.of("Grants a useful bonus.", "➜ Gain a second bonus."),
                texts("Item", "Grants a useful bonus.", "➜ Gain a second bonus."));
        assertTrue(texts("Item", "Level 13 → 14", "4.56%").isEmpty());
        assertEquals(List.of("Deals damage up to 100"), texts("Item", "Deals damage up to", "100"));
    }

    @Test
    void labelShapedEnglishEffectsRemainTranslatable() {
        for (String prose :
                List.of(
                        "Future Feature: Grants a useful bonus.",
                        "Cost: Reduced when you use this item.",
                        "Gain: 5 Health while in water.")) {
            assertEquals(List.of(prose), texts("Item", prose), prose);
        }
    }
}
