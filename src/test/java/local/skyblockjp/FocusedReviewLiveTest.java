package local.skyblockjp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="SKYBLOCK_JP_REVIEW_LIVE", matches=".+")
class FocusedReviewLiveTest {
    @TempDir Path dir;
    @Test void finalCombatAndLoggingWording() throws Exception {
        var config = Config.load(Path.of(System.getenv("SKYBLOCK_JP_REVIEW_LIVE")));
        try (var t = new Translator(config, dir.resolve("cache.json"), LoggerFactory.getLogger("focused-live"))) {
            for (String source : List.of("Can be cast after landing 3 hits. Shoot a beam, penetrating up to 5 enemies. The beam always crits.",
                "Gains 1 Sweep every 2,000 Logs cut on Galatea. (max 20)")) {
                var paragraph = new StyledParagraph(List.of(Component.literal(source)));
                String result = t.request(paragraph.encoded(), "final review").get(90, TimeUnit.SECONDS);
                assertNotNull(result);
                String decoded = paragraph.decode(result).getString();
                assertFalse(decoded.matches(".*(?:着地|landing|成功で|Logs|増加できる).*"));
                if (source.contains("landing")) assertTrue(decoded.matches(".*(?:命中|当て).*"));
                System.out.println(decoded);
                Thread.sleep(5000);
            }
        }
    }
}
