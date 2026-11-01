package local.skyblockjp;

import com.google.gson.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RewardHeadingTemplateTest {
    private static final Gson JSON = new Gson();
    private static final String SOURCE = "Level {v0} Rewards:", TARGET = "レベル{v0}の報酬:";
    private static ByteArrayInputStream stream(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    private static Map<String,Object> template(String channel,String source,String target,String type) {
        return Map.of("channel",channel,"speaker","","source",source,"target",target,
            "variables",List.of(Map.of("name","v0","type",type)),"source_ids",List.of("reviewed/ui/level_rewards"));
    }
    private static BundledDictionary dictionary(List<Map<String,Object>> templates) {
        var literal = Map.of("channel","ui","speaker","","source","Level XXXVII Rewards:",
            "target","レベルXXXVIIの報酬:","keep_original",false,
            "source_ids",List.of("observed/ui/runtime079_a9cf9140d89d72648c"));
        var dictionary = BundledDictionary.load(stream(JSON.toJson(Map.of("schema_version",1,"tier","candidate",
            "build_id","reward-heading-fixture","entries",List.of(literal)))));
        return dictionary.withMatching(stream(JSON.toJson(Map.of("schema_version",1,"build_id","reward-heading-fixture",
            "normalization","ascii_case_single_period_stat_glyphs_v1","aliases",List.of(),"templates",templates))));
    }
    private static BundledDictionary rewards() {
        var dictionary = dictionary(List.of(template("ui",SOURCE,TARGET,"roman")));
        assertTrue(dictionary.matchingStats().loaded(),dictionary.matchingStats().error());
        return dictionary;
    }

    @Test void skillRewardHeadingsPreserveCanonicalRomanValuesThroughOneHundred() {
        var dictionary = rewards();
        for (String roman : List.of("I","IV","XX","XXI","XXX","XXXV","XXXVII","XLIX","LVI","LX","XCIX","C")) {
            var lookup = dictionary.inspect("ui","Level " + roman + " Rewards:","");
            var match = lookup.match().orElseThrow();
            assertEquals("レベル" + roman + "の報酬:",match.text(),roman);
            assertEquals(roman.equals("XXXVII") ? "exact" : "template",lookup.mode(),roman);
        }
        assertEquals("レベルXXXVの報酬:",dictionary.lookup("ui","level XXXV rewards:","").orElseThrow().text());
        var literal = dictionary.lookup("ui","Level XXXVII Rewards:","").orElseThrow();
        assertEquals(List.of("observed/ui/runtime079_a9cf9140d89d72648c"),literal.sourceIds());
    }

    @Test void noncanonicalRomanNumbersPayloadsUnitsAndOtherScopesDoNotMatch() {
        var dictionary = rewards();
        for (String value : List.of("", "0", "21", "100", "101", "CI", "IC", "IIV", "IIII", "VX", "XXIIII", "MMMM", "xxi", "XxI", "Jerry", "XXI%", "XXI 5s", "XXI/XXII")) {
            assertTrue(dictionary.lookup("ui","Level " + value + " Rewards:","").isEmpty(),value);
        }
        for (String source : List.of("Level XXI Rewards", "Level XXI Reward:", "Level XXI Rewards: extra", "Next Level XXI Rewards:", "Level XXI Coin Rewards:", "[VIP] Jerry: Level XXI Rewards:")) {
            assertTrue(dictionary.lookup("ui",source,"").isEmpty(),source);
        }
        for (String channel : List.of("item","common","system","player_chat")) {
            assertTrue(dictionary.lookup(channel,"Level XXI Rewards:","").isEmpty(),channel);
        }
        assertTrue(dictionary.lookup("npc","Level XXI Rewards:","Bob").isEmpty());
    }

    @Test void largerRomansAreNotEnabledForItemCommonOrOtherUiTemplateShapes() {
        var dictionary = dictionary(List.of(template("ui",SOURCE,TARGET,"roman"),
            template("item","Permanent Haste {v0} effect.","常時Haste {v0}の効果。","roman"),
            template("common",SOURCE,TARGET,"roman"),
            template("ui","Tier {v0} upgrade rewards.","Tier {v0}の強化報酬。","roman")));
        assertTrue(dictionary.matchingStats().loaded(),dictionary.matchingStats().error());
        assertTrue(dictionary.lookup("item","Permanent Haste XX effect.","").isPresent());
        assertTrue(dictionary.lookup("item","Permanent Haste XXI effect.","").isEmpty());
        assertTrue(dictionary.lookup("item","Level XX Rewards:","").isPresent());
        assertTrue(dictionary.lookup("item","Level XXI Rewards:","").isEmpty());
        assertTrue(dictionary.lookup("ui","Tier XX upgrade rewards.","").isPresent());
        assertTrue(dictionary.lookup("ui","Tier XXI upgrade rewards.","").isEmpty());
    }

    @Test void rewardTargetAndVariableShapeAreExplicitlyWhitelisted() {
        for (var invalid : List.of(template("ui",SOURCE,"{v0} XPを得る。","roman"),
                template("ui",SOURCE,TARGET,"number"), template("ui",SOURCE,TARGET,"pet_name"))) {
            var dictionary = dictionary(List.of(invalid));
            assertFalse(dictionary.matchingStats().loaded());
            assertEquals(invalid.get("variables").toString().contains("pet_name") ? "unreviewed_ui_template" : "unreviewed_ui_reward_template",dictionary.matchingStats().error());
            assertEquals("レベルXXXVIIの報酬:",dictionary.lookup("ui","Level XXXVII Rewards:","").orElseThrow().text());
        }
    }

    @Test void fourRecordedNativeRewardHeadingsMatchAndRenderWithTheirSourceStyles() throws Exception {
        var dictionary = rewards();
        try (var input = getClass().getResourceAsStream("/native-reward-headings-0.7.9.json")) {
            assertNotNull(input);
            var rows = JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("cases");
            assertEquals(4,rows.size());
            for (var raw : rows) {
                var row = raw.getAsJsonObject();
                var source = Component.empty();
                for (var value : row.getAsJsonArray("runs")) {
                    var run = value.getAsJsonObject();
                    var style = Style.EMPTY.withColor(run.get("color").getAsInt()).withBold(run.get("bold").getAsBoolean()).withItalic(run.get("italic").getAsBoolean());
                    source.append(Component.literal(run.get("text").getAsString()).setStyle(style));
                }
                assertEquals(row.get("source").getAsString(),source.getString());
                var match = dictionary.lookup("ui",source.getString(),"").orElseThrow();
                var paragraph = new StyledParagraph(List.of(source));
                var encoded = paragraph.dictionaryEncoding(match);
                assertTrue(encoded.accepted(),source.getString() + ": " + encoded.reason());
                var rendered = paragraph.decode(encoded.text());
                assertEquals(row.get("target").getAsString(),rendered.getString());
                assertTrue(StyledParagraph.glyphs(rendered).stream().allMatch(g -> g.style().getColor().getValue() == 0xAAAAAA));
            }
        }
    }

    @Test void boundRomanHeadingValueKeepsItsOwnHighlightColor() {
        var source = Component.empty().append(Component.literal("Level ").setStyle(Style.EMPTY.withColor(0xAAAAAA)))
            .append(Component.literal("XXXV").setStyle(Style.EMPTY.withColor(0xFFAA00)))
            .append(Component.literal(" Rewards:").setStyle(Style.EMPTY.withColor(0xAAAAAA)));
        var paragraph = new StyledParagraph(List.of(source));
        var match = rewards().lookup("ui",source.getString(),"").orElseThrow();
        assertEquals(1,match.bindings().size());
        assertEquals("roman",match.bindings().getFirst().type());
        var encoded = paragraph.dictionaryEncoding(match);
        assertTrue(encoded.accepted(),encoded.reason());
        var rendered = paragraph.decode(encoded.text());
        assertEquals("レベルXXXVの報酬:",rendered.getString());
        int offset = 0;
        for (var glyph : StyledParagraph.glyphs(rendered)) {
            assertEquals(offset >= 3 && offset < 7 ? 0xFFAA00 : 0xAAAAAA,glyph.style().getColor().getValue());
            offset += glyph.text().length();
        }
    }
}
