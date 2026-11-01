package local.skyblockjp;

import net.minecraft.ChatFormatting;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChatHoverHintTest {
    @Test void textHoverKeepsEveryOriginalGlyphStyleAndNewlineWithoutMutatingSource() {
        var original = Component.literal("Quest details\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal("Second line").setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withItalic(true)
                .withClickEvent(new ClickEvent.RunCommand("/quest"))));
        var before = StyledParagraph.glyphs(original);
        var style = Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(original));
        var display = ChatHoverHint.compose(style, "G");
        assertNull(display.separateHint());
        assertEquals("Quest details\nSecond line\nGで翻訳", display.tooltip().getString());
        var actual = StyledParagraph.glyphs(display.tooltip());
        assertEquals(before, actual.subList(0, before.size()));
        assertEquals(before, StyledParagraph.glyphs(original));
        var hintStyle = actual.getLast().style();
        assertEquals(0xAAAAAA, hintStyle.getColor().getValue());
        assertFalse(hintStyle.isBold()); assertFalse(hintStyle.isItalic()); assertNull(hintStyle.getClickEvent());
        assertSame(original, ((HoverEvent.ShowText)style.getHoverEvent()).value());
    }
    @Test void plainHoverUsesTheConfiguredKeyAndNoExtraHint() {
        var result = ChatHoverHint.compose(Style.EMPTY, "H");
        assertEquals("Hで翻訳", result.tooltip().getString()); assertNull(result.separateHint());
    }
    @Test void styleHitTestingUsesTheCharacterUnderTheCursorRatherThanTheMessageRoot() {
        var normal = Style.EMPTY.withColor(ChatFormatting.GRAY);
        var linked = Style.EMPTY.withColor(ChatFormatting.GOLD).withHoverEvent(new HoverEvent.ShowText(Component.literal("Link details")));
        var sequence = FormattedCharSequence.composite(List.of(FormattedCharSequence.forward("AB", normal), FormattedCharSequence.forward("CD", linked)));
        var splitter = new StringSplitter((cp, style) -> cp == 'C' ? 2 : 1);
        assertNull(ChatHover.styleAt(sequence, -1, splitter));
        assertEquals(normal, ChatHover.styleAt(sequence, 1.5, splitter));
        assertEquals(linked, ChatHover.styleAt(sequence, 2, splitter));
        assertEquals(linked, ChatHover.styleAt(sequence, 3.5, splitter));
        assertEquals(linked, ChatHover.styleAt(sequence, 4.5, splitter));
        assertNull(ChatHover.styleAt(sequence, 5, splitter));
    }
}
