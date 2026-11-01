package local.skyblockjp;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

/** Logs the mod's final tooltip lines once per distinct layout; disk I/O stays off render thread. */
final class DisplayLog implements AutoCloseable {
    private final String session = UUID.randomUUID().toString();
    private final Set<String> seen = new LinkedHashSet<>();
    private final ThreadPoolExecutor writer =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128),
                    r -> {
                        var t = new Thread(r, "SkyBlock-JP-DisplayLog");
                        t.setDaemon(true);
                        return t;
                    });
    private final Path path;
    private final Logger log;

    DisplayLog(Path folder, Logger log) {
        path = folder.resolve("translation-display.jsonl");
        this.log = log;
    }

    void record(
            List<Component> original,
            List<Component> rendered,
            int width,
            int screenWidth,
            List<String> translations) {
        var before = original.stream().map(Component::getString).toList();
        var after = rendered.stream().map(Component::getString).toList();
        String signature = before + "|" + after + "|" + width + "|" + screenWidth;
        if (!seen.add(signature)) return;
        if (seen.size() > 512) seen.remove(seen.iterator().next());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("session", session);
        entry.put("version", "0.4.4");
        entry.put("item", before.isEmpty() ? "" : before.getFirst());
        entry.put("wrapWidth", width);
        entry.put("screenWidth", screenWidth);
        entry.put("originalLines", before);
        entry.put("translatedParagraphs", List.copyOf(translations));
        entry.put("renderedLines", after);
        entry.put(
                "stage",
                "SkyBlock JP tooltip callback output; before subsequent mods/vanilla layout");
        entry.put("capturedAt", java.time.Instant.now().toString());
        try {
            writer.execute(
                    () -> {
                        try {
                            TranslationDiagnostics.append(path, entry, "");
                        } catch (Exception e) {
                            log.warn(
                                    "SkyBlock JP: display log write failed ({})",
                                    e.getClass().getSimpleName());
                        }
                    });
        } catch (RejectedExecutionException e) {
            log.warn("SkyBlock JP: display log queue full; entry skipped");
        }
    }

    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
