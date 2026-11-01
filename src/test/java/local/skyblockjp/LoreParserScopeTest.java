package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoreParserScopeTest {
    @Test
    void uiTitleAndControlsAreIndependentBlocksAndWrappingIsKept() {
        var lines =
                List.of(
                        "Pets",
                        "View and manage all of your Pets.",
                        "",
                        "Level up your pets faster by gaining",
                        "XP in their favorite skill!",
                        "",
                        "Selected pet: Skeleton",
                        "Progress to Level 99: 43.9%",
                        "765,938.1/1.7M",
                        "",
                        "Also accessible via /pets.",
                        "",
                        "Click to view!",
                        "minecraft:bone",
                        "13個のコンポーネント",
                        "Minecraft");
        var blocks = LoreParser.parse(lines, true);
        assertEquals(
                List.of(
                        "Pets",
                        "View and manage all of your Pets.",
                        "Level up your pets faster by gaining XP in their favorite skill!",
                        "Selected pet: Skeleton",
                        "Also accessible via /pets.",
                        "Click to view!"),
                blocks.stream().map(LoreParser.Block::text).toList());
        assertEquals(0, blocks.getFirst().start());
        assertEquals(1, blocks.getFirst().end());
        assertTrue(LoreParser.parse(lines).stream().noneMatch(b -> b.start() == 0));
    }

    @Test
    void powerStoneStatHeadingsAreNotExtraMissingDescriptions() {
        var lines =
                List.of(
                        "Glacite Chunk",
                        "Combine 9 of this item at the",
                        "Thaumaturgist to unlock Frozen.",
                        "",
                        "Handle with gloves.",
                        "",
                        "At 750 Accessory Power:",
                        "+343.94 Defense",
                        "+47.58 True Defense",
                        "",
                        "Unique Power Bonus:",
                        "+10 True Defense",
                        "+25 Strength",
                        "+35% Crit Damage",
                        "LEGENDARY POWER STONE");
        var blocks = LoreParser.parse(lines);
        assertEquals(2, blocks.size());
        assertEquals(
                "Combine 9 of this item at the Thaumaturgist to unlock Frozen.",
                blocks.getFirst().text());
        assertEquals("Handle with gloves.", blocks.getLast().text());
    }

    @Test
    void prosePrefixesStarsAndSoulboundConsequencesAreNotSwallowed() {
        for (String prose :
                List.of(
                        "NPCs will pay more for this item.",
                        "Reforges your armor with an ancient power.",
                        "This item becomes SOULBOUND when used.",
                        "Gain 5% damage per ✪✪ applied to this item.",
                        "Ability: Deals 100 damage.",
                        "Full Set Bonus: Reduces damage taken by 5%.",
                        "Pickaxe Ability: Mining Burst - Grants +100 Mining Speed.")) {
            assertFalse(LoreParser.protectedLine(prose), prose);
            assertEquals(1, LoreParser.parse(List.of("Named item", prose)).size(), prose);
        }
        for (String metadata :
                List.of(
                        "NPC Sell Price: 5.0 Coins",
                        "Museum (Fishing): Not Donated",
                        "BAZAAR Sell Price: 100 Coins",
                        "Lowest BIN Price: 1,000 Coins",
                        "Reforge: Ancient",
                        "Ability: Instant Transmission RIGHT CLICK",
                        "Full Set Bonus: Witherborn",
                        "Ability: Damage Reduction",
                        "Ability: Heal",
                        "Pickaxe Ability: Maniac Miner",
                        "* Co-op Soulbound *",
                        "Damage: +100")) {
            assertTrue(LoreParser.protectedLine(metadata), metadata);
        }
    }

    @Test
    void wrappedUiInstructionsAreNotBrokenAtEveryPhysicalLine() {
        var blocks =
                LoreParser.parse(
                        List.of(
                                "Next Page",
                                "Right-click to select this",
                                "page and show more entries.",
                                "",
                                "Click to view!"),
                        true);
        assertEquals(
                List.of(
                        "Next Page",
                        "Right-click to select this page and show more entries.",
                        "Click to view!"),
                blocks.stream().map(LoreParser.Block::text).toList());
    }

    @Test
    void unchangedItemScopeStillProtectsNamesEnchantmentsAndGearStats() {
        var lines =
                List.of(
                        "Ancient Helmet",
                        "Health: +100",
                        "Defense: +50",
                        "",
                        "Growth V",
                        "Grants +75 Health.",
                        "",
                        "Ability: Burst RIGHT CLICK",
                        "Damages nearby enemies for 5 seconds.",
                        "Mana Cost: 50",
                        "EPIC HELMET");
        var blocks = LoreParser.parse(lines);
        assertEquals(2, blocks.size());
        assertEquals("Grants +75 Health.", blocks.getFirst().text());
        assertEquals("Damages nearby enemies for 5 seconds.", blocks.getLast().text());
    }
}
