package local.skyblockjp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Replays the reviewed source ranges and guards the nearby prose boundaries. */
class TooltipMetadataReviewTest {
    private static JsonObject fixtures() throws Exception {
        try (var input = TooltipMetadataReviewTest.class.getResourceAsStream("/remaining-metadata-0.7.10.json")) {
            assertNotNull(input);
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static LoreParser.Analysis analyze(String... lines) {
        return LoreParser.analyze(List.of(lines), new TooltipContext.Decision(TooltipContext.Kind.ITEM, "metadata-fixture", Map.of()));
    }
    private static List<String> paragraphs(LoreParser.Analysis result) {
        return result.paragraphs().stream().map(p -> p.block().text()).toList();
    }

    @Test void all152ReviewedRangesSeparate145StateValuesFromSevenTranslatableSources() throws Exception {
        var cases = fixtures().getAsJsonArray("cases"); assertEquals(152, cases.size());
        int preserved = 0, translated = 0;
        for (var raw : cases) {
            var row = raw.getAsJsonObject();
            List<String> lines = row.getAsJsonArray("lines").asList().stream().map(v -> v.getAsString()).toList();
            int start = row.get("start").getAsInt(), end = row.get("end").getAsInt();
            boolean expected = row.get("expectedPreserved").getAsBoolean();
            assertEquals(row.get("source").getAsString().replaceAll("\\s+", " "),
                String.join(" ", lines.subList(start, end).stream().map(String::strip).toList()).replaceAll("\\s+", " "));
            for (int index = start; index < end; index++) {
                var match = TooltipMetadata.classify(lines, index);
                if (expected) {
                    assertNotNull(match, row.get("source").getAsString() + " / " + index);
                    assertFalse(match.reason().isBlank());
                } else assertNull(match, row.get("source").getAsString());
            }
            if (expected) preserved++; else translated++;
        }
        assertEquals(145, preserved); assertEquals(7, translated);
    }

    @Test void compoundDurationsNeedAnEntireOrderedValueAndCannotHideAnEffectClause() {
        for (String s : List.of("Duration: 2d 22h 59m 8s", "Duration: 0d 8h 13m 1s", "Duration: 50h 24m",
                "Duration after: 4d 8h 10m 33s", "Starting in: 6h 53m", "Duration: 21m41s", "Duration: 5s"))
            assertEquals("COMPOUND_DURATION_VALUE", TooltipMetadata.classify(s).reason(), s);
        for (String s : List.of("Duration:", "Duration: 12", "Duration: 5s while you are sneaking.",
                "Duration: 5s and gains 10 Health.", "Duration after: 5s of invulnerability.",
                "Duration: 3m 1h", "Duration: 5s extra", "Starting in: five seconds"))
            assertNull(TooltipMetadata.classify(s), s);
    }

    @Test void timerBreakdownAndConfirmationValuesDoNotJoinTheirNeighboringExplanation() {
        var result = analyze("Mixin", "Duration: 50h 24m", "  +36h Default", "  +14h Alchemy Level", "",
            "The duration of Mixins can be stacked!", "Right-click to consume!");
        assertEquals(List.of("The duration of Mixins can be stacked!", "Right-click to consume!"), paragraphs(result));
        assertEquals("DURATION_BREAKDOWN_VALUE", result.lines().get(2).reason());
        assertEquals("DURATION_BREAKDOWN_VALUE", result.lines().get(3).reason());
        assertEquals(List.of("Adds 4 days to your Cookie Buff!"), paragraphs(analyze("Consume Cookie",
            "Adds 4 days to your Cookie Buff!", "", "Duration after: 4d 8h 10m 33s", "Bits available to gain after: 10,032")));
        assertNull(TooltipMetadata.classify("+36h Default"), "breakdown requires neighboring duration header");
    }

    @Test void calendarAndSoulEntriesRequireTheirOwnVerifiedSection() {
        var calendar = List.of("Calendar and Events", "View upcoming events!", "", "Active Events:",
            "Year of the Seal 14h 14m 47s", "The Great Spook 4d", "", "Next Event: 524th Spooky Festival");
        assertEquals("ACTIVE_EVENT_COUNTDOWN_VALUE", TooltipMetadata.classify(calendar, 4).reason());
        assertEquals("ACTIVE_EVENT_COUNTDOWN_VALUE", TooltipMetadata.classify(calendar, 5).reason());
        assertEquals("UPCOMING_EVENT_ORDINAL_VALUE", TooltipMetadata.classify(calendar, 7).reason());
        assertNull(TooltipMetadata.classify("Year of the Seal 14h 14m 47s"));
        assertNull(TooltipMetadata.classify("Next Event: 524th Spooky Festival"));
        var souls = List.of("Weapon", "Absorbed Souls:", "- Tank Zombie Lv47 (8 Mana)", "- Tank Zombie Lv48 (9 Mana)",
            "", "- Gain +10 Health on hit.");
        assertEquals("ABSORBED_SOUL_SECTION", TooltipMetadata.classify(souls, 1).reason());
        assertNull(TooltipMetadata.classify("Absorbed Souls:"));
        assertEquals("ABSORBED_SOUL_ENTRY", TooltipMetadata.classify(souls, 2).reason());
        assertEquals("ABSORBED_SOUL_ENTRY", TooltipMetadata.classify(souls, 3).reason());
        assertNull(TooltipMetadata.classify("- Tank Zombie Lv47 (8 Mana)"));
        assertNull(TooltipMetadata.classify(souls, 5));
    }

    @Test void preciseNumericTablesStayEnglishWhileEffectsAndFormulaSuffixesRemainProse() {
        for (String s : List.of("Stat has: 71 points", "Points left: 0!", "You have: 400 + 106.5 \uE022",
                "41.5 Skill Avg. (non-cosmetic)", "Increased EXP: +6.4% EXP", "Level 13 -> 14   (4.6k/100k)",
                "Base regen ticks: 1% of Max ❤ + 1.5❤", "Regen interval: Every 1 seconds", "Permanent: +70\uE020 Rift Time",
                "Use cost: 21m41s \uE020 Rift Time", "Boosters: ⸙/ꕮ", "ථ Hook NONE", "Skeletor Kills: 0"))
            assertNotNull(TooltipMetadata.classify(s), s);
        for (String s : List.of("Stat has: 5 points while equipped.", "Points left: none!", "You have: 400 enemies nearby.",
                "Increased EXP: +6.4% EXP while training.", "Base regen ticks: 1% of Max ❤ + 1.5❤ when active.",
                "Regen interval: Every 1 seconds while submerged.", "Permanent: +70\uE020 Rift Time when unlocked.",
                "Use cost: 5s \uE020 Rift Time and become invisible.", "Boosters: Grants extra damage.",
                "ථ Hook grants +10 Fishing Speed.", "Skeletor Kills: 10 for bonus damage.",
                "5 Combo (lasts 10s)", "+10 coins per kill"))
            assertNull(TooltipMetadata.classify(s), s);
    }

    @Test void ingredientAndSelectedEquipmentNamesNeedTheirOwnMenuEvidence() {
        var ingredients = List.of("Supercraft (24x crafts)", "✔ 13,952/13,824 (24x) Hard Stone", "328x from Inventory",
            "13,496x from Sacks", "", "Crafting 24 items into your sacks!", "Right-Click to set amount!");
        assertEquals("SUPERCRAFT_INGREDIENT_TOTAL", TooltipMetadata.classify(ingredients, 1).reason());
        assertEquals("SUPERCRAFT_INGREDIENT_SOURCE", TooltipMetadata.classify(ingredients, 2).reason());
        assertEquals("SUPERCRAFT_INGREDIENT_SOURCE", TooltipMetadata.classify(ingredients, 3).reason());
        assertNull(TooltipMetadata.classify("328x from Inventory"));
        var loadout = List.of("Loadout 10", "Belt: \uE068 Waxed Adaptive Belt ✪✪✪✪✪", "Pet: [Lvl 100] Enderman", "",
            "Left-click to equip!", "Right-click to edit");
        assertEquals("LOADOUT_SELECTED_COMPONENT_VALUE", TooltipMetadata.classify(loadout, 1).reason());
        assertEquals("LOADOUT_SELECTED_COMPONENT_VALUE", TooltipMetadata.classify(loadout, 2).reason());
        assertNull(TooltipMetadata.classify("Pet: [Lvl 100] Enderman"));
        var dangerous = List.of("Loadout 10", "Helmet: Grants +10 Health.", "Left-click to equip!", "Right-click to edit");
        assertNull(TooltipMetadata.classify(dangerous, 1));
        assertNull(TooltipMetadata.classify(ingredients, 5));
    }

    @Test void originalMetadataStringsRemainExactInTheLineDiagnostics() {
        var result = analyze("Tool", "Duration: 2d 22h 59m 8s", "Level 13 -> 14   (4.6k/100k)", "Grants a useful bonus.");
        assertEquals(List.of("Grants a useful bonus."), paragraphs(result));
        for (int i : List.of(1, 2)) {
            assertEquals("KEEP", result.lines().get(i).action()); assertEquals(-1, result.lines().get(i).paragraphIndex());
        }
        assertEquals("Duration: 2d 22h 59m 8s", result.lines().get(1).source());
        assertEquals("Level 13 -> 14   (4.6k/100k)", result.lines().get(2).source());
    }

    @Test void sackCapacityRequiresTheExactQuantityRowAndNeverAbsorbsItsExplanation() {
        var result = analyze("Small Lava Fishing Sack", "Item pickups go directly into", "your sacks.", "Capacity: 2,240 of each item",
            "Sacks sum their capacity.");
        assertEquals(List.of("Item pickups go directly into your sacks.", "Sacks sum their capacity."), paragraphs(result));
        assertEquals("SACK_PER_ITEM_CAPACITY_VALUE", result.lines().get(3).reason());
        assertNull(TooltipMetadata.classify("Capacity: 2,240 of each item while equipped."));
        assertNull(TooltipMetadata.classify("Capacity: Grants more room for items."));
        assertNull(TooltipMetadata.classify("Sell Price"), "operation header remains translatable");
    }
}
