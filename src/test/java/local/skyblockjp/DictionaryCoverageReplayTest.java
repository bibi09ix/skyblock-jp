package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class DictionaryCoverageReplayTest {
    @TempDir Path dir;

    private static byte[] resource(String name) throws Exception {
        try (var stream = DictionaryCoverageReplayTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(stream, name);
            return stream.readAllBytes();
        }
    }

    private static final class Corpus {
        static final BundledDictionary DICTIONARY = BundledDictionary.loadBundled();
    }

    @Test
    void fixedReviewedCohortProtectsEveryExistingTranslationAndNativeStyle() throws Exception {
        var inputs =
                DictionaryCoverageRunner.fixture(resource("dictionary-coverage-fixtures.json"));
        var report =
                DictionaryCoverageRunner.replay(
                        inputs, dir.resolve("unused.json"), Corpus.DICTIONARY);
        assertEquals(0, report.apiCalls());
        assertTrue(report.problems().isEmpty(), report.problems().toString());
        var baseline =
                JsonParser.parseString(
                                new String(
                                        resource("dictionary-coverage-baseline.json"),
                                        StandardCharsets.UTF_8))
                        .getAsJsonObject();
        assertEquals(
                inputs.fingerprint(),
                baseline.get("inputSha256").getAsString(),
                "Changing the fixed cohort/intent requires explicit baseline review");
        var current =
                report.cases().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        DictionaryCoverageReplay.CaseResult::id, c -> c));
        assertEquals(baseline.getAsJsonArray("cases").size(), current.size());
        int alreadyTranslated = 0, intendedTargets = 0, preserved = 0;
        for (var value : baseline.getAsJsonArray("cases")) {
            var before = value.getAsJsonObject();
            var after = current.get(before.get("id").getAsString());
            assertNotNull(after);
            assertEquals(before.get("weight").getAsInt(), after.weight());
            var lines = before.getAsJsonArray("lines");
            assertEquals(lines.size(), after.sourceLineCount());
            for (int index = 0; index < lines.size(); index++) {
                var expected = lines.get(index).getAsJsonObject();
                var actual = after.lines().get(index);
                String ref = after.id() + ":" + index;
                assertEquals(index, actual.line(), ref);
                assertEquals(
                        expected.get("sourceSha256").getAsString(), actual.sourceSha256(), ref);
                assertEquals(expected.get("intent").getAsString(), actual.intent(), ref);
                if (actual.intent().equals("TRANSLATE")) intendedTargets++;
                if (expected.get("outcome").getAsString().equals("TRANSLATED")) {
                    alreadyTranslated++;
                    assertEquals(
                            "TRANSLATED",
                            actual.outcome(),
                            ref + ": " + actual.stage() + "/" + actual.reason());
                    assertTrue(actual.japanese(), ref);
                    assertEquals(
                            expected.get("renderedSha256").getAsString(),
                            actual.renderedSha256(),
                            ref);
                    assertEquals(
                            expected.get("renderedStyleSha256").getAsString(),
                            actual.renderedStyleSha256(),
                            ref);
                } else if (actual.intent().equals("PRESERVE")
                        || !actual.outcome().equals("TRANSLATED")) {
                    if (actual.intent().equals("PRESERVE")) preserved++;
                    assertEquals(
                            expected.get("renderedSha256").getAsString(),
                            actual.renderedSha256(),
                            ref);
                    assertEquals(
                            expected.get("renderedStyleSha256").getAsString(),
                            actual.renderedStyleSha256(),
                            ref);
                }
            }
        }
        assertTrue(
                alreadyTranslated > 50,
                "The cohort must test actual translations, not just dictionary loads");
        assertTrue(
                intendedTargets > alreadyTranslated,
                "Known failures remain visible in the denominator");
        assertTrue(preserved > 20, "Names/stats and native formatting also need protection");
        assertFalse(
                Files.exists(dir.resolve("unused.json")), "Coverage must not write API cache data");
    }

    @Test
    void anUnclassifiedTargetIsStillPresentAndNeverSilentlyExempted() throws Exception {
        var inputs =
                DictionaryCoverageRunner.fixture(resource("dictionary-coverage-fixtures.json"));
        var input =
                inputs.cases().stream()
                        .filter(c -> c.id().equals("unknown-context"))
                        .findFirst()
                        .orElseThrow();
        var config = localConfig();
        try (var service =
                new Translator(
                        config,
                        dir.resolve("none.json"),
                        NOPLogger.NOP_LOGGER,
                        s -> {
                            fail("No API");
                            return null;
                        },
                        Corpus.DICTIONARY)) {
            var result = DictionaryCoverageReplay.replay(input, service, config);
            assertEquals(input.lines().size(), result.lines().size());
            var target = result.lines().get(0);
            assertEquals("TRANSLATE", target.intent());
            assertEquals("UNCLASSIFIED", target.outcome());
            assertEquals("CLASSIFICATION", target.stage());
        }
    }

    @Test
    void acceptedEnglishPreservationIsNotCountedAsJapaneseTranslation() {
        String body = "Grants +10 Mining Speed.";
        Component colored =
                Component.literal("Grants ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(
                                Component.literal("+10")
                                        .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                        .append(
                                Component.literal(" Mining Speed.")
                                        .withStyle(ChatFormatting.AQUA, ChatFormatting.ITALIC));
        var raw =
                new Gson()
                        .toJson(
                                Map.of(
                                        "schema_version",
                                        1,
                                        "tier",
                                        "candidate",
                                        "build_id",
                                        "coverage-keep",
                                        "entries",
                                        List.of(
                                                Map.of(
                                                        "channel",
                                                        "item",
                                                        "speaker",
                                                        "",
                                                        "source",
                                                        body,
                                                        "target",
                                                        body,
                                                        "keep_original",
                                                        true,
                                                        "source_ids",
                                                        List.of("fixture/keep")))));
        var dictionary =
                BundledDictionary.load(
                        new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        var input =
                new DictionaryCoverageReplay.Input(
                        "keep-error",
                        "Inventory",
                        1,
                        new TooltipContext.Evidence(
                                "fixture",
                                "",
                                TooltipContext.Location.PLAYER,
                                true,
                                "",
                                "minecraft:paper",
                                1,
                                0),
                        List.of(
                                new DictionaryCoverageReplay.SourceLine(
                                        "PRESERVE", "Name", Component.literal("Fixture Item")),
                                new DictionaryCoverageReplay.SourceLine(
                                        "TRANSLATE", "Explanation", colored)));
        var config = localConfig();
        try (var service =
                new Translator(
                        config,
                        dir.resolve("none.json"),
                        NOPLogger.NOP_LOGGER,
                        s -> {
                            fail("No API");
                            return null;
                        },
                        dictionary)) {
            var result = DictionaryCoverageReplay.replay(input, service, config);
            var line = result.lines().get(1);
            assertEquals("TRANSLATE", line.intent());
            assertEquals("KEEP_ORIGINAL", line.outcome());
            assertFalse(line.japanese());
            assertEquals(line.sourceSha256(), line.renderedSha256());
            assertEquals(DictionaryCoverageReplay.nativeHash(colored), line.renderedStyleSha256());
            assertTrue(result.problems().isEmpty());
        }
    }

    @Test
    void fixtureIdentityAndApiPolicyCannotBeRelaxedDuringReplay() throws Exception {
        var bytes = resource("dictionary-coverage-fixtures.json");
        var document =
                JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        var cases = document.getAsJsonArray("cases");
        cases.add(cases.get(0).deepCopy());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DictionaryCoverageRunner.fixture(
                                document.toString().getBytes(StandardCharsets.UTF_8)));
        var input = DictionaryCoverageRunner.fixture(bytes).cases().getFirst();
        var config = localConfig();
        config.apiTranslation = true;
        try (var service =
                new Translator(
                        config,
                        dir.resolve("none.json"),
                        NOPLogger.NOP_LOGGER,
                        s -> {
                            fail("No API");
                            return null;
                        },
                        Corpus.DICTIONARY)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DictionaryCoverageReplay.replay(input, service, config));
        }
    }

    @Test
    void captureExportsAreDeduplicatedAndProjectedToHashOnlyUnreviewedOutput() throws Exception {
        var fixture =
                JsonParser.parseString(
                                new String(
                                        resource("dictionary-coverage-fixtures.json"),
                                        StandardCharsets.UTF_8))
                        .getAsJsonObject()
                        .getAsJsonArray("cases")
                        .get(5)
                        .getAsJsonObject();
        var capture = new JsonObject();
        capture.addProperty("event", "TOOLTIP_DISPLAY");
        capture.addProperty("session", "fake-private-session");
        capture.addProperty("observedAt", "2026-01-01T00:00:00Z");
        capture.addProperty("originalStyledLinesComplete", true);
        var lines = new com.google.gson.JsonArray();
        for (var value : fixture.getAsJsonArray("lines"))
            lines.add(value.getAsJsonObject().get("runs"));
        var privateTitle = new com.google.gson.JsonArray();
        var privateRun = new JsonObject();
        privateRun.addProperty("text", "FakePrivatePlayer");
        privateRun.addProperty("color", 0xFFFFFF);
        privateRun.addProperty("ignoredHover", "fake-secret-hover-payload");
        privateTitle.add(privateRun);
        lines.set(0, privateTitle);
        capture.add("originalStyledLines", lines);
        var classification = new JsonObject();
        classification.add("evidence", fixture.get("evidence"));
        capture.add("tooltipClassification", classification);
        capture.addProperty("ignoredPlayerName", "FakePrivatePlayer");
        var summary = capture.deepCopy();
        summary.addProperty("recordKind", "STATE_SUMMARY");
        summary.addProperty("observationCount", 500);
        String log = capture + "\n" + summary + "\n";
        Path first = zip("first.zip", log), second = zip("second.zip", log);
        var inputs = DictionaryCoverageRunner.captureZips(List.of(first, second));
        assertEquals(1, inputs.cases().size());
        assertEquals(1, inputs.cases().getFirst().weight());
        assertEquals(1, inputs.duplicateLogs());
        assertEquals(1, inputs.skippedRecords());
        assertTrue(
                inputs.cases().getFirst().lines().stream()
                        .allMatch(l -> l.intent().equals("UNREVIEWED")));
        var report =
                DictionaryCoverageRunner.replay(
                        inputs, dir.resolve("none.json"), Corpus.DICTIONARY);
        String output = new Gson().toJson(report);
        for (String secret :
                List.of(
                        "FakePrivatePlayer",
                        "fake-private-session",
                        "fake-secret-hover-payload",
                        "Bottle of Jyrre",
                        "2026-01-01")) assertFalse(output.contains(secret), secret);
        assertTrue(report.problems().isEmpty());
    }

    private Path zip(String name, String log) throws Exception {
        Path path = dir.resolve(name);
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("logs/translation-display.jsonl"));
            zip.write(log.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    @Test
    void captureBudgetsRejectWholeRequestsIncludingDuplicateBytesAndRepeatedObservations()
            throws Exception {
        String record = capture("first").toString() + "\n";
        int bytes = record.getBytes(StandardCharsets.UTF_8).length;
        Path first = zip("budget-first.zip", record), second = zip("budget-second.zip", record);
        assertLimit(
                "ZIP count",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(first, second), reduced("zip", 1)));
        assertLimit(
                "log byte",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(first), reduced("log", bytes - 1)));
        // Identical exports still consume decompression/read budget before deduplication.
        assertLimit(
                "Total capture byte",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(first, second), reduced("total", bytes * 2L - 1)));
        Path repeated = zip("budget-repeat.zip", record + record);
        assertLimit(
                "observation",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(repeated), reduced("observations", 1)));
        var changed = capture("second");
        changed.getAsJsonArray("originalStyledLines")
                .get(0)
                .getAsJsonArray()
                .get(0)
                .getAsJsonObject()
                .addProperty("text", "Other Fixture Item");
        Path distinct = zip("budget-distinct.zip", record + changed + "\n");
        assertLimit(
                "Distinct capture case",
                () -> DictionaryCoverageRunner.captureZips(List.of(distinct), reduced("cases", 1)));
        assertLimit(
                "native character",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(first), reduced("characters", 1)));
        assertLimit(
                "native run",
                () -> DictionaryCoverageRunner.captureZips(List.of(first), reduced("runs", 1)));
        assertLimit(
                "Total native line",
                () -> DictionaryCoverageRunner.captureZips(List.of(first), reduced("lines", 1)));
        assertLimit(
                "raw field",
                () -> DictionaryCoverageRunner.captureZips(List.of(first), reduced("fields", 1)));
    }

    @Test
    void nativeAndRawStructureLimitsAreCheckedBeforeProjectionInsteadOfSkippingTheRecord()
            throws Exception {
        var oversizedText = capture("text");
        oversizedText
                .getAsJsonArray("originalStyledLines")
                .get(0)
                .getAsJsonArray()
                .get(0)
                .getAsJsonObject()
                .addProperty("text", "x".repeat(16_385));
        assertLimit(
                "native line character",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(
                                        zip(
                                                "large-text.zip",
                                                capture("valid") + "\n" + oversizedText + "\n"))));

        var manyLines = capture("lines");
        var lines = manyLines.getAsJsonArray("originalStyledLines");
        while (lines.size() <= 256) lines.add(lines.get(0).deepCopy());
        assertLimit(
                "native line limit",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(zip("many-lines.zip", manyLines + "\n"))));

        var manyRuns = capture("runs");
        var runs = manyRuns.getAsJsonArray("originalStyledLines").get(0).getAsJsonArray();
        while (runs.size() <= 256) runs.add(runs.get(0).deepCopy());
        assertLimit(
                "native line-run",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(zip("many-runs.zip", manyRuns + "\n"))));

        var metadata = capture("metadata");
        metadata.getAsJsonObject("tooltipClassification")
                .getAsJsonObject("evidence")
                .addProperty("containerTitle", "x".repeat(4097));
        assertLimit(
                "evidence character",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(zip("metadata.zip", metadata + "\n"))));

        var raw = capture("raw");
        var ignored = new com.google.gson.JsonObject();
        for (int i = 0; i < 50; i++) ignored.addProperty("ignored-" + i, "value");
        raw.add("ignoredPayload", ignored);
        assertLimit(
                "raw",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(zip("raw-fields.zip", raw + "\n")), reduced("fields", 40)));

        var nested = capture("depth");
        var outer = new com.google.gson.JsonArray();
        var cursor = outer;
        for (int i = 0; i < 40; i++) {
            var inner = new com.google.gson.JsonArray();
            cursor.add(inner);
            cursor = inner;
        }
        nested.add("ignoredPayload", outer);
        assertLimit(
                "JSON depth",
                () ->
                        DictionaryCoverageRunner.captureZips(
                                List.of(zip("raw-depth.zip", nested + "\n"))));
    }

    private static void assertLimit(String kind, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(DictionaryCoverageRunner.CaptureLimitException.class, action);
        assertTrue(failure.getMessage().contains(kind), failure.getMessage());
    }

    private static DictionaryCoverageRunner.CaptureLimits reduced(String kind, long value) {
        var defaults = DictionaryCoverageRunner.CaptureLimits.defaults();
        return new DictionaryCoverageRunner.CaptureLimits(
                kind.equals("zip") ? (int) value : defaults.zipCount(),
                kind.equals("log") ? value : defaults.logBytes(),
                kind.equals("total") ? value : defaults.totalBytes(),
                kind.equals("observations") ? (int) value : defaults.observations(),
                kind.equals("cases") ? (int) value : defaults.cases(),
                kind.equals("characters") ? value : defaults.nativeCharacters(),
                kind.equals("runs") ? value : defaults.nativeRuns(),
                kind.equals("lines") ? value : defaults.nativeLines(),
                kind.equals("fields") ? (int) value : defaults.rawFields());
    }

    private static JsonObject capture(String observedAt) {
        var row = new JsonObject();
        row.addProperty("event", "TOOLTIP_DISPLAY");
        row.addProperty("originalStyledLinesComplete", true);
        row.addProperty("observedAt", observedAt);
        var evidence =
                new Gson()
                        .toJsonTree(
                                Map.of(
                                        "screenClass",
                                        "fixture",
                                        "containerTitle",
                                        "",
                                        "location",
                                        "PLAYER",
                                        "slotMatches",
                                        true,
                                        "skyblockItemId",
                                        "",
                                        "minecraftItemId",
                                        "minecraft:paper",
                                        "slotIndex",
                                        1,
                                        "containerSize",
                                        0));
        var classification = new JsonObject();
        classification.add("evidence", evidence);
        row.add("tooltipClassification", classification);
        row.add(
                "originalStyledLines",
                new Gson()
                        .toJsonTree(
                                List.of(
                                        List.of(Map.of("text", "Fixture Item", "color", 0xFFFFFF)),
                                        List.of(
                                                Map.of(
                                                        "text",
                                                        "A new explanation.",
                                                        "color",
                                                        0xAAAAAA)))));
        return row;
    }

    private static Config localConfig() {
        var config = new Config();
        config.candidateDictionary = true;
        config.dictionaryAutoDisplay = true;
        config.apiTranslation = false;
        return config;
    }
}
