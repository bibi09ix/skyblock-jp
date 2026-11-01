package local.skyblockjp;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;

/** Provider-scoped LRU responses and disk lifecycle, independent of request publication locks. */
final class TranslationCacheStore implements AutoCloseable {
    private final Map<String, String> entries;
    private final Object persistence = new Object();
    private final Path path;
    private final Logger log;
    private long lastSave;
    private boolean closed;

    TranslationCacheStore(Path path, int limit, Logger log) {
        this.path = path;
        this.log = log;
        entries =
                Collections.synchronizedMap(
                        new LinkedHashMap<>(128, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(Map.Entry<String, String> entry) {
                                return size() > limit;
                            }
                        });
        try {
            if (Files.exists(path)) {
                var object = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                object.entrySet().stream()
                        .limit(limit)
                        .forEach(
                                entry ->
                                        entries.put(
                                                entry.getKey(), entry.getValue().getAsString()));
            }
        } catch (Exception e) {
            log.warn(
                    "SkyBlock JP: could not load translation cache ({})",
                    e.getClass().getSimpleName());
        }
    }

    String get(String key) {
        return entries.get(key);
    }

    boolean containsKey(String key) {
        return entries.containsKey(key);
    }

    void put(String key, String value) {
        entries.put(key, value);
    }

    void remove(String key) {
        entries.remove(key);
    }

    void saveIfDue() {
        synchronized (persistence) {
            if (!closed && System.currentTimeMillis() - lastSave > 5000) save();
        }
    }

    private void save() {
        try {
            Files.createDirectories(path.getParent());
            var temp = path.resolveSibling(path.getFileName() + ".tmp");
            Map<String, String> snapshot;
            synchronized (entries) {
                snapshot = new LinkedHashMap<>(entries);
            }
            Files.writeString(temp, new Gson().toJson(snapshot));
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            lastSave = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("SkyBlock JP: cache save failed ({})", e.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        synchronized (persistence) {
            if (closed) return;
            closed = true;
            save();
        }
    }
}
