package local.skyblockjp;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class CachedNpcRepairTest {
    @TempDir Path dir;
    @Test void repairsPreviouslySavedRiddleWithoutAnotherApiRequest() throws Exception {
        Config config = new Config(); config.provider = "GOOGLE";
        String source = "They are both lying, the reward is in my chest!";
        Path file = dir.resolve("cache.json");
        var log = LoggerFactory.getLogger("cached-npc-test");
        try (var t = new Translator(config, file, log, text -> "二人とも嘘だ、報酬は私のチェストの中にある！")) {
            t.requestNpc(source, "[NPC] Virginia").get(3, TimeUnit.SECONDS);
        }
        var oldCache = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(1, oldCache.size());
        String key = oldCache.keySet().iterator().next();
        oldCache.addProperty(key, "二人とも嘘だ、報酬は私の胸の中にある！");
        Files.writeString(file, oldCache.toString());
        AtomicInteger calls = new AtomicInteger();
        try (var t = new Translator(config, file, log, text -> { calls.incrementAndGet(); return text; })) {
            assertEquals("二人とも嘘だ、報酬は私のチェストの中にある！", t.requestNpc(source, "[NPC] Virginia").join());
            assertEquals(0, calls.get());
        }
        assertEquals("二人とも嘘だ、報酬は私のチェストの中にある！",
            JsonParser.parseString(Files.readString(file)).getAsJsonObject().get(key).getAsString());
    }
}
