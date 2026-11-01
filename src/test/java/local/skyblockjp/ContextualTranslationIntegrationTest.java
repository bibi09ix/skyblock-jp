package local.skyblockjp;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class ContextualTranslationIntegrationTest {
    @TempDir Path dir;
    @Test void identicalWordsInDifferentScreensHaveIndependentApiStateAndForceRefresh() throws Exception {
        var c=new Config();c.candidateDictionary=false;var calls=new AtomicInteger();
        var p=new StyledParagraph(List.of(Component.literal("Hello there!")));
        var a=Map.<String,Object>of("containerTitle","First screen","slotIndex",1);
        var b=Map.<String,Object>of("containerTitle","Second screen","slotIndex",1);
        try(var t=new Translator(c,dir.resolve("cache.json"),LoggerFactory.getLogger("context"),text->switch(calls.incrementAndGet()){case 1->"最初の訳。";case 2->"別の画面の訳。";default->"再翻訳した文。";})) {
            assertEquals("最初の訳。",t.request(p,"scope=ui; test",a).get(5,TimeUnit.SECONDS));
            assertEquals("別の画面の訳。",t.request(p,"scope=ui; test",b).get(5,TimeUnit.SECONDS));
            assertEquals("最初の訳。",t.cached(p,"ui",a));
            assertEquals("別の画面の訳。",t.cached(p,"ui",b));
            assertEquals("再翻訳した文。",t.retranslate(p,"scope=ui; test",a).get(5,TimeUnit.SECONDS));
            assertEquals("別の画面の訳。",t.cached(p,"ui",b));
            assertEquals(3,calls.get());
        }
    }
    @Test void mapOrderCannotCreateDifferentRequestsAndSeparatorTextCannotCreateCollisions() throws Exception {
        var c=new Config();var calls=new AtomicInteger();var p=new StyledParagraph(List.of(Component.literal("Hello there!")));
        var a=new LinkedHashMap<String,Object>();a.put("containerTitle","A|slotIndex=1");a.put("slotIndex",2);
        var reordered=new LinkedHashMap<String,Object>();reordered.put("slotIndex",2);reordered.put("containerTitle","A|slotIndex=1");
        var other=Map.<String,Object>of("containerTitle","A","slotIndex","1|slotIndex=2");
        try(var t=new Translator(c,dir.resolve("cache.json"),LoggerFactory.getLogger("context"),text->{calls.incrementAndGet();return "こんにちは。";})) {
            t.request(p,"scope=ui; test",a).get(5,TimeUnit.SECONDS);
            t.request(p,"scope=ui; test",reordered).get(5,TimeUnit.SECONDS);
            assertEquals(1,calls.get());
            t.request(p,"scope=ui; test",other).get(5,TimeUnit.SECONDS);
            assertEquals(2,calls.get());
        }
    }
    @Test void sameTextAcrossMenusDoesNotInheritManualTooltipSelection() {
        var p=new StyledParagraph(List.of(Component.literal("Hello there!")));
        var a=new TooltipContext.Decision(TooltipContext.Kind.UI,"test",Map.of("containerTitle","A"));
        var b=new TooltipContext.Decision(TooltipContext.Kind.UI,"test",Map.of("containerTitle","B"));
        assertNotEquals(TooltipTranslationKey.of(a,"General",List.of("ui"),List.of(p)),TooltipTranslationKey.of(b,"General",List.of("ui"),List.of(p)));
    }
    @Test void oldConfigGetsSafeReviewedOnlyFallbackAndTogglePersists() throws Exception {
        var path=dir.resolve("config.json");java.nio.file.Files.writeString(path,"{\"apiTranslation\":false,\"candidateDictionary\":true}");
        var c=Config.load(path);assertTrue(c.dictionaryPlainFallback);assertFalse(c.apiTranslation);assertTrue(c.candidateDictionary);
        c.dictionaryPlainFallback=false;Config.save(path,c);assertFalse(Config.load(path).dictionaryPlainFallback);
    }
}
