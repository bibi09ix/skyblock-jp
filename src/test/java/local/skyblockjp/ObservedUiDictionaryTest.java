package local.skyblockjp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ObservedUiDictionaryTest {
    private record Example(String source, String target, ChatFormatting color) {}
    private static final List<Example> EXAMPLES = List.of(
        new Example("Close", "閉じる", ChatFormatting.RED),
        new Example("Left-click to summon!", "左クリックで呼び出す！", ChatFormatting.YELLOW),
        new Example("Shift Left-click to toggle as favorite!", "Shiftを押しながら左クリックでお気に入りを登録・解除！", ChatFormatting.YELLOW),
        new Example("Right-click to convert to an item!", "右クリックでアイテムに戻す！", ChatFormatting.YELLOW),
        new Example("Also accessible via /profiles", "/profilesからも開ける。", ChatFormatting.DARK_GRAY),
        new Example("Also accessible via /stats", "/statsからも開ける。", ChatFormatting.DARK_GRAY),
        new Example("Right-click to manage!", "右クリックで管理！", ChatFormatting.DARK_GRAY),
        new Example("Left-click to call!", "左クリックで電話をかける！", ChatFormatting.YELLOW)
    );

    @Test void observedControlsResolveInUiAndRetainNativeColors() {
        var dictionary = BundledDictionary.loadBundled();
        for (var example : EXAMPLES) {
            var match = dictionary.lookup("ui", example.source(), "").orElseThrow();
            assertEquals(example.target(), match.text());
            assertTrue(match.sourceIds().stream().anyMatch(id -> id.startsWith("observed/ui/")));
            var source = Component.literal(example.source()).withStyle(example.color()).withStyle(style -> style.withItalic(false));
            var paragraph = new StyledParagraph(List.of(source));
            var encoding = paragraph.dictionaryEncoding(match);
            assertTrue(encoding.accepted(), example.source() + ": " + encoding.reason());
            var decoded = paragraph.decode(encoding.text());
            assertEquals(example.target(), decoded.getString());
            for (var glyph : StyledParagraph.glyphs(decoded)) {
                assertEquals(Component.literal("x").withStyle(example.color()).getStyle().getColor().getValue(),
                    glyph.style().getColor().getValue(), example.source());
                assertFalse(glyph.style().isItalic(), example.source());
            }
        }
    }

    @Test void observedUiWordsDoNotBecomeItemNamesOrNpcDialogue() {
        var dictionary = BundledDictionary.loadBundled();
        for (var example : EXAMPLES) {
            for (String channel : List.of("item", "npc", "system", "common", "player_chat"))
                assertTrue(dictionary.lookup(channel, example.source(), channel.equals("npc") ? "Farmhand" : "").isEmpty(), channel + ": " + example.source());
        }
        assertTrue(dictionary.lookup("ui", "Also accessible via /unobserved", "").isEmpty());
        assertTrue(dictionary.lookup("ui", "Left-click to summon a dragon!", "").isEmpty());
    }
}
