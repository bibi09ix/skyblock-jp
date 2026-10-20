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
class PetNpcLiveTest {
    @TempDir Path dir;
    @Test void reviewLatestNpcAndPetProblems() throws Exception {
        var config = Config.load(Path.of(System.getenv("SKYBLOCK_JP_PET_LIVE")));
        String[][] examples = {
            {"NPC", "Torrhus Canyon", "If you want to check it out for yourself, you can access it from the §aLaunch Pad §fover in the §2Moonglade Marsh§f."},
            {"NPC", "Lumber Jack", "Have you enchanted your axe with §aEfficiency V§f? It'll help ya break stuff a lot faster."},
            {"ITEM", "Enderman Zealot Madness", "Increases your odds to find a special Zealot by 25%."},
            {"ITEM", "Enderman Teleport Savvy", "Buffs Transmission abilities, granting 50 weapon damage for 5s on use"},
            {"ITEM", "Endermite Mite Bait", "Gain a 3% chance to dig up a bonus Nest Endermite per +1 Pet Luck (Stacks above 100%)"},
            {"ITEM", "Enderman Bubblegum", "Your pet fuses its power with placed Deployables to give them 2x duration."},
            {"ITEM", "Tarantula Webbed Cells", "Tarantula Broodfather's Vitality reduction is 21.6% less effective against you."},
            {"ITEM", "Endermite Combat Exp Boost", "Gives +20% pet exp for Combat."}
        };
        var results = new ArrayList<Map<String,String>>();
        try (var t = new Translator(config, dir.resolve("cache.json"), LoggerFactory.getLogger("pet-live"))) {
            for (var e : examples) {
                if (!results.isEmpty()) Thread.sleep(11000);
                boolean npc = e[0].equals("NPC");
                var p = npc ? NpcTranslation.paragraph(Component.literal("[NPC] " + e[1] + ": " + e[2])) : NpcTranslation.whole(Component.literal(e[2]));
                String response = (npc ? t.requestNpc(p, e[1]) : t.request(p.encoded(), e[1])).get(120, TimeUnit.SECONDS);
                assertNotNull(response, e[1] + ": " + t.lastError());
                String text = p.decode(response).getString();
                results.add(Map.of("name", e[1], "source", LoreParser.clean(e[2]), "translation", text));
                assertFalse(text.startsWith("If ")); assertFalse(text.startsWith("Have "));
                assertFalse(text.contains("上がるする")); assertFalse(text.contains("reduction"));
                if (e[1].contains("Teleport Savvy")) assertTrue(text.contains("Transmission abilities"));
                if (e[1].contains("Mite Bait")) assertTrue(text.matches(".*(?:超|越|上回).*"));
            }
        } finally {
            Files.writeString(Path.of(System.getenv("SKYBLOCK_JP_PET_OUTPUT")), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        }
        assertEquals(8, results.size());
    }
}
