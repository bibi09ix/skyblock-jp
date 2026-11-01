package local.skyblockjp;
import com.google.gson.*;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LatestReviewRegressionTest {
    private String corrected(String text) {
        var p = new StyledParagraph(List.of(Component.literal(text)));
        return p.decodeForDisplay(TranslationRepair.repair(p.encoded(), "不完全な訳文。")).getString();
    }
    @Test void restoresBalLocationAndKillCondition() {
        assertEquals("Magma Fieldsにいる間、Pristineが+3増加する。", corrected("Grants +3 Pristine while in the Magma Fields."));
        String result = corrected("While in the Crystal Hollows, killing mobs reduces your Heat by 4.");
        // The real source includes its Heat icon. Both grammars should preserve the condition.
        assertTrue(result.contains("モブを倒すと"), result);
        assertTrue(result.contains("Heatが4減少"), result);
    }
    @Test void guardianKeepsRecoveryIncreaseAndWaterCondition() {
        assertEquals("manaの回復量が30%増加し、水辺または水中ではこの効果が2倍になる。", corrected("Regenerate 30% extra mana, doubled when near or in water"));
        assertNull(TranslationDiagnostics.validate("doubled", "2倍になる"));
    }
    @Test void guardianLaserHasJapaneseUnitsWithoutDoubleMultiplier() {
        assertEquals("3秒ごとに、Intelligenceの20倍のダメージを敵に与える。", corrected("Zaps your enemies for 20x your  Intelligence every 3s."));
    }
    @Test void realColoredHeatSourceKeepsActionAndValues() {
        String source = "While in the <t0>Crystal Hollows</t0>, killing mobs reduces your <n0/><s0> Heat</s0> by <n1/>.";
        String corrected = TranslationRepair.repair(source, "<t0>Crystal Hollows</t0>で<n0/>Heatを<n1/>軽減する。");
        assertTrue(corrected.contains("モブを倒すと"));
        assertTrue(corrected.contains("<n0/><s0>Heat</s0>"));
        assertNull(TranslationDiagnostics.validate(source, corrected));
    }
    @Test void farmingIsHarvestingAndPowderMeansMoreObtained() {
        assertEquals("作物の収穫時に53%の確率でMushroomがドロップする。", corrected("You have a 53% chance to drop a Mushroom when farming crops."));
        assertEquals("すべての入手元から得るMithril Powderの量が+20%᠅増加する。", corrected("Grants +20% ᠅ Mithril Powder from all sources."));
    }
    @Test void sourceExactRulesDoNotApplyToSimilarButDifferentEffects() {
        assertEquals("別の訳。", SemanticTranslation.repair("Regenerate <n0/> extra <t0>mana</t0>, tripled when near or in water", "別の訳。"));
        assertEquals("別の訳。", SemanticTranslation.repair("While in <t0>Crystal Hollows</t0>, walking reduces your <n0/> Heat by <n1/>.", "別の訳。"));
    }
    @Test void cowRatioDoesNotInventExtraOne() {
        assertEquals("所持するStrengthが29.4増えるごとに、Farming Fortuneが+0.7増加する。", corrected("Grants +0.7 Farming Fortune for every 29.4 Strength you have."));
    }
    @Test void clearDurationIsMinutesAndTradeStaysMillion() {
        assertEquals("使用すると、60分間Intelligenceが上昇する。", corrected("Consume to boost your  Intelligence for 60m."));
        var trade = new StyledParagraph(List.of(Component.literal("ill do it for 100m LOL")));
        assertEquals("ill do it for 100m LOL", trade.decode(trade.encoded()).getString());
    }
    @Test void summonTimingIsNotRotationTiming() {
        String result = corrected("While in The Catacombs, summon a Haunted Skull that slowly revolves around you every 15 seconds or when you kill a mob with melee damage.");
        assertTrue(result.contains("15秒ごと、または近接攻撃でモブを倒すと"), result);
        assertTrue(result.endsWith("Haunted Skullを召喚する。"), result);
        assertEquals("Haunted Skullが命中した敵は2秒間スタンし、与えるダメージが5%減少する。", corrected("When an enemy is hit by a Haunted Skull, they are stunned for 2 seconds and deal 5% less damage."));
    }
    @Test void duplicateManaIsReplacedFromSourceFormula() {
        assertEquals("このアイテムのアビリティのmana消費を50%軽減する。", corrected("Reduces the ability mana cost of this item by 50%."));
    }
    @Test void colonProseIsNotProtectedAsAPlayer() {
        var p = new StyledParagraph(List.of(Component.literal("The type of Critter that shows up depends upon the type of Protected Tree you smeared:")));
        assertFalse(p.encoded().contains("<t"));
        var player = NpcTranslation.wholeChat(Component.literal("[488] ᛃ [MVP+] Player1: Hello"));
        assertEquals("Hello", ChatText.body(player.encoded()));
    }
    @Test void restoresBrokenNameAndRemovesHtmlBreak() {
        assertEquals("<t1>Magma Fields</t1>にいる間。\n効果。", TranslationRepair.repair("<t1>Magma Fields</t1>", "<t1>にいる間。<br/>効果。"));
    }
    @Test void translatedTreeWordRecoversOriginalEmphasis() {
        var p = new StyledParagraph(List.of(Component.literal("Protect the ").withStyle(ChatFormatting.WHITE)
            .append(Component.literal("Trees").withStyle(ChatFormatting.GREEN)).append(Component.literal("!"))));
        var result = p.decode(TranslationRepair.repair(p.encoded(), "木々を守ろう！"));
        assertTrue(StyledParagraph.glyphs(result).stream().anyMatch(g -> g.text().equals("木") && g.style().getColor().getValue() == net.minecraft.network.chat.TextColor.GREEN.getValue()));
    }
    @Test void allRecordedTablesRetainIndependentRows() throws Exception {
        try (var in = getClass().getResourceAsStream("/latest-review-fixtures.json")) {
            var fixtures = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : fixtures.getAsJsonArray("items")) {
                var r = entry.getAsJsonObject();
                List<String> lines = new ArrayList<>(); r.getAsJsonArray("originalLines").forEach(l -> lines.add(l.getAsString()));
                var blocks = LoreParser.parse(lines);
                String item = r.get("item").getAsString();
                if (item.equals("Pet Score Rewards")) assertFalse(blocks.stream().anyMatch(b -> b.text().contains("Score:")));
                if (item.equals("Step 4: Happy Huntin'!")) assertFalse(blocks.stream().anyMatch(b -> b.text().contains("Fig Tree:")));
                if (item.contains("Grandma Wolf")) {
                    var combo = blocks.stream().filter(b -> b.text().matches("\\d+ Combo.*")).toList();
                    assertEquals(6, combo.size());
                    assertTrue(combo.stream().allMatch(b -> b.end() == b.start() + 1));
                    assertTrue(blocks.stream().noneMatch(b -> b.text().contains("+15☯")));
                }
            }
        }
    }
}
