package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

class NpcPhoneLineRenderingTest {
    private JsonObject fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/npc-phone-lines-0.7.9.json")) {
            assertNotNull(input);
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    @Test
    void allEighteenEligiblePhoneLiteralsRetainTheirAdoptedBareTranslations() throws Exception {
        var dictionary = BundledDictionary.loadBundled();
        var rows = fixture().getAsJsonArray("cases");
        assertEquals(18, rows.size());
        for (var raw : rows) {
            var row = raw.getAsJsonObject();
            String source = row.get("source").getAsString(),
                    target = row.get("target").getAsString(),
                    speaker = row.get("speaker").getAsString();
            var match = dictionary.lookup("npc", source, speaker).orElseThrow();
            assertEquals(target, match.text(), source);
            assertTrue(match.sourceIds().contains(row.get("id").getAsString()), source);
            var bare = dictionary.lookup("npc", source.substring(2), speaker).orElseThrow();
            assertEquals(target.substring(2), bare.text());
            var paragraph =
                    new StyledParagraph(
                            List.of(
                                    Component.literal(source)
                                            .setStyle(Style.EMPTY.withColor(0xFFFFFF))));
            var encoded = paragraph.dictionaryEncoding(match);
            assertTrue(encoded.accepted(), source + ": " + encoded.reason());
            assertEquals(target, paragraph.decode(encoded.text()).getString());
        }
    }

    @Test
    void actualDeanPhonePrefixKeepsCyanIconAndWhiteBody() throws Exception {
        var nativeCase = fixture().getAsJsonObject("native");
        var source = Component.empty();
        for (var raw : nativeCase.getAsJsonArray("runs")) {
            var run = raw.getAsJsonObject();
            source.append(
                    Component.literal(run.get("text").getAsString())
                            .setStyle(Style.EMPTY.withColor(run.get("color").getAsInt())));
        }
        assertEquals(nativeCase.get("source").getAsString(), source.getString());
        var match =
                BundledDictionary.loadBundled()
                        .lookup("npc", source.getString(), "[NPC] Dean")
                        .orElseThrow();
        var paragraph = new StyledParagraph(List.of(source));
        var encoded = paragraph.dictionaryEncoding(match);
        assertTrue(encoded.accepted(), encoded.reason());
        var rendered = paragraph.decode(encoded.text());
        assertEquals(match.text(), rendered.getString());
        var glyphs = StyledParagraph.glyphs(rendered);
        assertEquals("✆", glyphs.getFirst().text());
        assertEquals(0x55FFFF, glyphs.getFirst().style().getColor().getValue());
        assertTrue(
                glyphs.stream().skip(1).allMatch(g -> g.style().getColor().getValue() == 0xFFFFFF));
    }

    @Test
    void noGlobalPhonePrefixStrippingOrCrossSpeakerFallbackIsEnabled() {
        var dictionary = BundledDictionary.loadBundled();
        String body =
                "If left unchecked, it is possible that the powers of this creature will grow.";
        assertTrue(dictionary.lookup("npc", "✆ " + body, "Bob").isEmpty());
        assertTrue(dictionary.lookup("npc", "☎ " + body, "Dean").isEmpty());
        assertTrue(dictionary.lookup("npc", "✆ ✆ " + body, "Dean").isEmpty());
        assertTrue(
                dictionary
                        .lookup("npc", "✆ Unknown conversation with a player.", "Dean")
                        .isEmpty());
        assertTrue(dictionary.lookup("ui", "✆ " + body, "").isEmpty());
        assertTrue(dictionary.lookup("item", "✆ " + body, "").isEmpty());
        assertTrue(dictionary.lookup("player_chat", "✆ " + body, "").isEmpty());
    }
}
