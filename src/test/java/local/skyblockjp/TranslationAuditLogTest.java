package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class TranslationAuditLogTest {
    @TempDir Path dir;

    @Test
    void aQueuedWriteFailureFailsTheBoundaryWithoutStoppingLaterSnapshots() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        var failures = new AtomicInteger();
        try (var audit =
                new TranslationAuditLog(
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("audit-boundary-failure-test"),
                        (path, row, secret) -> {
                            if (writes.incrementAndGet() == 1) {
                                entered.countDown();
                                assertTrue(release.await(3, TimeUnit.SECONDS));
                                throw new java.io.IOException("fake private filesystem detail");
                            }
                        })) {
            assertTrue(
                    audit.submit(
                            "fixture.jsonl",
                            Map.of("index", 1),
                            "",
                            () -> true,
                            failures::incrementAndGet));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var boundary = audit.barrier();
            release.countDown();
            var failure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> boundary.get(2, TimeUnit.SECONDS));
            assertEquals("Diagnostic snapshot write failed", failure.getCause().getMessage());
            assertFalse(failure.toString().contains("private"));
            assertEquals(1, failures.get());
            assertEquals(1, audit.skippedRecords());
            assertTrue(
                    audit.submit(
                            "fixture.jsonl",
                            Map.of("index", 2),
                            "",
                            () -> true,
                            failures::incrementAndGet));
            audit.barrier().get(2, TimeUnit.SECONDS);
            assertEquals(2, writes.get());
            assertEquals(1, failures.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void aBlockedDiagnosticWriteDoesNotOwnTheTranslationPublicationMonitor() throws Exception {
        var config = new Config();
        config.provider = "GOOGLE";
        config.candidateDictionary = true;
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        TranslationAuditLog.Writer slow =
                (path, row, secret) -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                };
        try (var translator =
                new Translator(
                        config,
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("audit-lock-test"),
                        text -> {
                            calls.incrementAndGet();
                            return "こんにちは。";
                        },
                        BundledDictionary.empty(),
                        System::currentTimeMillis,
                        slow)) {
            try {
                translator
                        .requestNpc(
                                new StyledParagraph(List.of(Component.literal("Novel greeting."))),
                                "Bob",
                                "")
                        .get(2, TimeUnit.SECONDS);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(
                        "こんにちは。",
                        assertTimeoutPreemptively(
                                Duration.ofSeconds(2),
                                () ->
                                        translator
                                                .request("Another new paragraph.")
                                                .get(1, TimeUnit.SECONDS)));
                assertEquals(2, calls.get());
            } finally {
                release.countDown();
            }
            translator.awaitDiagnostics().get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void fifoCapacityFailureCountsAndCloseAreIndependentOfTheTranslationQueue() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(257);
        var failures = new AtomicInteger();
        List<Integer> written = Collections.synchronizedList(new ArrayList<>());
        try (var audit =
                new TranslationAuditLog(
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("audit-fifo-test"),
                        (path, row, key) -> {
                            if ((int) row.get("index") == 0) {
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            }
                            written.add((int) row.get("index"));
                            completed.countDown();
                        })) {
            assertTrue(
                    audit.submit(
                            "fixture.jsonl",
                            Map.of("index", 0),
                            "",
                            () -> true,
                            failures::incrementAndGet));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 1; i <= 256; i++)
                assertTrue(
                        audit.submit(
                                "fixture.jsonl",
                                Map.of("index", i),
                                "",
                                () -> true,
                                failures::incrementAndGet));
            assertFalse(
                    audit.submit(
                            "fixture.jsonl",
                            Map.of("index", 257),
                            "",
                            () -> true,
                            failures::incrementAndGet));
            assertEquals(1, failures.get());
            assertEquals(1, audit.skippedRecords());
            release.countDown();
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            audit.barrier().get(2, TimeUnit.SECONDS);
            assertEquals(java.util.stream.IntStream.range(0, 257).boxed().toList(), written);
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentTranslatorCloseCallersWaitForTheSameLifecycleWithoutBlockingNewPolicyChecks()
            throws Exception {
        var entered = new CountDownLatch(1);
        var closing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var config = new Config();
        config.provider = "GOOGLE";
        config.candidateDictionary = true;
        var translator =
                new Translator(
                        config,
                        dir.resolve("close-cache.json"),
                        LoggerFactory.getLogger("translator-close-test"),
                        text -> "こんにちは。",
                        BundledDictionary.empty(),
                        System::currentTimeMillis,
                        (path, row, secret) -> {
                            entered.countDown();
                            for (; ; ) {
                                try {
                                    release.await();
                                    break;
                                } catch (InterruptedException ignored) {
                                    closing.countDown();
                                }
                            }
                        });
        try {
            translator
                    .requestNpc(
                            new StyledParagraph(List.of(Component.literal("Novel close fixture."))),
                            "Bob",
                            "")
                    .get(2, TimeUnit.SECONDS);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var first = CompletableFuture.runAsync(translator::close);
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            var second = CompletableFuture.runAsync(translator::close);
            assertNull(
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(1),
                            () ->
                                    translator
                                            .request("Requests after close must remain local.")
                                            .get(1, TimeUnit.SECONDS)));
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertTrue(Files.exists(dir.resolve("close-cache.json")));
        } finally {
            release.countDown();
            translator.close();
        }
    }

    @Test
    void recordsAndSecretsAreSnapshottedAndTheWriterRedactsThem() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var audit =
                new TranslationAuditLog(
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("audit-redaction-test"))) {
            audit.executor()
                    .execute(
                            () -> {
                                entered.countDown();
                                try {
                                    assertTrue(release.await(3, TimeUnit.SECONDS));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var row = new LinkedHashMap<String, Object>();
            row.put("source", "kept SECRET_CANARY");
            assertTrue(
                    audit.submit(
                            "fixture.jsonl",
                            row,
                            "SECRET_CANARY",
                            () -> true,
                            () -> fail("Unexpected IO failure")));
            row.put("source", "mutated afterwards");
            release.countDown();
            audit.barrier().get(2, TimeUnit.SECONDS);
            String saved = Files.readString(dir.resolve("fixture.jsonl"));
            assertTrue(saved.contains("kept [REDACTED]"));
            assertFalse(saved.contains("SECRET_CANARY"));
            assertFalse(saved.contains("mutated afterwards"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void writeFailureIsCountedAndDoesNotStopLaterRecords() throws Exception {
        var calls = new AtomicInteger();
        var failed = new AtomicInteger();
        var firstFailure = new CountDownLatch(1);
        try (var audit =
                new TranslationAuditLog(
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("audit-error-test"),
                        (path, row, secret) -> {
                            if (calls.incrementAndGet() == 1)
                                throw new java.io.IOException("fixture");
                        })) {
            audit.submit(
                    "fixture.jsonl",
                    Map.of(),
                    "",
                    () -> true,
                    () -> {
                        failed.incrementAndGet();
                        firstFailure.countDown();
                    });
            assertTrue(firstFailure.await(2, TimeUnit.SECONDS));
            audit.submit("fixture.jsonl", Map.of(), "", () -> true, failed::incrementAndGet);
            audit.barrier().get(2, TimeUnit.SECONDS);
            assertEquals(2, calls.get());
            assertEquals(1, failed.get());
            assertEquals(1, audit.skippedRecords());
        }
    }

    @Test
    void closingCancelsQueuedRecordsAndBarriersAndWaitsOnlyOnItsOwnActiveWrite() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new AtomicInteger();
        var audit =
                new TranslationAuditLog(
                        dir.resolve("cache.json"),
                        LoggerFactory.getLogger("audit-close-test"),
                        (path, row, secret) -> {
                            entered.countDown();
                            for (; ; ) {
                                try {
                                    release.await();
                                    break;
                                } catch (InterruptedException ignored) {
                                    /* Simulate a disk writer that cannot be interrupted. */
                                }
                            }
                            writes.incrementAndGet();
                        });
        try {
            audit.submit("fixture.jsonl", Map.of(), "", () -> true, () -> {});
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            audit.submit("fixture.jsonl", Map.of(), "", () -> true, () -> {});
            var barrier = audit.barrier();
            var closing = CompletableFuture.runAsync(audit::close);
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> barrier.get(2, TimeUnit.SECONDS));
            assertFalse(
                    closing.isDone(),
                    "close waits for its in-flight write, without Translator's lock");
            assertFalse(audit.submit("fixture.jsonl", Map.of(), "", () -> true, () -> {}));
            release.countDown();
            closing.get(2, TimeUnit.SECONDS);
            assertEquals(1, writes.get());
        } finally {
            release.countDown();
            audit.close();
        }
    }
}
