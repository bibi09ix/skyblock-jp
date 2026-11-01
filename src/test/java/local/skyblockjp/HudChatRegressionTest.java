package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class HudChatRegressionTest {
    @TempDir Path dir;

    @Test
    void savedHudOptionsAndExpiryIncludeNeverAndChatHistory() throws Exception {
        var c = new Config();
        c.hudX = .1;
        c.hudY = .9;
        c.hudWidth = 400;
        c.hudLines = 12;
        c.hudSeconds = 0;
        c.hudOpacity = .25;
        Config.save(dir.resolve("config.json"), c);
        var restored = Config.load(dir.resolve("config.json"));
        assertEquals(0, restored.hudSeconds);
        assertEquals(.25, restored.hudOpacity);
        assertEquals(24, TranslationHud.x(restored, 640, 400));
        assertEquals(131, TranslationHud.y(restored, 300, TranslationHud.panelHeight(restored)));
        assertTrue(TranslationHud.visible(1_000_000, 0, restored, false));
        restored.hudSeconds = 30;
        assertFalse(TranslationHud.visible(1_000_000, 0, restored, false));
        assertTrue(TranslationHud.visible(1_000_000, 0, restored, true));
        assertNotNull(
                ChatScreen.class.getDeclaredMethod(
                        "mouseScrolled", double.class, double.class, double.class, double.class));
    }

    @Test
    void deathTemplatePreservesVictimKillerAndTheirColors() {
        var source =
                Component.literal("☠ ")
                        .append(Component.literal("DarkNight_1451").withStyle(ChatFormatting.RED))
                        .append(Component.literal(" was killed by "))
                        .append(
                                Component.literal("Empyrean Gaia Construct")
                                        .withStyle(ChatFormatting.GOLD))
                        .append(".");
        var result = ChatText.death(source);
        assertEquals("☠ DarkNight_1451 は Empyrean Gaia Construct に倒された。", result.getString());
        var glyphs = StyledParagraph.glyphs(result);
        assertEquals(
                net.minecraft.network.chat.TextColor.RED.getValue(),
                glyphs.get(2).style().getColor().getValue());
        assertEquals(
                net.minecraft.network.chat.TextColor.GOLD.getValue(),
                glyphs.get(result.getString().indexOf("Empyrean")).style().getColor().getValue());
        assertNull(ChatText.death(Component.literal("Hello")));
    }

    @Test
    void headersAndAbbreviationsRemainWholeAndJsonCannotLeak() throws Exception {
        var p =
                NpcTranslation.wholeChat(
                        Component.literal(
                                "[402] ᛃ [MVP+] TwoSwitch73: lf mith diana i have l5 and bs"));
        assertTrue(p.encoded().contains("TwoSwitch73: </t"));
        assertTrue(p.encoded().contains(">l5</t"));
        assertFalse(p.encoded().contains("TwoSwitch<n"));
        var command = NpcTranslation.whole(Component.literal("lowballing 3b /visit me 100m+"));
        assertTrue(command.encoded().contains(">/visit me</t"));
        var input =
                NpcTranslation.wholeChat(
                        Component.literal("[260] ⚔ [MVP+] ManSui: cqwl u want to buy ti"));
        assertTrue(input.encoded().contains(">cqwl</t"));
        assertTrue(input.encoded().contains(">ti</t"));
        String encoded = input.encoded().replace("u want to buy", "を買いたい？");
        assertNotNull(ChatText.validate(input.encoded(), encoded + " u"));
        String wrapped =
                new com.google.gson.Gson()
                        .toJson(java.util.Map.of("speaker", "CHAT", "dialogue", encoded));
        assertEquals(encoded, ChatText.unwrap(wrapped));
        assertNotNull(NpcTranslation.validate(input.encoded(), wrapped));
        try (var t =
                new Translator(
                        new Config(),
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("test"),
                        text -> wrapped)) {
            assertEquals(encoded, t.requestChat(input, "").get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void coordinatesOnlyCanRemainUnchangedWithoutThreeRetries() throws Exception {
        var p =
                NpcTranslation.wholeChat(
                        Component.literal("[90] [MVP+] Zitely: x: 1, y: 2, z: 3 jawbus"));
        assertTrue(ChatText.coordinatesOnly(p.encoded()));
        assertFalse(
                ChatText.coordinatesOnly(
                        NpcTranslation.whole(Component.literal("Hello there")).encoded()));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try (var t =
                new Translator(
                        new Config(),
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("test"),
                        text -> {
                            calls.incrementAndGet();
                            return text;
                        })) {
            assertEquals(p.encoded(), t.requestChat(p, "").get(5, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
        }
    }
}
