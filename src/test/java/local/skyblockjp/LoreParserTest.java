package local.skyblockjp;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LoreParserTest {
    @Test void translatesShortParagraphsAfterBlankLinesAndNumericProse() {
        var blocks = LoreParser.parse(List.of("Sword", "Deals 100 damage.", "", "Explodes", "", "Damage nearby enemies", "for 3 seconds.", "Damage: +50", "RARE SWORD"));
        assertEquals(3, blocks.size());
        assertEquals("Explodes", blocks.get(1).text());
        assertEquals("Damage nearby enemies for 3 seconds.", blocks.get(2).text());
    }
    @Test void joinsWrappedDescriptionAndPreservesStats() {
        var lines = List.of("Aspect of the End", "Damage: +100", "Strength: +100", "", "Ability: Instant Transmission RIGHT CLICK", "Teleport 8 blocks ahead of", "you and gain +50 Speed", "for 3 seconds.", "Mana Cost: 50", "", "RARE SWORD");
        var blocks = LoreParser.parse(lines);
        assertEquals(1, blocks.size());
        assertEquals(5, blocks.getFirst().start());
        assertEquals(8, blocks.getFirst().end());
        assertEquals("Teleport 8 blocks ahead of you and gain +50 Speed for 3 seconds.", blocks.getFirst().text());
    }
    @Test void separatesParagraphsAndKeepsEnchantments() {
        var blocks = LoreParser.parse(List.of("Sword", "Sharpness V, Smite VI", "", "Deals damage to nearby enemies.", "", "Heals you for 20 Health.", "Cooldown: 5s", "LEGENDARY SWORD"));
        assertEquals(2, blocks.size());
    }
    @Test void unknownNumericStatsStayOriginal() {
        assertTrue(LoreParser.protectedLine("New Stat: +150 (Bonus +5)"));
        assertTrue(LoreParser.protectedLine("✯ Magic Find: +10"));
        assertTrue(LoreParser.parse(List.of("The Best Sword", "Strength: +50")).isEmpty());
    }
    @Test void catchesChangedOrMissingNumbers() {
        assertTrue(LoreParser.numbersPreserved("Deal 1,000 damage for 5 seconds", "5秒間、1,000ダメージ"));
        assertFalse(LoreParser.numbersPreserved("Deal 100 damage for 5 seconds", "5秒間、1,000ダメージ"));
    }
}
