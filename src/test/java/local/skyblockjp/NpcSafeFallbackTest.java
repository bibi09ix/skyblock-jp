package local.skyblockjp;

import com.google.gson.Gson;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NpcSafeFallbackTest {
    private static final BundledDictionary LIVE=BundledDictionary.loadBundled();
    private static final String GREETING="Hi, [player]! Welcome to the Furniture Shop.";
    private static final String GREETING_JA="やあ、[player]！ Furniture Shopへようこそ。";
    private static Map<String,Object> entry(String channel,String who,String source,String target) {
        return Map.of("channel",channel,"speaker",who,"source",source,"target",target,"keep_original",false,"source_ids",List.of("fixture"));
    }
    private static BundledDictionary fixture(List<Map<String,Object>> entries,List<Map<String,String>> blocked) {
        String json=new Gson().toJson(Map.of("schema_version",1,"tier","candidate","build_id","safe-fallback-fixture","entries",entries,"blocked_keys",blocked));
        return BundledDictionary.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void onlyKnownLocalPlayerAndExactSpeakerMayFillTheReviewedNameSlot() {
        String source=GREETING.replace("[player]","Probe_12");
        var accepted=LIVE.inspect("npc",source,"[NPC] Carpenter",Map.of("localPlayerName","Probe_12"));
        assertEquals("npc_template",accepted.mode());var match=accepted.match().orElseThrow();
        assertEquals(GREETING_JA.replace("[player]","Probe_12"),match.text());
        var binding=match.bindings().getFirst();
        assertEquals("Probe_12",source.substring(binding.sourceStart(),binding.sourceEnd()));
        assertEquals("Probe_12",match.text().substring(binding.targetStart(),binding.targetEnd()));
        // These requests share the same source: cached successful identity must not leak.
        assertTrue(LIVE.inspect("npc",source,"Carpenter",Map.of()).match().isEmpty());
        assertTrue(LIVE.inspect("npc",source,"Carpenter",Map.of("localPlayerName","Other_34")).match().isEmpty());
        assertTrue(LIVE.inspect("npc",source,"Banker",Map.of("localPlayerName","Probe_12")).match().isEmpty());
        assertTrue(LIVE.inspect("item",source,"",Map.of("localPlayerName","Probe_12")).match().isEmpty());
        for(String value:List.of("Health","Sam","Other_34","Probe 12","<player>"))
            assertTrue(LIVE.inspect("npc",GREETING.replace("[player]",value),"Carpenter",Map.of("localPlayerName","Probe_12")).match().isEmpty(),value);
        for(String changed:List.of("prefix "+source,source+" extra",source.replace("Welcome","Farewell")))
            assertTrue(LIVE.inspect("npc",changed,"Carpenter",Map.of("localPlayerName","Probe_12")).match().isEmpty(),changed);
    }
    @Test void onlyExplicitQuantityShapesVaryAndExistingFixedWordingWins() {
        for(String value:List.of("3","1,024","999999999")) {
            var result=LIVE.inspect("npc","i need "+value+" pls","Fetchur");
            assertEquals("npc_template",result.mode());
            assertEquals(value+"ほしい、よろしく",result.match().orElseThrow().text());
        }
        var fixed=LIVE.inspect("npc","i need 20 pls","Fetchur");
        assertEquals("exact",fixed.mode());assertEquals("20個ほしいんだ、おねがい",fixed.match().orElseThrow().text());
        String months="Did you know that SkyBlock has been running for 6280 in-game months already? Time flies.";
        var result=LIVE.inspect("npc",months,"Andrew");assertEquals("npc_template",result.mode());
        assertTrue(result.match().orElseThrow().text().contains("6280か月"));
        for(String bad:List.of("0","-3","+3","3.5","1e3","1,00","1000000000","Sam","3; /command"))
            assertTrue(LIVE.inspect("npc","i need "+bad+" pls","Fetchur").match().isEmpty(),bad);
        assertTrue(LIVE.inspect("npc","i need 3 pls","Andrew").match().isEmpty());
    }
    @Test void changedOrQuarantinedTemplateEvidenceDoesNotActivate() {
        String source=GREETING.replace("[player]","Probe_12");var context=Map.<String,Object>of("localPlayerName","Probe_12");
        var changed=fixture(List.of(entry("npc","Carpenter",GREETING,"別の訳：[player]")),List.of());
        assertTrue(changed.inspect("npc",source,"Carpenter",context).match().isEmpty());
        var blocked=fixture(List.of(entry("npc","Carpenter",GREETING,GREETING_JA)),
            List.of(Map.of("channel","npc","speaker","Carpenter","source",GREETING)));
        assertTrue(blocked.inspect("npc",source,"Carpenter",context).match().isEmpty());
        var conflict=fixture(List.of(entry("npc","Carpenter",GREETING,GREETING_JA),entry("npc","Carpenter",GREETING,"違う：[player]")),List.of());
        assertTrue(conflict.inspect("npc",source,"Carpenter",context).match().isEmpty());
        assertTrue(LIVE.inspect("npc","Words cannot describe how thankful I am, Probe_12!","Bartender",context).match().isEmpty());
        assertTrue(LIVE.inspect("npc","Hello Adventurer!","King Emmor",context).match().isEmpty());
    }
    @Test void approvedPlayerBindingRetainsAllNativeNameColorsIncludingRepeatedDigits() {
        for(String name:List.of("Probe_12","A12_B12")) {
            var source=Component.literal("Hi, ").withStyle(ChatFormatting.WHITE);
            for(int i=0;i<name.length();i++) source.append(Component.literal(name.substring(i,i+1)).withStyle(
                Character.isDigit(name.charAt(i))?(i>3?ChatFormatting.RED:ChatFormatting.AQUA):ChatFormatting.GOLD));
            source.append(Component.literal("! Welcome to the Furniture Shop.").withStyle(ChatFormatting.WHITE));
            var p=new StyledParagraph(List.of(source));var match=LIVE.inspect("npc",p.sourceText(),"Carpenter",Map.of("localPlayerName",name)).match().orElseThrow();
            var encoded=p.dictionaryEncoding(match);assertTrue(encoded.accepted(),()->encoded.reason()+encoded.details());
            var translated=p.decode(encoded.text());
            int from=source.getString().indexOf(name),to=translated.getString().indexOf(name);
            var originalColors=StyledParagraph.glyphs(source).subList(from,from+name.length()).stream().map(g->g.style().getColor()).toList();
            var translatedColors=StyledParagraph.glyphs(translated).subList(to,to+name.length()).stream().map(g->g.style().getColor()).toList();
            assertEquals(originalColors,translatedColors,name);
        }
    }
    @Test void extraGlyphLookupBindsLiveIconsWithoutChangingTheOldNormalizer() {
        assertEquals("❤ Health",DictionaryGlyphs.canonical("❤ Health"));
        assertNotEquals(DictionaryMatching.aliasKey("❤ Health"),DictionaryMatching.aliasKey("\ue010 Health"));
        String source="Fish Bait seems to give a really effective \ue00c Fishing Speed boost!";
        var angler=LIVE.inspect("npc",source,"Angler Angus");assertEquals("glyph_fallback",angler.mode());
        assertTrue(angler.match().orElseThrow().text().contains("\ue00c Fishing Speed"));
        assertTrue(LIVE.inspect("npc",source,"Geo").match().isEmpty());
        String geo="For example, adding Ruby to armor will increase its \ue010 Health, while Jasper increases \ue00d Strength.";
        var result=LIVE.inspect("npc",geo,"Geo");assertEquals("glyph_fallback",result.mode());
        assertTrue(result.match().orElseThrow().text().contains("\ue010 Health"));
        assertTrue(result.match().orElseThrow().text().contains("\ue00d Strength"));
        assertTrue(LIVE.inspect("npc",geo.replace("Ruby","Sapphire"),"Geo").match().isEmpty());
    }
    @Test void extraGlyphConflictsStayBlockedButNeverReplaceAnExistingExactTranslation() {
        var d=fixture(List.of(entry("item","","Grants +105 ❤ Health.","+105 ❤ Healthを獲得する。"),
            entry("item","","Grants +105 \ue010 Health.","\ue010 Healthが+105増加する。")),List.of());
        assertEquals("exact",d.inspect("item","Grants +105 ❤ Health.","").mode());
        assertEquals("exact",d.inspect("item","Grants +105 \ue010 Health.","").mode());
        var rejected=d.inspect("item","GRANTS +105 ❤ HEALTH.","");
        assertTrue(rejected.match().isEmpty());assertEquals("ambiguous",rejected.reason());
        var blocked=fixture(List.of(entry("item","","Grants +105 ❤ Health.","+105 ❤ Healthを獲得する。")),
            List.of(Map.of("channel","item","speaker","","source","Grants +105 \ue010 Health.")));
        assertTrue(blocked.inspect("item","GRANTS +105 \ue010 HEALTH.","").match().isEmpty());
        assertTrue(d.inspect("item","Grants +106 \ue010 Health.","").match().isEmpty());
        assertTrue(d.inspect("item","Grants +105 ❤ Love.","").match().isEmpty());
        assertEquals("▲◀▶◀▲▼▼◀▶▲",DictionaryGlyphs.canonicalFallback("▲◀▶◀▲▼▼◀▶▲"));
    }
}
