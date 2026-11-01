package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class ChatHudLifecycleRegressionTest {
    @Test
    void channelHeadersProtectNumericNamesAndUseTheSameHudBoundaries() {
        for (String prefix :
                List.of("", "Party > ", "Guild > ", "Officer > ", "Co-op > ", "From ", "To ")) {
            String text = prefix + "[MVP+] Player123: Hello there!";
            var header = ChatEnvelope.player(text);
            assertNotNull(header, text);
            assertEquals("Player123", text.substring(header.nameStart(), header.nameEnd()));
            var paragraph = NpcTranslation.wholeChat(Component.literal(text));
            assertTrue(paragraph.encoded().contains("Player123: </t"), paragraph.encoded());
            assertFalse(paragraph.encoded().contains("Player<n"), paragraph.encoded());
            if (prefix.contains(">"))
                assertTrue(paragraph.encoded().contains("&gt;"), paragraph.encoded());
            assertEquals(text, paragraph.decode(paragraph.encoded()).getString());
            var row = HudRows.message("チャット", paragraph.decode(paragraph.encoded()));
            assertEquals(
                    "Player123", row.name().getString(), text + " encoded=" + paragraph.encoded());
            assertEquals("Hello there!", row.text().getString());
            assertEquals("Hello there!", ChatText.body(paragraph.encoded()));
        }
        var extraSpaces =
                NpcTranslation.wholeChat(Component.literal("Party > [MVP+] Player123:   Hello!"));
        assertEquals("Hello!", ChatText.body(extraSpaces.encoded()));
    }

    @Test
    void headerParserRetainsRanksIconsGuildTagsAndDoesNotClaimNpcMessages() {
        String text = "Guild > [260] ⚔ [MVP+] Player123 [TAG]: hi";
        var header = ChatEnvelope.player(text);
        assertNotNull(header);
        assertEquals("Player123", text.substring(header.nameStart(), header.nameEnd()));
        assertEquals("hi", text.substring(header.bodyStart()));
        for (String excluded :
                List.of(
                        "[NPC] Elle: Hello!",
                        "[BOSS] Maxor: Hello!",
                        "[JP] Test: こんにちは",
                        "Select an option: Hello",
                        "Party > no colon")) assertNull(ChatEnvelope.player(excluded), excluded);
        var styled =
                Component.literal("Party > [MVP+] ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal("Player123").withStyle(ChatFormatting.AQUA))
                        .append(": ")
                        .append(Component.literal("こんにちは！").withStyle(ChatFormatting.GOLD));
        var row = HudRows.message("チャット", styled);
        assertEquals(
                0x55FFFF,
                StyledParagraph.glyphs(row.name()).getFirst().style().getColor().getValue());
        assertEquals(
                0xFFAA00,
                StyledParagraph.glyphs(row.text()).getFirst().style().getColor().getValue());
    }

    @Test
    void legacyFormattingBeforeNpcHeaderFlowsIntoBodyAndResetStillWorks() {
        var prefix = NpcTranslation.paragraph(Component.literal("§c[NPC] Elle: Hello there!"));
        var suffix = NpcTranslation.paragraph(Component.literal("[NPC] Elle: §cHello there!"));
        assertEquals("Hello there!", prefix.sourceText());
        assertEquals(prefix.decode(prefix.encoded()), suffix.decode(suffix.encoded()));
        assertEquals(
                0xFF5555,
                StyledParagraph.glyphs(prefix.decode(prefix.encoded()))
                        .getFirst()
                        .style()
                        .getColor()
                        .getValue());
        var reset = NpcTranslation.paragraph(Component.literal("§c§l[NPC] Elle: Hello§r again!"));
        var glyphs = StyledParagraph.glyphs(reset.decode(reset.encoded()));
        assertTrue(glyphs.getFirst().style().isBold());
        assertEquals(0xFF5555, glyphs.getFirst().style().getColor().getValue());
        assertFalse(glyphs.getLast().style().isBold());
        assertEquals(0xFFFFFF, glyphs.getLast().style().getColor().getValue());
    }

    @Test
    void legacyHeaderColorDoesNotOverrideANewNativeBodyStyle() {
        var message =
                Component.literal("§c[NPC] Elle:")
                        .append(Component.literal("Hello!").withStyle(ChatFormatting.GREEN));
        var paragraph = NpcTranslation.paragraph(message);
        assertEquals(
                0x55FF55,
                StyledParagraph.glyphs(paragraph.decode(paragraph.encoded()))
                        .getFirst()
                        .style()
                        .getColor()
                        .getValue());
    }

    @Test
    void connectionTransitionClearsOldHistoryBeforeNewEntriesAndIsIdempotent() throws Exception {
        var mod = new SkyBlockJp();
        var chats = new ChatTranslations(mod);
        try {
            Object first = new Object(), second = new Object();
            chats.syncConnection(first);
            mod.hud().add("システム", Component.literal("最初の通知"));
            chats.syncConnection(
                    first); // original message decoration must not erase its freshly displayed
            // translation
            assertEquals(1, historySize(mod.hud()));
            chats.syncConnection(second);
            assertEquals(0, historySize(mod.hud()));
            mod.hud().add("システム", Component.literal("接続後の通知"));
            chats.syncConnection(second);
            assertEquals(1, historySize(mod.hud()));
            chats.syncConnection(null);
            assertEquals(0, historySize(mod.hud()));
        } finally {
            mod.hud().close();
        }
    }

    private static int historySize(TranslationHud hud) throws Exception {
        var field = TranslationHud.class.getDeclaredField("entries");
        field.setAccessible(true);
        return ((Collection<?>) field.get(hud)).size();
    }

    @Test
    void onlyActuallyDrawnScrollableHudConsumesWheelInput() throws Exception {
        var hud = new TranslationHud();
        var c = new Config();
        try {
            assertFalse(
                    hud.scroll(10, 10, 1, c),
                    "never rendered or empty HUD must not consume scrolling");
            assertFalse(
                    new HudViewport(10, 10, 300, 44, 2, 2).scrollableAt(20, 20),
                    "all lines already fit");
            var viewport = new HudViewport(10, 10, 300, 55, 3, 20);
            assertTrue(viewport.scrollableAt(20, 20));
            assertFalse(
                    viewport.scrollableAt(20, 100),
                    "unused portion of configured panel is not drawn");
            assertFalse(viewport.scrollableAt(310, 20));
            assertEquals(17, viewport.scroll(16, 1));
            assertEquals(0, viewport.scroll(1, -1));
            var field = TranslationHud.class.getDeclaredField("viewport");
            field.setAccessible(true);
            field.set(hud, viewport);
            assertTrue(hud.scroll(20, 20, 1, c));
            hud.clear();
            assertFalse(
                    hud.scroll(20, 20, 1, c),
                    "disconnect/clear must invalidate the previous input rectangle");
        } finally {
            hud.close();
        }
    }

    @Test
    void fixedHistoryDoesNotRemeasureOrRewrapEveryFrame() {
        var messages = new ArrayList<HudRows.Message>();
        for (int i = 0; i < 200; i++)
            messages.add(HudRows.message("NPC" + i, Component.literal("履歴が長くても毎フレーム同じ文章を折り返さない。")));
        var cache = new HudLayoutCache();
        var calls = new AtomicInteger();
        Object font = new Object();
        ToIntFunction<Component> measure =
                text -> {
                    calls.incrementAndGet();
                    return text.getString().length() * 6;
                };
        var layout = cache.layout(messages, 320, font, measure);
        int initial = calls.get();
        assertTrue(initial > 200);
        for (int frame = 0; frame < 100; frame++)
            assertSame(layout, cache.layout(messages, 320, font, measure));
        assertEquals(
                initial,
                calls.get(),
                "unchanged history must perform zero additional font measurements");
        var rows =
                HudRows.window(
                        layout,
                        Math.max(0, layout.rows().size() - 13),
                        layout.rows().size(),
                        measure);
        assertTrue(rows.size() <= 13);
    }

    @Test
    void layoutCacheInvalidatesForTextStyleWidthFontAndResourceChanges() {
        var cache = new HudLayoutCache();
        Object font = new Object();
        ToIntFunction<Component> measure = text -> text.getString().length() * 6;
        var body = Component.literal("First");
        var messages =
                List.of(
                        new HudRows.Message(
                                Component.literal("NPC"), Component.empty().append(body)));
        var first = cache.layout(messages, 200, font, measure);
        body.append(" updated");
        var updated = cache.layout(messages, 200, font, measure);
        assertNotSame(
                first, updated, "mutable nested component changes must not reuse stale snapshot");
        body.withStyle(ChatFormatting.RED);
        var recolored = cache.layout(messages, 200, font, measure);
        assertNotSame(updated, recolored);
        var resized = cache.layout(messages, 180, font, measure);
        assertNotSame(recolored, resized);
        Object newFont = new Object();
        var fontChanged = cache.layout(messages, 180, newFont, measure);
        assertNotSame(resized, fontChanged);
        cache.invalidate();
        assertNotSame(fontChanged, cache.layout(messages, 180, newFont, measure));
    }

    @Test
    void resetSynchronizesActualScaleSliderKnobAndLabel() throws Exception {
        var c = new Config();
        c.hudTextScale = 1.5;
        var slider = new TextScaleSlider(0, 0, 200, c);
        assertTrue(slider.getMessage().getString().contains("150%"));
        c.resetHudLayout();
        slider.syncFromConfig();
        assertTrue(slider.getMessage().getString().contains("100%"));
        var value =
                net.minecraft.client.gui.components.AbstractSliderButton.class.getDeclaredField(
                        "value");
        value.setAccessible(true);
        assertEquals((1.0 - .75) / 1.75, value.getDouble(slider), 1e-9);
        slider.applyValue();
        assertEquals(1.0, c.hudTextScale);
    }
}
