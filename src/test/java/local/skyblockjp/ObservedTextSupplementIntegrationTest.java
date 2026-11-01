package local.skyblockjp;

import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ObservedTextSupplementIntegrationTest {
    @Test void everyReviewedSupplementReachesThePackagedDictionaryAndRenders() throws Exception {
        var dictionary = BundledDictionary.loadBundled();
        assertTrue(dictionary.stats().loaded(), dictionary.stats().error());
        assertEquals(0, dictionary.stats().rejectedEntries(), dictionary.stats().rejectionReasons().toString());
        var rows = JsonParser.parseString(Files.readString(Path.of("scripts/dictionary-observed-text.json")))
            .getAsJsonObject().getAsJsonArray("entries");
        assertTrue(rows.size() > 400);
        for (var value : rows) {
            var row = value.getAsJsonObject();
            if (!row.get("approved").getAsBoolean()) continue;
            String id = row.get("id").getAsString(), source = row.get("en").getAsString();
            String channel = row.get("channel").getAsString(), speaker = row.get("speaker").getAsString();
            var found = dictionary.inspect(channel, source, speaker);
            assertTrue(found.match().isPresent(), id + ": " + found.reason());
            var match = found.match().orElseThrow();
            assertEquals(row.get("ja").getAsString(), match.text(), id);
            assertTrue(match.sourceIds().contains(id), id + ": " + match.sourceIds());
            var paragraph = new StyledParagraph(List.of(Component.literal(source).withStyle(ChatFormatting.GRAY)));
            var rendered = paragraph.dictionaryEncoding(match);
            assertTrue(rendered.accepted(), id + ": " + rendered.reason() + " " + rendered.details());
            String expectedDisplay = match.text();
            if (source.equals("Consume to boost your ☯ Fishing Wisdom for 60m.")
                || source.equals("Consume to boost your \ue003 Intelligence for 60m."))
                expectedDisplay = expectedDisplay.replace("60m", "60分");
            assertEquals(expectedDisplay, paragraph.decode(rendered.text()).getString(), id);
            assertTrue(dictionary.lookup("player_chat", source, speaker).isEmpty(), id);
            if (channel.equals("npc")) {
                assertTrue(dictionary.lookup("npc", source, "Unobserved NPC").isEmpty(), id);
                assertTrue(dictionary.lookup("ui", source, "").stream().noneMatch(m -> m.sourceIds().contains(id)), id);
            }
        }
    }
}
