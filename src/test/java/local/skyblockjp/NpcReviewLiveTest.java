package local.skyblockjp;
import com.google.gson.*;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SKYBLOCK_JP_NPC_LIVE", matches=".+")
class NpcReviewLiveTest {
    @TempDir Path dir;
    @Test void checksObservedNpcFailures() throws Exception {
        var config = Config.load(Path.of(System.getenv("SKYBLOCK_JP_NPC_LIVE")));
        var results = new ArrayList<Map<String,String>>();
        try (var in = getClass().getResourceAsStream("/npc-review-fixtures.json");
             var t = new Translator(config, dir.resolve("cache.json"), LoggerFactory.getLogger("npc-live"))) {
            for (var entry : JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray()) {
                var e = entry.getAsJsonObject(); String speaker = e.get("speaker").getAsString(), source = e.get("source").getAsString();
                if (!(speaker.equals("Kat") || source.contains("How do you like") || source.contains("Do you want to change") || source.equals("Great!")
                    || source.contains("I know people") || source.contains("a century ago") || source.contains("Wait, is it?") || source.contains("someone needs to"))) continue;
                if (!results.isEmpty()) Thread.sleep(7000);
                var p = NpcTranslation.paragraph(Component.literal("[NPC] " + speaker + ": " + source));
                String result = t.requestNpc(p, speaker).get(120, TimeUnit.SECONDS);
                assertNotNull(result, speaker + ": " + t.lastError());
                assertNull(NpcTranslation.validate(p.encoded(), result));
                String decoded = p.decode(result).getString();
                results.add(Map.of("speaker", speaker, "source", source, "translation", decoded));
                if (source.contains("was able to upgrade")) assertTrue(decoded.matches("(?s).*(?:できた|した|しました|できました).*"), decoded);
                if (source.contains("I know people")) assertTrue(decoded.matches(".*(?:顔が広|人脈|知り合い).*"), decoded);
            }
        } finally {
            Files.writeString(Path.of(System.getenv("SKYBLOCK_JP_NPC_OUTPUT")), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        }
        assertEquals(10, results.size());
    }
}
