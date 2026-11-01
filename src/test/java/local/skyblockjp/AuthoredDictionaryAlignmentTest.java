package local.skyblockjp;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthoredDictionaryAlignmentTest {
    private static Component c(String text,int rgb) { return Component.literal(text).setStyle(Style.EMPTY.withColor(rgb)); }
    private static StyledParagraph p(Component text) { return new StyledParagraph(List.of(text)); }
    private static List<Integer> colors(Component output,String phrase) {
        int start=output.getString().indexOf(phrase); assertTrue(start>=0,output.getString());int at=0;var result=new ArrayList<Integer>();
        for(var glyph:StyledParagraph.glyphs(output)){if(at>=start&&at<start+phrase.length())result.add(glyph.style().getColor().getValue());at+=glyph.text().length();}return result;
    }
    private static DictionaryAlignment.Span span(String id,String kind,String source,String target,String from,String to,boolean plain) {
        int a=source.indexOf(from),b=to==null?-1:target.indexOf(to);assertTrue(a>=0);if(to!=null)assertTrue(b>=0);
        return new DictionaryAlignment.Span(id,kind,a,a+from.length(),b,b<0?-1:b+to.length(),from,to==null?"":to,kind.equals("ordinary"),plain);
    }
    private static DictionaryAlignment.Plan plan(String source,String target,DictionaryAlignment.Span...spans) {
        return new DictionaryAlignment.Plan("test-plan","item",source,target,DictionaryAlignment.sha256(source),DictionaryAlignment.sha256(target),true,List.of("test"),List.of(spans));
    }
    private static BundledDictionary.Match match(DictionaryAlignment.Plan plan) { return new BundledDictionary.Match(plan.target(),false,List.of("test"),List.of(),plan); }
    private static Component decode(StyledParagraph p,DictionaryAlignment.Plan plan,boolean plain) {
        var encoded=p.dictionaryEncoding(match(plan),plain);assertTrue(encoded.accepted(),encoded.reason()+encoded.details());assertTrue(StyledParagraph.validNumbers(p.encoded(),encoded.text()));return p.decode(encoded.text());
    }
    private static List<Component> lines(JsonObject row) {
        var result=new ArrayList<Component>();for(var raw:row.getAsJsonArray("lines")){var line=Component.empty();for(var s:raw.getAsJsonArray()){var v=s.getAsJsonObject();var style=Style.EMPTY;if(v.has("color"))style=style.withColor(v.get("color").getAsInt());style=style.withBold(v.get("bold").getAsBoolean()).withItalic(v.get("italic").getAsBoolean()).withUnderlined(v.get("underlined").getAsBoolean()).withStrikethrough(v.get("strikethrough").getAsBoolean()).withObfuscated(v.get("obfuscated").getAsBoolean());line.append(Component.literal(v.get("text").getAsString()).setStyle(style));}result.add(line);}return result;
    }
    @Test void actualLookupCarriesReviewedPlansAndRecordedNativeParagraphsRender() throws Exception {
        assertTrue(DictionaryAlignment.stats().loaded());assertEquals(81,DictionaryAlignment.stats().plans());assertEquals(0,DictionaryAlignment.stats().rejected());
        var dict=BundledDictionary.loadBundled();int checked=0,scopes=0;
        try(var in=getClass().getResourceAsStream("/authored-alignment-session-fixtures.json")){
            for(var raw:JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonArray()){
                var row=raw.getAsJsonObject();
                for(var scope:row.getAsJsonArray("scopes")){
                    var found=dict.inspect(scope.getAsJsonObject().get("channel").getAsString(),row.get("source").getAsString(),"");
                    assertTrue(found.match().isPresent(),row.get("source").getAsString());assertNotNull(found.match().get().alignment(),row.get("source").getAsString());
                    var p=new StyledParagraph(lines(row));var encoded=p.dictionaryEncoding(found.match().get(),true);
                    assertTrue(encoded.accepted(),row.get("source").getAsString()+" / "+encoded.reason()+encoded.details());
                    assertTrue(StyledParagraph.validNumbers(p.encoded(),encoded.text()));assertFalse(p.decode(encoded.text()).getString().contains("<s"));scopes++;
                }
                checked++;
            }
        }
        assertEquals(29,checked);assertEquals(60,scopes);
    }
    @Test void ordinaryAccentUsesNativeStyleInsteadOfStoredRgbAndBaseNeverUsesLeadingName() {
        String source="Trevor works mysteriously for 4 seconds.",target="Trevorは4秒間、不思議に働く。";
        var plan=plan(source,target,span("manner","ordinary",source,target,"mysteriously","不思議に",false));
        for(int rgb:List.of(0x1234AB,0xEE2299)) {
            var paragraph=p(c("Trevor",0xAA44CC).copy().append(c(" works ",0xAAAAAA)).append(Component.literal("mysteriously").setStyle(Style.EMPTY.withColor(rgb).withBold(true)))
                .append(c(" for ",0xAAAAAA)).append(c("4",0x33AA55)).append(c(" seconds.",0xAAAAAA)));
            var output=decode(paragraph,plan,false);assertEquals(Collections.nCopies(4,rgb),colors(output,"不思議に"));assertEquals(List.of(0xAAAAAA),colors(output,"は"));assertEquals(List.of(0x33AA55),colors(output,"4"));
            assertTrue(StyledParagraph.glyphs(output).stream().filter(g->g.text().equals("不")).findFirst().orElseThrow().style().isBold());
        }
    }
    @Test void hotChocolateFlavorMapsToItsActualJapaneseMeaningWithoutPlainFallback() throws Exception {
        JsonObject row;
        try(var input=getClass().getResourceAsStream("/authored-alignment-session-fixtures.json")) {
            row=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonArray().asList().stream()
                .map(JsonElement::getAsJsonObject).filter(r->r.get("source").getAsString().startsWith("Its warm chocolatey goodness")).findFirst().orElseThrow();
        }
        var original=new StyledParagraph(lines(row));
        for(String channel:List.of("item","ui")) {
            var match=BundledDictionary.loadBundled().lookup(channel,original.sourceText(),"").orElseThrow();
            assertEquals(row.get("target").getAsString(),match.text());assertNotNull(match.alignment());
            assertTrue(match.alignment().spans().stream().noneMatch(DictionaryAlignment.Span::allowPlain));
            var strict=original.dictionaryEncoding(match,false);assertTrue(strict.accepted(),strict.reason()+strict.details());
            assertEquals("AUTHORED",strict.details().get("alignmentMode"));assertEquals(List.of(),strict.details().get("plainStyleIds"));
            assertEquals(strict.text(),original.dictionaryEncoding(match,true).text());
            var output=original.decode(strict.text());assertEquals(match.text(),output.getString());
            assertEquals(Collections.nCopies("チョコレート".length(),0xFFFF55),colors(output,"チョコレート"));
            assertEquals(Collections.nCopies("Blazing Volcano".length(),0xFFAA00),colors(output,"Blazing Volcano"));
            assertEquals(Collections.nCopies("濃厚な".length(),0xAAAAAA),colors(output,"濃厚な"));
            int from=original.sourceText().indexOf("chocolatey"),to=from+"chocolatey".length(),at=0;
            var changed=Component.empty();
            for(var glyph:original.sourceGlyphs()) {
                var style=at>=from&&at<to?glyph.style().withColor(0x22AA77).withBold(true):glyph.style();
                changed.append(Component.literal(glyph.text()).setStyle(style));at+=glyph.text().length();
            }
            var paragraph=new StyledParagraph(List.of(changed));var encoded=paragraph.dictionaryEncoding(match,false);
            assertTrue(encoded.accepted(),encoded.reason());var nativeOutput=paragraph.decode(encoded.text());
            assertEquals(Collections.nCopies("チョコレート".length(),0x22AA77),colors(nativeOutput,"チョコレート"));
            int targetAt=match.text().indexOf("チョコレート");int cursor=0;
            for(var glyph:StyledParagraph.glyphs(nativeOutput)) {
                if(cursor>=targetAt&&cursor<targetAt+"チョコレート".length())assertTrue(glyph.style().isBold());
                cursor+=glyph.text().length();
            }
        }
    }
    @Test void plainFallbackIsExactReviewedOptInAndKeepsNativeValuesAndNames() {
        String source="Trevor works mysteriously for 4 seconds.",target="Trevorは4秒間、不思議に働く。";
        var plan=plan(source,target,span("manner","ordinary",source,target,"mysteriously",null,true));
        var p=p(c("Trevor",0xAA44CC).copy().append(c(" works ",0xAAAAAA)).append(c("mysteriously",0x1234AB)).append(c(" for ",0xAAAAAA)).append(c("4",0x33AA55)).append(c(" seconds.",0xAAAAAA)));
        assertEquals("PLAIN_ALIGNMENT_DISABLED",p.dictionaryEncoding(match(plan),false).reason());
        var result=p.dictionaryEncoding(match(plan),true);assertTrue(result.accepted(),result.reason());assertEquals(List.of("manner"),result.details().get("plainStyleIds"));
        var output=p.decode(result.text());assertEquals(List.of(0xAAAAAA,0xAAAAAA,0xAAAAAA,0xAAAAAA),colors(output,"不思議に"));assertEquals(List.of(0x33AA55),colors(output,"4"));assertEquals(Collections.nCopies(6,0xAA44CC),colors(output,"Trevor"));
        assertFalse(p.dictionaryEncoding(new BundledDictionary.Match(target,false,List.of("test")),true).accepted());
    }
    @Test void repeatedEqualValuesFollowAuthoredSemanticIdsAfterJapaneseReordering() {
        String source="Gain 10 alpha and 10 beta.",target="betaが10、alphaが10増加する。";
        int a=source.indexOf("10"),b=source.lastIndexOf("10"),x=target.indexOf("10"),y=target.lastIndexOf("10");
        var plan=plan(source,target,new DictionaryAlignment.Span("alpha","value",a,a+2,y,y+2,"10","10",false,false),new DictionaryAlignment.Span("beta","value",b,b+2,x,x+2,"10","10",false,false));
        var p=p(c("Gain ",0xAAAAAA).copy().append(c("10",0x22AA22)).append(c(" alpha and ",0xAAAAAA)).append(c("10",0xAA2222)).append(c(" beta.",0xAAAAAA)));
        assertEquals("AMBIGUOUS_VALUE_STYLE",p.dictionaryEncoding(target).reason());
        assertEquals(List.of(0xAA2222,0xAA2222),colors(decode(p,plan,false),"10"));
        assertEquals("STALE_ALIGNMENT_PLAN",p(c(source.replaceFirst("10","11"),0xAAAAAA)).dictionaryEncoding(match(plan),true).reason());
    }
    @Test void duplicateDifferentColoredOrdinaryPhrasesNeedExplicitDistinctSpans() {
        String source="Gain rewards and bonus rewards.",target="追加の報酬と通常の報酬を得る。";
        int a=source.indexOf("rewards"),b=source.lastIndexOf("rewards"),x=target.indexOf("報酬"),y=target.lastIndexOf("報酬");
        var plan=plan(source,target,new DictionaryAlignment.Span("normal","ordinary",a,a+7,y,y+2,"rewards","報酬",true,false),new DictionaryAlignment.Span("bonus","ordinary",b,b+7,x,x+2,"rewards","報酬",true,false));
        var p=p(c("Gain ",0xAAAAAA).copy().append(c("rewards",0xAA3300)).append(c(" and bonus ",0xAAAAAA)).append(c("rewards",0x1122CC)).append(c(".",0xAAAAAA)));
        assertEquals(List.of(0x1122CC,0x1122CC),colors(decode(p,plan,false),"報酬"));
    }
    @Test void aSingleNativeColorCanCoverAdjacentOrdinaryAndNameRoles() {
        String source="A perfectly refined Sapphire.",target="完璧に精製されたSapphire。";
        var plan=plan(source,target,span("refinement","ordinary",source,target,"refined","精製された",false),span("gem","name",source,target,"Sapphire","Sapphire",false));
        var p=p(c("A perfectly ",0xAAAAAA).copy().append(c("refined Sapphire",0x12ABEF)).append(c(".",0xAAAAAA)));
        var output=decode(p,plan,false);assertEquals(Collections.nCopies(5,0x12ABEF),colors(output,"精製された"));assertEquals(Collections.nCopies(8,0x12ABEF),colors(output,"Sapphire"));
    }
    @Test void malformedHashesRangesReferencesAndNameChangesNeverEnableFallback() {
        String source="It affects Zephyrus.",target="効果を与える。";var p=p(c("It affects ",0xAAAAAA).copy().append(c("Zephyrus",0x12ABEF)).append(c(".",0xAAAAAA)));
        var unsafe=plan(source,target,span("unknown","ordinary",source,target,"Zephyrus",null,true));
        assertEquals("UNSAFE_ORDINARY_ALIGNMENT",p.dictionaryEncoding(match(unsafe),true).reason());
        var wrongHash=new DictionaryAlignment.Plan("bad","item",source,target,"0".repeat(64),DictionaryAlignment.sha256(target),true,List.of(),unsafe.spans());
        assertEquals("ALIGNMENT_HASH_MISMATCH",p.dictionaryEncoding(match(wrongHash),true).reason());
        var outOfBounds=plan(source,target,new DictionaryAlignment.Span("bad","ordinary",-1,5,-1,-1,"bad","",true,true));
        assertEquals("INVALID_ALIGNMENT_SPAN",p.dictionaryEncoding(match(outOfBounds),true).reason());
        var changedName=plan(source,"Otherに効果を与える。",span("entity","name",source,"Otherに効果を与える。","Zephyrus","Other",false));
        assertEquals("ALIGNMENT_NAME_CHANGED",p.dictionaryEncoding(match(changedName),true).reason());
    }
    @Test void symbolsNumbersAndUnconfirmedTextCannotBeOrdinaryFallback() {
        for(String literal:List.of("10","Health","\uE010","☘")) {
            String source="It affects "+literal+".",target="効果を与える。";
            var plan=plan(source,target,span("not-prose","ordinary",source,target,literal,null,true));
            assertEquals("UNSAFE_ORDINARY_ALIGNMENT",p(Component.literal(source)).dictionaryEncoding(match(plan),true).reason(),literal);
        }
    }
    @Test void tamperedTargetAndSourceCannotReuseAReviewedPlan() {
        String source="It works mysteriously.",target="不思議に働く。";
        var plan=plan(source,target,span("manner","ordinary",source,target,"mysteriously",null,true));
        var p=p(c("It works ",0xAAAAAA).copy().append(c("mysteriously",0x12ABEF)).append(c(".",0xAAAAAA)));
        assertEquals("STALE_ALIGNMENT_PLAN",p.dictionaryEncoding(new BundledDictionary.Match("5秒間働く。",false,List.of("test"),List.of(),plan),true).reason());
        assertEquals("STALE_ALIGNMENT_PLAN",p(Component.literal("It fails mysteriously.")).dictionaryEncoding(match(plan),true).reason());
    }
    @Test void namedSpanCannotMatchADifferentDictionarySourceIdentityOrChannel() {
        var plan=DictionaryAlignment.plans().getFirst();
        assertNull(DictionaryAlignment.lookup(plan.channel(),plan.source(),plan.target(),List.of("foreign-source")));
        String other=plan.channel().equals("npc")?"item":"npc";assertNull(DictionaryAlignment.lookup(other,plan.source(),plan.target(),plan.sourceIds()));
    }
    @Test void newlyColoredUnreviewedProseCannotPiggybackOnAnApprovedPlainSpan() {
        String source="It works mysteriously.",target="不思議に働く。";
        var plan=plan(source,target,span("manner","ordinary",source,target,"mysteriously",null,true));
        var p=p(c("It ",0xAAAAAA).copy().append(c("works",0xCC1122)).append(c(" ",0xAAAAAA)).append(c("mysteriously",0x12ABEF)).append(c(".",0xAAAAAA)));
        assertEquals("STYLE_ALIGNMENT_MISSING",p.dictionaryEncoding(match(plan),true).reason());
    }
    @Test void aNewStyleBoundaryInsideAnApprovedOrdinaryPhraseMustNotEraseEitherColor() {
        String source="It works mysteriously.",target="不思議に働く。";
        var plan=plan(source,target,span("manner","ordinary",source,target,"mysteriously",null,true));
        var p=p(c("It works ",0xAAAAAA).copy().append(c("myster",0x123456)).append(c("iously",0xABCDEF)).append(c(".",0xAAAAAA)));
        assertEquals("STYLE_ALIGNMENT_MISSING",p.dictionaryEncoding(match(plan),true).reason());
    }
}
