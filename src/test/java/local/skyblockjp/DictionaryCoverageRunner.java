package local.skyblockjp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import org.slf4j.helpers.NOPLogger;

/** Offline CLI for the fixed public corpus and optional, local-only shared log ZIPs. */
public final class DictionaryCoverageRunner {
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_CASES = 10_000;
    private static final long MAX_LOG_BYTES = 32_000_000;
    private static final long MAX_TOTAL_INPUT_BYTES = 96_000_000;
    private static final int MAX_OBSERVATIONS = 50_000;
    private static final int MAX_ZIPS = 32;
    private static final long MAX_NATIVE_CHARACTERS = 8_000_000;
    private static final long MAX_NATIVE_RUNS = 500_000;
    private static final long MAX_NATIVE_LINES = 100_000;
    private static final int MAX_RAW_FIELDS = 16_384;
    private static final int MAX_RAW_DEPTH = 32;
    private static final int MAX_METADATA_CHARACTERS = 4096;

    /** Small injectable budgets let tests exercise every boundary without large input files. */
    record CaptureLimits(
            int zipCount,
            long logBytes,
            long totalBytes,
            int observations,
            int cases,
            long nativeCharacters,
            long nativeRuns,
            long nativeLines,
            int rawFields) {
        CaptureLimits {
            if (zipCount < 1
                    || logBytes < 1
                    || logBytes >= Integer.MAX_VALUE
                    || totalBytes < 1
                    || observations < 1
                    || cases < 1
                    || nativeCharacters < 1
                    || nativeRuns < 1
                    || nativeLines < 1
                    || rawFields < 1)
                throw new IllegalArgumentException("Invalid capture input budget");
        }

        static CaptureLimits defaults() {
            return new CaptureLimits(
                    MAX_ZIPS,
                    MAX_LOG_BYTES,
                    MAX_TOTAL_INPUT_BYTES,
                    MAX_OBSERVATIONS,
                    MAX_CASES,
                    MAX_NATIVE_CHARACTERS,
                    MAX_NATIVE_RUNS,
                    MAX_NATIVE_LINES,
                    MAX_RAW_FIELDS);
        }
    }

    static final class CaptureLimitException extends IllegalArgumentException {
        CaptureLimitException(String message) {
            super(message);
        }
    }

    private static final class CaptureBudget {
        private final CaptureLimits limits;
        private long bytes, nativeCharacters, nativeRuns, nativeLines;
        private int records;

        CaptureBudget(CaptureLimits limits) {
            this.limits = limits;
        }

        byte[] readLog(ZipFile zip, java.util.zip.ZipEntry entry) throws IOException {
            long remaining = limits.totalBytes() - bytes;
            if (entry.getSize() > limits.logBytes()) fail("Capture log byte limit exceeded");
            if (entry.getSize() > remaining) fail("Total capture byte limit exceeded");
            int allowed = (int) Math.min(limits.logBytes(), remaining);
            byte[] content;
            try (var stream = zip.getInputStream(entry)) {
                content = stream.readNBytes(allowed + 1);
            }
            if (content.length > limits.logBytes()) fail("Capture log byte limit exceeded");
            if (content.length > remaining) fail("Total capture byte limit exceeded");
            bytes += content.length;
            return content;
        }

        void observe() {
            if (++records > limits.observations()) fail("Capture observation limit exceeded");
        }

        void nativeRun(int characters) {
            if (characters > limits.nativeCharacters() - nativeCharacters)
                fail("Total native character limit exceeded");
            if (++nativeRuns > limits.nativeRuns()) fail("Total native run limit exceeded");
            nativeCharacters += characters;
        }

        void nativeLine() {
            if (++nativeLines > limits.nativeLines()) fail("Total native line limit exceeded");
        }
    }

    private static void fail(String message) {
        throw new CaptureLimitException(message);
    }

    record InputSet(
            String inputKind,
            String fingerprint,
            List<DictionaryCoverageReplay.Input> cases,
            int skippedRecords,
            int duplicateLogs) {}

    record Report(
            int schemaVersion,
            String inputKind,
            String scope,
            String inputSha256,
            String dictionaryBuildId,
            int apiCalls,
            int skippedInputRecords,
            int duplicateLogPayloads,
            List<DictionaryCoverageReplay.CaseResult> cases,
            List<String> problems) {}

    private DictionaryCoverageRunner() {}

