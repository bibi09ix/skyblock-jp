package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class ScopedDictionaryRoutingTest {
    @TempDir Path dir;

    private static Map<String, Object> entry(String channel, String source, String target) {
        return Map.of(
                "channel",
                channel,
                "speaker",
                "",
                "source",
                source,
                "target",
                target,
                "keep_original",
                false,
                "source_ids",
                List.of("fixture:" + channel + source.hashCode()));
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
                                        "scoped-fixture",
                                        "entries",
                                        List.of(entries)));
        var result =
                BundledDictionary.load(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(result.stats().loaded(), result.stats().error());
        return result;
    }

    private static Config config() {
        var c = new Config();
        c.candidateDictionary = true;
        c.provider = "GOOGLE";
        return c;
    }

    private static StyledParagraph paragraph(String source) {
        return new StyledParagraph(List.of(Component.literal(source)));
    }

    private Translator translator(
            String name, BundledDictionary dictionary, Translator.Transport transport) {
        return new Translator(
                config(),
                dir.resolve(name + ".json"),
                LoggerFactory.getLogger("scoped-routing-test"),
                transport,
                dictionary);
    }

    @Test
    void contextChannelRecognizesOnlyCanonicalUiScopePrefixes() {
        assertEquals("ui", Translator.contextChannel("scope=ui; item=Pets"));
        for (String context :
                List.of("item=scope=ui; Example", "item=Pets", "unspecified", "scope=ui")) {
            assertEquals("item", Translator.contextChannel(context), context);
        }
    }

    @Test
    void sameEnglishUsesSelectedItemOrUiDictionaryWithoutCallingApi() throws Exception {
        var p = paragraph("Welcome!");
        AtomicInteger calls = new AtomicInteger();
        var dict =
                dictionary(entry("item", "Welcome!", "ようこそ！"), entry("ui", "Welcome!", "いらっしゃい！"));
        try (var t =
                translator(
                        "scope",
                        dict,
                        text -> {
                            calls.incrementAndGet();
                            throw new IOException("local dictionary must not use transport");
                        })) {
            assertEquals("ようこそ！", p.decode(t.dictionaryTranslation(p, "item")).getString());
            assertEquals("いらっしゃい！", p.decode(t.dictionaryTranslation(p, "ui")).getString());
            var ui = t.request(p, "scope=ui; item=Pets");
            assertTrue(ui.isDone());
            assertEquals("いらっしゃい！", p.decode(ui.get(1, TimeUnit.SECONDS)).getString());
            assertEquals("ようこそ！", p.decode(t.request(p, "item=Actual item").join()).getString());
            assertEquals(0, calls.get());
            assertEquals(0, t.queueSize());
        }
    }

    @Test
    void uiOnlyEntryIsNotBorrowedByItemLookup() {
        var p = paragraph("View your pets.");
        AtomicInteger calls = new AtomicInteger();
        try (var t =
                translator(
                        "ui-only",
                        dictionary(entry("ui", p.sourceText(), "ペットを確認する。")),
                        text -> {
                            calls.incrementAndGet();
                            return text;
                        })) {
            assertNull(t.dictionaryTranslation(p, "item"));
            assertNotNull(t.dictionaryTranslation(p, "ui"));
            assertFalse(t.inspectDictionary(p, "item", "").accepted());
            assertTrue(t.inspectDictionary(p, "ui", "").accepted());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void failedForcedRefreshRestoresItsDictionaryWithoutClearingAnotherOverride() throws Exception {
        var first = paragraph("Welcome!");
        var second = paragraph("Good luck!");
        AtomicInteger calls = new AtomicInteger();
        var dict =
                dictionary(
                        entry("item", first.sourceText(), "ようこそ！"),
                        entry("item", second.sourceText(), "健闘を祈る！"));
        try (var t =
                translator(
                        "restore",
                        dict,
                        text -> {
                            calls.incrementAndGet();
                            if (text.equals(first.encoded())) return "いらっしゃいませ！";
                            throw new IOException("fixture refresh unavailable");
                        })) {
            assertEquals("いらっしゃいませ！", t.retranslate(first, "item=First").get(3, TimeUnit.SECONDS));
            assertEquals(
                    "API_RETRANSLATION_PRIORITY", t.inspectDictionary(first, "item", "").reason());
            assertEquals(
                    second.encoded(),
                    t.retranslate(second, "item=Second").get(3, TimeUnit.SECONDS));
            assertTrue(t.inspectDictionary(second, "item", "").accepted());
            assertEquals("健闘を祈る！", second.decode(t.cached(second)).getString());
            assertEquals(
                    "API_RETRANSLATION_PRIORITY", t.inspectDictionary(first, "item", "").reason());
            assertEquals("いらっしゃいませ！", t.cached(first));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void forcedUiTranslationCannotOverrideOrPopulateTheItemScope() throws Exception {
        var p = paragraph("Welcome!");
        AtomicInteger calls = new AtomicInteger();
        var dict =
                dictionary(
                        entry("item", p.sourceText(), "ようこそ！"),
                        entry("ui", p.sourceText(), "いらっしゃい！"));
        try (var t =
                translator(
                        "scope-override",
                        dict,
                        text -> {
                            calls.incrementAndGet();
                            return "歓迎します！";
                        })) {
            assertEquals(
                    "歓迎します！", t.retranslate(p, "scope=ui; item=Control").get(3, TimeUnit.SECONDS));
            assertEquals("API_RETRANSLATION_PRIORITY", t.inspectDictionary(p, "ui", "").reason());
            assertTrue(t.inspectDictionary(p, "item", "").accepted());
            assertEquals("ようこそ！", p.decode(t.cached(p, "item")).getString());
            assertEquals("歓迎します！", t.cached(p, "ui"));
            assertNull(t.cached(p.encoded(), "item"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void unknownUiApiCacheDoesNotLeakIntoAnUnknownItem() throws Exception {
        var p = paragraph("A changing explanation.");
        AtomicInteger calls = new AtomicInteger();
        try (var t =
                translator(
                        "scope-api",
                        dictionary(),
                        text -> {
                            calls.incrementAndGet();
                            return "変化する説明。";
                        })) {
            assertEquals(
                    "変化する説明。", t.request(p, "scope=ui; item=Control").get(3, TimeUnit.SECONDS));
            assertEquals("変化する説明。", t.cached(p, "ui"));
            assertNull(t.cached(p, "item"));
            assertNull(t.cached(p.encoded()));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void unrequestedTooltipKeepsItsDictionaryAfterACommonPromptWasRetranslatedElsewhere()
            throws Exception {
        var p = paragraph("Click to view!");
        AtomicInteger calls = new AtomicInteger();
        try (var t =
                translator(
                        "display-override",
                        dictionary(entry("ui", p.sourceText(), "クリックで表示！")),
                        text -> {
                            calls.incrementAndGet();
                            return "表示するにはクリック！";
                        })) {
            assertEquals(
                    "表示するにはクリック！",
                    t.retranslate(p, "scope=ui; item=Attack Speed").get(3, TimeUnit.SECONDS));
            assertEquals(
                    "API_RETRANSLATION_PRIORITY", t.inspectDictionary(p, "ui", "", true).reason());
            var automatic = t.inspectDictionary(p, "ui", "", false);
            assertTrue(automatic.accepted());
            assertEquals("クリックで表示！", p.decode(automatic.encoded()).getString());
            assertEquals("表示するにはクリック！", t.cached(p, "ui"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void emptyForcedRefreshRestoresTheKnownDictionary() throws Exception {
        var p = paragraph("Welcome!");
        try (var t =
                translator(
                        "scope-empty",
                        dictionary(entry("item", p.sourceText(), "ようこそ！")),
                        text -> "")) {
            assertEquals(p.encoded(), t.retranslate(p, "item=First").get(3, TimeUnit.SECONDS));
            assertTrue(t.inspectDictionary(p, "item", "").accepted());
            assertEquals("ようこそ！", p.decode(t.cached(p)).getString());
        }
    }
}
