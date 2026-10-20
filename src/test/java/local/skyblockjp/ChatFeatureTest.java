package local.skyblockjp;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatFeatureTest {
    @TempDir Path dir;
    @Test void buttonsPreserveOriginalLinksAndWorkAtEitherEnd() {
        var source = Component.literal("Click for quest").setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)
            .withClickEvent(new ClickEvent.RunCommand("/quest"))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Quest details"))));
        for (boolean first : new boolean[]{true, false}) {
            Component decorated = MessageHandles.decorate(source, "123", first);
            assertEquals(first ? "[訳] Click for quest" : "Click for quest [訳]", decorated.getString());
            Component recovered = MessageHandles.original(decorated);
            assertEquals(source, recovered);
            assertEquals(source.getStyle().getClickEvent(), recovered.getStyle().getClickEvent());
            Component button = first ? decorated.getSiblings().getFirst() : decorated.getSiblings().getLast();
            assertEquals("/sbjp translate 123", ((ClickEvent.RunCommand)button.getStyle().getClickEvent()).command());
        }
    }
    @Test void handlesEvictOldEntriesAndNeverContainChatText() {
        var handles = new MessageHandles(2);
        String old = handles.put(Component.literal("secret original"));
        String next = handles.put(Component.literal("Hello"));
        handles.put(Component.literal("World"));
        assertNull(handles.get(old)); assertEquals("Hello", handles.get(next).getString());
        assertFalse(next.contains("Hello")); handles.clear(); assertNull(handles.get(next));
    }
    @Test void playerNamesUrlsAndCommandsAreImmutable() {
        var p = NpcTranslation.whole(Component.literal("[MVP+] Player123: go to https://hypixel.net or /bank"));
        assertTrue(p.encoded().matches("(?s).*<t\\d+>[^<>]*Player123[^<>]*</t\\d+>.*"));
        assertTrue(p.encoded().contains(">https://hypixel.net</t"));
        assertTrue(p.encoded().contains(">/bank</t"));
        assertNull(NpcTranslation.validate("What is a language model?", "言語モデルって何？"));
    }
    @Test void configAndHudPositionSurviveResolutionChanges() throws Exception {
        var config = new Config(); config.translationHud = true; config.chatButtonFirst = true;
        config.hudX = 1; config.hudY = 0.25;
        Config.save(dir.resolve("config.json"), config);
        var restored = Config.load(dir.resolve("config.json"));
        assertTrue(restored.translationHud); assertTrue(restored.chatButtonFirst);
        assertEquals(640 - 320, TranslationHud.x(restored, 640, 320));
        assertEquals(1920 - 320, TranslationHud.x(restored, 1920, 320));
        assertEquals(0, TranslationHud.x(restored, 200, 320));
        assertEquals(50, TranslationHud.y(restored, 300, 100));
    }
    @Test void mixinTargetsMatchInstalledMinecraft26Api() throws Exception {
        assertNotNull(ChatComponent.class.getDeclaredMethod("addMessage", Component.class, MessageSignature.class, GuiMessageSource.class, GuiMessageTag.class));
        assertNotNull(ChatComponent.class.getDeclaredField("allMessages"));
        for (String name : new String[]{"defaultHandleClickEvent", "defaultHandleGameClickEvent"})
            assertNotNull(Screen.class.getDeclaredMethod(name, ClickEvent.class, Minecraft.class, Screen.class));
    }
    @Test void contextsAndManualChatHaveIndependentCaches() throws Exception {
        var calls = new AtomicInteger();
        var p = NpcTranslation.whole(Component.literal("I wonder if one goes missing..."));
        try (var t = new Translator(new Config(), dir.resolve("cache.json"), LoggerFactory.getLogger("chat-test"), text -> { calls.incrementAndGet(); return "1匹いなくなったら気づくかな…"; })) {
            t.requestNpc(p, "Seffea", "I love cats.").get(3, TimeUnit.SECONDS);
            t.requestNpc(p, "Seffea", "I love cats.").get(3, TimeUnit.SECONDS);
            t.requestNpc(p, "Seffea", "I love rabbits.").get(3, TimeUnit.SECONDS);
            t.requestChat(p, "I love cats.").get(3, TimeUnit.SECONDS);
            assertEquals(3, calls.get());
            assertTrue(Files.readString(dir.resolve("npc-translations.jsonl")).contains("I love cats."));
            assertTrue(Files.readString(dir.resolve("chat-translations.jsonl")).contains("1匹"));
        }
    }
}
