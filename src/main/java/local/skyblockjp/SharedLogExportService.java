package local.skyblockjp;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One background snapshot at a time; state outlives any individual settings screen. */
final class SharedLogExportService implements AutoCloseable {
    enum Status {
        IDLE,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CLOSED
    }

    enum FailureReason {
        NONE,
        DIAGNOSTICS_NOT_READY,
        EXPORT_FAILED
    }

    record State(Status status, SharedLogExporter.ExportResult result, FailureReason failure) {
        State(Status status, SharedLogExporter.ExportResult result) {
            this(status, result, FailureReason.NONE);
        }
    }

    @FunctionalInterface
    interface Operation {
        SharedLogExporter.ExportResult run() throws Exception;
    }

    private final ExecutorService worker;
    private volatile State state = new State(Status.IDLE, null);
    private boolean closed;

    SharedLogExportService() {
        this(
                Executors.newSingleThreadExecutor(
                        r -> {
                            var thread = new Thread(r, "SkyBlock-JP-Shared-Logs");
                            thread.setDaemon(true);
                            return thread;
                        }));
    }

    SharedLogExportService(ExecutorService worker) {
        this.worker = Objects.requireNonNull(worker);
    }

    State state() {
        return state;
    }

    boolean start(Operation operation) {
        return start(CompletableFuture.completedFuture(null), operation);
    }

    boolean start(CompletableFuture<Void> diagnosticsReady, Operation operation) {
        return start(diagnosticsReady, Duration.ofSeconds(3), operation);
    }

    synchronized boolean start(
            CompletableFuture<Void> diagnosticsReady, Duration timeout, Operation operation) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(diagnosticsReady);
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Diagnostic wait must be bounded and positive");
        if (closed || state.status() == Status.RUNNING) return false;
        state = new State(Status.RUNNING, null);
        try {
            worker.execute(
                    () -> {
                        try {
                            // This bounded wait runs on the export worker, never on the UI thread.
                            // It covers records queued at the click boundary, not API completion.
                            try {
                                diagnosticsReady.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                            } catch (ExecutionException
                                    | TimeoutException
                                    | java.util.concurrent.CancellationException notReady) {
                                synchronized (this) {
                                    if (!closed)
                                        state =
                                                new State(
                                                        Status.FAILED,
                                                        null,
                                                        FailureReason.DIAGNOSTICS_NOT_READY);
                                }
                                return;
                            }
                            var result = Objects.requireNonNull(operation.run());
                            synchronized (this) {
                                if (!closed) state = new State(Status.SUCCEEDED, result);
                            }
                        } catch (Exception failure) {
                            if (failure instanceof InterruptedException)
                                Thread.currentThread().interrupt();
                            // Exception messages can contain paths, source text or credentials.
                            synchronized (this) {
                                if (!closed)
                                    state =
                                            new State(
                                                    Status.FAILED,
                                                    null,
                                                    FailureReason.EXPORT_FAILED);
                            }
                        }
                    });
            return true;
        } catch (RejectedExecutionException stopped) {
            state = new State(Status.FAILED, null, FailureReason.EXPORT_FAILED);
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        state = new State(Status.CLOSED, null);
        worker.shutdownNow();
    }
}
