package local.skyblockjp;

import com.google.gson.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScopedDescriptionFallbacksTest {
    static final String SOURCE="View and edit preset sets.",TARGET="プリセットを確認・編集する。",ID="observed/item/fixture";
    static final Gson GSON=new Gson();
    private static Map<String,Object> context() {
        return Map.of("tooltipTitle","Loadouts","tooltipKind","UI","paragraphRole","DESCRIPTION","location","CONTAINER","slotMatches",true);
    }
    private static JsonObject rule() {
        return GSON.toJsonTree(Map.ofEntries(Map.entry("id","observed-applicability/fixture"),Map.entry("from_channel","item"),Map.entry("to_channel","ui"),
            Map.entry("source",SOURCE),Map.entry("target",TARGET),Map.entry("source_sha256",ScopedDescriptionFallbacks.hash(SOURCE)),Map.entry("target_sha256",ScopedDescriptionFallbacks.hash(TARGET)),
            Map.entry("source_ids",List.of(ID)),Map.entry("semantic_role","DESCRIPTION"),Map.entry("requires",context()))).getAsJsonObject();
    }
    private static JsonObject document(JsonObject... rules) {
        return GSON.toJsonTree(Map.of("schema_version",1,"build_id","fixture","rules",List.of(rules))).getAsJsonObject();
    }
    private static ScopedDescriptionFallbacks load(JsonObject doc) {
        return ScopedDescriptionFallbacks.load(new ByteArrayInputStream(GSON.toJson(doc).getBytes(StandardCharsets.UTF_8)));
    }
    private static BundledDictionary dictionary(String build,boolean ui) {
        var rows=new ArrayList<Map<String,Object>>();
        rows.add(Map.of("channel","item","speaker","","source",SOURCE,"target",TARGET,"keep_original",false,"source_ids",List.of(ID)));
        if(ui)rows.add(Map.of("channel","ui","speaker","","source",SOURCE,"target","既存の訳。","keep_original",false,"source_ids",List.of("fixture/ui")));
        return BundledDictionary.load(new ByteArrayInputStream(GSON.toJson(Map.of("schema_version",1,"tier","candidate","build_id",build,"entries",rows)).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void onlyAnExactBodyWithAllReviewedContextAndTheSameDictionaryBuildIsLicensed() {
        var bridge=load(document(rule()));assertTrue(bridge.loadedStats().loaded());
        var dict=dictionary("fixture",false);var result=bridge.lookup(dict,"ui",SOURCE,context());
        assertEquals("scoped_observed",result.mode());assertEquals(TARGET,result.match().orElseThrow().text());
        assertEquals(List.of(ID),result.match().orElseThrow().sourceIds());
        assertTrue(bridge.lookup(dictionary("different-test-build",false),"ui",SOURCE,context()).match().isEmpty());
        assertTrue(bridge.lookup(dict,"npc",SOURCE,context()).match().isEmpty());
        assertTrue(bridge.lookup(dict,"ui",SOURCE.toLowerCase(Locale.ROOT),context()).match().isEmpty());
        for(String field:context().keySet()) {
            var missing=new HashMap<>(context());missing.remove(field);
            assertEquals("scoped_description_context_mismatch",bridge.lookup(dict,"ui",SOURCE,missing).reason(),field);
        }
        var wrong=new HashMap<>(context());wrong.put("tooltipTitle","Another menu control");
        assertTrue(bridge.lookup(dict,"ui",SOURCE,wrong).match().isEmpty());
    }
    @Test void existingRequestedScopeAndChangedOriginsCannotBeOverwritten() {
        var bridge=load(document(rule()));
        assertEquals("scoped_description_existing_resolution",bridge.lookup(dictionary("fixture",true),"ui",SOURCE,context()).reason());
        var changed=rule();changed.addProperty("target","異なる説明。");changed.addProperty("target_sha256",ScopedDescriptionFallbacks.hash("異なる説明。"));
        assertEquals("scoped_description_origin_changed",load(document(changed)).lookup(dictionary("fixture",false),"ui",SOURCE,context()).reason());
        changed=rule();changed.add("source_ids",GSON.toJsonTree(List.of("observed/item/unrelated")));
        assertEquals("scoped_description_origin_changed",load(document(changed)).lookup(dictionary("fixture",false),"ui",SOURCE,context()).reason());
    }
    @Test void staleDuplicateConflictingOrUnlicensedResourcesFailClosed() {
        for(String field:List.of("source_sha256","target_sha256")) {
            var bad=rule();bad.addProperty(field,"0".repeat(64));assertFalse(load(document(bad)).loadedStats().loaded());
        }
        var duplicate=rule();duplicate.addProperty("id","observed-applicability/duplicate");
        assertFalse(load(document(rule(),duplicate)).loadedStats().loaded());
        var conflict=rule();conflict.addProperty("id","observed-applicability/conflict");conflict.addProperty("target","別の文脈の訳。");conflict.addProperty("target_sha256",ScopedDescriptionFallbacks.hash("別の文脈の訳。"));
        conflict.getAsJsonObject("requires").addProperty("tooltipTitle","Other");
        assertFalse(load(document(rule(),conflict)).loadedStats().loaded());
        for(String channel:List.of("npc","system","common")) {
            var bad=rule();bad.addProperty("to_channel",channel);assertFalse(load(document(bad)).loadedStats().loaded());
        }
        var title=rule();title.addProperty("semantic_role","UI_TITLE");title.getAsJsonObject("requires").addProperty("paragraphRole","UI_TITLE");
        assertFalse(load(document(title)).loadedStats().loaded());
        String duplicateField=GSON.toJson(document(rule())).replace("\"schema_version\":1","\"schema_version\":1,\"schema_version\":1");
        assertFalse(ScopedDescriptionFallbacks.load(new ByteArrayInputStream(duplicateField.getBytes(StandardCharsets.UTF_8))).loadedStats().loaded());
    }
}
