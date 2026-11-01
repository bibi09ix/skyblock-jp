package local.skyblockjp;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryRateStyleSafetyTest {
    private static Component t(String text, ChatFormatting color) { return Component.literal(text).withStyle(color); }

    @Test void typedRateDoesNotAuthorizeDroppingAnUnrelatedColoredPhrase() {
        var source = t("Heal 2❤", ChatFormatting.GRAY).copy().append(t("/s", ChatFormatting.BLUE))
            .append(t(" near ", ChatFormatting.GRAY)).append(t("Zephyrus", ChatFormatting.GOLD));
        var result = new StyledParagraph(List.of(source)).dictionaryEncoding("毎秒2❤回復する。");
        assertFalse(result.accepted());
    }

    @Test void partialAndExtendedNativeRateFragmentsCannotUseTheUnitShortcut() {
        String source = "Heal 2❤/s.";
        assertEquals(1, DictionaryRateTokens.styleSpans(source, "毎秒2❤回復する。", 7, 9).size());
        for (int[] range : List.of(new int[]{7, 8}, new int[]{8, 9}, new int[]{6, 9}, new int[]{7, 10})) {
            assertTrue(DictionaryRateTokens.styleSpans(source, "毎秒2❤回復する。", range[0], range[1]).isEmpty());
        }
        assertTrue(DictionaryRateTokens.styleSpans(source, "毎秒3❤回復する。", 7, 9).isEmpty());
        assertTrue(DictionaryRateTokens.styleSpans(source, "毎秒2❈回復する。", 7, 9).isEmpty());
    }
}
