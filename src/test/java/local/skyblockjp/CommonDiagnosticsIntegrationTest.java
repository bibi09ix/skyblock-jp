package local.skyblockjp;

import com.google.gson.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class CommonDiagnosticsIntegrationTest {
    @TempDir Path dir;
    private static final Gson JSON=new Gson();
    private static List<JsonObject> rows(Path file)throws Exception {
        if(!Files.exists(file))return List.of();
        return Files.readAllLines(file).stream().filter(s->!s.isBlank()).map(s->JsonParser.parseString(s).getAsJsonObject()).toList();
    }
    private static void flush(Translator t)throws Exception {
        var field=Translator.class.getDeclaredField("worker");field.setAccessible(true);
        ((ThreadPoolExecutor)field.get(t)).submit(()->{}).get(5,TimeUnit.SECONDS);
        field=Translator.class.getDeclaredField("diagnosticWorker");field.setAccessible(true);
        ((ThreadPoolExecutor)field.get(t)).submit(()->{}).get(5,TimeUnit.SECONDS);
    }
    private static BundledDictionary emptyDictionary(){
        return BundledDictionary.load(new ByteArrayInputStream("{\"schema_version\":1,\"build_id\":\"trace-fixture\",\"tier\":\"candidate\",\"entries\":[]}".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void coordinateOnlyFramesCoalesceButSourceStyleAndReasonTransitionsRemain()throws Exception {
        Path display=dir.resolve("translation-display.jsonl");
        try(var audit=new TooltipAudit(display,LoggerFactory.getLogger("audit"),"")) {
            for(int i=0;i<12;i++)audit.recordPipeline("same",List.of(Component.literal("Fixture")),"OBSERVATION","SOURCE_LIST_IDENTITY",Map.of("displayX",i,"displayY",i+20));
            audit.recordPipeline("same",List.of(Component.literal("Fixture").withStyle(ChatFormatting.RED)),"OBSERVATION","SOURCE_LIST_IDENTITY",Map.of());
            audit.recordPipeline("same",List.of(Component.literal("Fixture").withStyle(ChatFormatting.RED)),"OBSERVATION","UNIQUE_STYLED_CONTENT",Map.of());
            audit.recordPipeline("same",List.of(Component.literal("Fixture").withStyle(ChatFormatting.RED)),"OBSERVATION","UNIQUE_STYLED_CONTENT",Map.of("dictionaryContext",Map.of("containerTitle","Other menu")));
        }
        var saved=rows(dir.resolve("translation-pipeline.jsonl"));assertEquals(5,saved.size());
        assertEquals(1,saved.stream().filter(x->x.get("recordKind").getAsString().equals("STATE_SUMMARY")).count());
        assertEquals(12,saved.get(1).get("observationCount").getAsLong());
        for(var row:saved) {
            assertEquals(3,row.get("schemaVersion").getAsInt());assertEquals("MATCHED_ORIGINAL",row.get("sourceSnapshotKind").getAsString());
            assertTrue(row.has("originalLines"));assertFalse(row.has("observedDisplayLines"));
            assertNotNull(new SharedLogSanitizer(List.of()).sanitize("translation-pipeline.jsonl",row));
        }
    }
    @Test void provenanceContractAcceptsBothHistoricalAndNewSchemasAndRefusesContradictions()throws Exception {
        Path display=dir.resolve("translation-display.jsonl");
        try(var audit=new TooltipAudit(display,LoggerFactory.getLogger("audit"),"")) {
            audit.recordPipeline("unknown",List.of(Component.literal("Translated output")),"OBSERVATION","NO_ITEM_CALLBACK_MATCH",Map.of());
        }
        var row=rows(dir.resolve("translation-pipeline.jsonl")).getFirst();
        var sanitizer=new SharedLogSanitizer(List.of());assertNotNull(sanitizer.sanitize("translation-pipeline.jsonl",row));
        var old=row.deepCopy();old.addProperty("schemaVersion",2);assertNotNull(sanitizer.sanitize("translation-pipeline.jsonl",old));
        var wrong=row.deepCopy();wrong.addProperty("sourceSnapshotKind","MATCHED_ORIGINAL");assertNull(sanitizer.sanitize("translation-pipeline.jsonl",wrong));
        wrong=row.deepCopy();wrong.addProperty("recordKind","UNKNOWN");assertNull(sanitizer.sanitize("translation-pipeline.jsonl",wrong));
    }
    @Test void translatedNativeColorsAndFlagsSurviveTheActualZipExport()throws Exception {
        Path logs=dir.resolve("logs");Files.createDirectories(logs);
        var original=List.<Component>of(Component.literal("Fixture"),Component.literal("An explanation."));
        var translated=List.<Component>of(original.getFirst(),Component.literal("説明").withStyle(ChatFormatting.AQUA,ChatFormatting.BOLD));
        try(var audit=new TooltipAudit(logs.resolve("translation-display.jsonl"),LoggerFactory.getLogger("audit"),"gsk_fake_key_12345")) {
            audit.recordDetailed("fixture",original,translated,List.of("説明"),260,"GROQ","model","item","Fixture",List.of(),List.of(),"DICTIONARY",true);
        }
        var metadata=new SharedLogExporter.Metadata(ModVersion.CURRENT,"26.2","0.19.5","25","trace-fixture",false,true,true,true,true);
        var exported=SharedLogExporter.export(logs,dir.resolve("reports"),metadata,List.of("gsk_fake_key_12345"));
        assertEquals(1,exported.exportedRecords());assertEquals(0,exported.excludedRecords());
        try(var zip=new ZipFile(exported.zipPath().toFile())) {
            var row=JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("logs/translation-display.jsonl")).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(row.get("displayStyledLinesComplete").getAsBoolean());
            var run=row.getAsJsonArray("displayStyledLines").get(1).getAsJsonArray().get(0).getAsJsonObject();
            assertEquals("説明",run.get("text").getAsString());assertEquals(0x55ffff,run.get("color").getAsInt());assertTrue(run.get("bold").getAsBoolean());
            assertFalse(row.toString().contains("nativeStyle"));assertFalse(row.toString().contains("gsk_fake_key_12345"));
        }
    }
    @Test void nestedOffsetsAndFailureFragmentStayTypedAndDoNotCopyUnknownPayloads() {
        var input=new LinkedHashMap<String,Object>();input.put("candidateOffsets",List.of(List.of(1,4),List.of(8,12),"bad",List.of(-1,2)));
        input.put("originalStyledFragment","campfire");input.put("sourceOffset",9);input.put("offsetUnit","UTF16_JOINED_SOURCE");
        input.put("styleId",2);input.put("style",Map.of("colorRGB","#55FF55","bold",true,"clickEvent","private"));
        input.put("apiKey","gsk_do_not_copy_12345");
        var copied=DictionaryDiagnosticDetails.copy(input);
        assertEquals(List.of(List.of(1,4),List.of(8,12)),copied.get("candidateOffsets"));
        assertEquals("campfire",copied.get("originalStyledFragment"));assertEquals(9,copied.get("sourceOffset"));
        assertFalse(copied.containsKey("apiKey"));assertFalse(copied.toString().contains("clickEvent"));
    }
    @Test void npcPolicyAndApiCompletionJoinTheMissTraceWithoutSharingBodiesOrPlayerChat()throws Exception {
        var config=new Config();config.candidateDictionary=true;config.dictionaryDiagnostics=true;config.apiTranslation=false;
        var paragraph=new StyledParagraph(List.of(Component.literal("A new greeting.")));
        var apiCalls=new AtomicInteger();
        try(var t=new Translator(config,dir.resolve("cache.json"),LoggerFactory.getLogger("audit"),text->{apiCalls.incrementAndGet();return "こんにちは。";},emptyDictionary())) {
            assertNull(t.requestNpc(paragraph,"Bob","").get());flush(t);
            var first=rows(dir.resolve("npc-dictionary-decisions.jsonl"));
            var miss=first.stream().filter(x->x.get("event").getAsString().equals("NPC_DICTIONARY_DECISION")).findFirst().orElseThrow();
            var policy=first.stream().filter(x->x.get("event").getAsString().equals("NPC_TRANSLATION_STATUS")).findFirst().orElseThrow();
            assertEquals(miss.get("traceId"),policy.get("traceId"));assertEquals("API_DISABLED",policy.get("outcome").getAsString());
            config.apiTranslation=true;
            assertNotNull(t.requestNpc(paragraph,"Bob","").get(5,TimeUnit.SECONDS));flush(t);
            var statuses=rows(dir.resolve("npc-dictionary-decisions.jsonl")).stream().filter(x->x.get("event").getAsString().equals("NPC_TRANSLATION_STATUS")).toList();
            assertTrue(statuses.stream().anyMatch(x->x.get("outcome").getAsString().equals("TRANSLATED")));
            for(var row:statuses) {
                assertEquals(miss.get("traceId"),row.get("traceId"));assertFalse(row.has("source"));assertFalse(row.has("speaker"));
                assertNotNull(new SharedLogSanitizer(List.of()).sanitize("npc-dictionary-decisions.jsonl",row));
            }
            int before=statuses.size();config.apiTranslation=false;
            assertNull(t.requestChat(new StyledParagraph(List.of(Component.literal("PRIVATE_CHAT_CANARY"))),"").get());flush(t);
            assertEquals(before,rows(dir.resolve("npc-dictionary-decisions.jsonl")).stream().filter(x->x.get("event").getAsString().equals("NPC_TRANSLATION_STATUS")).count());
        }
        assertEquals(1,apiCalls.get());
    }
    @Test void statusSchemaRejectsBodiesCredentialsWrongOriginsAndInvalidTraceIds() {
        var row=JSON.toJsonTree(Map.of("schemaVersion",2,"event","NPC_TRANSLATION_STATUS","version",ModVersion.CURRENT,
            "channel","npc","traceId","a".repeat(16),"stage","RESULT","outcome","FAILED","attempt",1)).getAsJsonObject();
        var sanitizer=new SharedLogSanitizer(List.of("SECRET_CANARY"));
        assertNotNull(sanitizer.sanitize("npc-dictionary-decisions.jsonl",row));
        for(var changed:List.of(Map.entry("source","PRIVATE_CHAT_CANARY"),Map.entry("channel","chat"),Map.entry("traceId","wrong"),Map.entry("debug","SECRET_CANARY"))) {
            var bad=row.deepCopy();bad.addProperty(changed.getKey(),changed.getValue());assertNull(sanitizer.sanitize("npc-dictionary-decisions.jsonl",bad));
        }
    }
    @Test void saturatedDiagnosticQueueDoesNotUseTranslationCapacityOrBlockItsRequest()throws Exception {
        var config=new Config();config.candidateDictionary=true;config.dictionaryDiagnostics=true;config.apiTranslation=true;
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var t=new Translator(config,dir.resolve("cache.json"),LoggerFactory.getLogger("audit"),text->{calls.incrementAndGet();return "こんにちは。";},emptyDictionary())) {
            var d=Translator.class.getDeclaredField("diagnosticWorker");d.setAccessible(true);var diagnostics=(ThreadPoolExecutor)d.get(t);
            var w=Translator.class.getDeclaredField("worker");w.setAccessible(true);var translations=(ThreadPoolExecutor)w.get(t);
            diagnostics.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            while(diagnostics.getQueue().remainingCapacity()>0)diagnostics.execute(()->{});
            assertEquals(256,translations.getQueue().remainingCapacity());
            try {assertNotNull(t.requestNpc(new StyledParagraph(List.of(Component.literal("A queue-independent greeting."))),"Bob","").get(5,TimeUnit.SECONDS));}
            finally {release.countDown();}
            assertEquals(1,calls.get());
        } finally {release.countDown();}
    }
    @Test void aProvisionalCachedReplyIsNotReportedAsASuccessfulSavedTranslation()throws Exception {
        var config=new Config();config.candidateDictionary=true;config.dictionaryDiagnostics=true;config.apiTranslation=true;
        var calls=new AtomicInteger();var paragraph=new StyledParagraph(List.of(Component.literal("A novel response.")));
        try(var t=new Translator(config,dir.resolve("cache.json"),LoggerFactory.getLogger("audit"),text->{calls.incrementAndGet();return "An untranslated response.";},emptyDictionary())) {
            assertNotNull(t.requestNpc(paragraph,"Bob","").get(5,TimeUnit.SECONDS));flush(t);
            assertNotNull(t.requestNpc(paragraph,"Bob","").get(5,TimeUnit.SECONDS));flush(t);
            var statuses=rows(dir.resolve("npc-dictionary-decisions.jsonl")).stream().filter(x->x.get("event").getAsString().equals("NPC_TRANSLATION_STATUS")).toList();
            assertTrue(statuses.stream().anyMatch(x->x.get("outcome").getAsString().equals("CACHE_PROVISIONAL")));
            assertFalse(statuses.stream().anyMatch(x->x.get("outcome").getAsString().equals("CACHE_HIT")));
            assertTrue(statuses.stream().allMatch(x->x.has("observedAt")));assertEquals(1,calls.get());
        }
    }
    @Test void typedRejectionExplainsTheSourceAndTargetEffectLevelsWithoutUnknownFields() {
        var validation=EffectLevelTokens.inspect("Grants Haste IV.","Haste Vを付与する。");
        assertEquals("TYPED_EFFECT_LEVEL_MISMATCH",validation.reason());
        var details=DictionaryDiagnosticDetails.copy(validation.details());
        assertEquals(validation.details().get("sourceEffectLevels"),details.get("sourceEffectLevels"));
        assertEquals(validation.details().get("targetEffectLevels"),details.get("targetEffectLevels"));
        var row=JSON.toJsonTree(Map.of("schemaVersion",1,"event","NPC_DICTIONARY_DECISION","channel","npc","speaker","Bob",
            "source","Grants Haste IV.","stage","SEMANTIC_VALIDATION","reason",validation.reason(),"dictionaryDetails",details)).getAsJsonObject();
        var exported=new SharedLogSanitizer(List.of()).sanitize("npc-dictionary-decisions.jsonl",row);assertNotNull(exported);
        assertEquals(JSON.toJsonTree(details),exported.record().get("dictionaryDetails"));
    }
}
