package local.skyblockjp;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewedDictionaryTest {
    private static final Gson JSON=new Gson();
    private static InputStream stream(Object value){return new ByteArrayInputStream(JSON.toJson(value).getBytes(StandardCharsets.UTF_8));}
    private static final String EVIDENCE="a".repeat(64);
    private static Map<String,Object> rule(String channel,String speaker,String source,String target,Map<String,Object> requires) {
        var row=new LinkedHashMap<String,Object>();row.put("id","review");row.put("approved",true);row.put("channel",channel);row.put("speaker",speaker);
        row.put("source",source);row.put("source_sha256",ReviewedDictionary.hash(DictionaryMatching.aliasKey(source)));row.put("variants_sha256",EVIDENCE);
        row.put("target",target);row.put("keep_original",false);row.put("requires",requires);row.put("reason","Reviewed fixture");row.put("source_ids",List.of("fixture"));return row;
    }
    private static BundledDictionary base(String channel,String speaker,String source) {
        return BundledDictionary.load(stream(Map.of("schema_version",1,"build_id","fixture","tier","candidate",
            "entries",List.of(Map.of("channel","common","speaker","","source",source,"target",source,"keep_original",true,"source_ids",List.of("common"))),
            "blocked_keys",List.of(Map.of("channel",channel,"speaker",speaker,"source",source)),
            "review_evidence",List.of(Map.of("channel",channel,"speaker",speaker,"source",DictionaryMatching.aliasKey(source),"variants_sha256",EVIDENCE)))));
    }
    private static BundledDictionary reviewed(BundledDictionary base,List<Map<String,Object>> rules) {
        return base.withReviewed(stream(Map.of("schema_version",1,"build_id","fixture","rules",rules)));
    }
    @Test void explicitlyReviewedMeaningPrecedesQuarantineWithoutEscapingScope() {
        String source="Gain 10 Health.";var base=base("item","",source);
        assertEquals("ambiguous",base.inspect("item",source,"").reason());
        var dictionary=reviewed(base,List.of(rule("item","",source,"Healthが10増加する。",Map.of())));
        assertTrue(dictionary.reviewedStats().loaded(),dictionary.reviewedStats().error());
        assertEquals("reviewed",dictionary.inspect("item",source,"").mode());
        assertEquals("Healthが10増加する。",dictionary.lookup("item",source,"").orElseThrow().text());
        assertEquals(source,dictionary.lookup("ui",source,"").orElseThrow().text());
        assertTrue(dictionary.lookup("npc",source,"Bob").isEmpty());
        assertTrue(dictionary.lookup("item","Gain 11 Health.","").isEmpty());
    }
    @Test void staleUnapprovedOrMissingReviewCannotFallThroughToCommon() {
        String source="Gain 10 Health.";var base=base("item","",source);
        for(String field:List.of("source_sha256","variants_sha256","approved")) {
            var row=rule("item","",source,"Healthが10増加する。",Map.of());row.put(field,field.equals("approved")?false:"b".repeat(64));
            var dictionary=reviewed(base,List.of(row));
            assertEquals(1,dictionary.reviewedStats().rejectedRules());
            assertEquals("ambiguous",dictionary.inspect("item",source,"").reason());
            assertEquals("ambiguous",dictionary.inspect("item","gain 10 health","").reason());
        }
        assertEquals("ambiguous",base.withReviewed(null).inspect("item",source,"").reason());
    }
    @Test void contextIsRequiredTypedScopedAndPartOfTheCacheIdentity() {
        String source="General";var base=base("ui","",source);
        var ordinary=rule("ui","",source,"全般",Map.of("containerTitle","Status","location","CONTAINER","slotMatches",true));
        var named=rule("ui","",source,source,Map.of("containerTitle","(6/15) Hoppity's Collection","location","CONTAINER","slotMatches",true));
        named.put("id","named");named.put("keep_original",true);
        var dictionary=reviewed(base,List.of(ordinary,named));
        assertEquals("ambiguous",dictionary.inspect("ui",source,"").reason());
        assertEquals("全般",dictionary.inspect("ui",source,"",Map.of("containerTitle","Status","location","CONTAINER","slotMatches",true)).match().orElseThrow().text());
        assertTrue(dictionary.inspect("ui",source,"",Map.of("containerTitle","(6/15) Hoppity's Collection","location","CONTAINER","slotMatches",true)).match().orElseThrow().keepOriginal());
        assertEquals("ambiguous",dictionary.inspect("ui",source,"",Map.of("containerTitle","Status","location","CONTAINER","slotMatches",1)).reason());
        assertEquals("ambiguous",dictionary.inspect("ui",source,"",Map.of("ui_id","menu-ca-st")).reason());
    }
    @Test void realNpcPreviousDialogueMayBeRequiredButInventedWikiConditionsCannotBeUsed() {
        String source="I do!";var base=base("npc","Tel Kar",source);
        var row=rule("npc","Tel Kar",source,"持っているとも！",Map.of("previousDialogue","What a coincidence!","previousSpeaker","Tel Kar"));
        var dictionary=reviewed(base,List.of(row));
        assertTrue(dictionary.inspect("npc",source,"Tel Kar",Map.of("previousDialogue","What a coincidence!","previousSpeaker","Tel Kar")).match().isPresent());
        assertEquals("ambiguous",dictionary.inspect("npc",source,"Tel Kar",Map.of("previousDialogue","Different dialogue","previousSpeaker","Tel Kar")).reason());
        assertTrue(dictionary.inspect("npc",source,"Bob",Map.of("previousDialogue","What a coincidence!","previousSpeaker","Tel Kar")).match().isEmpty());
        row.put("requires",Map.of("selectedOption","Do you have a soul for me?"));
        assertFalse(reviewed(base,List.of(row)).reviewedStats().loaded());
    }
    @Test void malformedOrContradictoryReviewCannotChooseAConvenientMeaning() {
        String source="Gain 10 Health.";var base=base("item","",source);
        var first=rule("item","",source,"Healthが10増加する。",Map.of());
        var other=rule("item","",source,"Healthを10得る。",Map.of());other.put("id","other");
        assertEquals("conflicting_reviews",reviewed(base,List.of(first,other)).inspect("item",source,"").reason());
        var unsafe=rule("item","",source,"Healthが11増加する。",Map.of());
        var dictionary=reviewed(base,List.of(unsafe));assertFalse(dictionary.reviewedStats().loaded());
        assertEquals("ambiguous",dictionary.inspect("item",source,"").reason());
        assertFalse(base.withReviewed(new ByteArrayInputStream("{\"schema_version\":1,\"schema_version\":1}".getBytes(StandardCharsets.UTF_8))).reviewedStats().loaded());
    }
    @Test void allBundledRulesReachTheirReviewedTargetsAndLeaveUnrelatedContextClosed() throws Exception {
        var dictionary=BundledDictionary.loadBundled();
        assertTrue(dictionary.reviewedStats().loaded(),dictionary.reviewedStats().error());assertEquals(0,dictionary.reviewedStats().rejectedRules());
        try(var reader=new InputStreamReader(getClass().getResourceAsStream(ReviewedDictionary.RESOURCE),StandardCharsets.UTF_8)) {
            var rules=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("rules");assertTrue(rules.size()>=28);
            for(var value:rules) {
                var rule=value.getAsJsonObject();String channel=rule.get("channel").getAsString(),speaker=rule.get("speaker").getAsString(),source=rule.get("source").getAsString();
                Map<String,Object> context=new HashMap<>();rule.getAsJsonObject("requires").entrySet().forEach(e->{var v=e.getValue().getAsJsonPrimitive();context.put(e.getKey(),v.isBoolean()?v.getAsBoolean():v.isNumber()?v.getAsLong():v.getAsString());});
                var result=dictionary.inspect(channel,source,speaker,context);assertEquals("reviewed",result.mode(),source);
                assertEquals(rule.get("target").getAsString(),result.match().orElseThrow().text(),source);
                if(!context.isEmpty())assertTrue(dictionary.inspect(channel,source,speaker).match().isEmpty(),source);
            }
        }
    }
    @Test void reviewedPercentAndWrittenThreeKeepExactNativeQuantitiesAndColors() {
        var dictionary=BundledDictionary.loadBundled();
        for(String source:List.of(
                "Increases damage dealt by 1% for each percent of health your target has.",
                "Increases damage dealt by 1.2% for each percent of extra health that your target has above you up to 65%.",
                "Increases damage dealt by 1.25% for each percent of health missing on your target.",
                "Increases melee damage dealt by 50% for the first three hits on a mob.")) {
            var original=Component.empty();var numbers=StyledParagraph.NUMBER.matcher(source);int cursor=0;
            while(numbers.find()) {
                original.append(Component.literal(source.substring(cursor,numbers.start())).withStyle(ChatFormatting.GRAY));
                original.append(Component.literal(numbers.group()).withStyle(ChatFormatting.GREEN).withStyle(ChatFormatting.BOLD));cursor=numbers.end();
            }
            original.append(Component.literal(source.substring(cursor)).withStyle(ChatFormatting.GRAY));
            var match=dictionary.inspect("item",source,"").match().orElseThrow();
            assertEquals(BundledDictionary.numbers(source),BundledDictionary.numbers(match.text()),source);
            var paragraph=new StyledParagraph(List.of(original));var encoded=paragraph.dictionaryEncoding(match);
            assertTrue(encoded.accepted(),source+": "+encoded.reason()+" "+encoded.details());
            var decoded=paragraph.decode(encoded.text());
            assertEquals(BundledDictionary.numbers(source),BundledDictionary.numbers(decoded.getString()),source);
            var glyphs=StyledParagraph.glyphs(decoded);int offset=0;
            for(var glyph:glyphs) {
                if(glyph.text().matches("[0-9%]")) {
                    assertEquals(0x55FF55,glyph.style().getColor().getValue(),source+" @"+offset);
                    assertTrue(glyph.style().isBold(),source);
                }
                offset+=glyph.text().length();
            }
        }
    }
    @Test void sourceBoundArticleAndCutoffDecisionsDoNotAddNamesOrCommands() {
        var dictionary=BundledDictionary.loadBundled();
        assertEquals("ああ――",dictionary.lookup("npc","Y-","Ugo").orElseThrow().text());
        assertEquals("あんたが――",dictionary.lookup("npc","You-","Ugo").orElseThrow().text());
        assertTrue(dictionary.lookup("npc","Y-","Y").isEmpty());
        var article=dictionary.lookup("item","An empty bottle. Something in the back of your mind tells you it would be really useful to store a Scatha in it.","").orElseThrow();
        assertTrue(article.text().contains("Scatha"));assertFalse(article.text().contains("An"));
        for(String channel:List.of("item","ui")) {
            var command=dictionary.inspect(channel,"Also accessible via enchantments","");
            assertEquals("reviewed",command.mode());assertFalse(command.match().orElseThrow().text().contains("/"));
        }
    }
}
