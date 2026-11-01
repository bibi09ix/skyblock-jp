package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class TranslationRepairTest {
    @Test
    void impliedUnitCountsDoNotDisableNumericProtection() {
        String source = "Grants <n0/> per block. Each block consumes <n1/> Mana.";
        String repaired =
                TranslationRepair.repair(source, "ブロック破壊1回につき<n0/>を獲得し、1回につき<n1/> Manaを消費する。");
        assertNull(TranslationDiagnostics.validate(source, repaired));
        assertTrue(
                TranslationDiagnostics.validate(
                                source,
                                TranslationRepair.repair(
                                        source, "ブロック破壊10回につき<n0/>を獲得し、<n1/> Manaを消費する。"))
                        .startsWith("NUMBER_"));
        assertTrue(
                TranslationDiagnostics.validate(
                                "Gain <n0/> Health.",
                                TranslationRepair.repair("Gain <n0/> Health.", "1回につき<n0/>回復する。"))
                        .startsWith("NUMBER_"));
        assertNull(
                TranslationDiagnostics.validate(
                        "Gain <n0/> per Catacombs level.",
                        TranslationRepair.repair(
                                "Gain <n0/> per Catacombs level.", "Catacombsレベル1につき<n0/>増加する。")));
    }

    @Test
    void restoresUnambiguousLocationAndUnitColorsThroughDecode() {
        var paragraph =
                new StyledParagraph(
                        List.of(
                                Component.literal("Gain ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(
                                                Component.literal("50")
                                                        .withStyle(ChatFormatting.GREEN))
                                        .append(
                                                Component.literal(" Speed for ")
                                                        .withStyle(ChatFormatting.GRAY))
                                        .append(
                                                Component.literal("3 seconds")
                                                        .withStyle(ChatFormatting.YELLOW))
                                        .append(
                                                Component.literal(".")
                                                        .withStyle(ChatFormatting.GRAY))));
        String repaired =
                TranslationRepair.repair(paragraph.encoded(), "<n1/>秒間、<t0>Speed</t0>が<n0/>増加する。");
        assertNull(TranslationDiagnostics.validate(paragraph.encoded(), repaired));
        var decoded = paragraph.decode(repaired);
        assertEquals("3秒間、Speedが50増加する。", decoded.getString());
        assertEquals(
                net.minecraft.network.chat.TextColor.YELLOW.getValue(),
                StyledParagraph.glyphs(decoded).stream()
                        .filter(g -> g.text().equals("秒"))
                        .findFirst()
                        .orElseThrow()
                        .style()
                        .getColor()
                        .getValue());
        assertEquals(
                "<s2>Galatea</s2>で採掘する。",
                TranslationRepair.repair("on <s2>Galatea</s2>", "Galateaで採掘する。"));
    }

    @Test
    void doesNotGuessAmbiguousOrBrokenTags() {
        assertEquals(
                "GalateaとGalatea", TranslationRepair.repair("<s0>Galatea</s0>", "GalateaとGalatea"));
        assertEquals("<s0>Galatea", TranslationRepair.repair("<s0>Galatea</s0>", "<s0>Galatea"));
        assertNotNull(TranslationDiagnostics.validate("<s0>Galatea</s0>", "<s0>Galatea"));
    }

    @Test
    void splitsBulletItemsButKeepsWrappedSentencesTogether() {
        var blocks =
                LoreParser.parse(
                        List.of(
                                "Booster Cookie",
                                "‣ Ability to gain Bits!",
                                "‣ Quick access to menus",
                                "using their commands:",
                                "/bazaar, /bank",
                                "‣ Keep coins on death"));
        assertEquals(3, blocks.size());
        assertEquals(
                "‣ Quick access to menus using their commands: /bazaar, /bank",
                blocks.get(1).text());
        assertEquals(2, blocks.get(1).start());
        assertEquals(5, blocks.get(1).end());
        assertTrue(LoreParser.protectedLine("Renowned (Legendary):"));
        assertFalse(LoreParser.protectedLine("Grants extra damage."));
    }
}
