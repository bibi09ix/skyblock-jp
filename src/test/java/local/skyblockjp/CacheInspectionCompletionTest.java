package local.skyblockjp;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class CacheInspectionCompletionTest {
    @TempDir Path dir;

    @Test void synchronousCompletionCallbackSeesCacheBeforePendingEntryIsRemoved() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var config = new Config();
        config.provider = "GOOGLE";
        String source = "A curious gift.";
        try (var translator = new Translator(config, dir.resolve("cache.json"),
                LoggerFactory.getLogger("cache-completion-test"), text -> {
                    entered.countDown();
                    if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("fixture release timeout");
                    return "不思議な贈り物。";
                })) {
            var result = translator.request(source);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals("PENDING", translator.cacheInspection(source, "item").status());
                var pendingAtCompletion = new AtomicInteger();
                var inspection = result.thenApply(value -> {
                    pendingAtCompletion.set(translator.queueSize());
                    return translator.cacheInspection(source, "item");
                });
                release.countDown();
                var snapshot = inspection.get(3, TimeUnit.SECONDS);
                assertEquals(1, pendingAtCompletion.get(), "callback must run inside complete(), before finally removes the entry");
                assertEquals("API_CACHE", snapshot.status());
                assertEquals("", snapshot.error());
                assertEquals("不思議な贈り物。", result.get(3, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }
}
