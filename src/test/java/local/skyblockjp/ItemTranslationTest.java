package local.skyblockjp;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ItemTranslationTest {
    @Test void keepsWholeItemEnglishUntilEveryParagraphIsReady() {
        List<Component> original = List.of(Component.literal("Belt"), Component.literal("Grants speed."), Component.empty(), Component.literal("Grants fortune."));
        var blocks = LoreParser.parse(original.stream().map(Component::getString).toList());
        var paragraphs = blocks.stream().map(b -> new StyledParagraph(original.subList(b.start(), b.end()))).toList();
        Map<String, String> cache = new HashMap<>(); cache.put(paragraphs.getFirst().encoded(), "<t0>speed</t0>が増加する。");
        var partial = ItemTranslation.prepare(original, blocks, paragraphs, cache::get, List::of);
        assertFalse(partial.ready()); assertEquals(1, partial.complete()); assertEquals(original, partial.lines());
        cache.put(paragraphs.getLast().encoded(), "採掘運が増加する。");
        var complete = ItemTranslation.prepare(original, blocks, paragraphs, cache::get, List::of);
        assertTrue(complete.ready()); assertEquals("speedが増加する。", complete.lines().get(1).getString());
        assertEquals("", complete.lines().get(2).getString()); assertEquals("採掘運が増加する。", complete.lines().get(3).getString());
    }
    @Test void provisionalParagraphsDisplayTogetherWithOriginalForMissingValues() {
        List<Component> original = List.of(Component.literal("Belt"), Component.literal("Grants speed."), Component.empty(), Component.literal("Gain 5 fortune."));
        var blocks = LoreParser.parse(original.stream().map(Component::getString).toList());
        var paragraphs = blocks.stream().map(b -> new StyledParagraph(original.subList(b.start(), b.end()))).toList();
        var result = ItemTranslation.prepare(original, blocks, paragraphs, s -> "増加する。", List::of);
        assertTrue(result.ready()); assertNull(result.error());
        assertTrue(result.lines().get(3).getString().contains("増加する。"));
        assertTrue(result.lines().get(3).getString().contains("原文: Gain 5 fortune."));
    }
}
