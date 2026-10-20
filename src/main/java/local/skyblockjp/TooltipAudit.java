package local.skyblockjp;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Writes each changed tooltip once, off the render thread. */
final class TooltipAudit implements AutoCloseable {
    private final String session = UUID.randomUUID().toString();
    private final Map<String, String> seen = new LinkedHashMap<>();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(128), r -> { Thread t = new Thread(r, "SkyBlock-JP-Audit"); t.setDaemon(true); return t; });
    private final Path file;
    private final Logger logger;
    private final String secret;
    TooltipAudit(Path file, Logger logger, String secret) { this.file = file; this.logger = logger; this.secret = secret; }
    void forget(String key) { seen.remove(key); }
    void record(String key, List<Component> original, List<Component> rendered, List<String> translations, int width, String provider, String model) {
        List<String> before = original.stream().map(Component::getString).toList();
        List<String> after = rendered.stream().map(Component::getString).toList();
        String signature = width + "|" + after + "|" + translations;
        if (signature.equals(seen.get(key))) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("session", session); entry.put("version", "0.6.4"); entry.put("item", before.getFirst());
        entry.put("observedAt", java.time.Instant.now().toString());
        entry.put("provider", provider); entry.put("model", model); entry.put("wrapWidthPixels", width);
        entry.put("originalLines", before); entry.put("translatedParagraphs", translations);
        entry.put("displayLines", after); entry.put("displayText", String.join("\n", after));
        entry.put("stage", "mod tooltip lines before Minecraft/other-mod layout");
        try {
            writer.execute(() -> {
                try { TranslationDiagnostics.append(file, entry, secret); }
                catch (Exception e) { logger.warn("SkyBlock JP: tooltip audit could not save ({})", e.getClass().getSimpleName()); }
            });
            if (seen.size() >= 1000) seen.remove(seen.keySet().iterator().next());
            seen.put(key, signature);
        } catch (RejectedExecutionException e) { logger.warn("SkyBlock JP: tooltip audit queue full; record skipped"); }
    }
    public void close() {
        writer.shutdown();
        try { if (!writer.awaitTermination(3, TimeUnit.SECONDS)) writer.shutdownNow(); }
        catch (InterruptedException e) { writer.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}

