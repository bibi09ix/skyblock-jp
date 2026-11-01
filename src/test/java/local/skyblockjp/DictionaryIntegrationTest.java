package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class DictionaryIntegrationTest {
    @TempDir Path dir;

    private static Map<String, Object> entry(
            String channel, String speaker, String source, String target) {
        return Map.of(
                "channel",
                channel,
                "speaker",
                speaker,
                "source",
                source,
                "target",
                target,
                "keep_original",
                false,
                "source_ids",
                List.of("fixture:" + speaker + source.hashCode()));
    }

    @SafeVarargs
    private static BundledDictionary dictionary(Map<String, Object>... entries) {
        String json =
                new Gson()
                        .toJson(
                                Map.of(
                                        "schema_version",
                                        1,
                                        "tier",
                                        "candidate",
                                        "build_id",
                                        "integration-fixture",
                                        "entries",
                                        List.of(entries)));
        var dict =
                BundledDictionary.load(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(dict.stats().loaded(), dict.stats().error());
        return dict;
    }

    private static Config config(boolean enabled) {
        var config = new Config();
        config.provider = "GOOGLE";
        config.candidateDictionary = enabled;
        return config;
    }

    private Translator translator(
            Config config,
            String name,
            BundledDictionary dictionary,
            Translator.Transport transport) {
        return new Translator(
                config,
                dir.resolve(name + ".json"),
                LoggerFactory.getLogger("dictionary-test"),
                transport,
                dictionary);
    }

    private static StyledParagraph paragraph(String text) {
        return new StyledParagraph(List.of(Component.literal(text)));
    }

    @Test
    void exactMatchIsImmediateWithoutApiKeyAndPreservesNumberColor() throws Exception {
        Config config = config(true);
        config.provider = "GROQ";
        config.groqApiKey = "";
        var p =
                new StyledParagraph(
                        List.of(
                                Component.literal("Restore ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(
                                                Component.literal("50")
                                                        .withStyle(ChatFormatting.GREEN))
                                        .append(
                                                Component.literal(" Health.")
                                                        .withStyle(ChatFormatting.GRAY))));
        AtomicInteger calls = new AtomicInteger();
        try (var t =
                translator(
                        config,
                        "instant",
                        dictionary(entry("item", "", "Restore 50 Health.", "Healthを50回復する。")),
                        text -> {
                            calls.incrementAndGet();
                            return text;
                        })) {
            var future = t.request(p, "item=fixture");
            assertTrue(future.isDone());
            assertEquals(0, t.queueSize());
            String result = future.get(1, TimeUnit.SECONDS);
            assertEquals("Healthを50回復する。", p.decode(result).getString());
            assertEquals(
                    0x55FF55,
                    StyledParagraph.glyphs(p.decode(result)).stream()
                            .filter(g -> g.text().equals("5"))
                            .findFirst()
                            .orElseThrow()
                            .style()
                            .getColor()
                            .getValue());
            assertEquals(result, t.cached(p));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void optedOutAndUnknownTextsUseExistingApi() throws Exception {
        var p = paragraph("Restore 50 Health.");
        AtomicInteger calls = new AtomicInteger();
        var dict = dictionary(entry("item", "", p.sourceText(), "Healthを50回復する。"));
        try (var t =
                translator(
                        config(false),
                        "disabled",
                        dict,
                        text -> {
                            calls.incrementAndGet();
                            return text;
                        })) {
            assertEquals(p.encoded(), t.request(p, "item=fixture").get(3, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        }
        try (var t =
                translator(
                        config(true),
                        "unknown",
                        dict,
                        text -> {
                            calls.incrementAndGet();
                            return text;
                        })) {
            var unknown = paragraph("Restore 51 Health.");
            assertEquals(
                    unknown.encoded(), t.request(unknown, "item=unknown").get(3, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void npcLookupKeepsSpeakerScopeAndPlayerChatAlwaysUsesApi() throws Exception {
        var p = paragraph("Welcome!");
        AtomicInteger calls = new AtomicInteger();
        var dict =
                dictionary(
                        entry("npc", "Leo", "Welcome!", "やあ、いらっしゃい！"),
                        entry("npc", "Rose", "Welcome!", "ようこそ！"));
        try (var t =
                translator(
                        config(true),
                        "npc",
                        dict,
                        text -> {
                            calls.incrementAndGet();
                            return "こんにちは！";
                        })) {
            assertEquals("やあ、いらっしゃい！", t.requestNpc(p, "[NPC] Leo", "previous").join());
            assertEquals("ようこそ！", t.requestNpc(p, "Rose", "different context").join());
            assertEquals(0, calls.get());
            assertEquals("こんにちは！", t.requestNpc(p, "Unknown").get(3, TimeUnit.SECONDS));
            assertEquals("こんにちは！", t.requestChat(p, "").get(3, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void dictionaryPrecedesOldApiCacheAndRetranslationBypassesDictionary() throws Exception {
        var p = paragraph("Restore 50 Health.");
        String api = p.encodeDictionaryTranslation("50のHealthを回復する。");
        assertNotNull(api);
        var dict = dictionary(entry("item", "", p.sourceText(), "Healthを50回復する。"));
        try (var t = translator(config(false), "priority", dict, text -> api)) {
            t.request(p, "item=fixture").get(3, TimeUnit.SECONDS);
        }
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var t =
                translator(
                        config(true),
                        "priority",
                        dict,
                        text -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            assertTrue(release.await(3, TimeUnit.SECONDS));
                            return api;
                        })) {
            assertEquals(
                    "Healthを50回復する。", p.decode(t.request(p, "item=fixture").join()).getString());
            var fresh = t.retranslate(p.encoded(), "item=fixture");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertNull(t.cached(p));
            assertSame(fresh, t.retranslate(p.encoded(), "repeat click"));
            release.countDown();
            assertEquals(api, fresh.get(3, TimeUnit.SECONDS));
            assertEquals(api, t.cached(p));
            assertEquals(api, t.request(p, "item=fixture").join());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void unsafeStyleOrAmbiguousEntryFallsBackInsteadOfDroppingColors() throws Exception {
        var p =
                new StyledParagraph(
                        List.of(
                                Component.literal("An ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(
                                                Component.literal("excellent")
                                                        .withStyle(ChatFormatting.GOLD))
                                        .append(
                                                Component.literal(" gift.")
                                                        .withStyle(ChatFormatting.GRAY))));
        assertNull(p.encodeDictionaryTranslation("素晴らしい贈り物。"));
        AtomicInteger calls = new AtomicInteger();
        var dict =
                dictionary(
                        entry("item", "", p.sourceText(), "素晴らしい贈り物。"),
                        entry("item", "", "Welcome!", "ようこそ！"),
                        entry("item", "", "Welcome!", "いらっしゃい！"));
        try (var t =
                translator(
                        config(true),
                        "fallback",
                        dict,
                        text -> {
                            calls.incrementAndGet();
                            return text;
                        })) {
            assertEquals(p.encoded(), t.request(p, "item=fixture").get(3, TimeUnit.SECONDS));
            var conflict = paragraph("Welcome!");
            assertEquals(
                    conflict.encoded(),
                    t.request(conflict, "item=conflict").get(3, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void localRequestRetainsParagraphContextAndAtomicItemDisplay() throws Exception {
        var first = paragraph("Welcome!");
        var second = paragraph("A lovely gift.");
        var dict = dictionary(entry("item", "", "Welcome!", "ようこそ！"));
        AtomicInteger calls = new AtomicInteger();
        try (var t =
                translator(
                        config(true),
                        "paragraph-batch",
                        dict,
                        text -> {
                            calls.incrementAndGet();
                            return "素敵な贈り物。";
                        })) {
            assertEquals("ようこそ！", t.request(first, "item=fixture").join());
            assertEquals(0, calls.get());
            List<Component> lines =
                    List.of(
                            Component.literal("Test item"),
                            Component.literal("Welcome!"),
                            Component.literal(""),
                            Component.literal("A lovely gift."));
            var blocks =
                    List.of(
                            new LoreParser.Block(1, 2, "Welcome!"),
                            new LoreParser.Block(3, 4, "A lovely gift."));
            var partial =
                    ItemTranslation.prepareParagraphs(
                            lines, blocks, List.of(first, second), t::cached, List::of);
            assertFalse(partial.ready());
            assertEquals(lines, partial.lines());
            assertEquals(1, partial.complete());
            t.request(second, "item=fixture").get(3, TimeUnit.SECONDS);
            var complete =
                    ItemTranslation.prepareParagraphs(
                            lines, blocks, List.of(first, second), t::cached, List::of);
            assertTrue(complete.ready());
            assertEquals("ようこそ！", complete.lines().get(1).getString());
            assertEquals("素敵な贈り物。", complete.lines().get(3).getString());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void configDefaultsToDictionaryOnAndRoundTripKeepsExplicitOptOut() throws Exception {
        Path file = dir.resolve("config.json");
        Config c = Config.load(file);
        assertTrue(c.candidateDictionary);
        c.provider = "GOOGLE";
        c.hudTextScale = 1.5;
        c.groqApiKey = "placeholder";
        c.candidateDictionary = false;
        Config.save(file, c);
        c = Config.load(file);
        assertFalse(c.candidateDictionary);
        assertEquals(1.5, c.hudTextScale);
        assertEquals("placeholder", c.groqApiKey);
    }
}
