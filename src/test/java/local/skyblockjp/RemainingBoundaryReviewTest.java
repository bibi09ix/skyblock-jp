package local.skyblockjp;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** All changes come from the frozen 0.7.9 remaining-source review, with negative boundaries. */
class RemainingBoundaryReviewTest {
    private static final class Dictionary { static final BundledDictionary VALUE = BundledDictionary.loadBundled(); }
    private static final TooltipContext.Decision ITEM = new TooltipContext.Decision(TooltipContext.Kind.ITEM, "REMAINING_REVIEW", Map.of());
    private static boolean label(String value) {
        return Dictionary.VALUE.lookup("item", value, "").filter(BundledDictionary.Match::keepOriginal)
            .filter(match -> match.sourceIds().stream().anyMatch(id -> id.startsWith("item/label/"))).isPresent();
    }
    private static LoreParser.Analysis parse(List<String> lines) {
        return LoreParser.analyze(lines, ITEM, RemainingBoundaryReviewTest::label);
    }
    private static List<String> paragraphs(LoreParser.Analysis result) {
        return result.paragraphs().stream().map(p -> p.block().text()).toList();
    }

    @Test void wrappedProseDoesNotProbeDictionaryLabelsAtEveryRow() {
        var probes = new ArrayList<String>();
        var result = LoreParser.analyze(List.of("Example Item", "", "Grants a useful effect while holding", "this item near your enemies.", "",
            "The Smell Of Powder", "Grants +20% Mithril Powder."), ITEM,
            value -> { probes.add(value); return value.equals("The Smell Of Powder"); });
        assertEquals(List.of("The Smell Of Powder"), probes);
        assertEquals(List.of("Grants a useful effect while holding this item near your enemies.", "Grants +20% Mithril Powder."), paragraphs(result));
    }

    @Test void powerScrollDecorationsDoNotJoinExplicitAbilityNamesToBodies() {
        for (String header : List.of("⦾ Ability: Bad Health  RIGHT CLICK", "⦾ Ability: Soulcry  RIGHT CLICK", "⦾ Ability: Huge Heal  RIGHT CLICK")) {
            var result = parse(List.of("Example Weapon", header, "Gain +400\uE00B Ferocity against Endermen for 4s", "Cooldown: 4s"));
            assertEquals("ABILITY_NAME", result.lines().get(1).role(), header);
            assertEquals("EXPLICIT_ABILITY_HEADING", result.lines().get(1).reason());
            assertEquals(List.of("Gain +400\uE00B Ferocity against Endermen for 4s"), paragraphs(result));
        }
    }

    @Test void inlineAbilityEffectsAreStillProseRatherThanDiscardedHeaders() {
        var result = parse(List.of("Example Wand", "⦾ Ability: Fire Veil RIGHT CLICK Creates a veil of fire for 5s.", "Cooldown: 1s"));
        assertEquals(List.of("⦾ Ability: Fire Veil RIGHT CLICK Creates a veil of fire for 5s."), paragraphs(result));
        assertEquals("DESCRIPTION", result.lines().get(1).role());
    }

    @Test void approvedTheAndCapitalizedOfLabelsUseTheirExistingIdentity() {
        String title = "The Smell Of Powder";
        assertTrue(label(title));
        var result = parse(List.of("[Lvl 100] Mithril Golem", "Mining Pet", "", title, "Grants +20% ᠅ Mithril Powder from", "all sources."));
        assertEquals("ABILITY_NAME", result.lines().get(3).role());
        assertEquals(List.of("Grants +20% ᠅ Mithril Powder from all sources."), paragraphs(result));
        var unverified = LoreParser.analyze(List.of("Example Item", "", title, "Grants +20% Mithril Powder."), ITEM, s -> false);
        assertTrue(paragraphs(unverified).getFirst().contains(title), "The-prefixed ordinary prose is not stripped without verified label evidence");
    }

    @Test void approvedPetLabelsCanUseTheirWholeWrappedFollowingClause() {
        assertTrue(label("Creative Mind"));
        var result = parse(List.of("[Lvl 23] Precursor Drone", "All Skills", "", "Creative Mind", "This pet can have Drone Mods",
            "applied to it, which allows you to", "modify its Perks to your liking.", "Precursor Drones may possess up", "to 3 perks at a time, this one included."));
        assertEquals("ITEM_TYPE", result.lines().get(1).role());
        assertEquals("ABILITY_NAME", result.lines().get(3).role());
        assertEquals(List.of("This pet can have Drone Mods applied to it, which allows you to modify its Perks to your liking. Precursor Drones may possess up to 3 perks at a time, this one included."), paragraphs(result));
        var mining = parse(List.of("[Lvl 23] Precursor Drone", "All Skills", "", "Mining Off Camera", "When Mining, each collection",
            "progress grants a 0.0015% chance", "to drop a random enchanted mining", "item."));
        assertEquals("ABILITY_NAME", mining.lines().get(3).role());
        assertEquals(List.of("When Mining, each collection progress grants a 0.0015% chance to drop a random enchanted mining item."), paragraphs(mining));
    }

