package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class DictionaryAutoDisplayIntegrationTest {
    @TempDir Path dir;
    private static final List<Component> ORIGINAL =
            List.of(
                    Component.literal("Test item"),
                    Component.literal("Restores 50 Health."),
                    Component.empty(),
                    Component.literal("A curious gift."));
    private static final List<LoreParser.Block> BLOCKS =
            List.of(
                    new LoreParser.Block(1, 2, "Restores 50 Health."),
                    new LoreParser.Block(3, 4, "A curious gift."));
    private static final List<StyledParagraph> PARAGRAPHS =
            List.of(
                    new StyledParagraph(ORIGINAL.subList(1, 2)),
                    new StyledParagraph(ORIGINAL.subList(3, 4)));
    private static final Function<StyledParagraph, String> UNEXPECTED =
            p -> {
                throw new AssertionError("Unexpected cache lookup");
            };

    private static Config config(boolean auto) {
        var c = new Config();
        c.candidateDictionary = true;
        c.dictionaryAutoDisplay = auto;
        return c;
    }

    private static String known() {
        return PARAGRAPHS.getFirst().encodeDictionaryTranslation("Healthを50回復する。");
    }

    private static String other() {
        return PARAGRAPHS.getLast().encodeDictionaryTranslation("不思議な贈り物。");
    }

    private static ItemTooltipTranslation.Result display(
            Config c,
            boolean requested,
            Function<StyledParagraph, String> dictionary,
            Function<StyledParagraph, String> completed) {
        return ItemTooltipTranslation.prepare(
                ORIGINAL, BLOCKS, PARAGRAPHS, c, requested, dictionary, completed, List::of);
    }

    @Test
    void firstTooltipAlreadyContainsKnownParagraphWithoutApiKey() throws Exception {
        var c = config(true);
        c.groqApiKey = "";
        AtomicInteger calls = new AtomicInteger();
        String json =
                new Gson()
                        .toJson(
                                Map.of(
                                        "schema_version",
                                        1,
                                        "tier",
                                        "candidate",
                                        "build_id",
                                        "auto-fixture",
                                        "entries",
                                        List.of(
                                                Map.of(
                                                        "channel",
                                                        "item",
                                                        "speaker",
                                                        "",
                                                        "source",
                                                        "Restores 50 Health.",
                                                        "target",
                                                        "Healthを50回復する。",
                                                        "keep_original",
                                                        false,
                                                        "source_ids",
                                                        List.of("test")))));
        var dictionary =
                BundledDictionary.load(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        try (var t =
                new Translator(
                        c,
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("instant-test"),
                        text -> {
                            calls.incrementAndGet();
                            return text;
                        },
                        dictionary)) {
            var first = display(c, false, t::dictionaryTranslation, UNEXPECTED);
            assertEquals(ItemTooltipTranslation.Mode.DICTIONARY, first.mode());
            assertEquals("Healthを50回復する。", first.lines().get(1).getString());
            assertEquals("A curious gift.", first.lines().get(3).getString());
            assertEquals(1, first.dictionaryMatches());
            assertEquals(0, calls.get());
            assertEquals(0, t.queueSize());
            assertNull(
                    t.cached(
                            PARAGRAPHS
                                    .getFirst()
                                    .encoded())); // Display did not create an API cache record.
        }
    }

    @Test
    void offDoesNotAutomaticallyDisplayDictionary() {
        var result = display(config(false), false, UNEXPECTED, UNEXPECTED);
        assertEquals(ItemTooltipTranslation.Mode.ORIGINAL, result.mode());
        assertEquals(ORIGINAL, result.lines());
    }

    @Test
    void manualTranslationStillUsesDictionaryWhenInstantDisplayIsOff() {
        var result =
                display(
                        config(false),
                        true,
                        UNEXPECTED,
                        p -> p == PARAGRAPHS.getFirst() ? known() : other());
        assertEquals(ItemTooltipTranslation.Mode.FULL, result.mode());
        assertEquals("Healthを50回復する。", result.lines().get(1).getString());
    }

    @Test
    void unfinishedManualBatchKeepsOnlyTheAlreadyVisibleDictionaryParagraphs() {
        var result =
                display(
                        config(true),
                        true,
                        p -> p == PARAGRAPHS.getFirst() ? known() : null,
                        p -> p == PARAGRAPHS.getFirst() ? known() : null);
        assertEquals(ItemTooltipTranslation.Mode.PENDING, result.mode());
        assertEquals(1, result.complete());
        assertEquals("Healthを50回復する。", result.lines().get(1).getString());
        assertEquals("A curious gift.", result.lines().get(3).getString());
    }

    @Test
    void allKnownParagraphsAreImmediateWithoutConsultingAnyApiCache() {
        var result =
                display(
                        config(true),
                        false,
                        p -> p == PARAGRAPHS.getFirst() ? known() : other(),
                        UNEXPECTED);
        assertEquals(ItemTooltipTranslation.Mode.DICTIONARY, result.mode());
        assertEquals(2, result.dictionaryMatches());
        assertEquals("不思議な贈り物。", result.lines().get(3).getString());
    }

    @Test
    void masterDictionarySwitchStillDisablesInstantDisplay() {
        var c = config(true);
        c.candidateDictionary = false;
        assertEquals(ORIGINAL, display(c, false, UNEXPECTED, UNEXPECTED).lines());
    }

    @Test
    void rejectedLocalTextIsNotReportedAsTheDisplayedTranslation() {
        var result =
                display(
                        config(true),
                        false,
                        p -> p == PARAGRAPHS.getFirst() ? "Healthを500回復する。" : other(),
                        UNEXPECTED);
        assertEquals("Restores 50 Health.", result.lines().get(1).getString());
        assertEquals("Restores 50 Health.", result.paragraphs().getFirst());
        assertEquals("不思議な贈り物。", result.paragraphs().getLast());
    }
}