    static InputSet fixture(byte[] bytes) {
        if (bytes.length > MAX_LOG_BYTES)
            throw new IllegalArgumentException("Coverage fixture too large");
        var document =
                JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (document.get("schemaVersion").getAsInt() != 1)
            throw new IllegalArgumentException("Unknown coverage fixture schema");
        var cases = new ArrayList<DictionaryCoverageReplay.Input>();
        var ids = new HashSet<String>();
        for (var value : document.getAsJsonArray("cases")) {
            var input = DictionaryCoverageReplay.input(value.getAsJsonObject());
            if (!ids.add(input.id()))
                throw new IllegalArgumentException("Duplicate fixture case id");
            cases.add(input);
            if (cases.size() > MAX_CASES) throw new IllegalArgumentException("Too many cases");
        }
        if (cases.isEmpty()) throw new IllegalArgumentException("Empty coverage corpus");
        return new InputSet(
                "REVIEWED_FIXTURE", DictionaryCoverageReplay.hash(bytes), List.copyOf(cases), 0, 0);
    }

    /** Read only complete native TOOLTIP_DISPLAY records. Pipeline summaries are never added. */
    static InputSet captureZips(List<Path> paths) throws IOException {
        return captureZips(paths, CaptureLimits.defaults());
    }

    static InputSet captureZips(List<Path> paths, CaptureLimits limits) throws IOException {
        if (paths.size() > limits.zipCount()) fail("Capture ZIP count limit exceeded");
        var budget = new CaptureBudget(limits);
        var logHashes = new HashSet<String>();
        var observations = new HashSet<String>();
        var bySource = new LinkedHashMap<String, JsonObject>();
        int skipped = 0, duplicates = 0;
        for (Path path : paths) {
            try (var zip = new ZipFile(path.toFile())) {
                for (String name :
                        List.of(
                                "logs/translation-display.jsonl.1",
                                "logs/translation-display.jsonl")) {
                    var entry = zip.getEntry(name);
                    if (entry == null) continue;
                    byte[] bytes = budget.readLog(zip, entry);
                    if (!logHashes.add(name + ":" + DictionaryCoverageReplay.hash(bytes))) {
                        duplicates++;
                        continue;
                    }
                    for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                        if (line.isBlank()) continue;
                        budget.observe();
                        if (line.length() > 1_000_000) {
                            fail("Capture record character limit exceeded");
                        }
                        try {
                            validateRawRecord(line, limits.rawFields());
                            var row = JsonParser.parseString(line).getAsJsonObject();
                            if (!row.has("originalStyledLinesComplete")
                                    || !row.get("originalStyledLinesComplete").getAsBoolean()
                                    || !row.has("tooltipClassification")
                                    || (row.has("recordKind")
                                            && row.get("recordKind")
                                                    .getAsString()
                                                    .equals("STATE_SUMMARY"))
                                    || (row.has("event")
                                            && !row.get("event")
                                                    .getAsString()
                                                    .equals("TOOLTIP_DISPLAY"))) {
                                skipped++;
                                continue;
                            }
                            validateNativeCapture(row, budget);
                            JsonObject projected = projectCapture(row);
                            String state = DictionaryCoverageReplay.hash(projected.toString());
                            // Duplicated exports may overlap: an observed event is counted once.
                            String identity = captureIdentity(row, state);
                            if (!observations.add(identity)) continue;
                            var existing = bySource.get(state);
                            if (existing == null) {
                                projected.addProperty("id", "capture-" + state);
                                projected.addProperty("weight", 1);
                                bySource.put(state, projected);
                                if (bySource.size() > limits.cases())
                                    fail("Distinct capture case limit exceeded");
                            } else
                                existing.addProperty(
                                        "weight",
                                        Math.addExact(existing.get("weight").getAsInt(), 1));
                        } catch (com.google.gson.JsonParseException
                                | IllegalStateException
                                | NullPointerException invalid) {
                            skipped++;
                        } catch (IOException malformed) {
                            skipped++;
                        }
                    }
                }
            }
        }
        if (bySource.isEmpty())
            throw new IllegalArgumentException("No complete native display captures");
        var cases = bySource.values().stream().map(DictionaryCoverageReplay::input).toList();
        return new InputSet(
                "UNREVIEWED_CAPTURE",
                DictionaryCoverageReplay.hash(GSON.toJson(bySource)),
                cases,
                skipped,
                duplicates);
    }

    /** Bound the raw JSON structure before constructing a complete Gson object graph. */
    private static void validateRawRecord(String text, int maxFields) throws IOException {
        int depth = 0, fields = 0;
        long values = 0;
        try (var reader = new JsonReader(new StringReader(text))) {
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                if (++values > (long) maxFields * 4) fail("Capture raw value limit exceeded");
                switch (reader.peek()) {
                    case BEGIN_ARRAY -> {
                        reader.beginArray();
                        if (++depth > MAX_RAW_DEPTH) fail("Capture JSON depth limit exceeded");
                    }
                    case BEGIN_OBJECT -> {
                        reader.beginObject();
                        if (++depth > MAX_RAW_DEPTH) fail("Capture JSON depth limit exceeded");
                    }
                    case END_ARRAY -> {
                        reader.endArray();
                        depth--;
                    }
                    case END_OBJECT -> {
                        reader.endObject();
                        depth--;
                    }
                    case NAME -> {
                        if (++fields > maxFields) fail("Capture raw field limit exceeded");
                        if (reader.nextName().length() > 256)
                            fail("Capture field-name limit exceeded");
                    }
                    case STRING, NUMBER -> reader.nextString();
                    case BOOLEAN -> reader.nextBoolean();
                    case NULL -> reader.nextNull();
                    default -> throw new IllegalStateException("Invalid capture JSON");
                }
            }
        }
    }

    /** Validate and account for native data before copying it into retained projected cases. */
    private static void validateNativeCapture(JsonObject row, CaptureBudget budget) {
        var evidence = row.getAsJsonObject("tooltipClassification").getAsJsonObject("evidence");
        for (String key :
                List.of(
                        "screenClass",
                        "containerTitle",
                        "location",
                        "skyblockItemId",
                        "minecraftItemId")) {
            if (!evidence.has(key)) continue;
            var value = evidence.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalStateException("Invalid capture evidence");
            if (value.getAsString().length() > MAX_METADATA_CHARACTERS)
                fail("Capture evidence character limit exceeded");
        }
        for (String key : List.of("session", "observedAt", "capturedAt", "time", "eventSequence")) {
            if (!row.has(key) || row.get(key).isJsonNull()) continue;
            if (!row.get(key).isJsonPrimitive())
                throw new IllegalStateException("Invalid capture identity");
            if (row.get(key).getAsString().length() > MAX_METADATA_CHARACTERS)
                fail("Capture identity character limit exceeded");
        }
        var lines = row.getAsJsonArray("originalStyledLines");
        if (lines.isEmpty()) throw new IllegalStateException("Empty native capture");
        if (lines.size() > 256) fail("Capture native line limit exceeded");
        for (var line : lines) {
            budget.nativeLine();
            var runs = line.getAsJsonArray();
            if (runs.size() > 256) fail("Capture native line-run limit exceeded");
            int characters = 0;
            for (var value : runs) {
                var run = value.getAsJsonObject();
                var text = run.get("text");
                if (text == null
                        || !text.isJsonPrimitive()
                        || !text.getAsJsonPrimitive().isString())
                    throw new IllegalStateException("Invalid native text");
                characters += text.getAsString().length();
                if (characters > 16_384) fail("Capture native line character limit exceeded");
                budget.nativeRun(text.getAsString().length());
            }
        }
    }

    private static String captureIdentity(JsonObject row, String state) {
        // Legacy records can lack observation timestamps. A complete identical raw record
        // is then one observation; this is stored-layout frequency, never render-frame time.
        var identity = new ArrayList<String>();
        identity.add(state);
        for (String key : List.of("session", "observedAt", "capturedAt", "time", "eventSequence"))
            if (row.has(key) && !row.get(key).isJsonNull()) identity.add(key + "=" + row.get(key));
        if (identity.size() == 1) identity.add(DictionaryCoverageReplay.hash(row.toString()));
        return DictionaryCoverageReplay.hash(String.join("|", identity));
    }

    private static JsonObject projectCapture(JsonObject row) {
        var out = new JsonObject();
        out.addProperty("id", "pending");
        var rawEvidence = row.getAsJsonObject("tooltipClassification").getAsJsonObject("evidence");
        var evidence = new JsonObject();
        for (String key :
                List.of(
                        "screenClass",
                        "containerTitle",
                        "location",
                        "skyblockItemId",
                        "minecraftItemId"))
            evidence.addProperty(
                    key, rawEvidence.has(key) ? rawEvidence.get(key).getAsString() : "");
        evidence.addProperty(
                "slotMatches",
                rawEvidence.has("slotMatches") && rawEvidence.get("slotMatches").getAsBoolean());
        evidence.addProperty(
                "slotIndex",
                rawEvidence.has("slotIndex") ? rawEvidence.get("slotIndex").getAsInt() : -1);
        evidence.addProperty(
                "containerSize",
                rawEvidence.has("containerSize") ? rawEvidence.get("containerSize").getAsInt() : 0);
        out.add("evidence", evidence);
        out.addProperty("screen", "CAPTURE_" + evidence.get("location").getAsString());
        var lines = new com.google.gson.JsonArray();
        for (var value : row.getAsJsonArray("originalStyledLines")) {
            var line = new JsonObject();
            line.addProperty("intent", "UNREVIEWED");
            line.addProperty("reason", "CAPTURE_INTENT_NOT_REVIEWED");
            var safeRuns = new com.google.gson.JsonArray();
            for (var r : value.getAsJsonArray()) {
                var raw = r.getAsJsonObject();
                var safe = new JsonObject();
                for (String key :
                        List.of(
                                "text",
                                "color",
                                "bold",
                                "italic",
                                "underlined",
                                "strikethrough",
                                "obfuscated")) if (raw.has(key)) safe.add(key, raw.get(key));
                safeRuns.add(safe);
            }
            line.add("runs", safeRuns);
            lines.add(line);
        }
        out.add("lines", lines);
        return out;
    }

    static Report replay(InputSet inputs, Path scratchPath, BundledDictionary dictionary)
            throws IOException {
        var config = new Config();
        config.candidateDictionary = true;
        config.dictionaryAutoDisplay = true;
        config.dictionaryPlainFallback = true;
        config.apiTranslation = false;
        var calls = new AtomicInteger();
        var cases = new ArrayList<DictionaryCoverageReplay.CaseResult>();
        var problems = new ArrayList<String>();
        // A fresh isolated path prevents a local user's API cache from influencing a replay.
        // Translator may save an empty cache on close; only this new scratch directory is cleaned.
        Path parent = scratchPath.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path scratch = Files.createTempDirectory(parent, "coverage-scratch-");
        Path cachePath = scratch.resolve("unused-api-cache.json");
        try (var translator =
                new Translator(
                        config,
                        cachePath,
                        NOPLogger.NOP_LOGGER,
                        text -> {
                            calls.incrementAndGet();
                            throw new AssertionError("Coverage cannot call an API");
                        },
                        dictionary)) {
            for (var input : inputs.cases()) {
                var result = DictionaryCoverageReplay.replay(input, translator, config);
                cases.add(result);
                for (String problem : result.problems()) problems.add(result.id() + ":" + problem);
            }
        } finally {
            Files.deleteIfExists(cachePath);
            Files.deleteIfExists(scratch);
        }
        if (calls.get() != 0) problems.add("API_CALL_ATTEMPTED");
        return new Report(
                DictionaryCoverageReplay.SCHEMA_VERSION,
                inputs.inputKind(),
                "Offline local tooltip callback; not viewport wrapping, other mods or whole-game coverage",
                inputs.fingerprint(),
                dictionary.stats().buildId(),
                calls.get(),
                inputs.skippedRecords(),
                inputs.duplicateLogs(),
                List.copyOf(cases),
                List.copyOf(problems));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException(
                    "Usage: DictionaryCoverageRunner fixture.json output.json OR --zip output.json log.zip [log.zip ...]");
        InputSet inputs;
        Path output;
        if (args[0].equals("--zip")) {
            if (args.length < 3) throw new IllegalArgumentException("Missing capture ZIP");
            output = Path.of(args[1]).toAbsolutePath();
            inputs = captureZips(java.util.Arrays.stream(args).skip(2).map(Path::of).toList());
        } else {
            byte[] fixture;
            try (var stream = Files.newInputStream(Path.of(args[0]))) {
                fixture = stream.readNBytes((int) MAX_LOG_BYTES + 1);
            }
            inputs = fixture(fixture);
            output = Path.of(args[1]).toAbsolutePath();
        }
        Files.createDirectories(output.getParent());
        var dictionary = BundledDictionary.loadBundled();
        if (!dictionary.stats().loaded())
            throw new IllegalStateException(dictionary.stats().error());
        var report =
                replay(inputs, output.resolveSibling("coverage-unused-cache.json"), dictionary);
        Files.writeString(output, GSON.toJson(report) + "\n", StandardCharsets.UTF_8);
        System.out.println(
                "Dictionary coverage: "
                        + report.cases().size()
                        + " distinct inputs, "
                        + report.problems().size()
                        + " invariant failures, "
                        + report.apiCalls()
                        + " API calls");
        if (!report.problems().isEmpty())
            throw new IllegalStateException("Coverage replay invariants failed");
    }
}
