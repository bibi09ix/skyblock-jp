package local.skyblockjp;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real 0.7.3 tooltip failures: prose must survive while structured values stay original. */
class TooltipPipelineClassificationRegressionTest {
    private static TooltipContext.Evidence evidence(String title, TooltipContext.Location location, boolean matches, String id) {
        return new TooltipContext.Evidence("fixture.ContainerScreen", title, location, matches, id,
            "minecraft:player_head", location == TooltipContext.Location.UNKNOWN ? -1 : 13, 90);
    }
    private static LoreParser.Analysis item(String id, String... lines) {
        var text = List.of(lines);
        return LoreParser.analyze(text, TooltipContext.analyze(evidence("Storage", TooltipContext.Location.CONTAINER, true, id), text));
    }
    private static List<String> texts(LoreParser.Analysis result) {
        return result.paragraphs().stream().map(p -> p.block().text()).toList();
    }

    @Test void consumingJerryCandyKeepsBothWrappedSentencesAndNoneOfItsStatTable() {
        var result = item("JERRY_CANDY", "Jerry Candy", "Right-click to consume and", "gain the Jerry Effect (20:00):",
            " +100\uE010 Health", " +20\uE00D Strength", " +2\uE00B Ferocity", " +100\uE003 Intelligence",
            " +3\uE01A Magic Find", "", "UNCOMMON", "minecraft:player_head", "skyblock:JERRY_CANDY");
        assertEquals(List.of("Right-click to consume and gain the Jerry Effect (20:00):"), texts(result));
        assertEquals("item", result.paragraphs().getFirst().channel());
        assertEquals("JOIN", result.lines().get(2).action());
        for (int i = 3; i <= 7; i++) {
            assertEquals("KEEP", result.lines().get(i).action(), "buff stat row " + i);
            assertEquals(-1, result.lines().get(i).paragraphIndex());
        }
    }

    @Test void merchantItemSeparatesItsProseFromCostIngredientsAndUiAction() {
        var result = item("SCARLETON_PREMIUM", "Scarleton Premium", "Basic Brew", "",
            "Use this item in place of an", "Awkward Potion for certain", "potions.", "",
            "Increases the effect of Wounded", "Potions by 10%.", "", "RARE", "", "Cost",
            "Mushroom Spore x50", "18,000 Coins", "", "Click to trade!", "minecraft:player_head", "skyblock:SCARLETON_PREMIUM");
        assertEquals(List.of("Use this item in place of an Awkward Potion for certain potions.",
            "Increases the effect of Wounded Potions by 10%.", "Click to trade!"), texts(result));
        assertEquals(List.of("item", "item", "ui"), result.paragraphs().stream().map(LoreParser.Paragraph::channel).toList());
        for (int i = 12; i <= 14; i++) assertEquals("KEEP", result.lines().get(i).action(), "cost row " + i);
        assertEquals("ITEM_NAME", result.lines().getFirst().role());
    }

    @Test void numericDurationDoesNotBecomePartOfTheCandycombEffect() {
        var result = item("CANDYCOMB", "Candycomb", "Hunting Tool", "", "Grants 1.25\u2763 Charm Chance towards",
            "mobs inside The Catacombs.", "Duration: 12h", "", "Left-click to consume!", "", "RARE SALT");
        assertEquals(List.of("Grants 1.25\u2763 Charm Chance towards mobs inside The Catacombs.", "Left-click to consume!"), texts(result));
        assertEquals("KEEP", result.lines().get(5).action());
        assertEquals("ui", result.paragraphs().getLast().channel());
    }

    @Test void combatStatsMenuKeepsNumericRowsButTranslatesItsExplanationAndControl() {
        var lines = List.of("Combat Stats", "Stats that influence how much", "damage you take and deal when in", "combat.", "",
            " \uE010 Health 3,266.5", " \uE008 Defense 1,091.01", " \uE027 True Defense 20.5", " \uE00D Strength 277.75",
            " \uE02C Crit Chance 117%", " \uE007 Crit Damage 184%", " \uE001 Attack Speed 15%", " \uE00B Ferocity 2",
            " \uE024 Swing Range 3", " \uE003 Intelligence 1,348.84", " \uE002 Ability Damage 30.5%", " \uE011 Health Regen 164.75",
            " \uE028 Vitality 113", " \uE014 Mending 100", "", "Click for details!", "minecraft:stone_sword", "19個のコンポーネント", "Minecraft");
        var context = TooltipContext.analyze(evidence("Stats & Equipment", TooltipContext.Location.CONTAINER, true, ""), lines);
        assertEquals(TooltipContext.Kind.UI, context.kind());
        var result = LoreParser.analyze(lines, context);
        assertEquals(List.of("Combat Stats", "Stats that influence how much damage you take and deal when in combat.", "Click for details!"), texts(result));
        for (int i = 5; i <= 18; i++) assertEquals("KEEP", result.lines().get(i).action(), "numeric metric row " + i);
        for (int i = 21; i <= 23; i++) assertEquals("DEBUG", result.lines().get(i).role(), "debug row " + i);
    }

