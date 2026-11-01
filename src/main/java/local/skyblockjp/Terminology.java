package local.skyblockjp;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reviewed literal terminology; grammar and quantity validation stay in their own code. */
final class Terminology {
    private static final String RESOURCE = "/assets/skyblock_jp/terminology.json";
    private static final Data DATA = loadBundled();

    record Data(
            List<String> protectedNames,
            List<String> extraProtectedNames,
            Set<String> ordinaryStatWords,
            Map<String, String> entityAliases,
            Map<String, String> suffixAliases,
            Map<String, List<String>> targetAliases) {}

    private Terminology() {}

    static List<String> protectedNames() {
        return DATA.protectedNames();
    }

    static List<String> extraProtectedNames() {
        return DATA.extraProtectedNames();
    }

    static Set<String> ordinaryStatWords() {
        return DATA.ordinaryStatWords();
    }

    static Map<String, String> entityAliases() {
        return DATA.entityAliases();
    }

    static Map<String, String> suffixAliases() {
        return DATA.suffixAliases();
    }

    static List<String> targetAliases(String literal) {
        return DATA.targetAliases().getOrDefault(literal.toLowerCase(Locale.ROOT), List.of());
    }

    private static Data loadBundled() {
        try (InputStream input = Terminology.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Missing " + RESOURCE);
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Invalid bundled terminology: " + RESOURCE, failure);
        }
    }

    static Data parse(String json) {
        JsonObject doc = JsonParser.parseString(json).getAsJsonObject();
        if (doc.get("schema_version").getAsInt() != 1)
            throw new IllegalArgumentException("Terminology schema");
        List<String> names = strings(doc.getAsJsonArray("protected_names"));
        List<String> extra = strings(doc.getAsJsonArray("extra_protected_names"));
        Set<String> ordinary =
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(strings(doc.getAsJsonArray("ordinary_stat_words"))));
        if (!names.containsAll(ordinary))
            throw new IllegalArgumentException("Ordinary stat word lacks protected form");
        Map<String, String> entities = map(doc.getAsJsonObject("entity_aliases"));
        Map<String, String> suffix = map(doc.getAsJsonObject("suffix_aliases"));
        Map<String, List<String>> targets = new LinkedHashMap<>();
        for (var entry : doc.getAsJsonObject("target_aliases").entrySet()) {
            String key = literal(entry.getKey()).toLowerCase(Locale.ROOT);
            if (targets.putIfAbsent(key, strings(entry.getValue().getAsJsonArray())) != null)
                throw new IllegalArgumentException("Duplicate target alias");
        }
        return new Data(
                names, extra, ordinary, entities, suffix, Collections.unmodifiableMap(targets));
    }

    private static String literal(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 160
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid terminology literal");
        return value;
    }

    private static String string(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Terminology values must be strings");
        return literal(value.getAsString());
    }

    private static List<String> strings(JsonArray values) {
        if (values == null || values.isEmpty() || values.size() > 4096)
            throw new IllegalArgumentException("Terminology list size");
        var result = new LinkedHashSet<String>();
        for (var value : values)
            if (!result.add(string(value)))
                throw new IllegalArgumentException("Duplicate terminology literal");
        return List.copyOf(result);
    }

    private static Map<String, String> map(JsonObject values) {
        if (values == null || values.size() > 4096)
            throw new IllegalArgumentException("Terminology map size");
        var result = new LinkedHashMap<String, String>();
        for (var entry : values.entrySet())
            result.put(literal(entry.getKey()), string(entry.getValue()));
        return Collections.unmodifiableMap(result);
    }
}
