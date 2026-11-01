package local.skyblockjp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class TooltipPipelineAuditRegressionTest {
    @TempDir Path dir;
    private TooltipAudit audit(Path path) { return new TooltipAudit(path, LoggerFactory.getLogger("pipeline-audit-test"), "gsk_fixture_secret"); }
    private static List<JsonObject> rows(Path path) throws Exception {
        return Files.readAllLines(path).stream().map(l -> JsonParser.parseString(l).getAsJsonObject()).toList();
    }
    private static TooltipContext.Decision context() {
        return new TooltipContext.Decision(TooltipContext.Kind.ITEM, "SKYBLOCK_ITEM_ID", Map.of("skyblockItemId", "FIXTURE"));
    }

    @Test void lineRejectionAndColorRestoreRejectionAreSeparateTraceStages() throws Exception {
        Path path = dir.resolve("display.jsonl");
        List<Component> source = List.of(Component.literal("Fixture"), Component.literal("Damage: +100"), Component.empty(), Component.literal("An unfamiliar effect."));
        var analysis = LoreParser.analyze(source.stream().map(Component::getString).toList(), context());
        var blocks = analysis.paragraphs().stream().map(LoreParser.Paragraph::block).toList();
        assertEquals(1, blocks.size());
        var decision = new Translator.DictionaryDecision(null, "STYLE_ALIGNMENT_MISSING", "exact", "item", List.of("fixture"),
            Map.of("stage", "STYLE_RESTORE", "originalStyledFragment", "unfamiliar", "candidateCount", 0));
        try (var audit = audit(path)) {
            audit.recordDetailed("fixture", source, source, List.of("An unfamiliar effect."), 260, "GROQ", "model", "item", "Storage",
                blocks, List.of(decision), "ORIGINAL", true, context(), analysis,
                List.of(new Translator.CacheInspection("EMPTY", "")), false, true);
        }
        var row = rows(path).getFirst();
        assertEquals(TooltipAudit.tooltipId("fixture"), row.get("tooltipId").getAsString());
        assertEquals("SKYBLOCK_ITEM_ID", row.getAsJsonObject("tooltipClassification").get("reason").getAsString());
        var stat = row.getAsJsonArray("lineDecisions").get(1).getAsJsonObject();
        assertEquals("KEEP", stat.get("action").getAsString());
        assertEquals("NUMERIC_STAT_ROW", stat.get("reason").getAsString());
        assertEquals(2, stat.get("lineNumber").getAsInt());
        var paragraph = row.getAsJsonArray("paragraphDecisions").get(0).getAsJsonObject();
        assertEquals("STYLE_RESTORE", paragraph.get("stopStage").getAsString());
        assertEquals("unfamiliar", paragraph.getAsJsonObject("dictionaryDetails").get("originalStyledFragment").getAsString());
        assertEquals("ORIGINAL", paragraph.get("supplier").getAsString());
    }

    @Test void apiFailureReturningOriginalMustNotBeReportedAsSavedApiTranslation() throws Exception {
        Path path = dir.resolve("failed.jsonl");
        List<Component> source = List.of(Component.literal("Fixture"), Component.literal("A newly added explanation."));
        var analysis = LoreParser.analyze(source.stream().map(Component::getString).toList(), context());
        var decision = new Translator.DictionaryDecision(null, "not_found", "none", "item", List.of(), Map.of("stage", "DICTIONARY_LOOKUP"));
        try (var audit = audit(path)) {
            audit.recordDetailed("failed", source, source, List.of("A newly added explanation."), 260, "GROQ", "model", "item", "Storage",
                analysis.paragraphs().stream().map(LoreParser.Paragraph::block).toList(), List.of(decision), "FULL", false,
                context(), analysis, List.of(new Translator.CacheInspection("API_FAILED_ORIGINAL", "network unavailable")), true, true);
        }
        var row = rows(path).getFirst();
        assertEquals("ORIGINAL", row.get("provider").getAsString());
        var paragraph = row.getAsJsonArray("paragraphDecisions").get(0).getAsJsonObject();
        assertEquals("DICTIONARY_LOOKUP", paragraph.get("dictionaryStage").getAsString());
        assertEquals("API_REQUEST", paragraph.get("stopStage").getAsString());
        assertEquals("API_FAILED_ORIGINAL", paragraph.get("apiState").getAsString());
        assertEquals("API_FAILED_ORIGINAL", paragraph.get("displayReason").getAsString());
    }

    @Test void earlyGatesDeduplicateButDistinctManualKeyRequestsRemainObservableAndRedacted() throws Exception {
        Path path = dir.resolve("display.jsonl");
        List<Component> original = List.of(Component.literal("Fixture"));
        try (var audit = audit(path)) {
            for (int i = 0; i < 3; i++) audit.recordPipeline("same", original, "ELIGIBILITY", "SHIFT_ORIGINAL", Map.of("screenTitle", "Storage"));
            for (int i = 0; i < 2; i++) audit.recordPipeline("same", original, "MANUAL_INPUT", "TEXT_INPUT_FOCUSED", Map.of("debugSecret", "gsk_fixture_secret"));
        }
        var pipeline = dir.resolve("translation-pipeline.jsonl");
        var records = rows(pipeline);
        assertEquals(3, records.size());
        assertEquals("ELIGIBILITY", records.getFirst().get("stage").getAsString());
        assertNotEquals(records.get(1).get("eventSequence"), records.get(2).get("eventSequence"));
        assertTrue(records.stream().allMatch(r -> r.get("tooltipId").getAsString().equals(TooltipAudit.tooltipId("same"))));
        assertFalse(Files.readString(pipeline).contains("gsk_fixture_secret"));
        assertFalse(Files.exists(path), "early-gate diagnostics must stay separate from actual display records");
    }
    @Test void unmatchedDisplayIsNotMisrepresentedAsAnOriginalSourceSnapshot() throws Exception {
        Path path=dir.resolve("display.jsonl");
        try(var audit=audit(path)) {
            audit.recordPipeline("copied",List.of(Component.literal("日本語化済み"),Component.literal("JP: 辞書を適用")),
                "OBSERVATION","NO_ITEM_CALLBACK_MATCH",Map.of());
        }
        var row=rows(dir.resolve("translation-pipeline.jsonl")).getFirst();
        assertFalse(row.has("originalLines"));
        assertEquals("UNMATCHED_DISPLAY",row.get("sourceSnapshotKind").getAsString());
        assertEquals("日本語化済み",row.getAsJsonArray("observedDisplayLines").get(0).getAsString());
    }
}