    @Test void numericStatsAndStatLabelsCannotSwallowProseBeginningWithStatWords() {
        var result = item("TEST", "Test item", " \uE010 Effective Health 25,000", "Flat: +100", "Stat Cap: 400", " \uE001 Attack Speed", "",
            "Damage nearby enemies", "for 3 seconds.", "", "Speed increases while you are in water.");
        assertEquals(List.of("Damage nearby enemies for 3 seconds.", "Speed increases while you are in water."), texts(result));
        for (int i = 1; i <= 4; i++) assertEquals("KEEP", result.lines().get(i).action());
    }

    @Test void unknownSlotAndStackMismatchNeverInventPlayerInventoryOrMenuEvidence() {
        var lines = List.of("Next Page", "Click to view!");
        for (var raw : List.of(evidence("SkyBlock Menu", TooltipContext.Location.UNKNOWN, false, ""),
            evidence("SkyBlock Menu", TooltipContext.Location.CONTAINER, false, ""))) {
            var result = TooltipContext.analyze(raw, lines);
            assertEquals(TooltipContext.Kind.UNKNOWN, result.kind());
            assertNotEquals("PLAYER_INVENTORY", result.reason());
            assertEquals(false, result.evidence().get("slotMatches"));
            assertEquals("KEEP", LoreParser.analyze(lines, result).lines().getFirst().action());
        }
    }

    @Test void strongItemEvidenceWinsOverTrustedMenuNamesAndUiInstructions() {
        var control = List.of("Medium Frog Treat", "Use at Kat to upgrade Frog Pets", "from Uncommon to Rare.", "RARE", "Click to trade!");
        assertEquals(TooltipContext.Kind.ITEM, TooltipContext.analyze(evidence("Pet Sitter", TooltipContext.Location.CONTAINER, true, "MEDIUM_FROG_TREAT"), control).kind());
        assertEquals(TooltipContext.Kind.ITEM, TooltipContext.analyze(evidence("Pet Sitter", TooltipContext.Location.CONTAINER, true, ""), control).kind());
        assertEquals(TooltipContext.Kind.ITEM, TooltipContext.analyze(evidence("Pets", TooltipContext.Location.CONTAINER, true, ""),
            List.of("[Lvl 100] Silverfish", "Mining Pet", "Grants Mining Wisdom.", "Click to view!")).kind());
        assertEquals(TooltipContext.Kind.ITEM, TooltipContext.analyze(evidence("SkyBlock Menu", TooltipContext.Location.PLAYER, true, ""),
            List.of("Sword", "Click to view!")).kind());
    }

    @Test void namedNpcMerchantControlsAreUiEvenWithoutAWhitelistedScreenTitle() {
        var lines = List.of("Sell Item", "Click items in your inventory to sell", "them to this Shop!", "minecraft:hopper");
        var context = TooltipContext.analyze(evidence("Ezekiel", TooltipContext.Location.CONTAINER, true, ""), lines);
        assertEquals(TooltipContext.Kind.UI, context.kind());
        var result = LoreParser.analyze(lines, context);
        assertEquals(List.of("Sell Item", "Click items in your inventory to sell them to this Shop!"), texts(result));
        assertTrue(result.paragraphs().stream().allMatch(p -> p.channel().equals("ui")));
    }

    @Test void confirmationMenuWithoutClickFooterRetainsItsTitleAndCompleteWarning() {
        var lines = List.of("Confirm", "WARNING: You will not be able to", "retrieve your pet for 2 hours and",
            "its level will change as a result of", "the rarity upgrade.", "minecraft:green_terracotta");
        var context = TooltipContext.analyze(evidence("Confirm Hiring Kat", TooltipContext.Location.CONTAINER, true, ""), lines);
        assertEquals(TooltipContext.Kind.UI, context.kind());
        assertEquals(List.of("Confirm", "WARNING: You will not be able to retrieve your pet for 2 hours and its level will change as a result of the rarity upgrade."),
            texts(LoreParser.analyze(lines, context)));
    }

    @Test void knownAbilityStructureDoesNotTurnTitleCaseAloneIntoAnExclusion() {
        var result = item("PET", "[Lvl 100] Example", "Mining Pet", "", "Dexterity", "Grants +150 Mining Speed and", "permanent Haste III.", "",
            "A Curious Gift", "", "Deals damage up to", "100", "", "Ability: Burst RIGHT CLICK", "Damages nearby enemies for 5 seconds.");
        assertEquals(List.of("Grants +150 Mining Speed and permanent Haste III.", "A Curious Gift", "Deals damage up to 100", "Damages nearby enemies for 5 seconds."), texts(result));
        assertEquals("KEEP", result.lines().get(3).action());
        assertEquals("JOIN", result.lines().get(10).action());
    }

    @Test void existingJapaneseMixedWithEnglishNamesIsNotSentForAnotherTranslation() {
        var result = item("TEST", "Test item", "SkyBlock がついに Hypixel に登場！", "", "与えるDamageが10%増加する。", "", "A newly added description.");
        assertEquals(List.of("A newly added description."), texts(result));
        assertEquals("KEEP", result.lines().get(1).action());
        assertEquals("KEEP", result.lines().get(3).action());
        assertEquals("JAPANESE_TEXT_PRESENT", result.lines().get(1).reason());
    }
}
