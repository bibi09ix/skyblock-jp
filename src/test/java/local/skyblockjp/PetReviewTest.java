package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class PetReviewTest {
    private static List<LoreParser.Block> descriptions(List<String> lines) {
        return LoreParser.analyze(
                        lines,
                        new TooltipContext.Decision(
                                TooltipContext.Kind.ITEM, "ITEM_FIXTURE", Map.of()))
                .paragraphs()
                .stream()
                .filter(p -> p.channel().equals("item"))
                .map(LoreParser.Paragraph::block)
                .toList();
    }

    static Map<String, List<String>> fixtures() throws Exception {
        var result = new LinkedHashMap<String, List<String>>();
        try (var in = PetReviewTest.class.getResourceAsStream("/pet-review-fixtures.json")) {
            for (var entry :
                    JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonArray()) {
                var item = entry.getAsJsonObject();
                List<String> lines = new ArrayList<>();
                item.getAsJsonArray("originalLines").forEach(v -> lines.add(v.getAsString()));
                result.put(item.get("item").getAsString(), lines);
            }
        }
        return result;
    }

    @Test
    void petDescriptionsKeepCompleteMeaningAndOriginalHeadings() throws Exception {
        var all = fixtures();
        for (String pet :
                List.of(
                        "[Lvl 1] Bal",
                        "[Lvl 100] Enderman",
                        "[Lvl 72] Tarantula",
                        "[Lvl 82] Sheep")) {
            var lines = all.get(pet);
            var blocks = descriptions(lines);
            assertEquals(
                    pet.contains("Bal") ? 3 : pet.contains("Enderman") ? 5 : 4, blocks.size(), pet);
            for (var b : blocks) {
                assertFalse(b.text().contains("Held Item:"));
                assertFalse(b.text().contains("click"));
                assertFalse(
                        b.text()
                                .matches(
                                        ".*(?:Mana Saver|Eight Legs|Zealot Madness|Furnace|Web Battlefield).*"));
                assertTrue(b.start() > 1);
            }
        }
        assertTrue(
                descriptions(all.get("[Lvl 82] Sheep")).stream()
                        .anyMatch(
                                b ->
                                        b.text()
                                                .equals(
                                                        "Reduces the mana cost of abilities by 16.4%.")));
        assertTrue(
                descriptions(all.get("[Lvl 100] Enderman")).stream()
                        .anyMatch(b -> b.text().endsWith("special Zealot by 25%.")));
    }

    @Test
    void nonPetWrappedTailsAreNotMetadata() throws Exception {
        var all = fixtures();
        assertEquals(1, descriptions(all.get("Coal Minion XI")).size());
        var compactor = descriptions(all.get("Super Compactor 3000"));
        assertEquals(1, compactor.size(), compactor.toString());
        assertTrue(compactor.getFirst().text().contains("minion upgrade. This will automatically"));
        assertTrue(
                descriptions(all.get("Salty Rod of Champions")).stream()
                        .anyMatch(
                                b ->
                                        b.text()
                                                .equals(
                                                        "Increases the chance to catch COMMON Sea Creatures by 25%.")));
        assertTrue(
                descriptions(all.get("Refined Gemstone Drill LT-522")).stream()
                        .anyMatch(
                                b ->
                                        b.text()
                                                .contains(
                                                        "+800 Mining Speed when mining Gemstones.")));
        assertEquals(1, descriptions(all.get(" Waxed Adaptive Belt ✪✪✪✪✪")).size());
        assertEquals(1, descriptions(all.get("Coal")).size());
    }

    @Test
    void omittedEquipmentTargetsFailValidationAndRetainColor() {
        String names = "Spider, Tarantula, Primordial and Spirit Boots";
        var p =
                new StyledParagraph(
                        List.of(
                                Component.literal("Decreases the mana cost of ")
                                        .append(
                                                Component.literal(names)
                                                        .withStyle(ChatFormatting.GREEN))
                                        .append(Component.literal(" by 36%."))));
        assertTrue(p.encoded().contains(names + "</t"));
        String result =
                p.encoded()
                        .replace("Decreases the ", "減少する ")
                        .replace(" cost of ", "消費 ")
                        .replace(" by ", "を ");
        assertNull(TranslationDiagnostics.validate(p.encoded(), result));
        assertNotNull(
                TranslationDiagnostics.validate(
                        p.encoded(), result.replaceAll("<t\\d+>Spider[^<]+</t\\d+>", "")));
        var glyphs = StyledParagraph.glyphs(p.decode(result));
        int at = p.decode(result).getString().indexOf(names);
        assertEquals(
                net.minecraft.network.chat.TextColor.GREEN.getValue(),
                glyphs.get(at).style().getColor().getValue());
    }

    @Test
    void fractionsNeverSplitWhenTheyFit() {
        String text = "確保済み: 6/8 Timecharms";
        var lines = TooltipWrap.wrap(Component.literal(text), 65, c -> c.getString().length() * 6);
        assertTrue(lines.stream().anyMatch(c -> c.getString().contains("6/8")));
        assertEquals(text, String.join("", lines.stream().map(Component::getString).toList()));
    }

    @Test
    void repairsObservedGrammarWithoutInventingTargets() {
        String names = "Spider, Tarantula, Primordial and Spirit Boots";
        var p =
                new StyledParagraph(
                        List.of(
                                Component.literal(
                                        "Decreases the mana cost of " + names + " by 36%.")));
        String bad = "<t0>mana</t0>コストを<t1>" + names + "</t1>で<n0/>減少させる。";
        String fixed = TranslationRepair.repair(p.encoded(), bad);
        assertEquals(names + "のmana消費を36%軽減する。", p.decode(fixed).getString());
        assertNull(TranslationDiagnostics.validate(p.encoded(), fixed));
        assertNotNull(
                TranslationDiagnostics.validate(
                        p.encoded(),
                        TranslationRepair.repair(p.encoded(), "<t0>mana</t0>を<n0/>減少させる。")));
        assertEquals("確率を上昇させる", TranslationRepair.repair("Increases the chance", "確率を上昇させるする"));
        var sheep =
                new StyledParagraph(
                        List.of(Component.literal("Reduces the mana cost of abilities by 16.4%.")));
        assertEquals(
                "アビリティのmana消費を16.4%軽減する。",
                sheep.decode(
                                TranslationRepair.repair(
                                        sheep.encoded(), "<t0>mana</t0>消費を<n0/>で減少させる。"))
                        .getString());
        assertEquals(
                "アビリティのmana消費を16.4%軽減する。",
                sheep.decode(TranslationRepair.repair(sheep.encoded(), "アビリティのmana消費を<n0/>で減少させる。"))
                        .getString());
    }
}
