package local.skyblockjp;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class OptionPromptTest {
    @TempDir Path dir;
    @Test void automaticTargetIncludesOptionPromptButNotPlayerChat() {
        assertTrue(NpcTranslation.automaticMessage("Select an option: [Yes] [No]"));
        assertTrue(NpcTranslation.automaticMessage("§eSelect an option: §a[Yes] §c[No]"));
        assertTrue(NpcTranslation.automaticMessage("[NPC] Elle: Hello!"));
        assertFalse(NpcTranslation.automaticMessage("[VIP] Player: Select an option: [Yes]"));
        assertFalse(NpcTranslation.automaticMessage("Player: Hello!"));
        assertFalse(NpcTranslation.automaticMessage("[NPC] Elle"));
    }
    @Test void completePromptIsSentAndOriginalChoicesKeepClicks() throws Exception {
        var yes = Component.literal("[Yes]").withStyle(ChatFormatting.GREEN)
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent.RunCommand("/answer yes")));
        var no = Component.literal("[No]").setStyle(Style.EMPTY.withColor(ChatFormatting.RED).withClickEvent(new ClickEvent.RunCommand("/answer no")));
        var original = Component.literal("Select an option: ").withStyle(ChatFormatting.YELLOW).append(yes).append(Component.literal(" ")).append(no);
        var paragraph = NpcTranslation.whole(original);
        assertTrue(paragraph.encoded().contains("Select an option:"));
        assertFalse(paragraph.encoded().contains("<t0>Select an option:"));
        String result = "選択肢を選んでください: <s0>[はい]</s0> <s1>[いいえ]</s1>";
        try (var t = new Translator(new Config(), dir.resolve("cache.json"), LoggerFactory.getLogger("test"), text -> {
            assertEquals(paragraph.encoded(), text);
            return result;
        })) {
            var translated = paragraph.decodeForDisplay(t.requestNpc(paragraph, "選択肢", "").get(3, TimeUnit.SECONDS));
            assertEquals("選択肢を選んでください: [はい] [いいえ]", translated.getString());
            assertEquals("Select an option: [Yes] [No]", original.getString());
            assertTrue(StyledParagraph.glyphs(translated).stream().anyMatch(g -> g.text().equals("は") && g.style().getClickEvent().equals(new ClickEvent.RunCommand("/answer yes"))));
            assertEquals(new ClickEvent.RunCommand("/answer no"), no.getStyle().getClickEvent());
        }
    }
    @Test void manualPromptDoesNotGetSplitIntoHeaderAndBody() {
        var p = NpcTranslation.whole(Component.literal("Select an option: [Yes] [No]"));
        assertEquals(p.encoded(), ChatText.body(p.encoded()));
        var player = NpcTranslation.wholeChat(Component.literal("[VIP] Player: hello"));
        assertFalse(ChatText.body(player.encoded()).contains("Player:"));
    }
}
