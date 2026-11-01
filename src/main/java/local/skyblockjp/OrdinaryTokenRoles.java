package local.skyblockjp;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit source-bound homonym decisions; never a general relaxation of stat-name matching. */
final class OrdinaryTokenRoles {
    static final String RESOURCE = "/assets/skyblock_jp/dictionary/ordinary-tokens.json";
    private static final Set<String> HOMONYMS =
            Set.of("health", "strength", "speed", "vitality", "defense", "intelligence");

    record Decision(String id, Set<String> words) {
        Decision {
            words = Set.copyOf(words);
        }
    }

    record Stats(boolean loaded, int rules, String error) {}

    record Index(Map<String, Decision> sources, Stats stats) {
        Index {
            sources = Map.copyOf(sources);
        }

        Set<String> words(String source) {
            var decision = sources.get(BundledDictionary.normalize(source));
            return decision == null ? Set.of() : decision.words();
        }
    }

    private static final class Holder {
        private static final Index INDEX =
                load(OrdinaryTokenRoles.class.getResourceAsStream(RESOURCE));
    }

    private OrdinaryTokenRoles() {}

    static Set<String> wordsForSource(String source) {
        return Holder.INDEX.words(source);
    }

    static Stats stats() {
        return Holder.INDEX.stats();
    }

    private static Index failed(String error) {
        return new Index(Map.of(), new Stats(false, 0, error));
    }

    static Index load(InputStream input) {
        if (input == null) return failed("RESOURCE_MISSING");
        try (input) {
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            require(bytes.length <= 256 * 1024, "RESOURCE_TOO_LARGE");
            var decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var reader =
                    new JsonReader(
                            new InputStreamReader(new ByteArrayInputStream(bytes), decoder))) {
                reader.setStrictness(Strictness.STRICT);
                var root =
                        object(read(reader, 0), Set.of("schema_version", "normalization", "rules"));
                require(reader.peek() == JsonToken.END_DOCUMENT, "TRAILING_CONTENT");
                require(
                        root.get("schema_version").equals(1L)
                                && root.get("normalization").equals("bundled-source-v1"),
                        "SCHEMA_MISMATCH");
                var result = new HashMap<String, Decision>();
                var ids = new HashSet<String>();
                for (var raw : array(root.get("rules"), 512)) {
                    var rule =
                            object(
                                    raw,
                                    Set.of(
                                            "id",
                                            "approved",
                                            "source",
                                            "source_sha256",
                                            "reason",
                                            "ordinary_spans"));
                    require(rule.get("approved") instanceof Boolean, "APPROVAL_REQUIRED");
                    if (!Boolean.TRUE.equals(rule.get("approved"))) continue;
                    String id = text(rule.get("id")), source = text(rule.get("source"));
                    require(!id.isBlank() && id.length() <= 160 && ids.add(id), "INVALID_RULE_ID");
                    require(
                            !source.isBlank() && source.equals(BundledDictionary.normalize(source)),
                            "SOURCE_NOT_NORMALIZED");
                    require(
                            hash(source).equals(text(rule.get("source_sha256"))),
                            "SOURCE_HASH_MISMATCH");
                    require(!text(rule.get("reason")).isBlank(), "REVIEW_REASON_REQUIRED");
                    var spans = new HashMap<String, Set<Integer>>();
                    for (var rawSpan : array(rule.get("ordinary_spans"), 64)) {
                        var span = object(rawSpan, Set.of("start", "end", "text"));
                        String word = text(span.get("text"));
                        int start = integer(span.get("start")), end = integer(span.get("end"));
                        require(HOMONYMS.contains(word), "UNSAFE_ORDINARY_WORD");
                        require(
                                start >= 0
                                        && end > start
                                        && end <= source.length()
                                        && source.substring(start, end).equals(word),
                                "SPAN_MISMATCH");
                        require(
                                (start == 0 || !identifier(source.charAt(start - 1)))
                                        && (end == source.length()
                                                || !identifier(source.charAt(end))),
                                "SPAN_NOT_WHOLE_WORD");
                        require(
                                spans.computeIfAbsent(word, ignored -> new HashSet<>()).add(start),
                                "DUPLICATE_SPAN");
                    }
                    require(!spans.isEmpty(), "MISSING_ORDINARY_SPANS");
                    for (var entry : spans.entrySet()) {
                        // forText currently applies lowercase-word patterns to the full paragraph.
                        // Do not partially demote a word also used as a formal lowercase stat.
                        var occurrences =
                                Pattern.compile(
                                                "(?<![A-Za-z])"
                                                        + Pattern.quote(entry.getKey())
                                                        + "(?![A-Za-z])")
                                        .matcher(source)
                                        .results()
                                        .map(m -> m.start())
                                        .collect(java.util.stream.Collectors.toSet());
                        require(
                                occurrences.equals(entry.getValue()),
                                "MIXED_OR_UNANNOTATED_OCCURRENCES");
                    }
                    require(
                            result.putIfAbsent(source, new Decision(id, spans.keySet())) == null,
                            "DUPLICATE_SOURCE");
                }
                return new Index(result, new Stats(true, result.size(), ""));
            }
        } catch (Exception error) {
            return failed(error instanceof Invalid ? error.getMessage() : "MALFORMED_RESOURCE");
        }
    }

    private static boolean identifier(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(Object value) throws Invalid {
        require(value instanceof String, "STRING_REQUIRED");
        return (String) value;
    }

    private static int integer(Object value) throws Invalid {
        require(
                value instanceof Long && (Long) value >= 0 && (Long) value <= 16000,
                "OFFSET_REQUIRED");
        return ((Long) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, Set<String> keys) throws Invalid {
        require(value instanceof Map, "OBJECT_REQUIRED");
        var map = (Map<String, Object>) value;
        require(map.keySet().equals(keys), "INVALID_FIELDS");
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, int limit) throws Invalid {
        require(value instanceof List && ((List<?>) value).size() <= limit, "ARRAY_REQUIRED");
        return (List<Object>) value;
    }

    private static Object read(JsonReader reader, int depth) throws Exception {
        require(depth <= 6, "DEPTH_LIMIT");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                var fields = new LinkedHashMap<String, Object>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    require(!fields.containsKey(key), "DUPLICATE_FIELD");
                    fields.put(key, read(reader, depth + 1));
                    require(fields.size() <= 8, "FIELD_LIMIT");
                }
                reader.endObject();
                yield fields;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                var values = new ArrayList<Object>();
                while (reader.hasNext()) {
                    require(values.size() < 512, "ARRAY_LIMIT");
                    values.add(read(reader, depth + 1));
                }
                reader.endArray();
                yield values;
            }
            case STRING -> {
                String value = reader.nextString();
                require(value.length() <= 16000, "STRING_LIMIT");
                yield value;
            }
            case NUMBER -> {
                String value = reader.nextString();
                require(value.matches("[0-9]{1,5}"), "INTEGER_REQUIRED");
                yield Long.parseLong(value);
            }
            case BOOLEAN -> reader.nextBoolean();
            default -> throw new Invalid("INVALID_JSON_VALUE");
        };
    }

    private static void require(boolean valid, String error) throws Invalid {
        if (!valid) throw new Invalid(error);
    }

    private static final class Invalid extends Exception {
        Invalid(String error) {
            super(error);
        }
    }
}
