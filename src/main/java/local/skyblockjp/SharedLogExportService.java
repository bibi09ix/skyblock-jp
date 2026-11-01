package local.skyblockjp;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** One background snapshot at a time; state outlives any individual settings screen. */
final class SharedLogExportService implements AutoCloseable {
    enum Status { IDLE, RUNNING, SUCCEEDED, FAILED, CLOSED }
    record State(Status status, SharedLogExporter.ExportResult result) {}
    @FunctionalInterface interface Operation { SharedLogExporter.ExportResult run() throws Exception; }
    private final ExecutorService worker;
    private volatile State state = new State(Status.IDLE, null);
    private boolean closed;

    SharedLogExportService() {
        this(Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "SkyBlock-JP-Shared-Logs");
            thread.setDaemon(true);
            return thread;
        }));
    }
    SharedLogExportService(ExecutorService worker) { this.worker = Objects.requireNonNull(worker); }
    State state() { return state; }
    synchronized boolean start(Operation operation) {
        Objects.requireNonNull(operation);
        if (closed || state.status() == Status.RUNNING) return false;
        state = new State(Status.RUNNING, null);
        try {
            worker.execute(() -> {
                try {
                    var result = Objects.requireNonNull(operation.run());
                    synchronized (this) { if (!closed) state = new State(Status.SUCCEEDED, result); }
                } catch (Exception failure) {
                    // Exception messages can contain paths, source text or credentials.
                    synchronized (this) { if (!closed) state = new State(Status.FAILED, null); }
                }
            });
            return true;
        } catch (RejectedExecutionException stopped) {
            state = new State(Status.FAILED, null);
            return false;
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        state = new State(Status.CLOSED, null);
        worker.shutdownNow();
    }
}
