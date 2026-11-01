package local.skyblockjp;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class ApiTranslationPolicyTest {
    @TempDir Path dir;

    private static Config config(boolean api, boolean dictionary) {
        var config = new Config();
        config.apiTranslation = api;
        config.candidateDictionary = dictionary;
        config.provider = "GOOGLE";
        return config;
    }
    private static StyledParagraph paragraph(String source) {
        return new StyledParagraph(List.of(Component.literal(source)));
    }
    private static Map<String, Object> entry(String channel, String speaker, String source, String target) {
        return Map.of("channel", channel, "speaker", speaker, "source", source, "target", target,
            "keep_original", false, "source_ids", List.of("policy:" + channel + source.hashCode()));
    }
    @SafeVarargs private static BundledDictionary dictionary(Map<String, Object>... entries) {
        String json = new Gson().toJson(Map.of("schema_version", 1, "tier", "candidate", "build_id", "api-policy-fixture",
            "entries", List.of(entries)));
        var dictionary = BundledDictionary.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertTrue(dictionary.stats().loaded(), dictionary.stats().error());
        return dictionary;
    }
    private Translator translator(Config config, String name, BundledDictionary dictionary, Translator.Transport transport) {
        return new Translator(config, dir.resolve(name + ".json"), LoggerFactory.getLogger("api-policy-test"), transport, dictionary);
    }
    private static void assertImmediateNull(CompletableFuture<String> result) {
        assertTrue(result.isDone(), "disabled requests must not wait in the API queue");
        assertNull(result.join());
    }

    @Test void disabledApiKeepsItemUiAndSpeakerScopedDictionaryButNeverQueuesUnknownOrChat() {
        var config = config(false, true);
        config.provider = "GROQ";
        config.groqApiKey = "";
        var source = paragraph("Welcome!");
        var missing = paragraph("A previously unseen greeting.");
        var dictionary = dictionary(entry("item", "", "Welcome!", "ようこそ！"),
            entry("ui", "", "Welcome!", "いらっしゃい！"), entry("npc", "Leo", "Welcome!", "よく来たね！"));
        AtomicInteger calls = new AtomicInteger();
        try (var translator = translator(config, "local-only", dictionary, text -> {
            calls.incrementAndGet();
            throw new IOException("disabled API transport must never be called");
        })) {
            var item = translator.request(source, "item=Fixture");
            assertTrue(item.isDone());
            assertEquals("ようこそ！", source.decode(item.join()).getString());
            assertEquals("いらっしゃい！", source.decode(translator.request(source, "scope=ui; item=Menu").join()).getString());
            assertEquals("よく来たね！", source.decode(translator.requestNpc(source, "Leo").join()).getString());
            assertEquals("ようこそ！", source.decode(translator.cached(source, "item")).getString());
            assertEquals("いらっしゃい！", source.decode(translator.dictionaryTranslation(source, "ui")).getString());
            assertImmediateNull(translator.request(missing, "item=Unknown"));
            assertImmediateNull(translator.request(missing, "scope=ui; item=Unknown"));
            assertImmediateNull(translator.request(source.encoded()));
            assertImmediateNull(translator.requestNpc(missing, "Leo"));
            assertImmediateNull(translator.requestNpc(source, "Unknown"));
            assertImmediateNull(translator.requestChat(source, "previous"));
            assertImmediateNull(translator.request("x".repeat(4001)));
            assertNull(translator.cached(source.encoded()));
            assertNull(translator.cached(source.encoded(), "ui"));
            assertEquals(new Translator.CacheInspection("API_DISABLED", ""), translator.cacheInspection(source.encoded(), "item"));
            assertEquals(0, translator.queueSize());
            assertEquals(0, calls.get());
            assertFalse(Files.exists(dir.resolve("translation-failures.jsonl")));
        }
    }

    @Test void offHidesPersistentAndProvisionalCacheAndErrorsWithoutDestroyingAnyState() throws Exception {
        var config = config(true, false);
        AtomicInteger calls = new AtomicInteger();
        try (var translator = translator(config, "state", dictionary(), text -> {
            calls.incrementAndGet();
            return switch (text) {
                case "A curious gift." -> "不思議な贈り物。";
                case "A changing menu." -> "変化するメニュー。";
                case "Deal 50 damage." -> "500ダメージを与える。";
                default -> throw new IOException("fixture failure");
            };
        })) {
            assertEquals("不思議な贈り物。", translator.request("A curious gift.").get(3, TimeUnit.SECONDS));
            assertEquals("変化するメニュー。", translator.request("A changing menu.", "scope=ui; item=Menu").get(3, TimeUnit.SECONDS));
            assertEquals("500ダメージを与える。", translator.request("Deal 50 damage.").get(3, TimeUnit.SECONDS));
            assertEquals("An unavailable reply.", translator.request("An unavailable reply.").get(3, TimeUnit.SECONDS));
            String error = translator.error("An unavailable reply.");
            assertNotNull(error);
            assertFalse(translator.lastError().isBlank());
            config.apiTranslation = false;
            for (String text : List.of("A curious gift.", "A changing menu.", "Deal 50 damage.", "An unavailable reply.")) {
                assertNull(translator.cached(text));
                assertNull(translator.cached(text, "ui"));
                assertEquals(new Translator.CacheInspection("API_DISABLED", ""), translator.cacheInspection(text, "item"));
                assertImmediateNull(translator.request(text));
                assertFalse(translator.pending(text));
            }
            assertNull(translator.error("An unavailable reply."));
            assertEquals("", translator.lastError());
            translator.retry("Deal 50 damage.");
            translator.retry("An unavailable reply.", "item");
            config.apiTranslation = true;
            assertEquals("不思議な贈り物。", translator.cached("A curious gift."));
            assertEquals("変化するメニュー。", translator.cached("A changing menu.", "ui"));
            assertEquals("500ダメージを与える。", translator.cached("Deal 50 damage."));
            assertEquals(error, translator.error("An unavailable reply."));
            assertEquals("API_CACHE", translator.cacheInspection("A curious gift.", "item").status());
            assertEquals("API_PROVISIONAL", translator.cacheInspection("Deal 50 damage.", "item").status());
            assertEquals(4, calls.get());
        }
    }

    @Test void offIgnoresManualOverridesAndMakesRefreshAndConnectionTestNonDestructive() throws Exception {
        var config = config(true, true);
        var item = paragraph("Welcome!");
        var ui = paragraph("Good luck!");
        var dictionary = dictionary(entry("item", "", item.sourceText(), "ようこそ！"),
            entry("ui", "", ui.sourceText(), "健闘を祈る！"));
        String probe = "This item restores 50 Health.";
        AtomicInteger calls = new AtomicInteger();
        try (var translator = translator(config, "overrides", dictionary, text -> {
            calls.incrementAndGet();
            return text.equals(probe) ? "このアイテムはHealthを50回復する。" : "いらっしゃいませ！";
        })) {
            String probeResult = translator.request(probe).get(3, TimeUnit.SECONDS);
            String itemResult = translator.retranslate(item, "item=Fixture").get(3, TimeUnit.SECONDS);
            String uiResult = translator.retranslate(ui.encoded(), "scope=ui; item=Menu").get(3, TimeUnit.SECONDS);
            assertEquals("API_RETRANSLATION_PRIORITY", translator.inspectDictionary(item, "item", "").reason());
            assertEquals("API_RETRANSLATION_PRIORITY", translator.inspectDictionary(ui, "ui", "").reason());
            config.apiTranslation = false;
            assertEquals("ようこそ！", item.decode(translator.cached(item)).getString());
            assertEquals("健闘を祈る！", ui.decode(translator.cached(ui, "ui")).getString());
            assertEquals("ようこそ！", item.decode(translator.request(item, "item=Fixture").join()).getString());
            assertImmediateNull(translator.retranslate(item, "item=Fixture"));
            assertImmediateNull(translator.retranslate(ui.encoded(), "scope=ui; item=Menu"));
            assertImmediateNull(translator.connectionTest());
            config.apiTranslation = true;
            assertEquals(itemResult, translator.cached(item));
            assertEquals(uiResult, translator.cached(ui, "ui"));
            assertEquals(probeResult, translator.cached(probe));
            assertEquals("API_RETRANSLATION_PRIORITY", translator.inspectDictionary(item, "item", "").reason());
            assertEquals("API_RETRANSLATION_PRIORITY", translator.inspectDictionary(ui, "ui", "").reason());
            assertEquals(3, calls.get());
        }
    }

    @Test void offHidesPreviouslyCachedNpcAndPlayerChatResponses() throws Exception {
        var config = config(true, false);
        var source = paragraph("Hello there!");
        AtomicInteger calls = new AtomicInteger();
        try (var translator = translator(config, "dialogue", dictionary(), text -> { calls.incrementAndGet(); return "こんにちは！"; })) {
            assertEquals("こんにちは！", translator.requestNpc(source, "Leo", "previous").get(3, TimeUnit.SECONDS));
            assertEquals("こんにちは！", translator.requestChat(source, "previous").get(3, TimeUnit.SECONDS));
            config.apiTranslation = false;
            assertImmediateNull(translator.requestNpc(source, "Leo", "previous"));
            assertImmediateNull(translator.requestChat(source, "previous"));
            config.apiTranslation = true;
            assertEquals("こんにちは！", translator.requestNpc(source, "Leo", "previous").join());
            assertEquals("こんにちは！", translator.requestChat(source, "previous").join());
            assertEquals(2, calls.get());
        }
    }

    @Test void diskCacheSurvivesAnOffSessionAndReappearsWhenEnabled() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String source = "A lasting gift.";
        try (var translator = translator(config(true, false), "disk", dictionary(), text -> { calls.incrementAndGet(); return "長く残る贈り物。"; })) {
            assertEquals("長く残る贈り物。", translator.request(source).get(3, TimeUnit.SECONDS));
        }
        String saved = Files.readString(dir.resolve("disk.json"));
        try (var translator = translator(config(false, false), "disk", dictionary(), text -> { calls.incrementAndGet(); return "呼び出されない。"; })) {
            assertNull(translator.cached(source));
            assertImmediateNull(translator.request(source));
            assertImmediateNull(translator.retranslate(source, "item=Fixture"));
        }
        assertEquals(saved, Files.readString(dir.resolve("disk.json")));
        try (var translator = translator(config(true, false), "disk", dictionary(), text -> { calls.incrementAndGet(); return "呼び出されない。"; })) {
            assertEquals("長く残る贈り物。", translator.request(source).join());
        }
        assertEquals(1, calls.get());
    }

    @Test void closeCancelsQueuedWorkAndIgnoresAnInFlightTransportThatReturnsLate() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var transportThread = new AtomicReference<Thread>();
        AtomicInteger calls = new AtomicInteger();
        var translator = translator(config(true, false), "closed", dictionary(), text -> {
            calls.incrementAndGet();
            if (text.equals("A saved gift.")) return "保存された贈り物。";
            transportThread.set(Thread.currentThread());
            entered.countDown();
            // Some custom transports can return successfully even after shutdownNow().
            boolean waiting = true;
            while (waiting) {
                try { waiting = !release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) {}
            }
            return "遅れて届いた贈り物。";
        });
        try {
            translator.request("A saved gift.").get(3, TimeUnit.SECONDS);
            var delayed = translator.request("A delayed gift.");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var queued = translator.request("A queued gift.");
            translator.close();
            assertNull(delayed.get(1, TimeUnit.SECONDS));
            assertNull(queued.get(1, TimeUnit.SECONDS));
            assertEquals(0, translator.queueSize());
            String savedAtClose = Files.readString(dir.resolve("closed.json"));
            assertImmediateNull(translator.request("A new gift."));
            assertImmediateNull(translator.connectionTest());
            release.countDown();
            transportThread.get().join(3000);
            assertFalse(transportThread.get().isAlive());
            assertEquals(savedAtClose, Files.readString(dir.resolve("closed.json")), "late response must not overwrite the replacement service cache");
            assertEquals(2, calls.get(), "queued/new requests must never reach transport after close");
            try (var reopened = translator(config(true, false), "closed", dictionary(), text -> { throw new AssertionError("cache read only"); })) {
                assertEquals("保存された贈り物。", reopened.cached("A saved gift."));
                assertNull(reopened.cached("A delayed gift."));
                assertNull(reopened.cached("A queued gift."));
            }
        } finally {
            release.countDown();
            translator.close();
        }
    }

    @Test void disablingWhileTransportIsRunningDropsItsOutputAndQueuedRequests() throws Exception {
        var config = config(true, false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var translator = translator(config, "toggle-running", dictionary(), text -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("fixture release timeout");
            return "途中で無効になった贈り物。";
        })) {
            try {
                var running = translator.request("A running gift.");
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var queued = translator.request("A queued gift.");
                config.apiTranslation = false;
                release.countDown();
                assertNull(running.get(3, TimeUnit.SECONDS));
                assertNull(queued.get(3, TimeUnit.SECONDS));
                assertEquals(1, calls.get());
                config.apiTranslation = true;
                assertNull(translator.cached("A running gift."));
                assertNull(translator.cached("A queued gift."));
                assertEquals("", translator.lastError());
            } finally { release.countDown(); }
        }
    }
}
