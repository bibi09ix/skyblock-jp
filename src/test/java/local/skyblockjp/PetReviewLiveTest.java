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

@EnabledIfEnvironmentVariable(named="SKYBLOCK_JP_PET_LIVE", matches=".+")
class PetReviewLiveTest {
    @TempDir Path dir;
    @Test void verifiesPetAndSplitSentenceFixes() throws Exception {
        var config = Config.load(Path.of(System.getenv("SKYBLOCK_JP_PET_LIVE")));
        var results = new ArrayList<Map<String,String>>();
        try (var translator = new Translator(config, dir.resolve("cache.json"), LoggerFactory.getLogger("pet-review"))) {
            for (var item : PetReviewTest.fixtures().entrySet()) {
                for (var block : LoreParser.parse(item.getValue())) {
                    String source = block.text();
                    if (!(source.contains("Spirit Boots") || source.endsWith("special Zealot by 25%.")
                        || source.equals("Reduces the mana cost of abilities by 16.4%.")
                        || source.equals("Increases the chance to catch COMMON Sea Creatures by 25%.")
                        || (item.getKey().equals("Super Compactor 3000") && source.startsWith("This item")))) continue;
                    if (!results.isEmpty()) Thread.sleep(15000);
                    var p = new StyledParagraph(item.getValue().subList(block.start(), block.end()).stream().map(s -> (Component)Component.literal(s)).toList());
                    String translated = translator.request(p.encoded(), "pet review=" + item.getKey()).get(120, TimeUnit.SECONDS);
                    assertNotNull(translated, translator.error(p.encoded()));
                    assertNull(TranslationDiagnostics.validate(p.encoded(), translated));
                    String decoded = p.decode(translated).getString();
                    results.add(Map.of("item", item.getKey(), "source", source, "translation", decoded));
                    if (source.contains("Spirit Boots")) for (String name : List.of("Spider", "Tarantula", "Primordial", "Spirit Boots")) assertTrue(decoded.contains(name));
                    if (source.contains("16.4%")) assertTrue(decoded.contains("16.4%"));
                    if (source.contains("special Zealot")) assertTrue(decoded.contains("special Zealot"));
                }
            }
        } finally {
            Files.writeString(Path.of(System.getenv("SKYBLOCK_JP_PET_OUTPUT")), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        }
        assertEquals(5, results.size());
    }
}
