package local.skyblockjp;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class TooltipPlanIntegrationTest {
    @TempDir Path dir;
    private static final String A="Hardened Diamond Helmet Recipe",B="Hardened Diamond Boots Recipe";
    private static final List<Component> SOURCE=List.of(Component.literal("Collection Reward"),Component.literal("Rewards:"),Component.literal(A),Component.literal(B));
    private static TooltipContext.Decision context(String menu,boolean matches) {
        return new TooltipContext.Decision(TooltipContext.Kind.UI,"fixture",Map.of("containerTitle",menu,"location","CONTAINER","slotMatches",matches));
    }
    private static LoreParser.Analysis analysis(TooltipContext.Decision c) {return LoreParser.analyze(SOURCE.stream().map(Component::getString).toList(),c);}
    private static BundledDictionary dictionary(boolean whole,boolean changedName) {
        var rows=new ArrayList<Map<String,Object>>();
        for(String source:List.of(A,B))rows.add(Map.of("channel","ui","speaker","","source",source,
            "target",changedName?"Different Itemのレシピ":source.substring(0,source.length()-7)+"のレシピ","keep_original",false,"source_ids",List.of("fixture")));
        if(whole)rows.add(Map.of("channel","ui","speaker","","source",A+" "+B,"target",A+"と"+B+"。","keep_original",false,"source_ids",List.of("fixture")));
        var raw=new Gson().toJson(Map.of("schema_version",1,"tier","candidate","build_id","partition-fixture","entries",rows));
        return BundledDictionary.load(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
    private Translator service(Config config,boolean whole,boolean changedName,Translator.Transport transport) {
        return new Translator(config,dir.resolve("cache.json"),LoggerFactory.getLogger("partition"),transport,dictionary(whole,changedName));
    }
    private static Config config(){var c=new Config();c.candidateDictionary=true;c.dictionaryAutoDisplay=true;c.apiTranslation=false;return c;}
    private static long recipeRows(TooltipPlan.Result p){return p.analysis().paragraphs().stream().filter(x->x.role().equals("RECIPE_LIST_ENTRY")).count();}
    @Test void independentRecipesReachTheRealRendererWithoutChangingSourceCoverage() {
        var c=context("Mining Collection",true);var config=config();
        try(var t=service(config,false,false,s->{fail("No API");return null;})) {
            var p=TooltipPlan.prepare(SOURCE,analysis(c),c,t,false);assertEquals(2,recipeRows(p));
            var index=new IdentityHashMap<StyledParagraph,Integer>();for(int i=0;i<p.paragraphs().size();i++)index.put(p.paragraphs().get(i),i);
            var rendered=ItemTooltipTranslation.prepare(SOURCE,p.blocks(),p.paragraphs(),config,false,x->p.decisions().get(index.get(x)).encoded(),x->null,List::of,x->false);
            assertEquals(2,rendered.dictionaryMatches());assertEquals("Hardened Diamond Helmetのレシピ",rendered.lines().get(2).getString());
            assertEquals("Hardened Diamond Bootsのレシピ",rendered.lines().get(3).getString());
            assertEquals("Rewards:",rendered.lines().get(1).getString());
            for(int i=0;i<p.analysis().lines().size();i++)assertEquals(i,p.analysis().lines().get(i).index());
        }
    }
    @Test void anExistingWholeTranslationAlwaysWinsOverTheOptionalPartition() {
        var c=context("Mining Collection",true);
        try(var t=service(config(),true,false,s->{fail("No API");return null;})) {
            var p=TooltipPlan.prepare(SOURCE,analysis(c),c,t,false);assertEquals(0,recipeRows(p));
            assertTrue(p.decisions().stream().anyMatch(Translator.DictionaryDecision::accepted));
            assertTrue(p.blocks().stream().anyMatch(b->b.start()==2&&b.end()==4));
        }
    }
    @Test void wrongMenuUnknownSlotAndDifferentItemNamesNeverLicenseThePartition() {
        for(var c:List.of(context("Storage",true),context("Mining Collection",false)))
            try(var t=service(config(),false,false,s->{fail("No API");return null;})) {assertEquals(0,recipeRows(TooltipPlan.prepare(SOURCE,analysis(c),c,t,false)));}
        var c=context("Mining Collection",true);
        try(var t=service(config(),false,true,s->{fail("No API");return null;})) {assertEquals(0,recipeRows(TooltipPlan.prepare(SOURCE,analysis(c),c,t,false)));}
    }
    @Test void aPendingForcedApiRefreshCannotChangeTheIntrinsicPartition()throws Exception {
        var c=context("Mining Collection",true);var config=config();config.apiTranslation=true;
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var t=service(config,false,false,s->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return s;})) {
            var initial=TooltipPlan.prepare(SOURCE,analysis(c),c,t,true);assertEquals(2,recipeRows(initial));
            int child=0;while(!initial.analysis().paragraphs().get(child).role().equals("RECIPE_LIST_ENTRY"))child++;
            var future=t.retranslate(initial.paragraphs().get(child),"scope=ui; partition fixture",initial.contexts().get(child));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));var pending=TooltipPlan.prepare(SOURCE,analysis(c),c,t,true);
                assertEquals(initial.blocks(),pending.blocks());assertEquals(2,recipeRows(pending));
                assertEquals("API_RETRANSLATION_PRIORITY",pending.decisions().get(child).reason());
            } finally {release.countDown();}
            assertNotNull(future.get(5,TimeUnit.SECONDS));
        } finally {release.countDown();}
    }
    @Test void cachedRowClassificationIncludesTheInstructionReason()throws Exception {
        var type=Class.forName("local.skyblockjp.SkyBlockJp$ParseKey");
        var constructor=type.getDeclaredConstructor(TooltipContext.Kind.class,String.class,List.class);constructor.setAccessible(true);
        List<String> lines=List.of("Storage unlocked at tier IV");
        var ordinary=constructor.newInstance(TooltipContext.Kind.UI,"STRUCTURED_MENU",lines);
        var instruction=constructor.newInstance(TooltipContext.Kind.UI,"MINION_LOCKED_SLOT_INSTRUCTION",lines);
        var cache=new HashMap<Object,String>();cache.put(ordinary,"title");cache.put(instruction,"description");
        assertEquals(2,cache.size());assertEquals("description",cache.get(instruction));
    }
    @Test void duplicateSubmissionIdentityUsesTheSameRoleContextAsTheActualApiCache()throws Exception {
        var config=config();config.apiTranslation=true;config.provider="GOOGLE";
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var p=new StyledParagraph(List.of(Component.literal("A repeated explanation.")));
        var a=Map.<String,Object>of("paragraphRole","DESCRIPTION","location","CONTAINER");
        var b=Map.<String,Object>of("paragraphRole","UI_TITLE","location","CONTAINER");
        try(var t=service(config,false,false,s->{calls.incrementAndGet();return "説明です。";})) {
            var identities=new HashSet<String>();assertTrue(identities.add(t.requestIdentity(p,"ui",a)));
            assertTrue(identities.add(t.requestIdentity(p,"ui",b)));
            assertFalse(identities.add(t.requestIdentity(p,"ui",new TreeMap<>(a))));
            assertNotNull(t.request(p,"scope=ui; first",a).get(5,TimeUnit.SECONDS));
            assertNotNull(t.request(p,"scope=ui; second",b).get(5,TimeUnit.SECONDS));
            assertEquals(2,calls.get());
            assertEquals("API_CACHE",t.cacheInspection(p.encoded(),"ui",a).status());
            assertEquals("API_CACHE",t.cacheInspection(p.encoded(),"ui",b).status());
            assertNotNull(t.request(p,"scope=ui; first again",a).get(5,TimeUnit.SECONDS));assertEquals(2,calls.get());
        }
    }
}
