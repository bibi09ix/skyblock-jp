package local.skyblockjp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic records only. Assert actual archive contents and source fidelity. */
class SharedLogExporterPrivacyTest {
    @TempDir Path temp;
    private static final Gson JSON = new Gson();
    private static final String SOURCE = "Gain  +7 \uE008 Defense.";
    private static final String TRANSLATION = "\uE008 Defenseが+7増加する。";
    private static final String NOW = "2026-10-30T05:00:00Z";
    private static final String PRIVATE = "PRIVATE_CHAT_CANARY_DO_NOT_EXPORT";

    private static SharedLogExporter.Metadata metadata() {
        return new SharedLogExporter.Metadata("0.7.11", "26.2", "0.19.5", "25.0.4", "fixture-dictionary-build",
            false, true, true, true, false);
    }
    private static JsonObject object(Map<String, ?> values) { return JSON.toJsonTree(values).getAsJsonObject(); }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static JsonObject run(String text, int rgb) {
        return object(Map.of("text", text, "color", rgb, "bold", true, "italic", false, "underlined", false,
            "strikethrough", false, "obfuscated", false));
    }
    private static JsonObject visualRun(String text, Style style) {
        return object(Map.of("text", text, "color", style.getColor().getValue(), "bold", style.isBold(), "italic", style.isItalic(),
            "underlined", style.isUnderlined(), "strikethrough", style.isStrikethrough(), "obfuscated", style.isObfuscated()));
    }
    private static JsonObject display() {
        var classification = object(Map.of("kind", "ITEM", "reason", "SKYBLOCK_ID",
            "evidence", Map.of("screenClass", "fixture.Inventory", "containerTitle", "Inventory", "location", "PLAYER",
                "slotMatches", true, "slotIndex", 7, "containerSize", 36, "skyblockItemId", "FIXTURE_BELT", "minecraftItemId", "minecraft:leather")));
        var paragraph = object(Map.of("start", 1, "end", 2, "paragraphIndex", 0, "paragraphId", "fixture-tooltip:0",
            "source", SOURCE, "channel", "item", "role", "DESCRIPTION", "dictionaryAccepted", true,
            "dictionaryStage", "ACCEPTED", "displayedText", TRANSLATION));
        paragraph.add("dictionaryDetails", object(Map.of("dictionaryBuildId", "fixture-dictionary-build",
            "validationStage", "AUTHORED_ALIGNMENT", "sourceSha256", "a".repeat(64), "dictionaryContext", Map.of("slotIndex", 7))));
        var result = object(Map.of("schemaVersion", 2, "event", "TOOLTIP_DISPLAY", "version", "0.7.10", "session", "fixture-session",
            "tooltipId", "fixture-tooltip", "time", NOW, "observedAt", NOW, "channel", "item", "screenTitle", "Inventory"));
        result.add("originalLines", JSON.toJsonTree(List.of("Test Belt", SOURCE)));
        result.add("originalStyledLines", JSON.toJsonTree(List.of(List.of(run("Test Belt", 0xFFAA00)), List.of(run(SOURCE, 0x55FFFF)))));
        result.addProperty("originalStyledLinesComplete", true);
        result.addProperty("originalStyledGlyphCount", "Test Belt".length() + SOURCE.length());
        result.addProperty("normalizationVersion", "ascii_case_single_period_stat_glyphs_v1");
        result.add("paragraphDecisions", JSON.toJsonTree(List.of(paragraph)));
        result.add("tooltipClassification", classification);
        result.add("lineDecisions", JSON.toJsonTree(List.of(Map.of("index", 1, "lineNumber", 2, "source", SOURCE,
            "role", "DESCRIPTION", "action", "TRANSLATE", "reason", "PROSE_CANDIDATE", "paragraphIndex", 0))));
        result.add("displayLines", JSON.toJsonTree(List.of("Test Belt", TRANSLATION)));
        result.add("translatedParagraphs", JSON.toJsonTree(List.of(TRANSLATION)));
        return result;
    }
    private static JsonObject pipeline() {
        return object(Map.of("schemaVersion", 2, "event", "TOOLTIP_PIPELINE", "version", "0.7.10", "session", "fixture-session",
            "tooltipId", "fixture-tooltip", "stage", "OBSERVATION", "reason", "SOURCE_UNMATCHED",
            "sourceSnapshotKind", "UNMATCHED_DISPLAY", "observedDisplayLines", List.of("Test Belt", SOURCE),
            "details", Map.of("channel", "item", "screenClass", "fixture.Inventory", "slotIndex", 7)));
    }
    private static JsonObject npcDecision() throws Exception {
        var row = object(Map.of("schemaVersion", 1, "event", "NPC_DICTIONARY_DECISION", "version", "0.7.10",
            "session", "npc-fixture-session", "decisionId", "b".repeat(64), "channel", "npc", "speaker", "Dean",
            "source", "A precise greeting.", "reason", "not_found", "time", NOW));
        row.addProperty("sourceComplete", true); row.addProperty("sourceSha256", hash("A precise greeting."));
        row.addProperty("encodedSource", "A precise greeting."); row.addProperty("encodedSourceComplete", true);
        row.addProperty("previousDialogue", "A prior NPC sentence."); row.addProperty("previousDialogueComplete", true);
        row.addProperty("previousDialogueSha256", hash("A prior NPC sentence."));
        row.add("dictionaryContext", object(Map.of("previousDialogue", "A prior NPC sentence.")));
        row.addProperty("dictionaryBuildId", "fixture-dictionary-build");
        row.addProperty("styleFingerprintSha256", "f".repeat(64));
        row.add("sourceStyledRuns", JSON.toJsonTree(List.of(run("A precise greeting.", 0xFFFFFF))));
        row.addProperty("sourceStyledComplete", true); row.addProperty("sourceStyledOrigin", "NATIVE_JOINED_SOURCE");
        return row;
    }
    private static JsonObject npcSuccess() {
        return object(Map.of("mode", "bundled-dictionary", "channel", "npc", "speaker", "Dean", "context", "NPC=Dean",
            "source", "A successful greeting.", "translation", "挨拶の翻訳。", "buildId", "fixture-dictionary-build",
            "sourceIds", List.of("npc/dialogue/fixture"), "keepOriginal", false, "time", NOW));
    }
    private Path logs() throws Exception { return Files.createDirectories(temp.resolve("logs")); }
    private Path out() { return temp.resolve("exported"); }
    private static void write(Path folder, String file, JsonObject... records) throws Exception {
        var lines = new StringBuilder();
        for (var row : records) lines.append(JSON.toJson(row)).append('\n');
        Files.writeString(folder.resolve(file), lines, StandardCharsets.UTF_8);
    }
    private static Map<String, String> entries(Path zipPath) throws Exception {
        var result = new LinkedHashMap<String, String>();
        try (var archive = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            for (var item : archive.stream().toList()) {
                assertFalse(item.isDirectory()); assertFalse(item.getName().startsWith("/"));
                assertFalse(item.getName().contains(".."), item.getName());
                try (var input = archive.getInputStream(item)) {
                    result.put(item.getName(), new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }
    private static List<JsonObject> rows(Map<String, String> zip, String file) {
        var content = zip.get("logs/" + file);
        if (content == null) return List.of();
        return content.lines().filter(s -> !s.isBlank()).map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
    }
    private static String all(Map<String, String> zip) { return String.join("\n", zip.keySet()) + String.join("\n", zip.values()); }

    @Test void onlyFourAllowlistedLogsAndTheirRotationAreReadAndOriginalFilesRemainUnchanged() throws Exception {
        var logs = logs();
        write(logs, "translation-display.jsonl", display()); write(logs, "translation-display.jsonl.1", display());
        write(logs, "translation-pipeline.jsonl", pipeline()); write(logs, "npc-dictionary-decisions.jsonl", npcDecision());
        write(logs, "dictionary-translations.jsonl", npcSuccess());
        for (String excluded : List.of("config.json", "config.json.tmp", "cache.json", "chat-translations.jsonl", "hud-display.jsonl",
                "npc-translations.jsonl", "translation-failures.jsonl", "translation-repairs.jsonl", "latest.log", "accounts.json",
                "translation-display.jsonl.2", "translation-display.jsonl.backup", "other.jsonl")) {
            var row = display(); row.add("originalLines", JSON.toJsonTree(List.of(excluded, PRIVATE)));
            write(logs, excluded, row);
        }
        var nested = Files.createDirectories(logs.resolve("nested"));
        var hidden = display(); hidden.add("originalLines", JSON.toJsonTree(List.of(PRIVATE + "_NESTED_OR_PARENT")));
        write(nested, "translation-display.jsonl", hidden); write(temp, "translation-display.jsonl", hidden);
        var before = new LinkedHashMap<Path, byte[]>();
        try (var files = Files.walk(logs)) { for (var file : files.filter(Files::isRegularFile).toList()) before.put(file, Files.readAllBytes(file)); }
        var result = SharedLogExporter.export(logs, out(), metadata(), List.of());
        var zip = entries(result.zipPath());
        assertEquals(5, result.exportedRecords());
        assertEquals(Set.of("logs/translation-display.jsonl", "logs/translation-display.jsonl.1", "logs/translation-pipeline.jsonl",
            "logs/npc-dictionary-decisions.jsonl", "logs/dictionary-translations.jsonl", "manifest.json", "README.txt"), zip.keySet());
        assertFalse(all(zip).contains(PRIVATE));
        assertFalse(all(zip).contains(temp.toString()), "archive metadata must not publish the local absolute path");
        assertTrue(new ArrayList<>(zip.keySet()).indexOf("logs/translation-display.jsonl.1") < new ArrayList<>(zip.keySet()).indexOf("logs/translation-display.jsonl"));
        for (var value : before.entrySet()) assertArrayEquals(value.getValue(), Files.readAllBytes(value.getKey()), value.getKey().getFileName().toString());
    }

    @Test void safeReplayTextColorsLineIndicesContextAndCompletenessAreRetainedWithoutStyleInteractionPayloads() throws Exception {
        var logs = logs(); var row = display();
        var style = row.getAsJsonArray("originalStyledLines").get(1).getAsJsonArray().get(0).getAsJsonObject();
        style.addProperty("nativeStyle", "hover/insertion NATIVE_STYLE_PRIVATE_CANARY");
        style.add("clickEvent", object(Map.of("action", "run_command", "value", "/msg CLICK_PRIVATE_CANARY hello")));
        style.add("hoverEvent", object(Map.of("value", "HOVER_PRIVATE_CANARY")));
        style.addProperty("insertion", "INSERTION_PRIVATE_CANARY");
        row.addProperty("unknownPayload", "UNKNOWN_FIELD_PRIVATE_CANARY");
        row.getAsJsonArray("paragraphDecisions").get(0).getAsJsonObject().getAsJsonObject("dictionaryDetails")
            .add("unknownNested", object(Map.of("private", "UNKNOWN_NESTED_PRIVATE_CANARY")));
        write(logs, "translation-display.jsonl", row); write(logs, "npc-dictionary-decisions.jsonl", npcDecision());
        var result = SharedLogExporter.export(logs, out(), metadata(), List.of()); var zip = entries(result.zipPath());
        assertEquals(2, result.exportedRecords());
        var exported = rows(zip, "translation-display.jsonl").getFirst();
        assertEquals(row.get("originalLines"), exported.get("originalLines"));
        assertEquals(row.get("originalStyledLinesComplete"), exported.get("originalStyledLinesComplete"));
        assertEquals(row.get("originalStyledGlyphCount"), exported.get("originalStyledGlyphCount"));
        var exportedRun = exported.getAsJsonArray("originalStyledLines").get(1).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(run(SOURCE, 0x55FFFF), exportedRun);
        var paragraph = exported.getAsJsonArray("paragraphDecisions").get(0).getAsJsonObject();
        assertEquals(1, paragraph.get("start").getAsInt()); assertEquals(2, paragraph.get("end").getAsInt());
        assertEquals(SOURCE, paragraph.get("source").getAsString()); assertEquals(TRANSLATION, paragraph.get("displayedText").getAsString());
        assertEquals(2, exported.getAsJsonArray("lineDecisions").get(0).getAsJsonObject().get("lineNumber").getAsInt());
        assertEquals("PLAYER", exported.getAsJsonObject("tooltipClassification").getAsJsonObject("evidence").get("location").getAsString());
        var npc = rows(zip, "npc-dictionary-decisions.jsonl").getFirst();
        assertEquals(hash("A precise greeting."), npc.get("sourceSha256").getAsString());
        assertEquals("f".repeat(64), npc.get("styleFingerprintSha256").getAsString());
        assertEquals("A prior NPC sentence.", npc.getAsJsonObject("dictionaryContext").get("previousDialogue").getAsString());
        assertTrue(npc.get("sourceStyledComplete").getAsBoolean());
        assertEquals(1, exported.get("exportOriginalLine").getAsInt());
        assertTrue(result.omittedFields() > 0);
        var manifest = JsonParser.parseString(zip.get("manifest.json")).getAsJsonObject();
        assertEquals(result.omittedFields(), manifest.get("omittedFields").getAsInt());
        assertTrue(manifest.getAsJsonArray("files").asList().stream().map(value -> value.getAsJsonObject())
            .filter(value -> value.has("omittedFields")).anyMatch(value -> value.get("omittedFields").getAsInt() > 0));
        assertTrue(manifest.get("replayPolicy").getAsString().contains("original style fingerprints may cover omitted metadata"));
        for (String canary : List.of("NATIVE_STYLE_PRIVATE_CANARY", "CLICK_PRIVATE_CANARY", "HOVER_PRIVATE_CANARY", "INSERTION_PRIVATE_CANARY",
                "UNKNOWN_FIELD_PRIVATE_CANARY", "UNKNOWN_NESTED_PRIVATE_CANARY")) assertFalse(all(zip).contains(canary), canary);
    }

    @Test void mixedPlayerAndChatRowsInsideOtherwiseAllowedFilesAreExcluded() throws Exception {
        var logs = logs();
        var direct = display(); direct.addProperty("channel", "chat"); direct.add("originalLines", JSON.toJsonTree(List.of(PRIVATE)));
        var nested = display(); nested.getAsJsonArray("paragraphDecisions").get(0).getAsJsonObject().addProperty("channel", "player");
        nested.add("originalLines", JSON.toJsonTree(List.of(PRIVATE + "_NESTED")));
        write(logs, "translation-display.jsonl", display(), direct, nested);
        var pipeline = pipeline(); pipeline.getAsJsonObject("details").addProperty("channel", "player_chat");
        pipeline.add("observedDisplayLines", JSON.toJsonTree(List.of(PRIVATE + "_PIPELINE"))); write(logs, "translation-pipeline.jsonl", pipeline);
        var success = npcSuccess(); success.addProperty("context", "CHAT"); success.addProperty("source", PRIVATE + "_SUCCESS");
        var chat = npcSuccess(); chat.addProperty("channel", "chat"); chat.addProperty("source", PRIVATE + "_CHANNEL");
        write(logs, "dictionary-translations.jsonl", npcSuccess(), success, chat);
        var npc = npcDecision(); npc.addProperty("channel", "chat"); npc.addProperty("source", PRIVATE + "_NPC");
        write(logs, "npc-dictionary-decisions.jsonl", npc);
        var result = SharedLogExporter.export(logs, out(), metadata(), List.of()); var zip = entries(result.zipPath());
        assertEquals(2, result.exportedRecords()); assertTrue(result.excludedRecords() >= 6);
        assertFalse(all(zip).contains(PRIVATE));
    }

    @Test void dictionaryNpcSuccessWithoutVersionOrSessionAndObservedOutputDistinctionRemainUsable() throws Exception {
        var logs = logs(); var success = npcSuccess();
        assertFalse(success.has("version")); assertFalse(success.has("session"));
        write(logs, "dictionary-translations.jsonl", success); write(logs, "translation-pipeline.jsonl", pipeline());
        var result = SharedLogExporter.export(logs, out(), metadata(), List.of()); var zip = entries(result.zipPath());
        assertEquals(2, result.exportedRecords());
        var npc = rows(zip, "dictionary-translations.jsonl").getFirst();
        assertEquals("A successful greeting.", npc.get("source").getAsString()); assertEquals("挨拶の翻訳。", npc.get("translation").getAsString());
        assertEquals("fixture-dictionary-build", npc.get("buildId").getAsString()); assertFalse(npc.has("version"));
        var observed = rows(zip, "translation-pipeline.jsonl").getFirst();
        assertEquals("UNMATCHED_DISPLAY", observed.get("sourceSnapshotKind").getAsString());
        assertTrue(observed.has("observedDisplayLines")); assertFalse(observed.has("originalLines"));
    }

    @Test void fixedSystemSuccessIsKeptWhilePlayerChatHeadersCannotHideInsideNpcRows() throws Exception {
        var logs = logs();
        var system = npcSuccess(); system.addProperty("channel", "system"); system.addProperty("speaker", "");
        system.addProperty("context", "scope=system;notification=fixture");
        system.addProperty("source", "You earned 5 SkyBlock XP."); system.addProperty("translation", "5 SkyBlock XPを獲得した。");
        var spoof = npcSuccess(); spoof.addProperty("source", "Guild > [MVP+] PrivateUser: " + PRIVATE);
        write(logs, "dictionary-translations.jsonl", npcSuccess(), system, spoof);
        var decision = npcDecision(); decision.addProperty("source", "[MVP+] PrivateUser: " + PRIVATE + "_RANK");
        write(logs, "npc-dictionary-decisions.jsonl", decision);
        var result = SharedLogExporter.export(logs, out(), metadata(), List.of()); var zip = entries(result.zipPath());
        assertEquals(2, result.exportedRecords()); assertFalse(all(zip).contains(PRIVATE));
        assertEquals(Set.of("npc", "system"), rows(zip, "dictionary-translations.jsonl").stream()
            .map(row -> row.get("channel").getAsString()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void realProductionNpcAndTooltipSuccessContextFormsAreAcceptedWithoutPublishingDuplicatedContextBodies() throws Exception {
        var logs = logs();
        var npc = npcSuccess(); npc.addProperty("speaker", "[NPC] Dean"); npc.addProperty("context", "NPC=[NPC] Dean");
        var system = npcSuccess(); system.addProperty("channel", "system"); system.addProperty("speaker", "");
        system.addProperty("context", "scope=system; notification");
        var item = npcSuccess(); item.addProperty("channel", "item"); item.addProperty("speaker", "");
        item.addProperty("context", "scope=item; tooltipId=fixture; paragraph=0; item=Test Belt; lines=2-2; original=" + SOURCE);
        var ui = item.deepCopy(); ui.addProperty("channel", "ui");
        ui.addProperty("context", "scope=ui; tooltipId=fixture; paragraph=0; item=Test Menu; lines=2-2; original=" + SOURCE);
        write(logs, "dictionary-translations.jsonl", npc, system, item, ui);
        var result = SharedLogExporter.export(logs, out(), metadata(), List.of()); var zip = entries(result.zipPath());
        assertEquals(4, result.exportedRecords());
        assertEquals(Set.of("npc", "system", "item", "ui"), rows(zip, "dictionary-translations.jsonl").stream()
            .map(row -> row.get("channel").getAsString()).collect(java.util.stream.Collectors.toSet()));
        assertFalse(all(zip).contains("tooltipId=fixture; paragraph=0; item="), "raw context duplicates user-supplied item/source text; structured fields suffice");
    }

    @Test void basicDisplayLogWhenDetailedDiagnosticsAreOffStillKeepsItsOriginalAndDisplayedText() throws Exception {
        var logs = logs();
        var basic = object(Map.of("schemaVersion", 2, "event", "TOOLTIP_DISPLAY", "version", "0.7.10", "session", "fixture-session",
            "tooltipId", "fixture-basic", "item", "Test Belt", "time", NOW, "originalLines", List.of("Test Belt", SOURCE),
            "displayLines", List.of("Test Belt", TRANSLATION), "displayText", "Test Belt\n" + TRANSLATION));
        assertFalse(basic.has("channel")); assertFalse(basic.has("originalStyledLines"));
        write(logs, "translation-display.jsonl", basic);
        var result = SharedLogExporter.export(logs, out(), metadata(), List.of()); var zip = entries(result.zipPath());
        assertEquals(1, result.exportedRecords());
        var actual = rows(zip, "translation-display.jsonl").getFirst();
        assertEquals(basic.get("originalLines"), actual.get("originalLines"));
        assertEquals(basic.get("displayText"), actual.get("displayText"));
        assertFalse(actual.has("originalStyledLines"));
    }

    @Test void configuredAndRecognizableCredentialsDropWholeRecordsInsteadOfMutatingSourceAndItsHash() throws Exception {
        var logs = logs(); String saved = "configured-secret-privacy-fixture-4815", draft = "draft-secret-privacy-fixture-2390";
        var credentials = List.of(saved, draft, "Bearer A1b2C3_d4.E5-F6Token987654321", "gsk_" + "A".repeat(45),
            "sk-proj-" + "B".repeat(42), "AIza" + "C".repeat(35), "prefixgsk_" + "D".repeat(45), "xAIza" + "E".repeat(35));
        var lines = new StringBuilder(JSON.toJson(display())).append('\n');
        for (String value : credentials) {
            var row = display(); row.add("originalLines", JSON.toJsonTree(List.of("DO_NOT_MUTATE", "private token " + value)));
            lines.append(JSON.toJson(row)).append('\n');
        }
        var escaped = display(); escaped.add("originalLines", JSON.toJsonTree(List.of("ESCAPED_PRIVATE_ROW", saved)));
        lines.append(JSON.toJson(escaped).replace(saved, "\\u0063" + saved.substring(1))).append('\n');
        var nested = display(); nested.getAsJsonArray("paragraphDecisions").get(0).getAsJsonObject().getAsJsonObject("dictionaryDetails")
            .addProperty("targetCandidate", draft);
        lines.append(JSON.toJson(nested)).append('\n');
        Files.writeString(logs.resolve("translation-display.jsonl"), lines, StandardCharsets.UTF_8);
        var result = SharedLogExporter.export(logs, out(), metadata(), List.of(saved, draft)); var zip = entries(result.zipPath());
        assertEquals(1, result.exportedRecords()); assertTrue(result.excludedRecords() >= credentials.size() + 2);
        assertEquals(display().get("originalLines"), rows(zip, "translation-display.jsonl").getFirst().get("originalLines"));
        for (String value : credentials) assertFalse(all(zip).contains(value));
        assertFalse(all(zip).contains("ESCAPED_PRIVATE_ROW")); assertFalse(all(zip).contains("DO_NOT_MUTATE"));
        assertFalse(all(zip).contains("REDACTED"), "retained source text must not be silently redacted while hashes claim original input");
    }

    @Test void malformedUnknownSchemaAndTruncatedRecordsCannotLeakRawInputAndDoNotStopLaterFiles() throws Exception {
        var logs = logs(); var unknown = display(); unknown.addProperty("schemaVersion", 999);
        unknown.add("originalLines", JSON.toJsonTree(List.of("UNKNOWN_SCHEMA_PRIVATE_CANARY")));
        var duplicate = display(); duplicate.add("originalLines", JSON.toJsonTree(List.of("DUPLICATE_KEY_PRIVATE_CANARY")));
        String duplicateBase = JSON.toJson(duplicate);
        String duplicateJson = duplicateBase.substring(0, duplicateBase.length() - 1) + ",\"channel\":\"chat\",\"channel\":\"item\"}";
        Files.writeString(logs.resolve("translation-display.jsonl"), JSON.toJson(display()) + "\n"
            + "not-json MALFORMED_PRIVATE_CANARY\n" + JSON.toJson(unknown) + "\n" + duplicateJson + "\n"
            + "{\"event\":\"TOOLTIP_DISPLAY\",\"schemaVersion\":2,\"originalLines\":[\"TRUNCATED_PRIVATE_CANARY", StandardCharsets.UTF_8);
        write(logs, "dictionary-translations.jsonl", npcSuccess());
        var result = assertDoesNotThrow(() -> SharedLogExporter.export(logs, out(), metadata(), List.of()));
        var zip = entries(result.zipPath()); assertEquals(2, result.exportedRecords()); assertTrue(result.hasWarnings());
        assertTrue(result.malformedRecords() > 0 || result.truncatedFiles() > 0 || result.excludedRecords() > 0);
        for (String value : List.of("UNKNOWN_SCHEMA_PRIVATE_CANARY", "MALFORMED_PRIVATE_CANARY", "TRUNCATED_PRIVATE_CANARY", "DUPLICATE_KEY_PRIVATE_CANARY"))
            assertFalse(all(zip).contains(value), value);
    }

    @Test void oversizedDeepAndBroadJsonInputIsBoundedWithoutExposingOrRewritingSourceFiles() throws Exception {
        var logs = logs();
        var bigRow = display(); bigRow.add("originalLines", JSON.toJsonTree(List.of("OVERSIZED_PRIVATE_CANARY" + "X".repeat(1_100_000))));
        String big = JSON.toJson(bigRow) + "\n";
        var deepRow = display(); deepRow.add("originalLines", JSON.toJsonTree(List.of("DEEPLY_NESTED_PRIVATE_CANARY")));
        String deepBase = JSON.toJson(deepRow);
        String deep = deepBase.substring(0, deepBase.length() - 1) + ",\"unknown\":"
            + "[".repeat(96) + "\"nested\"" + "]".repeat(96) + "}\n";
        var broadRow = display(); broadRow.add("originalLines", JSON.toJsonTree(List.of("NODE_LIMIT_PRIVATE_CANARY")));
        String broadBase = JSON.toJson(broadRow);
        String broad = broadBase.substring(0, broadBase.length() - 1) + ",\"unknown\":["
            + "0,".repeat(120_000) + "0]}\n";
        Files.writeString(logs.resolve("translation-display.jsonl"), big + deep + broad + JSON.toJson(display()) + "\n", StandardCharsets.UTF_8);
        var before = Files.readAllBytes(logs.resolve("translation-display.jsonl"));
        var result = assertDoesNotThrow(() -> SharedLogExporter.export(logs, out(), metadata(), List.of()));
        var zip = entries(result.zipPath()); assertEquals(1, result.exportedRecords()); assertTrue(result.hasWarnings());
        assertArrayEquals(before, Files.readAllBytes(logs.resolve("translation-display.jsonl")));
        for (String value : List.of("OVERSIZED_PRIVATE_CANARY", "DEEPLY_NESTED_PRIVATE_CANARY", "NODE_LIMIT_PRIVATE_CANARY"))
            assertFalse(all(zip).contains(value), value);
    }

    @Test void emptyLogsAndEmptyDictionaryBuildCreateAUsefulLocalBundleWithoutAnyConfigurationCopy() throws Exception {
        var logs = logs(); var metadata = new SharedLogExporter.Metadata("0.7.11", "26.2", "0.19.5", "25", "", false, false, false, false, false);
        var result = SharedLogExporter.export(logs, out(), metadata, List.of()); var zip = entries(result.zipPath());
        assertEquals(0, result.exportedRecords()); assertTrue(zip.containsKey("manifest.json")); assertTrue(zip.containsKey("README.txt"));
        assertTrue(zip.get("manifest.json").contains("0.7.11")); assertFalse(zip.keySet().stream().anyMatch(s -> s.endsWith("config.json")));
    }

    @Test void everyRotationAndCurrentFileIsBoundedAtFourMiBAndAggregateReadIsBoundedAtThirtyTwoMiB() throws Exception {
        var logs = logs(); String padding = " ".repeat(4 * 1024 * 1024 + 262_144) + "\n";
        var expectedOrder = new ArrayList<String>();
        for (String base : List.of("translation-display.jsonl", "translation-pipeline.jsonl", "npc-dictionary-decisions.jsonl", "dictionary-translations.jsonl")) {
            for (String suffix : List.of(".1", "")) {
                String filename = base + suffix; expectedOrder.add("logs/" + filename);
                JsonObject first = switch (base) {
                    case "translation-display.jsonl" -> display();
                    case "translation-pipeline.jsonl" -> pipeline();
                    case "npc-dictionary-decisions.jsonl" -> npcDecision();
                    default -> npcSuccess();
                };
                var late = first.deepCopy(); String marker = "LATE_PRIVATE_BOUND_CANARY_" + filename;
                if (base.equals("translation-display.jsonl")) late.add("originalLines", JSON.toJsonTree(List.of(marker)));
                else if (base.equals("translation-pipeline.jsonl")) late.add("observedDisplayLines", JSON.toJsonTree(List.of(marker)));
                else { late.addProperty("source", marker); late.addProperty("sourceSha256", hash(marker)); }
                Files.writeString(logs.resolve(filename), JSON.toJson(first) + "\n" + padding + JSON.toJson(late) + "\n", StandardCharsets.UTF_8);
            }
        }
        var result = SharedLogExporter.export(logs, out(), metadata(), List.of()); var zip = entries(result.zipPath());
        assertEquals(8, result.exportedRecords()); assertEquals(8, result.truncatedFiles());
        assertEquals(expectedOrder, zip.keySet().stream().filter(name -> name.startsWith("logs/")).toList());
        assertFalse(all(zip).contains("LATE_PRIVATE_BOUND_CANARY"));
        var manifest = JsonParser.parseString(zip.get("manifest.json")).getAsJsonObject();
        assertEquals(32L * 1024 * 1024, manifest.get("bytesScanned").getAsLong());
        for (var report : manifest.getAsJsonArray("files")) {
            var file = report.getAsJsonObject();
            assertEquals(4L * 1024 * 1024, file.get("bytesScanned").getAsLong());
            assertTrue(file.get("snapshotBytes").getAsLong() > file.get("bytesScanned").getAsLong());
            assertTrue(file.get("truncated").getAsBoolean());
        }
    }

    @Test void sanitizedNativeSourceCanRebuildStyledParagraphWithEveryTextGlyphRgbAndVisualFlagIntact() throws Exception {
        var logs = logs();
        Style gray = Style.EMPTY.withColor(0xAAAAAA).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand("/fixture PRIVATE_INTERACTION_CANARY"))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("PRIVATE_INTERACTION_CANARY")))
            .withInsertion("PRIVATE_INTERACTION_CANARY");
        Style green = Style.EMPTY.withColor(0x55FF55).withItalic(true);
        Style aqua = Style.EMPTY.withColor(0x55FFFF).withUnderlined(true).withStrikethrough(true).withObfuscated(true);
        var source = Component.empty().append(Component.literal("Gain  ").setStyle(gray))
            .append(Component.literal("+7").setStyle(green)).append(Component.literal(" \uE008 Defense.").setStyle(aqua));
        var nativeParagraph = new StyledParagraph(List.of(source)); assertEquals(SOURCE, nativeParagraph.sourceText());
        var rawRuns = new JsonArray();
        for (var glyph : nativeParagraph.sourceGlyphs()) {
            var raw = visualRun(glyph.text(), glyph.style());
            raw.addProperty("nativeStyle", glyph.style().toString());
            if (glyph.style().getClickEvent() != null) raw.addProperty("clickEvent", glyph.style().getClickEvent().toString());
            if (glyph.style().getHoverEvent() != null) raw.addProperty("hoverEvent", glyph.style().getHoverEvent().toString());
            if (glyph.style().getInsertion() != null) raw.addProperty("insertion", glyph.style().getInsertion());
            rawRuns.add(raw);
        }
        var row = npcDecision(); row.addProperty("source", nativeParagraph.sourceText());
        row.addProperty("encodedSource", nativeParagraph.encoded()); row.addProperty("sourceSha256", hash(nativeParagraph.sourceText()));
        row.addProperty("styleFingerprintSha256", hash(nativeParagraph.dictionaryFingerprint()));
        row.addProperty("sourceStyledGlyphCount", nativeParagraph.sourceGlyphs().size()); row.add("sourceStyledRuns", rawRuns);
        write(logs, "npc-dictionary-decisions.jsonl", row);
        var result = SharedLogExporter.export(logs, out(), metadata(), List.of()); var zip = entries(result.zipPath());
        assertEquals(1, result.exportedRecords()); assertFalse(all(zip).contains("PRIVATE_INTERACTION_CANARY"));
        var exported = rows(zip, "npc-dictionary-decisions.jsonl").getFirst(); var rebuiltSource = Component.empty();
        for (var value : exported.getAsJsonArray("sourceStyledRuns")) {
            var raw = value.getAsJsonObject();
            Style style = Style.EMPTY.withColor(raw.get("color").getAsInt()).withBold(raw.get("bold").getAsBoolean())
                .withItalic(raw.get("italic").getAsBoolean()).withUnderlined(raw.get("underlined").getAsBoolean())
                .withStrikethrough(raw.get("strikethrough").getAsBoolean()).withObfuscated(raw.get("obfuscated").getAsBoolean());
            rebuiltSource.append(Component.literal(raw.get("text").getAsString()).setStyle(style));
        }
        var rebuilt = new StyledParagraph(List.of(rebuiltSource));
        assertEquals(nativeParagraph.sourceText(), rebuilt.sourceText());
        assertEquals(nativeParagraph.sourceGlyphs().stream().map(g -> visualRun(g.text(), g.style())).toList(),
            rebuilt.sourceGlyphs().stream().map(g -> visualRun(g.text(), g.style())).toList());
        assertEquals(exported.get("sourceSha256").getAsString(), hash(rebuilt.sourceText()));
        assertEquals(hash(nativeParagraph.dictionaryFingerprint()), exported.get("styleFingerprintSha256").getAsString());
        assertNotEquals(nativeParagraph.dictionaryFingerprint(), rebuilt.dictionaryFingerprint(), "removed interaction payloads make the original style fingerprint opaque");
        for (var glyph : rebuilt.sourceGlyphs()) {
            assertNull(glyph.style().getClickEvent()); assertNull(glyph.style().getHoverEvent()); assertNull(glyph.style().getInsertion());
        }
    }

    @Test void metadataCannotSmuggleCredentialsOrPrivatePathsIntoTheManifestOrFilename() throws Exception {
        var logs = logs(); String secret = "metadata-key-privacy-fixture-0491";
        var unsafe = new SharedLogExporter.Metadata(secret, "C:\\Users\\PRIVATE_METADATA_PATH\\Minecraft", "0.19.5", "25", "", false, true, true, true, false);
        var result = SharedLogExporter.export(logs, out(), unsafe, List.of(secret)); var zip = entries(result.zipPath());
        assertFalse(all(zip).contains(secret)); assertFalse(all(zip).contains("PRIVATE_METADATA_PATH"));
        assertFalse(result.zipPath().getFileName().toString().contains(secret));
        assertFalse(result.zipPath().getFileName().toString().contains("PRIVATE_METADATA_PATH"));
    }

    @Test void repeatedExportsUseDistinctFilesAndCannotRecursivelyIncludeAnEarlierZip() throws Exception {
        var logs = logs(); write(logs, "translation-display.jsonl", display());
        var first = SharedLogExporter.export(logs, logs, metadata(), List.of());
        var firstBytes = Files.readAllBytes(first.zipPath());
        var second = SharedLogExporter.export(logs, logs, metadata(), List.of());
        assertNotEquals(first.zipPath(), second.zipPath()); assertArrayEquals(firstBytes, Files.readAllBytes(first.zipPath()));
        assertEquals(1, second.exportedRecords());
        assertFalse(entries(second.zipPath()).keySet().stream().anyMatch(s -> s.endsWith(".zip")));
    }

    @Test void outputDirectoryFailureCannotExposePrivatePathsOrSecretsThroughAnyExceptionCause() throws Exception {
        var logs = logs(); String secret = "path-key-privacy-fixture-3174";
        Path blocker = temp.resolve("PRIVATE_ERROR_PATH_" + secret);
        Files.writeString(blocker, "existing-file");
        var error = assertThrows(IOException.class, () -> SharedLogExporter.export(logs, blocker, metadata(), List.of(secret)));
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String description = String.valueOf(cause.getMessage());
            assertFalse(description.contains(secret)); assertFalse(description.contains("PRIVATE_ERROR_PATH"));
            assertFalse(description.contains(temp.toString()));
        }
        assertEquals("existing-file", Files.readString(blocker));
    }
}
