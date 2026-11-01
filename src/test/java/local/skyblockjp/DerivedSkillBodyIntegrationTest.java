package local.skyblockjp;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independently pinned official pairs and a real wrapped skill-menu tooltip. No provider/client. */
class DerivedSkillBodyIntegrationTest {
    private static final BundledDictionary DICTIONARY=BundledDictionary.loadBundled();
    private static final String PREFIX="derived/skill_body/";
    private static JsonObject fixture(String resource) throws Exception {
        try(var input=DerivedSkillBodyIntegrationTest.class.getResourceAsStream(resource)) {
            assertNotNull(input,resource);
            return JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static List<Component> components(JsonArray rows) {
        var result=new ArrayList<Component>();
        for(var row:rows) {
            var line=Component.empty();
            for(var raw:row.getAsJsonArray()) {
                var run=raw.getAsJsonObject();var style=Style.EMPTY;
                if(run.has("color"))style=style.withColor(run.get("color").getAsInt());
                style=style.withBold(run.get("bold").getAsBoolean()).withItalic(run.get("italic").getAsBoolean())
                    .withUnderlined(run.get("underlined").getAsBoolean()).withStrikethrough(run.get("strikethrough").getAsBoolean())
                    .withObfuscated(run.get("obfuscated").getAsBoolean());
                line.append(Component.literal(run.get("text").getAsString()).setStyle(style));
            }result.add(line);
        }return result;
    }
    private static void color(Component output,String fragment,int rgb) {
        int start=output.getString().indexOf(fragment);assertTrue(start>=0,output.getString());int offset=0,checked=0;
        for(var glyph:StyledParagraph.glyphs(output)) {
            if(offset>=start&&offset<start+fragment.length()) {
                assertNotNull(glyph.style().getColor());assertEquals(rgb,glyph.style().getColor().getValue(),fragment);checked++;
            }offset+=glyph.text().length();
        }assertEquals(fragment.length(),checked,fragment);
    }
    private static boolean label(String title) {
        return DICTIONARY.lookup("item",title,"").filter(BundledDictionary.Match::keepOriginal)
            .filter(match->match.sourceIds().stream().anyMatch(id->id.startsWith("item/label/"))).isPresent();
    }
    @Test void everyApprovedDerivedBodyHasARealPackagedUiLookupAndRendersItsPinnedJapanese() throws Exception {
        assertTrue(DICTIONARY.stats().loaded(),DICTIONARY.stats().error());
        var pairs=fixture("/skill-body-derived-pairs.json").getAsJsonArray("pairs");assertEquals(507,pairs.size());
        var ids=new HashSet<String>();var sources=new HashSet<String>();var skills=new HashSet<String>();
        for(var value:pairs) {
            var pair=value.getAsJsonObject();String source=pair.get("en").getAsString(),target=pair.get("ja").getAsString();
            String id=pair.get("id").getAsString();assertTrue(ids.add(id),id);assertTrue(sources.add(source),source);
            assertTrue(id.startsWith(PREFIX));skills.add(pair.get("skill").getAsString());
            assertEquals(pair.get("source_sha256").getAsString(),DictionaryAlignment.sha256(source));
            assertEquals(pair.get("target_sha256").getAsString(),DictionaryAlignment.sha256(target));
            var found=DICTIONARY.inspect("ui",source,"",Map.of("containerTitle","Your Skills"));
            assertTrue(found.match().isPresent(),source+" / "+found.reason());var match=found.match().orElseThrow();
            assertTrue(match.sourceIds().contains(id),source+" / "+match.sourceIds());assertFalse(match.keepOriginal(),source);
            assertEquals(target,match.text(),source);
            var paragraph=new StyledParagraph(List.of(Component.literal(source).withStyle(ChatFormatting.GRAY)));
            var encoded=paragraph.dictionaryEncoding(match,true);assertTrue(encoded.accepted(),source+" / "+encoded.reason()+encoded.details());
            assertTrue(StyledParagraph.validNumbers(paragraph.encoded(),encoded.text()),source);
            assertEquals(target,paragraph.decode(encoded.text()).getString(),source);
        }
        assertEquals(Set.of("ALCHEMY","COMBAT","ENCHANTING","FARMING","FISHING","FORAGING","HUNTING","MINING","TAMING"),skills);
    }
    @Test void derivedUiSourceIdentityNeverSpillsIntoItemNpcSystemOrCommonBodies() throws Exception {
        for(var value:fixture("/skill-body-derived-pairs.json").getAsJsonArray("pairs")) {
            var pair=value.getAsJsonObject();String source=pair.get("en").getAsString();
            for(String channel:List.of("item","npc","system","common")) {
                var result=DICTIONARY.inspect(channel,source,channel.equals("npc")?"Dean":"");
                // A separately sourced existing item/common entry is legitimate; UI derivations are not.
                result.match().ifPresent(match->assertTrue(match.sourceIds().stream().noneMatch(id->id.startsWith(PREFIX)),channel+": "+source));
                if(channel.equals("npc"))assertTrue(result.match().isEmpty(),"A skill-menu body must not become Dean's dialogue: "+source);
            }
        }
    }
    @Test void derivingUiBodiesDoesNotModifyTheApprovedFullCommonLabelAndBodyPairs() throws Exception {
        for(var value:fixture("/skill-body-derived-pairs.json").getAsJsonArray("pairs")) {
            var pair=value.getAsJsonObject();String source=pair.get("full_en").getAsString(),target=pair.get("full_ja").getAsString();
            var found=DICTIONARY.inspect("common",source,"");assertTrue(found.match().isPresent(),source);
            var match=found.match().orElseThrow();assertEquals(BundledDictionary.normalize(target),match.text(),source);
            assertTrue(match.sourceIds().contains(pair.get("source_id").getAsString()),source);
            assertTrue(match.sourceIds().stream().noneMatch(id->id.startsWith(PREFIX)),source);
        }
    }
    @Test void realFarmhandWrappedBodyRetainsDistinctNumbersArrowIconAndNameColors() throws Exception {
        for(var raw:fixture("/new-skill-body-native-fixtures.json").getAsJsonArray("fixtures")) {
            var row=raw.getAsJsonObject();var sourceLines=new ArrayList<String>();
            row.getAsJsonArray("originalLines").forEach(v->sourceLines.add(v.getAsString()));
            var lines=components(row.getAsJsonArray("originalStyledLines"));assertEquals(sourceLines.size(),lines.size());
            for(int i=0;i<sourceLines.size();i++)assertEquals(sourceLines.get(i),lines.get(i).getString());
            var context=row.getAsJsonObject("classification").getAsJsonObject("evidence");
            var evidence=new TooltipContext.Evidence(context.get("screenClass").getAsString(),context.get("containerTitle").getAsString(),
                TooltipContext.Location.valueOf(context.get("location").getAsString()),context.get("slotMatches").getAsBoolean(),
                context.get("skyblockItemId").getAsString(),context.get("minecraftItemId").getAsString(),
                context.get("slotIndex").getAsInt(),context.get("containerSize").getAsInt());
            var decision=TooltipContext.analyze(evidence,sourceLines);assertEquals(TooltipContext.Kind.UI,decision.kind());
            var analysis=LoreParser.analyze(sourceLines,decision,DerivedSkillBodyIntegrationTest::label);
            String source=row.get("source").getAsString();assertEquals(row.get("source_sha256").getAsString(),DictionaryAlignment.sha256(source));
            var parsed=analysis.paragraphs().stream().filter(p->p.block().text().equals(source)).findFirst().orElseThrow();
            assertEquals("ui",parsed.channel());assertEquals("DESCRIPTION",parsed.role());
            assertTrue(analysis.paragraphs().stream().noneMatch(p->p.block().text().contains("Farmhand XXXVII")));
            assertEquals("KEEP",analysis.lines().stream().filter(l->l.source().strip().equals("Farmhand XXXVII")).findFirst().orElseThrow().action());
            assertEquals("KEEP",analysis.lines().stream().filter(l->l.source().strip().equals("+5\ue010 Health")).findFirst().orElseThrow().action());
            var paragraph=new StyledParagraph(lines.subList(parsed.block().start(),parsed.block().end()));assertEquals(source,paragraph.sourceText());
            var found=DICTIONARY.inspect("ui",paragraph.sourceText(),"",decision.evidence());assertTrue(found.match().isPresent());
            var match=found.match().orElseThrow();assertTrue(match.sourceIds().contains(row.get("derived_id").getAsString()));
            var result=paragraph.dictionaryEncoding(match,true);assertTrue(result.accepted(),result.reason()+result.details());
            var output=paragraph.decode(result.text());assertEquals(row.get("target").getAsString(),output.getString());
            color(output,"+",0x55FF55);color(output,"144",0x555555);color(output,"➜",0x555555);color(output,"148",0x55FF55);
            // The separator between two protected atoms is ordinary whitespace;
            // check the visible icon and stat name, including its internal space.
            color(output,"\ue051",0xFFAA00);color(output,"Farming Fortune",0xFFAA00);
            assertTrue(StyledParagraph.validNumbers(paragraph.encoded(),result.text()));
            assertFalse(paragraph.dictionaryEncoding(match.text().replace("148","149")).accepted());
            assertFalse(paragraph.dictionaryEncoding(match.text().replace("Farming Fortune","Mining Fortune")).accepted());
        }
    }
}