    @Test void conditionalPetBodyDoesNotSwallowItsNameOrNumericStatRows() {
        assertTrue(label("Hive"));
        var result = parse(List.of("[Lvl 1] Bee", "Farming Pet", "", "Hive", "For each player within 25 blocks:",
            " +1.09\uE003 Intelligence", " +1.07\uE00D Strength", " +1.04\uE008 Defense"));
        assertEquals("ABILITY_NAME", result.lines().get(3).role());
        assertEquals(List.of("For each player within 25 blocks:"), paragraphs(result));
        assertTrue(result.lines().subList(5, 8).stream().allMatch(line -> line.role().equals("STAT")));
    }

    @Test void approvedSubjectLedPetEffectCanUseDropVerbWithoutWeakeningStatGrammar() {
        assertTrue(label("Magnetic Nose"));
        var result = parse(List.of("[Lvl 100] Mole", "Mining Pet", "", "Magnetic Nose", "Automatons drop their parts 50%", "more frequently."));
        assertEquals("ABILITY_NAME", result.lines().get(3).role());
        assertEquals(List.of("Automatons drop their parts 50% more frequently."), paragraphs(result));
        assertTrue(LoreParser.numericStat("Drop Chance: +50%"), "Extra verified-body verbs must not change generic stat classification");
    }

    @Test void cachedPetsWithoutBlankSeparatorsStillNeedApprovedLabelsAndBoundaries() {
        var lines = List.of("[Lvl 100] Scatha", "Mining Pet", "Mining Speed: +100", "Mining Fortune: +125", "Burrowing",
            "Grants a +50% chance to find", "Treasure Chests while mining.", "Drill Infusion", "Grants +125\uE053 Gemstone Fortune to", "Drills.",
            "Bejeweled Eyes", "Earn +20% Gemstone Powder from all", "sources.");
        var result = parse(lines);
        for (int at : List.of(4, 7, 10)) assertEquals("ABILITY_NAME", result.lines().get(at).role(), "row " + at);
        assertEquals(List.of("Grants a +50% chance to find Treasure Chests while mining.", "Grants +125\uE053 Gemstone Fortune to Drills.",
            "Earn +20% Gemstone Powder from all sources."), paragraphs(result));
        var unknown = LoreParser.analyze(lines, ITEM, s -> false);
        assertTrue(paragraphs(unknown).getFirst().startsWith("Burrowing "), "Missing separator cannot justify an unknown label split");
    }

    @Test void recognizedLabelInsideUnfinishedProseIsNotASection() {
        var result = parse(List.of("[Lvl 100] Enderman", "Combat Pet", "", "This effect improves", "Teleport Savvy", "Buffs Transmission abilities."));
        assertEquals(List.of("This effect improves Teleport Savvy Buffs Transmission abilities."), paragraphs(result));
        assertTrue(result.lines().stream().noneMatch(line -> line.role().equals("ABILITY_NAME")));
        var stat = parse(List.of("Example Pet", "Combat Pet", "", "Teleport Savvy", "Strength: +20"));
        assertEquals(List.of("Teleport Savvy"), paragraphs(stat), "A stat table is not a following ability explanation");
    }

    @Test void tamingPetPassivelyAndLessCommonEffectLeadsSeparateTheirNames() {
        var owl = parse(List.of("[Lvl 64] Owl", "Taming Pet", "", "Fast Learner", "Passively grants +3.2☯ Taming", "Wisdom."));
        assertEquals("ITEM_TYPE", owl.lines().get(1).role());
        assertEquals("ABILITY_NAME", owl.lines().get(3).role());
        assertEquals(List.of("Passively grants +3.2☯ Taming Wisdom."), paragraphs(owl));
        for (var pair : List.of(Map.entry("Stellar Bonus", "Earn +10% Mithril Powder near a Fallen Star."),
                                Map.entry("Salty Bonus", "This gives 2x stats while on Jerry's Workshop."),
                                Map.entry("ථ Treasure Hook", "Only allows you to catch items and Treasure."))) {
            var result = parse(List.of("Example Item", "", pair.getKey(), pair.getValue()));
            assertEquals("ABILITY_NAME", result.lines().get(2).role());
            assertEquals(List.of(pair.getValue()), paragraphs(result));
        }
    }

