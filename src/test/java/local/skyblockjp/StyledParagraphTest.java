package local.skyblockjp;

import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StyledParagraphTest {
    @Test void grayProseRemainsUntaggedEvenWhenColoredStatNamesDominate() {
        var p = new StyledParagraph(List.of(Component.literal("Grants ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("50 Mining Speed").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" and ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("40 Mining Fortune").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" while in ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("Dwarven Mines").withStyle(ChatFormatting.DARK_GREEN))));
        assertTrue(p.encoded().startsWith("Grants <n0/>"));
        assertTrue(p.encoded().contains(" and <n1/>"));
        assertTrue(p.encoded().contains(" while in <s"));
        assertFalse(p.encoded().contains(">Grants"));
    }
    @Test void restoresNumericValueAndColorAfterReordering() {
        var original = Component.literal("Gain ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("+1,000.5%").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" damage for ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("3").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" seconds.").withStyle(ChatFormatting.GRAY));
        var p = new StyledParagraph(List.of(original));
        assertEquals("Gain <n0/> damage for <n1/> seconds.", p.encoded());
        var decoded = p.decode("<n1/>秒間、ダメージが<n0/>増加する。");
        assertEquals("3秒間、ダメージが+1,000.5%増加する。", decoded.getString());
        var glyphs = StyledParagraph.glyphs(decoded);
        assertEquals(net.minecraft.network.chat.TextColor.YELLOW.getValue(), glyphs.getFirst().style().getColor().getValue());
        var plus = glyphs.stream().filter(g -> g.text().equals("+")).findFirst().orElseThrow();
        assertEquals(net.minecraft.network.chat.TextColor.GREEN.getValue(), plus.style().getColor().getValue());
    }
    @Test void retainsColoredWordsAndJoinsWrappedLines() {
        var p = new StyledParagraph(List.of(Component.literal("Hit nearby ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("enemies").withStyle(ChatFormatting.RED)), Component.literal("for 5 damage.").withStyle(ChatFormatting.GRAY)));
        assertTrue(p.encoded().contains("<s0>"));
        assertFalse(p.encoded().contains("\n"));
        var decoded = p.decode("周囲の<s0>敵</s0>に<n0/>ダメージを与える。");
        var enemy = StyledParagraph.glyphs(decoded).stream().filter(g -> g.text().equals("敵")).findFirst().orElseThrow();
        assertEquals(net.minecraft.network.chat.TextColor.RED.getValue(), enemy.style().getColor().getValue());
    }
    @Test void rejectsMissingDuplicatedAndBrokenMarkers() {
        assertFalse(StyledParagraph.validNumbers("Gain <n0/> Health.", "体力が増加する。"));
        assertFalse(StyledParagraph.validNumbers("Gain <n0/> Health.", "<n0/><n0/>体力"));
        assertFalse(StyledParagraph.validNumbers("<s0>red</s0>", "</s0>赤<s0>"));
        assertFalse(StyledParagraph.validNumbers("Gain <n0/> Health.", "<n0/>体力が100増加"));
    }
}
