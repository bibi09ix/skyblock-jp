package local.skyblockjp;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.*;
import com.google.gson.*;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryDiagnosticsProvenanceTest {
    @TempDir Path dir;
    @Test void dictionaryDecisionRetainsCandidateHashesAndActualContextEvenForRejectedColor() {
        var row=Map.of("channel","item","speaker","","source","Gain 4 Health.","target","Healthが4増加する。","keep_original",false,"source_ids",List.of("fixture"));
        var dictionary=BundledDictionary.load(new ByteArrayInputStream(new Gson().toJson(Map.of("schema_version",1,"build_id","test","tier","candidate","entries",List.of(row))).getBytes(StandardCharsets.UTF_8)));
        var config=new Config();config.candidateDictionary=true;config.apiTranslation=false;
        try(var t=new Translator(config,dir.resolve("cache.json"),LoggerFactory.getLogger("diagnostic"),text->{throw new AssertionError("API must not run");},dictionary)) {
            var p=new StyledParagraph(List.of(Component.literal("Gain 4 Health.")));
            var decision=t.inspectDictionary(p,"item","",false,Map.of("containerTitle","Test menu"));
            assertTrue(decision.accepted());
            assertEquals("Healthが4増加する。",decision.details().get("targetCandidate"));
            assertEquals("test",decision.details().get("dictionaryBuildId"));
            assertTrue(decision.details().get("sourceSha256").toString().matches("[0-9a-f]{64}"));
            assertTrue(decision.details().get("targetSha256").toString().matches("[0-9a-f]{64}"));
            assertEquals(Map.of("containerTitle","Test menu"),decision.details().get("dictionaryContext"));
        }
    }
    @Test void truncatedStyledSnapshotsAreExplicitAndCompleteOnesRemainMarkedComplete() throws Exception {
        Path log=dir.resolve("translation-display.jsonl");
        try(var audit=new TooltipAudit(log,LoggerFactory.getLogger("audit"),"")) {
            audit.recordDetailed("complete",List.of(Component.literal("Item")),List.of(Component.literal("Item")),List.of(),260,"","","item","Test",List.of(),List.of(),"ORIGINAL",true);
            var many=new ArrayList<Component>();for(int i=0;i<257;i++)many.add(Component.literal("Line "+i));
            audit.recordDetailed("truncated",many,many,List.of(),260,"","","item","Test",List.of(),List.of(),"ORIGINAL",true);
        }
        var rows=Files.readAllLines(log).stream().map(s->JsonParser.parseString(s).getAsJsonObject()).toList();
        assertTrue(rows.get(0).get("originalStyledLinesComplete").getAsBoolean());
        assertFalse(rows.get(1).get("originalStyledLinesComplete").getAsBoolean());
        assertEquals(256,rows.get(1).getAsJsonArray("originalStyledLines").size());
    }
}
