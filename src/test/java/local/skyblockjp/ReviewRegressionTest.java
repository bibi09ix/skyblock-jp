package local.skyblockjp;
import com.google.gson.*;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ReviewRegressionTest {
    @Test void keepsDrillPartsOnTheirOwnOriginalLines() {
        List<String> lines = List.of("Drill", "Titanium-Infused Fuel Tank", "25,000 Max Fuel Capacity", "",
            "Ruby-Polished Drill Engine", "+250 Mining Speed", "", "Sunny Side Goblin Egg Part",
            "Grants +50 Mining Fortune, but this Drill", "consumes quadruple the Fuel.");
        var blocks = LoreParser.parse(lines);
        assertEquals(1, blocks.size()); assertEquals(8, blocks.getFirst().start());
        assertTrue(blocks.getFirst().text().startsWith("Grants"));
        assertFalse(LoreParser.protectedLine("Grants more Fuel Tank capacity."));
    }
    @Test void rendersMinutesOnlyInExplicitTimeContextAndKeepsValueColor() {
        var p = new StyledParagraph(List.of(Component.literal("Lasts ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("60m").withStyle(ChatFormatting.GREEN)).append(Component.literal(".").withStyle(ChatFormatting.GRAY))));
        Component decoded = p.decode("<n0/>間有効。");
        assertEquals("60分間有効。", decoded.getString());
        assertEquals(net.minecraft.network.chat.TextColor.GREEN.getValue(), StyledParagraph.glyphs(decoded).get(2).style().getColor().getValue());
        var coins = new StyledParagraph(List.of(Component.literal("Costs 60m coins.")));
        assertEquals("60mコイン。", coins.decode("<n0/>コイン。").getString());
    }
    @Test void catchesKnownMeaningAndUntranslatedTextFailures() {
        String source = "Can be cast after landing <n0/> hits.";
        assertTrue(TranslationDiagnostics.validate(source, "着地後<n0/>ヒットで発動できる。").startsWith("MEANING_"));
        assertTrue(TranslationDiagnostics.validate(source, "landing <n0/> hitsの後に発動できる。").startsWith("MEANING_"));
        assertNull(TranslationDiagnostics.validate(source, "攻撃を<n0/>回命中させると発動できる。"));
        assertEquals("Can be activated after hitting enemies <n0/> times.", TranslationInput.clarify(source));
        assertEquals("敵に<n0/>回命中させた後に発動できる。", TranslationRepair.repair(source, "敵に成功で<n0/>回命中させた後にキャストできる。"));
        assertEquals("敵に攻撃を<n0/>回命中させた後に発動できる。", TranslationRepair.repair(source, "敵を<n0/>回命中させた後に発動できる。"));
        assertTrue(TranslationDiagnostics.validate("Gain more per gem (max <n0/>).", "gem per 獲得(max <n0/>)。").startsWith("UNTRANSLATED_"));
    }
    @Test void listMarkersCannotDisappearDuringTranslation() {
        var p = new StyledParagraph(List.of(Component.literal("‣ Grants flight.")));
        assertThrows(IllegalArgumentException.class, () -> p.decode("飛行できる。"));
        assertEquals("‣ 飛行できる。", p.decode("<n0/> 飛行できる。").getString());
    }
    @Test void protectedPerUnitAndMissingTermWrappersRemainRecoverable() {
        String source = "Gain <n0/> per <t0>Perfect Gemstone</t0>.";
        String repaired = TranslationRepair.repair(source, "Perfect Gemstone1個につき<n0/>増加する。");
        assertNull(TranslationDiagnostics.validate(source, repaired));
        assertNotNull(TranslationDiagnostics.validate(source, "<t0>Perfect Gemstone</t0>2個につき<n0/>増加する。"));
        assertEquals("<t0>mana</t0>を消費する。", TranslationRepair.repair("<t0>mana</t0>", "Manaを消費する。"));
        assertEquals("ManaとMana", TranslationRepair.repair("<t0>Mana</t0>", "ManaとMana"));
        assertEquals("原木<n0/>個を伐採する。", TranslationRepair.repair("Cut <n0/> Logs.", "<n0/> Logsを伐採する。"));
    }
    @Test void wrapsEveryReviewedParagraphWithoutSplittingNumbersCommandsOrKatakana() throws Exception {
        try (var stream = getClass().getResourceAsStream("/reviewed-display-fixtures.json")) {
            var records = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
            int checked = 0;
            Pattern tokens = Pattern.compile("/[A-Za-z]+|[+−-]?\\d[\\d,]*(?:\\.\\d+)?|[ァ-ヺー]+");
            for (var record : records) for (var paragraph : record.getAsJsonObject().getAsJsonArray("translatedParagraphs")) {
                String text = paragraph.getAsString();
                for (int width : List.of(160, 260, 320)) {
                    var lines = TooltipWrap.wrap(Component.literal(text), width, ReviewRegressionTest::width).stream().map(Component::getString).toList();
                    assertEquals(text, String.join("", lines));
                    int at = 0;
                    for (int i = 0; i < lines.size() - 1; i++) {
                        at += lines.get(i).length();
                        int boundary = at;
                        tokens.matcher(text).results().forEach(m -> {
                            if (width(Component.literal(m.group())) <= width)
                                assertFalse(m.start() < boundary && boundary < m.end(), record.getAsJsonObject().get("item") + " split " + m.group());
                        });
                    }
                }
                checked++;
            }
            assertEquals(50, checked);
        }
    }
    private static int width(Component c) {
        return c.getString().codePoints().map(cp -> cp < 128 ? 6 : 9).sum();
    }
}
