package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SharedLogSnapshotTest {
    @TempDir Path dir;

    private TooltipAudit audit(Path path) {
        return new TooltipAudit(path, LoggerFactory.getLogger("shared-snapshot-test"), "");
    }

    private static ThreadPoolExecutor writer(TooltipAudit audit) throws Exception {
        var field = TooltipAudit.class.getDeclaredField("writer");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(audit);
    }

    private static void waitUninterruptibly(CountDownLatch release) {
        boolean interrupted = false;
        for (; ; ) {
            try {
                if (!release.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Fixture release timed out");
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void awaitStatus(
            SharedLogExportService service, SharedLogExportService.Status expected)
            throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (service.state().status() != expected && System.nanoTime() < end) Thread.sleep(5);
        assertEquals(expected, service.state().status());
    }

    @Test
    void clickBoundaryFlushesObservationSummariesBeforeTheBackgroundZip() throws Exception {
        Path logs = dir.resolve("logs"), display = logs.resolve("translation-display.jsonl");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exports = new AtomicInteger();
        try (var audit = audit(display);
                var service = new SharedLogExportService()) {
            writer(audit)
                    .execute(
                            () -> {
                                entered.countDown();
                                waitUninterruptibly(release);
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++)
                audit.recordPipeline(
                        "one-source",
                        List.of(Component.literal("A game explanation.")),
                        "OBSERVATION",
                        "SOURCE_LIST_IDENTITY",
                        Map.of());
            var boundary =
                    assertTimeoutPreemptively(Duration.ofMillis(500), audit::snapshotBarrier);
            assertFalse(boundary.isDone());
            var metadata =
                    new SharedLogExporter.Metadata(
                            "fixture", "26.2", "fixture", "25", "fixture", false, true, true, true,
                            false);
            assertTimeoutPreemptively(
                    Duration.ofMillis(500),
                    () ->
                            assertTrue(
                                    service.start(
                                            boundary,
                                            Duration.ofSeconds(2),
                                            () -> {
                                                exports.incrementAndGet();
                                                return SharedLogExporter.export(
                                                        logs,
                                                        dir.resolve("reports"),
                                                        metadata,
                                                        List.of());
                                            })));
            assertEquals(0, exports.get());
            release.countDown();
            awaitStatus(service, SharedLogExportService.Status.SUCCEEDED);
            assertEquals(1, exports.get());
            try (var zip = new ZipFile(service.state().result().zipPath().toFile())) {
                var entry = zip.getEntry("logs/translation-pipeline.jsonl");
                assertNotNull(entry);
                var rows =
                        new String(
                                        zip.getInputStream(entry).readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .lines()
                                .map(s -> JsonParser.parseString(s).getAsJsonObject())
                                .toList();
                assertEquals(2, rows.size());
                assertEquals("STATE_CHANGE", rows.get(0).get("recordKind").getAsString());
                assertEquals("STATE_SUMMARY", rows.get(1).get("recordKind").getAsString());
                assertEquals(3, rows.get(1).get("observationCount").getAsInt());
            }
            // A second snapshot of the same cumulative state does not duplicate the summary.
            audit.snapshotBarrier().get(2, TimeUnit.SECONDS);
            assertEquals(2, Files.readAllLines(logs.resolve("translation-pipeline.jsonl")).size());
        } finally {
            release.countDown();
        }
    }

    @Test
    void timeoutQueueRejectionAndClosedBoundariesNeverProduceAPartialZip() throws Exception {
        for (var boundary :
                List.of(
                        new CompletableFuture<Void>(),
                        CompletableFuture.<Void>failedFuture(
                                new RejectedExecutionException("fake private path")),
                        CompletableFuture.<Void>failedFuture(
                                new java.util.concurrent.CancellationException(
                                        "fake private text")))) {
            var exports = new AtomicInteger();
            try (var service = new SharedLogExportService()) {
                assertTimeoutPreemptively(
                        Duration.ofMillis(500),
                        () ->
                                assertTrue(
                                        service.start(
                                                boundary,
                                                Duration.ofMillis(30),
                                                () -> {
                                                    exports.incrementAndGet();
                                                    throw new AssertionError(
                                                            "Incomplete diagnostic boundary must not export");
                                                })));
                awaitStatus(service, SharedLogExportService.Status.FAILED);
                assertEquals(
                        SharedLogExportService.FailureReason.DIAGNOSTICS_NOT_READY,
                        service.state().failure());
                assertEquals(0, exports.get());
                assertNull(service.state().result());
                assertFalse(service.state().toString().contains("private"));
                assertTrue(
                        service.start(
                                () ->
                                        new SharedLogExporter.ExportResult(
                                                dir.resolve("safe.zip"), 0, 0, 0, 0, 0)));
                awaitStatus(service, SharedLogExportService.Status.SUCCEEDED);
            }
        }
    }

    @Test
    void fullTooltipQueueAndStoppedWriterFailTheirSnapshotFuture() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var audit = audit(dir.resolve("full.jsonl"));
        try {
            var writer = writer(audit);
            writer.execute(
                    () -> {
                        entered.countDown();
                        waitUninterruptibly(release);
                    });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 128; i++) writer.execute(() -> {});
            var boundary = audit.snapshotBarrier();
            assertThrows(ExecutionException.class, () -> boundary.get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            audit.close();
        }
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> audit.snapshotBarrier().join());
    }

    @Test
    void queuedDiskFailureCannotBeReportedAsACompletedSnapshot() throws Exception {
        Path notDirectory = dir.resolve("file-instead-of-directory");
        Files.writeString(notDirectory, "fixture");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var audit = audit(notDirectory.resolve("display.jsonl"))) {
            writer(audit)
                    .execute(
                            () -> {
                                entered.countDown();
                                waitUninterruptibly(release);
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            audit.record(
                    "fixture",
                    List.of(Component.literal("A game explanation.")),
                    List.of(Component.literal("ゲームの説明。")),
                    List.of("ゲームの説明。"),
                    260,
                    "DICTIONARY",
                    "");
            var boundary = audit.snapshotBarrier();
            release.countDown();
            assertThrows(ExecutionException.class, () -> boundary.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void exportBarrierDoesNotWaitForAnApiRequestThatHasNotProducedDiagnosticsYet()
            throws Exception {
        var ready = CompletableFuture.completedFuture((Void) null);
        var unrelatedApiRequest = new CompletableFuture<String>();
        try (var service = new SharedLogExportService()) {
            assertTrue(
                    service.start(
                            ready,
                            () ->
                                    new SharedLogExporter.ExportResult(
                                            dir.resolve("safe.zip"), 0, 0, 0, 0, 0)));
            awaitStatus(service, SharedLogExportService.Status.SUCCEEDED);
            assertFalse(unrelatedApiRequest.isDone());
        }
    }
}