    @Test void wrappedColonClausesRejoinOnlyWhenSyntaxSuppliesContinuationEvidence() {
        var hotspot = parse(List.of("Hotspot Ring", "", "Grants buffs when fishing in a", "Fishing Hotspot:", "• +5\uE00C Fishing Speed"));
        assertEquals(List.of("Grants buffs when fishing in a Fishing Hotspot:"), paragraphs(hotspot));
        var tree = parse(List.of("Step 4: Happy Huntin'!", "", "The type of \uE087 Critter that shows up", "depends upon the type of Protected", "Tree you smeared:"));
        assertEquals(List.of("The type of \uE087 Critter that shows up depends upon the type of Protected Tree you smeared:"), paragraphs(tree));
        var isolated = parse(List.of("Example Item", "", "Grants +10 Strength.", "Unknown Section:", "Has an independent explanation."));
        assertEquals(List.of("Grants +10 Strength.", "Unknown Section:", "Has an independent explanation."), paragraphs(isolated));
    }

    @Test void dashAndSquareBulletEffectsAreSeparateWithoutDroppingAmbiguousWords() {
        var lastStand = parse(List.of("Enchanted Book", "Last Stand IV", "When falling below 40% ❤ Health:",
            "- Gain +10% \uE008 Defense for 10s.", "- Regen 8\uE028 Vitality.", "Cooldown: 30s"));
        assertEquals(List.of("When falling below 40% ❤ Health:", "- Gain +10% \uE008 Defense for 10s.", "- Regen 8\uE028 Vitality."), paragraphs(lastStand));
        var leech = parse(List.of("Strike Them Where It Hurts", "", "◼ Abilities deal % of time left!", "◼ Tons of left clicking.", "◼ Reward: Leech Sword (or better!)"));
        assertEquals(List.of("◼ Abilities deal % of time left!", "◼ Tons of left clicking.", "◼ Reward: Leech Sword (or better!)"), paragraphs(leech));
        assertTrue(paragraphs(leech).getFirst().contains("%"), "No missing numeric mechanic is invented or discarded");
    }

    @Test void repeatedCompleteNumericActionsSplitButPhysicalWrappingStillJoins() {
        var lines = List.of("Suspicious Pooch Sword", "", "Deal +20\uE050 Damage per Wolf Slayer level", "Deal +200% \uE050 Damage against Wolves",
            "Take -20% damage from \uE071 Animal mobs", "Gain +45☯ Combat Wisdom against \uE071 Animal mobs");
        assertEquals(lines.subList(2, 6), paragraphs(parse(lines)));
        var wrapped = parse(List.of("Example Item", "", "Gain +5 Strength for every 5% of total HP", "you have for 5s. Capped at +100 Strength."));
        assertEquals(List.of("Gain +5 Strength for every 5% of total HP you have for 5s. Capped at +100 Strength."), paragraphs(wrapped));
    }

    @Test void anvilOperationAndMarketLabelDoNotJoinTheirFollowingDescriptionOrValue() {
        var dye = parse(List.of("Aquamarine Dye", "Combinable in Anvil", "Changes the color of an armor piece", "to #7FFFD4!", "LEGENDARY DYE"));
        assertEquals(List.of("Combinable in Anvil", "Changes the color of an armor piece to #7FFFD4!"), paragraphs(dye));
        var sale = parse(List.of("Example Item", "Sell Price", "1,000 Coins"));
        assertEquals(List.of("Sell Price"), paragraphs(sale));
        assertEquals("COST_VALUE", sale.lines().get(2).role());
    }

    @Test void categoryLabelsStayEnglishWhileActualEffectAndActiveEventHeaderRemainEligible() {
        for (String category : List.of("Weapon", "Lava Rod", "Hunting Pet", "All Skills")) {
            var result = parse(List.of("Example Item", category, "", "Grants +10 Strength."));
            assertEquals("ITEM_TYPE", result.lines().get(1).role());
            assertEquals(List.of("Grants +10 Strength."), paragraphs(result));
        }
        var event = parse(List.of("Calendar and Events", "", "Active Events:", "New Year Celebration 1h 12m 37s"));
        assertEquals(List.of("Active Events:"), paragraphs(event));
        assertEquals("ACTIVE_EVENT_COUNTDOWN_VALUE", event.lines().get(3).reason());
    }
}
