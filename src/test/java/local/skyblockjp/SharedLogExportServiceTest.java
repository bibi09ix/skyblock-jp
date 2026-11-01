package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SharedLogExportServiceTest {
    private static SharedLogExporter.ExportResult result() {
        return new SharedLogExporter.ExportResult(Path.of("safe.zip"), 7, 1, 0, 0, 3);
    }

    @Test
    void duplicateClicksCannotStartTwoJobsAndCompletedStateSurvivesReads() {
        var worker = new ManualWorker();
        try (var service = new SharedLogExportService(worker)) {
            assertEquals(SharedLogExportService.Status.IDLE, service.state().status());
            assertTrue(service.start(SharedLogExportServiceTest::result));
            assertEquals(SharedLogExportService.Status.RUNNING, service.state().status());
            assertFalse(
                    service.start(
                            () -> {
                                fail("Duplicate task must not run");
                                return result();
                            }));
            assertEquals(1, worker.tasks.size());
            worker.runNext();
            var firstScreen = service.state();
            assertEquals(SharedLogExportService.Status.SUCCEEDED, firstScreen.status());
            assertEquals(7, firstScreen.result().exportedRecords());
            assertSame(firstScreen, service.state());
            assertTrue(service.start(SharedLogExportServiceTest::result));
            worker.runNext();
            assertEquals(SharedLogExportService.Status.SUCCEEDED, service.state().status());
        }
    }

    @Test
    void failuresDoNotExposeExceptionMessagesAndCanBeRetried() {
        var worker = new ManualWorker();
        try (var service = new SharedLogExportService(worker)) {
            service.start(
                    () -> {
                        throw new IOException("Bearer private-key C:/private/path secret chat");
                    });
            worker.runNext();
            assertEquals(SharedLogExportService.Status.FAILED, service.state().status());
            assertNull(service.state().result());
            assertFalse(service.state().toString().contains("private"));
            assertTrue(service.start(SharedLogExportServiceTest::result));
            worker.runNext();
            assertEquals(SharedLogExportService.Status.SUCCEEDED, service.state().status());
        }
    }

    @Test
    void shutdownCancelsQueuedWorkAndRejectsNewExports() {
        var worker = new ManualWorker();
        var service = new SharedLogExportService(worker);
        service.start(
                () -> {
                    fail("Cancelled export must not run");
                    return result();
                });
        service.close();
        service.close();
        assertTrue(worker.isShutdown());
        assertTrue(worker.tasks.isEmpty());
        assertEquals(SharedLogExportService.Status.CLOSED, service.state().status());
        assertFalse(service.start(SharedLogExportServiceTest::result));
    }

    @Test
    void workerRejectionDoesNotLeaveProgressStuck() {
        var worker = new ManualWorker();
        worker.shutdown();
        try (var service = new SharedLogExportService(worker)) {
            assertFalse(service.start(SharedLogExportServiceTest::result));
            assertEquals(SharedLogExportService.Status.FAILED, service.state().status());
        }
    }

    static final class ManualWorker extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean stopped;

        void runNext() {
            tasks.removeFirst().run();
        }

        @Override
        public void execute(Runnable task) {
            if (stopped) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(task);
        }

        @Override
        public void shutdown() {
            stopped = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            stopped = true;
            var remaining = List.copyOf(tasks);
            tasks.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return stopped;
        }

        @Override
        public boolean isTerminated() {
            return stopped && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
