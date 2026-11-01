package local.skyblockjp;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryPipelineRedesignTest {
    private static final Gson JSON = new Gson();
    private static InputStream stream(Object value) { return new ByteArrayInputStream(JSON.toJson(value).getBytes(StandardCharsets.UTF_8)); }
    private static Map<String,Object> entry(String channel,String source,String target) {
        return Map.of("channel",channel,"speaker","","source",source,"target",target,"keep_original",false,"source_ids",List.of("fixture"));
    }
    private static Map<String,Object> template(String source,String target,String... types) {
        var variables = new ArrayList<Map<String,String>>();
        for(int i=0;i<types.length;i++)variables.add(Map.of("name","v"+i,"type",types[i]));
        return Map.of("channel","item","speaker","","source",source,"target",target,"variables",variables,"source_ids",List.of("fixture"));
    }
    private static BundledDictionary dictionary(List<Map<String,Object>> entries,List<Map<String,Object>> templates) {
        var base=BundledDictionary.load(stream(Map.of("schema_version",1,"build_id","fixture","tier","candidate","entries",entries)));
        var dictionary=base.withMatching(stream(Map.of("schema_version",1,"build_id","fixture","normalization","ascii_case_single_period_stat_glyphs_v1","aliases",List.of(),"templates",templates)));
        assertTrue(dictionary.matchingStats().loaded(),dictionary.matchingStats().error());return dictionary;
    }
    @Test void leadingVariablesHaveStableLiteralAnchors() {
        var dictionary=dictionary(List.of(),List.of(template("+{v0} Intelligence for {v1} seconds.","{v1}秒間、Intelligenceが+{v0}増加する。","number","number")));
        var match=dictionary.lookup("item","+10 Intelligence for 4 seconds.","").orElseThrow();
        assertEquals("4秒間、Intelligenceが+10増加する。",match.text());
        assertTrue(dictionary.lookup("item","+10 Intelligence for four seconds.","").isEmpty());
        assertTrue(dictionary.lookup("system","+10 Intelligence for 4 seconds.","").isEmpty());
        assertEquals(".5秒間、Intelligenceが+10増加する。",dictionary.lookup("item","+10 Intelligence for .5 seconds.","").orElseThrow().text());
    }
    @Test void equalValuesRetainDistinctNamedSourceTargetBindings() {
        String source="Every 4 hits grants 4 seconds of power.";
        var dictionary=dictionary(List.of(),List.of(template("Every {v0} hits grants {v1} seconds of power.","{v1}秒間、{v0}回ごとの攻撃で力を得る。","number","number")));
        var match=dictionary.lookup("item",source,"").orElseThrow();
        assertEquals(2,match.bindings().size());
        var duration=match.bindings().getFirst();
        assertEquals("v1",duration.name());assertEquals(source.indexOf("4 seconds"),duration.sourceStart());
        assertEquals(0,duration.targetStart());
        for(var binding:match.bindings()) {
            assertEquals(binding.sourceText(),source.substring(binding.sourceStart(),binding.sourceEnd()));
            assertEquals(binding.targetText(),match.text().substring(binding.targetStart(),binding.targetEnd()));
        }
    }
    @Test void repeatedVariablesRetainOccurrenceOffsetsAndCannotBindDifferentValues() {
        var dictionary=dictionary(List.of(),List.of(template("Gain {v0} Health and {v0} Mana.","Healthを{v0}、Manaを{v0}得る。","number")));
        var match=dictionary.lookup("item","Gain 4 Health and 4 Mana.","").orElseThrow();
        assertEquals(2,match.bindings().size());
        assertNotEquals(match.bindings().get(0).sourceStart(),match.bindings().get(1).sourceStart());
        assertTrue(dictionary.lookup("item","Gain 4 Health and 5 Mana.","").isEmpty());
    }
    @Test void glyphAliasesAreStatScopedAndKeepTheLiveGlyph() {
        var dictionary=dictionary(List.of(entry("item","Gain +10☘ Farming Fortune.","☘ Farming Fortuneが+10増加する。")),List.of());
        var match=dictionary.lookup("item","Gain +10\ue051 Farming Fortune.","").orElseThrow();
        assertEquals("\ue051 Farming Fortuneが+10増加する。",match.text());
        assertEquals("glyph",match.bindings().getFirst().type());
        assertTrue(dictionary.lookup("item","Gain +10\ue053 Farming Fortune.","").isEmpty());
        assertTrue(dictionary.lookup("item","Gain +10\ue051 Mining Fortune.","").isEmpty());
        assertTrue(dictionary.lookup("npc","Gain +10\ue051 Farming Fortune.","Bob").isEmpty());
    }
    @Test void glyphEquivalentRecordsAgreeButDifferentMeaningsRemainAmbiguous() {
        var old=entry("item","Gain +10☘ Farming Fortune.","☘ Farming Fortuneが+10増加する。");
        var modern=entry("item","Gain +10\ue051 Farming Fortune.","\ue051 Farming Fortuneが+10増加する。");
        assertTrue(dictionary(List.of(old,modern),List.of()).lookup("item","gain +10\ue051 farming fortune","").isPresent());
        var conflict=entry("item","Gain +10\ue051 Farming Fortune.","\ue051 Farming Fortuneを+10得る。");
        assertEquals("ambiguous",dictionary(List.of(old,conflict),List.of()).inspect("item","gain +10\ue051 farming fortune","").reason());
    }
    @Test void templateGlyphVariantsKeepValuesAndBindings() {
        var dictionary=dictionary(List.of(),List.of(template("Grants +{v0} Mining Speed for {v1} seconds.","{v1}秒間、 Mining Speedが+{v0}増加する。","number","number")));
        var match=dictionary.lookup("item","Grants +150⸕ Mining Speed for 10 seconds.","").orElseThrow();
        assertEquals("10秒間、⸕ Mining Speedが+150増加する。",match.text());
        assertEquals(3,match.bindings().size());
    }
    @Test void everyBundledTemplateIsReachableWithFreshTypedValues() throws Exception {
        var dictionary=BundledDictionary.loadBundled();
        try(var input=getClass().getResourceAsStream(DictionaryMatching.RESOURCE);var reader=new InputStreamReader(input,StandardCharsets.UTF_8)) {
            var templates=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("templates");
            assertTrue(templates.size()>=306);
            for(var value:templates) {
                var template=value.getAsJsonObject();String live=template.get("source").getAsString();int i=0;
                for(var variableValue:template.getAsJsonArray("variables")) {
                    var variable=variableValue.getAsJsonObject();
                    String replacement=switch(variable.get("type").getAsString()) {case "roman"->"IV";case "pet_name"->"Skeleton";default->(123+i++)+".45";};
                    live=live.replace("{"+variable.get("name").getAsString()+"}",replacement);
                }
                assertTrue(dictionary.lookup(template.get("channel").getAsString(),live,"").isPresent(),live);
            }
        }
    }
    @Test void quarantinedLiteralCannotEscapeThroughCommonAliasesOrTemplates() {
        var base = BundledDictionary.load(stream(Map.of("schema_version", 1, "build_id", "fixture", "tier", "candidate",
                "entries", List.of(entry("common", "Gain 10 Health.", "Healthが10増加する。")),
                "blocked_keys", List.of(Map.of("channel", "item", "speaker", "", "source", "Gain 10 Health.")))));
        assertTrue(base.stats().loaded(), base.stats().error());
        assertEquals(1, base.stats().conflictingKeys());
        var matching = Map.of("schema_version", 1, "build_id", "fixture", "normalization", "ascii_case_single_period_stat_glyphs_v1",
                "aliases", List.of(entry("item", "gain 10 health", "Healthが10増加する。")),
                "templates", List.of(template("Gain {v0} Health.", "Healthが{v0}増加する。", "number")));
        for (var dictionary : List.of(base, base.withMatching(stream(matching)), base.withMatching(null))) {
            for (String source : List.of("Gain 10 Health.", "gain 10 health", " GAIN\u00a010 HEALTH. ")) {
                var lookup = dictionary.inspect("item", source, "");
                assertTrue(lookup.match().isEmpty(), source);
                assertEquals("ambiguous", lookup.reason(), source);
            }
            assertTrue(dictionary.lookup("ui", "Gain 10 Health.", "").isPresent());
            assertTrue(dictionary.lookup("system", "Gain 10 Health.", "").isPresent());
        }
        assertTrue(base.withMatching(stream(matching)).lookup("item", "Gain 11 Health.", "").isPresent());
    }
    @Test void commonQuarantinePreservesGlyphEquivalenceAndSpeakerIsolationWithoutSidecar() {
        String source = "Gain +10☘ Farming Fortune.";
        var dictionary = BundledDictionary.load(stream(Map.of("schema_version", 1, "build_id", "fixture", "tier", "candidate",
                "entries", List.of(entry("item", "Gain +10\ue051 Farming Fortune.", "\ue051 Farming Fortuneが+10増加する。"),
                        Map.of("channel", "npc", "speaker", "Bob", "source", source, "target", "☘ Farming Fortuneが+10増加する。", "keep_original", false, "source_ids", List.of("fixture"))),
                "blocked_keys", List.of(Map.of("channel", "common", "speaker", "", "source", source)))));
        assertTrue(dictionary.stats().loaded(), dictionary.stats().error());
        assertEquals("ambiguous", dictionary.inspect("item", "Gain +10\ue051 Farming Fortune.", "").reason());
        assertTrue(dictionary.lookup("npc", source, "Bob").isPresent());
    }
    @Test void malformedQuarantineNeverSilentlyDisappears() {
        for (Object blocked : List.of("not-an-array", List.of(Map.of("channel", "item", "source", "Gain 10 Health.")),
                List.of(Map.of("channel", "npc", "speaker", "", "source", "Gain 10 Health.")),
                List.of(Map.of("channel", "item", "speaker", "", "source", "<bad>")))) {
            var dictionary = BundledDictionary.load(stream(Map.of("schema_version", 1, "build_id", "fixture", "tier", "candidate",
                    "entries", List.of(entry("common", "Gain 10 Health.", "Healthが10増加する。")), "blocked_keys", blocked)));
            assertFalse(dictionary.stats().loaded());
            assertTrue(dictionary.lookup("item", "Gain 10 Health.", "").isEmpty());
        }
    }
    @Test void allBundledQuarantinedKeysRemainBlockedWithoutExplicitReviews() throws Exception {
        var dictionary = BundledDictionary.load(getClass().getResourceAsStream(BundledDictionary.RESOURCE))
                .withMatching(getClass().getResourceAsStream(DictionaryMatching.RESOURCE));
        try (var input = getClass().getResourceAsStream(BundledDictionary.RESOURCE);
                var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var blocked = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("blocked_keys");
            assertFalse(blocked.isEmpty());
            assertEquals(blocked.size(), dictionary.stats().conflictingKeys());
            for (var value : blocked) {
                var row = value.getAsJsonObject();
                String channel = row.get("channel").getAsString(), source = row.get("source").getAsString(), speaker = row.get("speaker").getAsString();
                assertEquals("ambiguous", dictionary.inspect(channel, source, speaker).reason(), channel + ": " + source);
                assertEquals("ambiguous", dictionary.inspect(channel, DictionaryMatching.aliasKey(source), speaker).reason(), channel + ": " + source);
            }
        }
    }
}
