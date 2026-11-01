package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class TranslatorTest {
    @TempDir Path dir;

    private static Config apiConfig() {
        var c = new Config();
        c.candidateDictionary = false;
        return c;
    }

    @Test
    void retranslationSendsOriginalAndReplacesPersistentCache() throws Exception {
        String source = "Gain 50 Strength for 5 seconds.";
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Path file = dir.resolve("refresh.json");
        try (Translator t =
                new Translator(
                        apiConfig(),
                        file,
                        LoggerFactory.getLogger("test"),
                        text -> {
                            if (text.equals("Restore Health.")) return "Healthを回復する。";
                            assertEquals(source, text);
                            if (calls.incrementAndGet() == 1) return "Strengthが50増加する。持続時間は5秒。";
                            started.countDown();
                            assertTrue(release.await(3, TimeUnit.SECONDS));
                            return "5秒間、Strengthが50増加する。";
                        })) {
            t.request(source).get(3, TimeUnit.SECONDS);
            t.request("Restore Health.").get(3, TimeUnit.SECONDS);
            var fresh = t.retranslate(source, "test item");
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertNull(t.cached(source));
            assertEquals("Healthを回復する。", t.cached("Restore Health."));
            assertSame(fresh, t.retranslate(source, "repeated click"));
            release.countDown();
            assertEquals("5秒間、Strengthが50増加する。", fresh.get(3, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        }
        try (Translator t =
                new Translator(
                        apiConfig(),
                        file,
                        LoggerFactory.getLogger("test"),
                        text -> {
                            throw new AssertionError("Expected refreshed disk cache");
                        })) {
            assertEquals("5秒間、Strengthが50増加する。", t.request(source).get());
        }
    }

    @Test
    void deduplicatesPendingAndPersistsWholeParagraph() throws Exception {
        Config c = apiConfig();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Path file = dir.resolve("cache.json");
        try (Translator t =
                new Translator(
                        c,
                        file,
                        LoggerFactory.getLogger("test"),
                        text -> {
                            calls.incrementAndGet();
                            started.countDown();
                            release.await(3, TimeUnit.SECONDS);
                            return "5秒間、Strengthが50増加する。";
                        })) {
            String paragraph = "Gain 50 Strength for 5 seconds.";
            var first = t.request(paragraph);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertSame(first, t.request(paragraph));
            release.countDown();
            assertEquals("5秒間、Strengthが50増加する。", first.get(3, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(first.join(), t.cached(paragraph));
        }
        try (Translator t =
                new Translator(
                        c,
                        file,
                        LoggerFactory.getLogger("test"),
                        text -> {
                            throw new AssertionError("Cache hit must not call API");
                        })) {
            assertEquals(
                    "5秒間、Strengthが50増加する。", t.request("Gain 50 Strength for 5 seconds.").get());
        }
    }

    @Test
    void displaysProvisionalNumbersAndLogsWithoutPersisting() throws Exception {
        try (Translator t =
                new Translator(
                        apiConfig(),
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("test"),
                        text -> "500ダメージを与える。")) {
            assertEquals("500ダメージを与える。", t.request("Deal 50 damage.").get(10, TimeUnit.SECONDS));
            assertEquals("500ダメージを与える。", t.cached("Deal 50 damage."));
            assertNull(t.error("Deal 50 damage."));
            assertTrue(t.lastError().isBlank());
            t.awaitDiagnostics().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(
                    java.nio.file.Files.readString(dir.resolve("translation-failures.jsonl"))
                            .contains("DISPLAY_ANYWAY"));
            t.retry("Deal 50 damage.");
            assertNull(t.error("Deal 50 damage."));
        }
    }
}
