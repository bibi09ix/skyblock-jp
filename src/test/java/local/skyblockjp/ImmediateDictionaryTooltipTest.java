package local.skyblockjp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImmediateDictionaryTooltipTest {
    private static List<StyledParagraph> paragraphs(List<Component> original, List<LoreParser.Block> blocks) {
        return blocks.stream().map(b -> new StyledParagraph(original.subList(b.start(), b.end()))).toList();
    }
    private static List<String> strings(List<Component> lines) { return lines.stream().map(Component::getString).toList(); }

    @Test void firstPreparationShowsKnownTextWhileUnregisteredParagraphStaysOriginal() {
        List<Component> original = List.of(Component.literal("Test Item"), Component.literal("Welcome!"),
            Component.empty(), Component.literal("Unknown explanation."));
        var snapshot = List.copyOf(original);
        var blocks = List.of(new LoreParser.Block(1, 2, "Welcome!"), new LoreParser.Block(3, 4, "Unknown explanation."));
        var paragraphs = paragraphs(original, blocks);
        AtomicInteger calls = new AtomicInteger();
        var result = ItemTranslation.prepareDictionary(original, blocks, paragraphs, p -> {
            calls.incrementAndGet();
            return p.sourceText().equals("Welcome!") ? p.encodeDictionaryTranslation("ようこそ！") : null;
        }, List::of);
        assertEquals(List.of("Test Item", "ようこそ！", "", "Unknown explanation."), strings(result.lines()));
        assertEquals(1, result.complete()); assertEquals(2, result.total()); assertFalse(result.ready());
        assertNull(result.error()); assertEquals(2, calls.get());
        assertEquals(snapshot, original); assertNotSame(original, result.lines());
    }

    @Test void differentWrapLengthsPreserveBlankLinesUnknownBlocksAndOriginalIndexes() {
        List<Component> original = List.of(Component.literal("Item"), Component.literal("A lovely"),
            Component.literal("gift."), Component.empty(), Component.literal("Unknown explanation."),
            Component.empty(), Component.literal("Welcome!"), Component.literal("Strength: +10"));
        var blocks = List.of(new LoreParser.Block(1, 3, "A lovely gift."),
            new LoreParser.Block(4, 5, "Unknown explanation."), new LoreParser.Block(6, 7, "Welcome!"));
        var result = ItemTranslation.prepareDictionary(original, blocks, paragraphs(original, blocks), p -> {
            return switch (p.sourceText()) {
                case "A lovely gift." -> p.encodeDictionaryTranslation("素敵な贈り物。");
                case "Welcome!" -> p.encodeDictionaryTranslation("ようこそ！");
                default -> null;
            };
        }, component -> component.getString().equals("ようこそ！")
            ? List.of(Component.literal("よう"), Component.literal("こそ！")) : List.of(component));
        assertEquals(List.of("Item", "素敵な贈り物。", "", "Unknown explanation.", "", "よう", "こそ！", "Strength: +10"),
            strings(result.lines()));
        assertEquals(2, result.complete()); assertNull(result.error());
    }

    @Test void invalidValuesOrMarkersLeaveTheirParagraphUnchangedAndOtherHitsStillDisplay() {
        for (String invalid : List.of("Healthを500回復する。", "Healthを回復する。", "<n999/>回復する。", "")) {
            List<Component> original = List.of(Component.literal("Item"), Component.literal("Restore 50 Health."),
                Component.empty(), Component.literal("Welcome!"));
            var blocks = List.of(new LoreParser.Block(1, 2, "Restore 50 Health."), new LoreParser.Block(3, 4, "Welcome!"));
            var result = ItemTranslation.prepareDictionary(original, blocks, paragraphs(original, blocks), p ->
                p.sourceText().equals("Welcome!") ? p.encodeDictionaryTranslation("ようこそ！") : invalid, List::of);
            assertEquals(List.of("Item", "Restore 50 Health.", "", "ようこそ！"), strings(result.lines()), invalid);
            assertEquals(1, result.complete()); assertNotNull(result.error()); assertFalse(result.ready());
        }
    }

    @Test void localDisplayRetainsValueAndNameColorsWithoutChangingStatRows() {
        Component description = Component.literal("Restore ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("50").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("Health").withStyle(ChatFormatting.RED))
            .append(Component.literal(".").withStyle(ChatFormatting.GRAY));
        Component stat = Component.literal("Defense: +25").withStyle(ChatFormatting.BLUE);
        List<Component> original = List.of(Component.literal("Item"), stat, description, Component.literal("Unknown explanation."));
        var blocks = List.of(new LoreParser.Block(2, 3, "Restore 50 Health."), new LoreParser.Block(3, 4, "Unknown explanation."));
        var result = ItemTranslation.prepareDictionary(original, blocks, paragraphs(original, blocks), p ->
            p.sourceText().equals("Restore 50 Health.") ? p.encodeDictionaryTranslation("Healthを50回復する。") : null, List::of);
        assertSame(stat, result.lines().get(1)); assertSame(original.get(3), result.lines().get(3));
        Component translated = result.lines().get(2);
        assertEquals("Healthを50回復する。", translated.getString());
        assertColor(translated, "Health", 0xFF5555); assertColor(translated, "50", 0x55FF55);
        assertEquals("Restore 50 Health.", description.getString());
    }

    @Test void noHitsReturnAnUnchangedCopyAndNoWrapWork() {
        List<Component> original = new ArrayList<>(List.of(Component.literal("Item"), Component.literal("Unknown explanation.")));
        var blocks = List.of(new LoreParser.Block(1, 2, "Unknown explanation."));
        AtomicInteger wraps = new AtomicInteger();
        var result = ItemTranslation.prepareDictionary(original, blocks, paragraphs(original, blocks), p -> null, c -> {
            wraps.incrementAndGet(); return List.of(c);
        });
        assertEquals(original, result.lines()); assertNotSame(original, result.lines());
        assertEquals(0, result.complete()); assertEquals(1, result.total()); assertFalse(result.ready());
        assertNull(result.error()); assertEquals(0, wraps.get());
    }

    @Test void allLocalHitsAreReadyOnTheFirstCall() {
        List<Component> original = List.of(Component.literal("Item"), Component.literal("Welcome!"));
        var blocks = List.of(new LoreParser.Block(1, 2, "Welcome!"));
        var result = ItemTranslation.prepareDictionary(original, blocks, paragraphs(original, blocks),
            p -> p.encodeDictionaryTranslation("ようこそ！"), List::of);
        assertTrue(result.ready()); assertEquals("ようこそ！", result.lines().get(1).getString());
    }

    private static void assertColor(Component component, String fragment, int expected) {
        int start = component.getString().indexOf(fragment);
        assertTrue(start >= 0);
        int offset = 0;
        for (var glyph : StyledParagraph.glyphs(component)) {
            if (offset >= start && offset < start + fragment.length()) {
                assertNotNull(glyph.style().getColor());
                assertEquals(expected, glyph.style().getColor().getValue());
            }
            offset += glyph.text().length();
        }
    }
}
