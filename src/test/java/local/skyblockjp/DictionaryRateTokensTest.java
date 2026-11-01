package local.skyblockjp;

import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryRateTokensTest {
    private static Component t(String text, ChatFormatting color) { return Component.literal(text).withStyle(color); }
    private static Component translated(Component source, String target) {
        var p = new StyledParagraph(List.of(source)); var result = p.dictionaryEncoding(target);
        assertTrue(result.accepted(), () -> result.reason() + result.details() + " / " + source.getString());
        return p.decode(result.text());
    }
    private static void reject(String source, String target) {
        var result = new StyledParagraph(List.of(t(source, ChatFormatting.GRAY))).dictionaryEncoding(target);
        assertFalse(result.accepted(), source + " => " + target);
    }
    private static void color(Component value, String fragment, ChatFormatting expected) {
        int start = value.getString().indexOf(fragment); assertTrue(start >= 0, value.getString()); int offset = 0;
        for (var glyph : StyledParagraph.glyphs(value)) {
            if (offset >= start && offset < start + fragment.length()) assertEquals(t("x", expected).getStyle().getColor(), glyph.style().getColor());
            offset += glyph.text().length();
        }
    }
    @Test void allObservedHealthRateDescriptionsKeepExactAmountsAndCaps() {
        for (int value : List.of(2, 3, 4)) {
            String source = "Heal " + value + "❤/s per enemy within 10 blocks, up to " + value * 10 + "❤/s.";
            String target = "10ブロック以内の敵ごとに毎秒" + value + "❤回復する。上限は毎秒" + value * 10 + "❤。";
            assertEquals(target, translated(t(source, ChatFormatting.GRAY), target).getString());
            assertTrue(CommandTokens.parse(source).isEmpty());
            reject(source, target.replace("上限は毎秒", "上限は"));
            reject(source, target.replace("毎秒" + value + "❤", "毎分" + value + "❤"));
        }
        for (var pair : Map.of("15%", "4%", "20%", "7%", "25%", "10%").entrySet()) {
            String source = "While in combat on the Crimson Isle, spawn a pool every 8s. Bathing in it heals "
                + pair.getKey() + "❤ now and " + pair.getValue() + "❤/s for 8s.";
            String target = "Crimson Isleで戦闘中、8秒ごとに水たまりを作る。中に入ると、即座に❤を" + pair.getKey()
                + "回復し、さらに8秒間、毎秒❤を" + pair.getValue() + "回復する。";
            assertEquals(target, translated(t(source, ChatFormatting.GRAY), target).getString());
            reject(source, target.replace("毎秒❤を", "毎分❤を"));
            reject(source, target.replace("毎秒❤を", "毎秒を"));
        }
    }
    @Test void rateValueGlyphAndColoredUnitRemainBoundWhenJapaneseUsesAPrefix() {
        var source = t("Heal ", ChatFormatting.GRAY).copy().append(t("2", ChatFormatting.GREEN))
            .append(t("❤", ChatFormatting.RED)).append(t("/s", ChatFormatting.BLUE)).append(t(".", ChatFormatting.GRAY));
        for (String target : List.of("毎秒2❤回復する。", "秒あたり❤を2回復する。", "2❤秒あたり回復する。")) {
            var result = translated(source, target);
            color(result, "2", ChatFormatting.GREEN); color(result, "❤", ChatFormatting.RED);
            color(result, target.contains("毎秒") ? "毎秒" : "秒あたり", ChatFormatting.BLUE);
        }
        for (String target : List.of("毎秒3❤回復する。", "毎秒2回復する。", "毎秒2❈回復する。",
            "毎分2❤回復する。", "2❤回復する。", "毎秒2❤と毎秒2❤を回復する。", "毎秒2❤/s回復する。")) reject(source.getString(), target);
    }
    @Test void equalRateValuesUseCapRolesToPreserveDifferentNativeStyles() {
        var source = t("Heal ", ChatFormatting.GRAY).copy().append(t("2", ChatFormatting.GREEN))
            .append(t("❤", ChatFormatting.RED)).append(t("/s", ChatFormatting.BLUE))
            .append(t(" per enemy, up to ", ChatFormatting.GRAY)).append(t("2", ChatFormatting.GOLD))
            .append(t("❤", ChatFormatting.LIGHT_PURPLE)).append(t("/s", ChatFormatting.YELLOW));
        String target = "上限は毎秒2❤。敵ごとに毎秒2❤回復する。";
        var output = translated(source, target);
        color(output, "2", ChatFormatting.GOLD); color(output, "❤", ChatFormatting.LIGHT_PURPLE); color(output, "毎秒", ChatFormatting.YELLOW);
        var numerals = StyledParagraph.glyphs(output).stream().filter(g -> g.text().equals("2")).toList();
        assertEquals(t("x", ChatFormatting.GREEN).getStyle().getColor(), numerals.getLast().style().getColor());
        reject(source.getString(), "毎秒2❤と毎秒2❤回復する。");
    }
    @Test void slashSWithoutTheExactHealthRateShapeRemainsACommand() {
        for (String source : List.of("Use /s.", "Use /s Steve.", "Use ❤/s.", "Use 2 /s.")) {
            assertFalse(CommandTokens.parse(source).isEmpty(), source);
            reject(source, "毎秒の説明。");
        }
        assertEquals("/s", CommandTokens.parse("Use /s.").getFirst().text());
        assertFalse(DictionaryRateTokens.isRateSuffix("Heal 2/s.", 6, 8));
        assertFalse(DictionaryRateTokens.inspect("Heal 2❤/s.", "2❤回復する。").reason().isEmpty());
    }
}
