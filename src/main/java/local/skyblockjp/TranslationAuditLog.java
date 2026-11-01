package local.skyblockjp;

import com.google.gson.Gson;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/** Bounded FIFO diagnostics with a lifecycle lock independent from translation publication. */
final class TranslationAuditLog implements AutoCloseable {
    @FunctionalInterface
    interface Writer {
        void append(Path path, Map<String, Object> record, String secret) throws Exception;
    }

    private final ThreadPoolExecutor worker;
    private final Object writeLifecycle = new Object();
    private final Path directory;
    private final Logger log;
    private final Writer writer;
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();
    private final java.util.Set<CompletableFuture<Void>> barriers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    TranslationAuditLog(Path cachePath, Logger log) {
        this(cachePath, log, TranslationDiagnostics::append);
    }

    TranslationAuditLog(Path cachePath, Logger log, Writer writer) {
        directory = cachePath.getParent();
        this.log = log;
        this.writer = writer;
        worker =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(256),
                        r -> {
                            var thread = new Thread(r, "SkyBlock-JP-Diagnostics");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    long skippedRecords() {
        return skipped.get();
    }

    boolean submit(
            String filename,
            Map<String, Object> record,
            String secret,
            BooleanSupplier enabled,
            Runnable failed) {
        if (closed) return false;
        // Snapshot before returning to the producer, including the key used for redaction.
        var snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(record));
        try {
            worker.execute(
                    () -> {
                        synchronized (writeLifecycle) {
                            if (closed || !enabled.getAsBoolean()) return;
                            try {
                                if (filename.equals("translation-failures.jsonl"))
                                    log.warn(
                                            "SkyBlock JP translation diagnostic: {}",
                                            TranslationDiagnostics.redact(
                                                    new Gson().toJson(snapshot), secret));
                                writer.append(directory.resolve(filename), snapshot, secret);
                            } catch (Exception e) {
                                writeFailures.incrementAndGet();
                                skipped.incrementAndGet();
                                failed.run();
                                log.warn(
                                        "SkyBlock JP: audit write failed ({}, {})",
                                        filename,
                                        e.getClass().getSimpleName());
                            }
                        }
                    });
            return true;
        } catch (RejectedExecutionException e) {
            skipped.incrementAndGet();
            failed.run();
            return false;
        }
    }

    CompletableFuture<Void> barrier() {
        var complete = new CompletableFuture<Void>();
        long failureEpoch = writeFailures.get();
        barriers.add(complete);
        try {
            worker.execute(
                    () -> {
                        if (writeFailures.get() != failureEpoch)
                            complete.completeExceptionally(
                                    new java.io.IOException("Diagnostic snapshot write failed"));
                        else complete.complete(null);
                        barriers.remove(complete);
                    });
        } catch (RejectedExecutionException e) {
            complete.completeExceptionally(e);
            barriers.remove(complete);
        }
        return complete;
    }

    // Package-private test seam for the bounded queue; production submissions use submit().
    ThreadPoolExecutor executor() {
        return worker;
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        barriers.forEach(
                barrier ->
                        barrier.completeExceptionally(
                                new java.util.concurrent.CancellationException(
                                        "Diagnostic log closed")));
        barriers.clear();
        // Finish any write already in progress before close returns. This never holds Translator's
        // lock.
        synchronized (writeLifecycle) {
        }
    }
}
