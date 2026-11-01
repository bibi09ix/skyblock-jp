package local.skyblockjp;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemainingStyleRestorationTest {
    private JsonArray fixtures() throws Exception {
        try(var input=getClass().getResourceAsStream("/remaining-style-native-0.7.10.json")) {
            assertNotNull(input);
            return JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonArray();
        }
    }
    private JsonObject fixture(String id) throws Exception {
        for(var raw:fixtures()) if(raw.getAsJsonObject().get("id").getAsString().equals(id)) return raw.getAsJsonObject();
        throw new AssertionError(id);
    }
    private List<Component> lines(JsonObject fixture) {
        var lines=new ArrayList<Component>();
        for(var rawLine:fixture.getAsJsonArray("lines")) {
            var line=Component.empty();
            for(var rawRun:rawLine.getAsJsonArray()) {
                var run=rawRun.getAsJsonObject();var style=Style.EMPTY;
                if(run.has("color"))style=style.withColor(run.get("color").getAsInt());
                style=style.withBold(run.get("bold").getAsBoolean()).withItalic(run.get("italic").getAsBoolean())
                    .withUnderlined(run.get("underlined").getAsBoolean()).withStrikethrough(run.get("strikethrough").getAsBoolean())
                    .withObfuscated(run.get("obfuscated").getAsBoolean());
                line.append(Component.literal(run.get("text").getAsString()).setStyle(style));
            }
            lines.add(line);
        }
        return lines;
    }
    private Component render(JsonObject row,String channel) {
        var paragraph=new StyledParagraph(lines(row));assertEquals(row.get("source").getAsString(),paragraph.sourceText());
        var match=BundledDictionary.loadBundled().lookup(channel,paragraph.sourceText(),"").orElseThrow(()->new AssertionError(paragraph.sourceText()));
        assertEquals(row.get("target").getAsString(),match.text(),row.get("id").getAsString());
        var encoded=paragraph.dictionaryEncoding(match,false);
        assertTrue(encoded.accepted(),row.get("id").getAsString()+": "+encoded.reason()+encoded.details());
        assertTrue(StyledParagraph.validNumbers(paragraph.encoded(),encoded.text()));
        var result=paragraph.decode(encoded.text());assertEquals(match.text(),result.getString());
        assertFalse(java.util.regex.Pattern.compile("<s(\\d+)></s\\1>").matcher(encoded.text()).find(),"No empty/plain style workaround");
        return result;
    }
    private Component render(String id) throws Exception {return render(fixture(id),"item");}
    private List<Integer> colors(Component component,String literal,int occurrence) {
        String text=component.getString();int start=-1;
        for(int i=0;i<=occurrence;i++)start=text.indexOf(literal,start+1);
        assertTrue(start>=0,text+" missing "+literal);var result=new ArrayList<Integer>();int at=0;
        for(var glyph:StyledParagraph.glyphs(component)) {
            if(at>=start&&at<start+literal.length())result.add(glyph.style().getColor().getValue());
            at+=glyph.text().length();
        }
        return result;
    }
    private void color(Component component,String literal,int occurrence,int rgb) {
        assertEquals(Collections.nCopies(literal.length(),rgb),colors(component,literal,occurrence),literal);
    }
    @Test void allElevenBaselineFailuresAndTwoCorrectlySeparatedBodiesRenderInBothTooltipScopes() throws Exception {
        var rows=fixtures();assertEquals(13,rows.size());
        for(var row:rows)for(String channel:List.of("item","ui"))render(row.getAsJsonObject(),channel);
    }
    @Test void samePercentagesFollowTheirExplicitSemanticSlotsAndRetainDifferentColors() throws Exception {
        var result=render("52");color(result,"+1%",0,0x55FFFF);color(result,"+1%",1,0xFF5555);
        color(result,"Magic Find",0,0x55FFFF);color(result,"Combat Stats",0,0xFF5555);
    }
    @Test void pluralPetCategorySwapAndWholeDisplayConditionRetainThreeNativeAccents() throws Exception {
        var result=render("91");color(result,"Pet Items",0,0x55FF55);color(result,"Pet Item",1,0x55FF55);
        color(result,"交換",0,0xFFFF55);color(result,"アイテムを使うには、ペットを表示しておく必要がある！",0,0x555555);
    }
    @Test void refinedHeadingAndOreCategoryRetainTheirSeparateColorsWithOrWithoutHeading() throws Exception {
        var combined=render("146");color(combined,"Refined Bonus",0,0x5555FF);color(combined,"Ores",0,0xFFAA00);color(combined,"0.25%",0,0x55FF55);
        var body=render("refined-body");color(body,"Ores",0,0xFFAA00);color(body,"0.25%",0,0x55FF55);
    }
    @Test void maximumParentheticalKeepsDarkGrayAcrossJapanesePunctuationAndNumericValue() throws Exception {
        var combined=render("178");color(combined,"（最大5%引き）。",0,0x555555);
        var body=render("owl-training-maximum-body");color(body,"（最大5%割引）",0,0x555555);
    }
    @Test void chapterPluralAndOnlyTheExplicitSmallRewardOccurrenceKeepTheirAccents() throws Exception {
        color(render("188"),"Chapters",0,0x55FFFF);
        var rewards=render("188-rewards");color(rewards,"報酬",0,0xFFAA00);color(rewards,"報酬",1,0xAAAAAA);
    }
    @Test void achievementPluralAndJapanesePossessivePreserveTheirNativeStyles() throws Exception {
        color(render("215"),"Achievement Points",0,0xFFFF55);
        var result=render("389");color(result,"Tarantula Broodfather",0,0xFF5555);color(result,"による",0,0xFF5555);
        color(result,"Vitality",0,0xAA0000);color(result,"25%",0,0x55FF55);
    }
    @Test void exactEnglishProtectedAtomsAreRestoredRatherThanRemovedOrLoosened() throws Exception {
        assertTrue(render("202").getString().contains("swing range"));
        assertTrue(render("1419").getString().contains("Honeyhives"));
        var result=render("1420").getString();
        for(String name:List.of("Trees","Critters","Torrhus Sea Creatures"))assertTrue(result.contains(name),name);
    }
    @Test void articleExceptionCannotHideLaterCapitalizedNamesOrStaleHashes() throws Exception {
        String source="The Zephyrus pet must be visible.",target="ペットを表示する必要がある。";
        var span=new DictionaryAlignment.Span("bad","ordinary",0,source.length(),0,target.length(),source,target,true,false);
        var plan=new DictionaryAlignment.Plan("not-reviewed-name","item",source,target,DictionaryAlignment.sha256(source),DictionaryAlignment.sha256(target),true,List.of("fixture"),List.of(span));
        assertEquals("UNSAFE_ORDINARY_ALIGNMENT",DictionaryAlignment.validate(plan,source,target));
        var approved=DictionaryAlignment.plans().stream().filter(p->p.id().equals("review0710/item/pet-item-condition")).findFirst().orElseThrow();
        var stale=new DictionaryAlignment.Plan(approved.id(),approved.channel(),approved.source(),approved.target(),"0".repeat(64),approved.targetSha256(),true,approved.sourceIds(),approved.spans());
        assertEquals("ALIGNMENT_HASH_MISMATCH",DictionaryAlignment.validate(stale,approved.source(),approved.target()));
        assertEquals("STALE_ALIGNMENT_PLAN",DictionaryAlignment.validate(approved,approved.source().replace("swap","discard"),approved.target()));
    }
    @Test void sourceIdentityAndNewColorBoundariesCannotReuseTheReviewedWholeSentenceMapping() throws Exception {
        var row=fixture("91");String source=row.get("source").getAsString(),target=row.get("target").getAsString();
        assertNull(DictionaryAlignment.lookup("item",source,target,List.of("foreign")));
        assertNull(DictionaryAlignment.lookup("npc",source,target,List.of()));
        var match=BundledDictionary.loadBundled().lookup("item",source,"").orElseThrow();
        int start=source.indexOf("The pet must be visible");int visible=source.indexOf("visible",start);
        var changed=Component.literal(source.substring(0,start)).setStyle(Style.EMPTY.withColor(0xAAAAAA))
            .append(Component.literal(source.substring(start,visible)).setStyle(Style.EMPTY.withColor(0x555555)))
            .append(Component.literal("visible").setStyle(Style.EMPTY.withColor(0x1122CC)))
            .append(Component.literal(source.substring(visible+7)).setStyle(Style.EMPTY.withColor(0x555555)));
        assertFalse(new StyledParagraph(List.of(changed)).dictionaryEncoding(match,false).accepted());
    }
}
