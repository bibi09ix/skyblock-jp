package local.skyblockjp;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ColumnsPetRegressionTest {
    private int width(Component c) { return c.getString().codePointCount(0, c.getString().length()) * 6; }
    @Test void speakerAndBodyWrapIndependentlyAndContinuationIdentifiesSpeaker() {
        var body = Component.literal("自分で確かめたいなら、Moonglade MarshにあるLaunch Padから行けるよ。").append(Component.literal("123").withStyle(ChatFormatting.GOLD));
        var message = HudRows.message("[NPC] Torrhus Canyon", body);
        var layout = HudRows.layout(List.of(message), 260, this::width);
        assertTrue(layout.rows().size() > 1);
        assertEquals(238, layout.nameWidth() + layout.textWidth());
        assertEquals(body.getString(), String.join("", layout.rows().stream().map(r -> r.text().getString()).toList()));
        assertTrue(layout.rows().stream().flatMap(r -> StyledParagraph.glyphs(r.text()).stream()).filter(g -> g.text().equals("1"))
            .anyMatch(g -> g.style().getColor().getValue() == net.minecraft.network.chat.TextColor.GOLD.getValue()));
        var visible = HudRows.window(layout, 1, layout.rows().size(), this::width);
        assertFalse(visible.getFirst().name().getString().isEmpty());
        assertEquals("Torrhus Canyon", visible.getFirst().speaker().getString());
    }
    @Test void playerNameMovesIntoLeftColumnKeepingNativeColor() {
        var text = Component.literal("[402] ᛃ [MVP+] ").append(Component.literal("TwoSwitch73").withStyle(ChatFormatting.BLUE)).append(": 募集しています。");
        var message = HudRows.message("チャット", text);
        assertEquals("TwoSwitch73", message.name().getString());
        assertEquals("募集しています。", message.text().getString());
        assertEquals(net.minecraft.network.chat.TextColor.BLUE.getValue(), StyledParagraph.glyphs(message.name()).getFirst().style().getColor().getValue());
    }
    @Test void ordinaryNpcWordsAreNotProtectedButChatAddresseeIs() {
        for (String source : List.of("If you want to check it out for yourself.", "Have you enchanted your axe?", "Can you help me?")) {
            var p = NpcTranslation.whole(Component.literal(source));
            assertFalse(p.encoded().contains("<t"), p.encoded());
        }
        var chat = NpcTranslation.wholeChat(Component.literal("[260] [MVP+] ManSui: cqwl u want to buy ti"));
        assertTrue(chat.encoded().contains(">cqwl</t"));
        assertFalse(NpcTranslation.wholeChat(Component.literal("[MVP+] A: Have you done it?")).encoded().contains(">Have</t"));
    }
    @Test void petTitlesStaySeparateAndNamedAbilityCannotBeDropped() {
        var lines = List.of("[Lvl 100] Endermite", "Mining Pet", "", "Daily Commuter", "Transmission Abilities cost 40% less", "mana", "", "Mite Bait", "Gain a 3% chance to dig up a bonus", "Nest Endermite per +1 Pet Luck", "(Stacks above 100%)");
        var blocks = LoreParser.parse(lines);
        assertEquals(2, blocks.size()); assertEquals(4, blocks.getFirst().start());
        assertFalse(blocks.getFirst().text().contains("Daily Commuter"));
        var p = NpcTranslation.whole(Component.literal("Buffs Transmission abilities, granting 50 weapon damage for 5s on use"));
        assertTrue(p.encoded().contains(">Transmission abilities</t"));
        assertNotNull(TranslationDiagnostics.validate(p.encoded(), "使用時に<n0/>の武器ダメージを<n1/>付与する"));
    }
    @Test void petMeaningAndGrammarAreCheckedBeyondNumbers() {
        String debuff = "<t0>Tarantula Broodfather</t0>'s <n0/> <t1>Vitality</t1> reduction is <n1/> less effective against you.";
        String fixed = TranslationRepair.repair(debuff, "<t0>Tarantula Broodfather</t0>の<n0/> <t1>Vitality</t1>軽減効果は<n1/>効果が低くなる");
        assertTrue(fixed.contains("減少効果")); assertTrue(fixed.endsWith("弱まる。")); assertTrue(StyledParagraph.validMarkers(debuff, fixed));
        assertEquals("Combatで得るペット経験値が<n0/>増加する。", TranslationRepair.repair("Gives <n0/> pet exp for Combat.", "<n0/> Combatで得るペット経験値が増える"));
        assertEquals("special Zealotの出現率が<n0/>上がる", TranslationRepair.repair("Increases odds", "special Zealotの出現率が<n0/>上がるする"));
        assertNotNull(TranslationCoverage.missing("(Stacks above <n0/>)", "(スタックが<n0/>以上)"));
        assertNull(TranslationCoverage.missing("(Stacks above <n0/>)", "(<n0/>を超えて加算される)"));
        String source = "Your pet fuses its power with placed <t0>Deployables</t0> to give them <n0/> duration.";
        assertNotNull(TranslationCoverage.missing(source, "<t0>Deployables</t0>の持続時間を<n0/>増加させる"));
        assertNull(TranslationCoverage.missing(source, "<t0>Deployables</t0>の持続時間を<n0/>にする"));
        assertNotNull(TranslationCoverage.missing(source.replace("<n0/> duration", "<n0/>x duration"), "<t0>Deployables</t0>の持続時間を<n0/>x増加させる"));
        assertNotNull(TranslationCoverage.missing("Vitality reduction is <n0/> less effective", "Vitality reductionは<n0/>効果が低くなる"));
    }
}
