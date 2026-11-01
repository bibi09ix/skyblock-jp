package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@EnabledIfEnvironmentVariable(named = "SKYBLOCK_JP_OCT_LIVE", matches = ".+")
class OctoberLiveTest {
    @TempDir Path dir;

    @Test
    void verifiesNewChatAndRecentTranslationProblems() throws Exception {
        var config = Config.load(Path.of(System.getenv("SKYBLOCK_JP_OCT_LIVE")));
        var results = new ArrayList<Map<String, String>>();
        String[][] examples = {
            {
                "Torrhus Canyon",
                "The second island of §2Galatea §f- §6Torrhus Canyon§f, is here!",
                ""
            },
            {
                "Torrhus Canyon",
                "Between §bMiria’s Contests§f, the §2Critter Safari§f, §6Torrhus Sea Creatures§f, and the new §aCollections§f, there's so much to do!",
                ""
            },
            {
                "Torrhus Canyon",
                "If you want to check it out for yourself, you can access it from the §aLaunch Pad §fover in the §2Moonglade Marsh§f.",
                ""
            },
            {"Farmhand", "Did you craft a §aWheat Minion§f yet? Here's the recipe!", ""},
            {
                "Seffea",
                "I wonder if she will notice if one goes missing...",
                "I love Rhanora's cats, but she wont let me play with any of them!"
            },
            {"Arba", "This is absurd!", ""},
            {"CHAT", "[MVP+] Player123: Can someone help me find the bank?", ""},
            {
                "ITEM",
                "There are exactly 33,333 Chocobit Rabbits, each one labeled #1 to #33333!",
                ""
            },
            {
                "ITEM",
                "The more unique Chocolate Rabbits that you find, the more your Chocolate Factory will produce!",
                ""
            }
        };
        try (var t =
                new Translator(
                        config, dir.resolve("cache.json"), LoggerFactory.getLogger("oct-live"))) {
            for (var e : examples) {
                if (!results.isEmpty()) Thread.sleep(9000);
                boolean chat = e[0].equals("CHAT"), item = e[0].equals("ITEM");
                var p =
                        chat || item
                                ? NpcTranslation.whole(Component.literal(e[1]))
                                : NpcTranslation.paragraph(
                                        Component.literal("[NPC] " + e[0] + ": " + e[1]));
                String response =
                        (chat
                                        ? t.requestChat(p, e[2])
                                        : item
                                                ? t.request(p.encoded(), "October item review")
                                                : t.requestNpc(p, e[0], e[2]))
                                .get(120, TimeUnit.SECONDS);
                assertNotNull(response, e[0] + ": " + t.lastError());
                String text = p.decode(response).getString();
                results.add(
                        Map.of(
                                "speaker",
                                e[0],
                                "source",
                                e[1],
                                "previous",
                                e[2],
                                "translation",
                                text));
                if (e[1].contains("second island")) assertTrue(text.contains("2"));
                if (e[0].equals("Seffea")) {
                    assertFalse(text.contains("誰か"));
                    assertTrue(text.matches(".*(?:1匹|一匹|猫).*"));
                }
                if (chat) assertTrue(text.contains("Player123"));
                if (e[1].contains("#33333")) {
                    assertTrue(text.contains("#1"));
                    assertTrue(text.contains("33333"));
                }
                assertFalse(text.contains("over in the"));
                assertFalse(text.contains("The second island"));
            }
        } finally {
            Files.writeString(
                    Path.of(System.getenv("SKYBLOCK_JP_OCT_OUTPUT")),
                    new GsonBuilder().setPrettyPrinting().create().toJson(results));
        }
        assertEquals(9, results.size());
    }
}
