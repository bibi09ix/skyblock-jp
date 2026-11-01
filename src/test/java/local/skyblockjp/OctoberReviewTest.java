package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class OctoberReviewTest {
    static JsonObject fixtures() throws Exception {
        try (var in =
                OctoberReviewTest.class.getResourceAsStream("/october-review-fixtures.json")) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    @Test
    void numberedContinuationAndChocolateStatTableStayIntact() throws Exception {
        var records = fixtures().getAsJsonArray("items");
        for (var record : records) {
            var r = record.getAsJsonObject();
            List<String> lines = new ArrayList<>();
            r.getAsJsonArray("originalLines").forEach(v -> lines.add(v.getAsString()));
            var blocks = LoreParser.parse(lines);
            if (r.get("item").getAsString().equals("Chocobit Tower"))
                assertTrue(blocks.stream().anyMatch(b -> b.text().contains("#1 to #33333!")));
            if (r.get("item").getAsString().equals("Hoppity's Collection"))
                assertFalse(
                        blocks.stream()
                                .anyMatch(
                                        b ->
                                                b.text().contains("Total Chocolate")
                                                        || b.text().contains("+589")));
        }
    }

    @Test
    void removesInventedColorsOnlyWhenThereIsNoSourceColor() {
        assertEquals("馬鹿げてる！", TranslationRepair.repair("This is absurd!", "<s1>馬鹿げてる！</s1>"));
        String source = "<s0>Hi!</s0>";
        String incorrect = "<s1>こんにちは！</s1>";
        assertNotNull(
                TranslationDiagnostics.validate(
                        source, TranslationRepair.repair(source, incorrect)));
        assertNotNull(
                TranslationDiagnostics.validate(
                        "Gain <n0/>.", TranslationRepair.repair("Gain <n0/>.", "<s1>増加する。</s1>")));
    }

    @Test
    void acceptsOrdinalCountsAndCatchesUntranslatedConnectingProse() {
        assertNull(NpcTranslation.validate("The second island is here!", "2つ目の島が登場したよ！"));
        assertNotNull(NpcTranslation.validate("The second island is here!", "3つ目の島が登場したよ！"));
        assertTrue(
                NpcTranslation.validate(
                                "The second island is here!",
                                "The second island of Galatea、ここにあるよ！")
                        .startsWith("UNTRANSLATED_PROSE"));
        assertTrue(
                NpcTranslation.validate(
                                "Access it from Launch Pad over in the Moonglade Marsh.",
                                "Launch Pad over in the Moonglade Marshから行けるよ。")
                        .startsWith("UNTRANSLATED_PROSE"));
        assertEquals("手伝う", TranslationRepair.repair("Help Hoppity", "手伝うする"));
        var p =
                NpcTranslation.paragraph(
                        Component.literal("[NPC] Farmhand: Did you craft a §aWheat Minion§f yet?"));
        assertFalse(p.encoded().contains("<s"));
        assertNotNull(
                TranslationDiagnostics.validate(
                        "<t0>Wheat Minion</t0>", "<t0>Wheat Minion</t0>を作った？Wheat Minion"));
    }

    @Test
    void preservesDistinctRabbitMeaningAndMultiwordNames() {
        assertNotNull(
                TranslationDiagnostics.validate(
                        "The more unique rabbits you find, the more it produces.",
                        "ウサギを見つけるほど生産量が増加する。"));
        assertNull(
                TranslationDiagnostics.validate(
                        "The more unique rabbits you find, the more it produces.",
                        "異なる種類のウサギを見つけるほど生産量が増加する。"));
        String text = "Chocolate Shopで買える。";
        var lines = TooltipWrap.wrap(Component.literal(text), 90, c -> c.getString().length() * 6);
        assertTrue(lines.stream().anyMatch(c -> c.getString().contains("Chocolate Shop")));
    }
}
