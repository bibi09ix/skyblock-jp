package local.skyblockjp;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
class NpcLevelRegressionTest {
    @TempDir Path dir;
    private String fix(String text, String response, String previous) {
        var p = NpcTranslation.paragraph(Component.literal("[NPC] Test: " + text));
        String result = TranslationRepair.repair(p.encoded(), NpcTranslationRepair.repair(p.encoded(), response, previous));
        assertNull(NpcTranslation.validate(p.encoded(), result), result);
        return p.decode(result).getString();
    }
    @Test void decomposedLevelRetainsItsSkillLabelAndDifferentOriginalColors() {
        var source = Component.literal("[NPC] Fann: Come back to me when you have ")
            .append(Component.literal("Taming Skill ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal("10").withStyle(ChatFormatting.GOLD));
        var p = NpcTranslation.paragraph(source);
        assertTrue(p.encoded().contains("<t0>Taming</t0>"));
        assertTrue(p.encoded().contains("<n0/>"));
        assertTrue(p.encoded().contains("Skill"));
        var output = p.decode(NpcTranslationRepair.repair(p.encoded(), "訳文。10", ""));
        assertEquals("Taming Skill 10に到達したら、また来てね。", output.getString());
        assertTrue(StyledParagraph.glyphs(output).stream().anyMatch(g -> g.text().equals("T") && g.style().getColor().getValue() == net.minecraft.network.chat.TextColor.GREEN.getValue()));
        assertTrue(StyledParagraph.glyphs(output).stream().anyMatch(g -> g.text().equals("S") && g.style().getColor().getValue() == net.minecraft.network.chat.TextColor.GREEN.getValue()));
        assertTrue(StyledParagraph.glyphs(output).stream().anyMatch(g -> g.text().equals("1") && g.style().getColor().getValue() == net.minecraft.network.chat.TextColor.GOLD.getValue()));
    }
    @Test void miningRequirementIsNotDuplicatedOrConvertedToSkillAcquisition() {
        assertEquals("Mining Skill 5に到達したら、また来てね。", fix("Come back once you have reached §aMining Skill §f5!", "採掘スキルがMining Skill 5になったら。", ""));
    }
    @Test void rustyUnlockKeepsLevelAdjacentAndHasNoTrailingNumber() {
        assertEquals("うんざりするけど、少なくともSkyBlock Level 6になるとAuto-pickupが解放されるよ。", fix("It drives me insane, but at least you'll unlock §aAuto-pickup §fat §bSkyBlock Level §f6.", "解除されるよ6.", ""));
    }
    @Test void coalPaymentKeepsCountAndOriginalXColor() {
        assertEquals("普通はリフォージにCoinsがかかるけど、今日は特別に、最初のアイテムはCoalを10個払えばリフォージしてあげるよ。", fix("Reforging usually costs Coins, but since I'm feeling friendly I can reforge your first item for Coal §ex§f10.", "できるよx10", ""));
    }
    @Test void ordinaryColoredWordsAreTranslatedAndKeepEmphasis() {
        assertEquals("邪悪な力が墓を支配した。", fix("An §cevil force §ftook control of the graves.", "<s0>evil force</s0>が墓を支配した。", ""));
        assertEquals("金が大好き！", fix("I love §6goooold§f!", "<s0>goooold</s0>が大好き！", ""));
    }
    @Test void ordinaryTranslatedWordRecoversDroppedColorSpan() {
        assertEquals("邪悪な力が墓を支配した。", fix("An §cevil force §ftook control of the graves.", "邪悪な力が墓を支配した。", ""));
        assertEquals("金が大好き！", fix("I love §6goooold§f!", "金が大好き！", ""));
    }
    @Test void nameAnswerUsesContextInsteadOfTurningNameIntoOccupation() {
        assertEquals("私の名前？", fix("My name?", "名前は？", ""));
        assertEquals("Blacksmithだよ。", fix("Blacksmith.", "鍛冶屋だよ。", "My name?"));
        assertEquals("鍛冶屋だよ。", NpcTranslationRepair.repair("Blacksmith.", "鍛冶屋だよ。", "Who works here?"));
    }
    @Test void requirementRuleDoesNotInventLevelConditionForItemOwnership() {
        assertEquals("Coalを持ってきてね。", NpcTranslationRepair.repair("Come back to me when you have <t0>Coal</t0>", "Coalを持ってきてね。", ""));
    }
    @Test void sourceQuantityIsVariableNotHardcoded() {
        assertEquals("Taming Skill 25に到達したら、また来てね。", fix("Come back to me when you have Taming Skill 25", "最後に25", ""));
        assertEquals("Mining Skill 50に到達したら、また来てね。", fix("Come back once you have reached Mining Skill 50!", "末尾に50", ""));
        assertEquals("Combat Level XVに到達したら、また来てね。", fix("Come back to me when you have Combat Level XV", "Combatレベル XIVだね。", ""));
        assertEquals("Foraging Skill 40に到達したら、また来てね。", fix("Come back to me when you have Foraging Skill 40", "最後に10", ""));
    }
    @Test void oldWholeLevelMarkersStillRepairWithoutInventingAValue() {
        String source = "Come back to me when you have <t0>Taming Skill 25</t0>";
        assertEquals("<t0>Taming Skill 25</t0>に到達したら、また来てね。", NpcTranslationRepair.repair(source, "最後に10", ""));
    }
    @Test void automaticNpcPathAppliesAndAuditsRepair() throws Exception {
        var p = NpcTranslation.paragraph(Component.literal("[NPC] Fann: Come back to me when you have Taming Skill 10"));
        try (var t = new Translator(new Config(), dir.resolve("cache.json"), LoggerFactory.getLogger("test"), text -> "また来てね。10")) {
            String result = t.requestNpc(p, "Fann").get(3, TimeUnit.SECONDS);
            assertEquals("Taming Skill 10に到達したら、また来てね。", p.decode(result).getString());
            String log = Files.readString(dir.resolve("translation-repairs.jsonl"));
            assertTrue(log.contains("rawResponse")); assertTrue(log.contains("repairedResponse"));
            assertTrue(Files.readString(dir.resolve("npc-translations.jsonl")).contains("\"provisional\":false"));
        }
    }
}
