package local.skyblockjp;
import com.google.gson.GsonBuilder;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SKYBLOCK_JP_CHAT_LIVE", matches=".+")
class ChatLiveTest {
    @TempDir Path dir;
    @Test void verifyCommandArgumentsStayUntranslated() throws Exception {
        var config = Config.load(Path.of(System.getenv("SKYBLOCK_JP_CHAT_LIVE")));
        String source = "[382] ✿ §b[MVP§f+§b] GHOST_GAMER_69§f: lowballing 3b /visit me 100m+";
        try (var t = new Translator(config, dir.resolve("cache.json"), LoggerFactory.getLogger("chat-live"))) {
            var p = NpcTranslation.whole(Component.literal(source));
            String response = t.requestChat(p, "").get(120, TimeUnit.SECONDS);
            assertNotNull(response, t.lastError()); String text = p.decode(response).getString();
            assertTrue(text.contains("/visit me")); assertFalse(text.contains("低く見積もって"));
            var path = Path.of(System.getenv("SKYBLOCK_JP_CHAT_OUTPUT"));
            var results = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonArray();
            results.get(3).getAsJsonObject().addProperty("translation", text);
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(results));
        }
    }
    @Test void reviewActualChatFailures() throws Exception {
        var config = Config.load(Path.of(System.getenv("SKYBLOCK_JP_CHAT_LIVE")));
        var examples = List.of(
            "[260] ⚔ §b[MVP§f+§b] ManSui§f: cqwl u want to buy ti",
            "[384] ꕁ §b[MVP§c+§b] ForVictory2017§f: lf mythic i have fleece p me",
            "[402] ᛃ §b[MVP§c+§b] TwoSwitch73§f: lf mith diana i have l5 and bs",
            "[382] ✿ §b[MVP§f+§b] GHOST_GAMER_69§f: lowballing 3b /visit me 100m+");
        var results = new ArrayList<Map<String,String>>();
        try (var t = new Translator(config, dir.resolve("cache.json"), LoggerFactory.getLogger("chat-live"))) {
            for (var source : examples) {
                if (!results.isEmpty()) Thread.sleep(9000);
                var p = NpcTranslation.whole(Component.literal(source));
                String response = t.requestChat(p, "").get(120, TimeUnit.SECONDS);
                assertNotNull(response, t.lastError());
                String text = p.decode(response).getString();
                results.add(Map.of("source", LoreParser.clean(source), "translation", text));
                assertFalse(text.startsWith("{"));
                if (source.contains("ManSui")) { assertTrue(text.contains("cqwl")); assertTrue(text.contains("ti")); assertFalse(text.contains("cqwl u")); }
                if (source.contains("TwoSwitch")) { assertTrue(text.contains("TwoSwitch73")); assertTrue(text.contains("l5")); assertTrue(text.contains("bs")); }
                assertFalse(text.contains("lf ")); assertFalse(text.contains("低く見積もって"));
            }
        } finally {
            Files.writeString(Path.of(System.getenv("SKYBLOCK_JP_CHAT_OUTPUT")), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        }
        assertEquals(4, results.size());
    }
}
