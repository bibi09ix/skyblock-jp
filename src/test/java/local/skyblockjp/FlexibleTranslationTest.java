package local.skyblockjp;

import org.junit.jupiter.api.Test;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FlexibleTranslationTest {
    @Test void understandsProseQuantitiesWithoutRelaxingProtectedValues() {
        for (String[] pair : new String[][] {
            {"Heal <n0/> per second.", "1秒ごとに<n0/>回復する。"},
            {"Gain <n0/> every minute.", "1分ごとに<n0/>獲得する。"},
            {"Only one item.", "アイテムは1つだけ。"},
            {"Consumes quadruple the Fuel.", "Fuelを4倍消費する。"},
            {"Spawns a minion every <n0/> seconds, maximum <n1/>.", "<n0/>秒ごとに1体生成し、最大<n1/>体まで。"},
            {"Gain two charges.", "チャージを2つ獲得する。"}}) {
            assertNull(TranslationDiagnostics.validate(pair[0], pair[1]), pair[0]);
        }
        assertNotNull(TranslationDiagnostics.validate("Heal <n0/> per second.", "2秒ごとに<n0/>回復する。"));
        assertNotNull(TranslationDiagnostics.validate("Heal <n0/> per second.", "1秒ごとに50回復する。"));
        assertNotNull(TranslationDiagnostics.validate("Only one item.", "アイテムは1つ、追加で1つ。"));
        assertNotNull(TranslationDiagnostics.validate("Gain 50 Health.", "体力が5増える。"));
        assertNotNull(TranslationDiagnostics.validate("Gain <n0/>.", "<n0/>に加えて+1増える。"));
    }
    @Test void restoresIconsAndColorsAndAcceptsNaturalCountsAtDecode() {
        var paragraph = new StyledParagraph(List.of(Component.literal("Gain ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("+10\uE00D Strength").withStyle(ChatFormatting.RED))
            .append(Component.literal(" every minute.").withStyle(ChatFormatting.GRAY))));
        assertFalse(paragraph.encoded().contains("\uE00D"));
        String translated = "1分ごとに<n0/><n1/><t0>Strength</t0>が増加する。";
        assertNull(TranslationDiagnostics.validate(paragraph.encoded(), translated));
        Component decoded = paragraph.decode(translated);
        assertEquals("1分ごとに+10\uE00DStrengthが増加する。", decoded.getString());
        assertEquals(net.minecraft.network.chat.TextColor.RED.getValue(), StyledParagraph.glyphs(decoded).stream().filter(g -> g.text().equals("\uE00D")).findFirst().orElseThrow().style().getColor().getValue());
        assertThrows(IllegalArgumentException.class, () -> paragraph.decode(translated.replace("<n1/>", "")));
    }
    @Test void excludesBulletStatsWithoutDroppingDescriptions() {
        assertTrue(LoreParser.protectedLine("‣ +15\uE01A Magic Find"));
        assertTrue(LoreParser.protectedLine("• +50 Strength"));
        assertFalse(LoreParser.protectedLine("• Heal yourself for 50❤ per second."));
        assertFalse(LoreParser.protectedLine("‣ +25☯ on all Wisdom stats"));
    }
    @Test void rejectsMissingApplicationConditionButAcceptsParaphrase() {
        String source = "The pet must be visible to apply the item!";
        assertTrue(TranslationDiagnostics.validate(source, "ペットが見えている必要がある。").startsWith("CONTENT_MISSING"));
        assertNull(TranslationDiagnostics.validate(source, "アイテムを装着するには、ペットを表示しておく必要がある。"));
        assertNull(TranslationDiagnostics.validate("You can swap it at any time.", "いつでも入れ替えられる。"));
    }
}
