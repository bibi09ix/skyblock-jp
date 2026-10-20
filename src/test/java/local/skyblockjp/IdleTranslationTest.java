package local.skyblockjp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IdleTranslationTest {
    @TempDir Path dir;
    private IdleTranslations.Job job(String id, String text) { return new IdleTranslations.Job(id, text, "item=" + id); }
    @Test void waitsForUseAndForegroundCompletionThenPacesEachParagraph() {
        var idle = new IdleTranslations(); var c = new Config();
        idle.clear(0); idle.observe("old", List.of(job("old", "old-text")), 1);
        idle.observe("new", List.of(job("new", "first"), job("new", "second")), 2);
        idle.observe("new", List.of(job("new", "first")), 99_000); // Same hover does not keep extending idle.
        assertNull(idle.next(120_001, 0, c, true, s -> false));
        assertNull(idle.next(120_002, 0, c, false, s -> false));
        assertNull(idle.next(150_000, 100_000, c, true, s -> false));
        assertEquals("first", idle.next(220_000, 100_000, c, true, s -> false).text());
        assertNull(idle.next(399_999, 100_000, c, true, s -> false));
        assertEquals("second", idle.next(400_000, 100_000, c, true, s -> false).text());
        assertEquals("old-text", idle.next(580_000, 100_000, c, true, s -> false).text());
        assertNull(idle.next(760_000, 100_000, c, true, s -> false));
    }
    @Test void skipsCachedWorkCapsHistoryAndClearsAcrossConnections() {
        var idle = new IdleTranslations(); var c = new Config(); idle.clear(0);
        for (int n = 0; n < 25; n++) idle.observe("item" + n, List.of(job("item" + n, "text" + n)), n);
        assertEquals(20, idle.itemCount());
        c.backgroundTranslation = false;
        assertNull(idle.next(200_000, 0, c, true, s -> false));
        c.backgroundTranslation = true;
        assertEquals("text23", idle.next(200_000, 0, c, true, s -> s.equals("text24")).text());
        idle.clear(300_000);
        assertNull(idle.next(600_000, 0, c, true, s -> false));
    }
    @Test void backgroundFailureDoesNotRetryOrPolluteManualFallbackAndPauses() throws Exception {
        var c = new Config(); c.provider = "GOOGLE"; var calls = new AtomicInteger();
        try (var t = new Translator(c, dir.resolve("cache.json"), LoggerFactory.getLogger("idle-test"), text -> {
            if (calls.incrementAndGet() == 1) throw new java.io.IOException("test offline");
            return "説明を翻訳する。";
        })) {
            assertNull(t.requestBackground("This item is powerful.", "test").get(3, TimeUnit.SECONDS));
            assertEquals(1, calls.get()); assertNull(t.cached("This item is powerful."));
            assertFalse(t.backgroundReady()); assertEquals("", t.lastError());
            t.retry("This item is powerful.");
            assertEquals("説明を翻訳する。", t.request("This item is powerful.", "manual").get(3, TimeUnit.SECONDS));
            assertEquals(2, calls.get()); assertTrue(t.lastForegroundUse() > 0);
        }
    }
    @Test void backgroundCannotQueueBehindActiveManualWork() throws Exception {
        var gate = new CountDownLatch(1); var started = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var t = new Translator(new Config(), dir.resolve("cache.json"), LoggerFactory.getLogger("idle-test"), text -> {
            calls.incrementAndGet(); started.countDown(); gate.await(3, TimeUnit.SECONDS); return "説明を翻訳する。";
        })) {
            var foreground = t.request("This item is powerful.");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertNull(t.requestBackground("Another item.", "test").get(1, TimeUnit.SECONDS));
            gate.countDown(); foreground.get(2, TimeUnit.SECONDS); assertEquals(1, calls.get());
        } finally { gate.countDown(); }
    }
    @Test void rateLimitedBackgroundRequestHasNoAutomaticRetry() throws Exception {
        var calls = new AtomicInteger();
        try (var t = new Translator(new Config(), dir.resolve("cache.json"), LoggerFactory.getLogger("idle-test"), text -> {
            calls.incrementAndGet(); throw new Translator.TranslationFailure("HTTP 429", true);
        })) {
            assertNull(t.requestBackground("Test item description.", "item=test").get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get()); assertFalse(t.backgroundReady());
            assertNull(t.requestBackground("Another description.", "item=other").get(1, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        }
    }
    @Test void backgroundSettingsPersistAndMinimumIntervalsAreClamped() throws Exception {
        var c = new Config(); c.backgroundIdleSeconds = 1; c.backgroundIntervalSeconds = 1;
        var path = dir.resolve("config.json"); Config.save(path, c); var loaded = Config.load(path);
        assertTrue(loaded.backgroundTranslation); assertEquals(60, loaded.backgroundIdleSeconds); assertEquals(120, loaded.backgroundIntervalSeconds);
    }
}
