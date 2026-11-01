package local.skyblockjp;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryLiveFormattingTest {
    private static MutableComponent gray(String text) { return Component.literal(text).withStyle(ChatFormatting.GRAY); }
    @Test void coloredMultiplierAttachedToItsNumberIsAUnitRatherThanAWordBoundary() {
        var p=new StyledParagraph(List.of(gray("Your pet fuses its power with placed Deployables to give them ")
            .append(Component.literal("2x").withStyle(ChatFormatting.GREEN)).append(gray(" duration."))));
        var result=p.dictionaryEncoding("Petが設置したDeployableに力を合わせ、その持続時間を2xにする。");
        assertTrue(result.accepted(),result.reason());
        assertEquals(0x55FF55,StyledParagraph.glyphs(p.decode(result.text())).stream().filter(g->g.text().equals("x")).findFirst().orElseThrow().style().getColor().getValue());
        assertNull(p.encodeDictionaryTranslation("Petが設置したDeployableに力を合わせ、その持続時間を3xにする。"));
    }
    @Test void coloredPluralEntityUsesItsVerifiedSingularWithoutLosingTheValue() {
        var p=new StyledParagraph(List.of(gray("Grants a ").append(Component.literal("+18.5%").withStyle(ChatFormatting.GREEN))
            .append(gray(" chance to catch ")).append(Component.literal("Sharks ").withStyle(ChatFormatting.AQUA))
            .append(gray("during the ")).append(Component.literal("Fishing Festival").withStyle(ChatFormatting.AQUA)).append(gray("."))));
        var result=p.dictionaryEncoding("Fishing Festival中、Sharkが釣れる確率が+18.5%上昇する。");
        assertTrue(result.accepted(),result.reason());
        assertEquals(0x55FFFF,StyledParagraph.glyphs(p.decode(result.text())).stream().filter(g->g.text().equals("S")).findFirst().orElseThrow().style().getColor().getValue());
    }
    @Test void leadingActionIsNotPartOfTheProtectedCropList() {
        var p=new StyledParagraph(List.of(gray("Farming Wheat, Carrots, and Potatoes has a 0.03% chance of dropping a Cropie.")));
        assertFalse(p.encoded().contains(">Farming Wheat"));
        assertNotNull(p.encodeDictionaryTranslation("Wheat、Carrots、Potatoesの収穫時に、0.03%の確率でCropieが落ちる。"));
        assertNull(p.encodeDictionaryTranslation("Wheat、Carrotsの収穫時に、0.03%の確率でCropieが落ちる。"));
    }
    @Test void numericCapKeepsItsColorWhenAPluralCountNounBecomesACounter() {
        var p=new StyledParagraph(List.of(gray("Gain 13.3 Crit Damage for every nearby wolf monster ")
            .append(Component.literal("Max 10 wolves").withStyle(ChatFormatting.DARK_GRAY))));
        var result=p.dictionaryEncoding("近くにいるWolfモンスターごとに、Crit Damageが13.3増加する。計算対象は最大10体まで。");
        assertTrue(result.accepted(),result.reason());
        assertEquals(0x555555,StyledParagraph.glyphs(p.decode(result.text())).stream().filter(g->g.text().equals("体")).findFirst().orElseThrow().style().getColor().getValue());
        assertNull(p.encodeDictionaryTranslation("近くにいるWolfモンスターごとに、Crit Damageが13.3増加する。計算対象は最大11体まで。"));
    }
    @Test void collectionInflectionPreservesTheOriginalNamedComponent() {
        var p=new StyledParagraph(List.of(gray("View all of your SkyBlock progress, including your Skills, Collections, Recipes, and more!")));
        var result=p.dictionaryEncoding("Skill、Collection、Recipeなど、SkyBlockのすべての進行状況を確認しよう！");
        assertTrue(result.accepted(),result.reason());assertTrue(p.decode(result.text()).getString().contains("Collections"));
    }
    @Test void armorSetMenuIsTrustedButRealArmorStaysItemScoped() {
        assertTrue(TooltipContext.trustedMenu("(1/3) Armor Sets"));
        assertEquals(TooltipContext.Kind.ITEM,TooltipContext.classify("(1/3) Armor Sets",false,"MOOGMA_LEGGINGS",List.of("Moogma Leggings","RARE LEGGINGS")));
    }
    @Test void proseMultiplierWordsPermitOnlyTheirOwnDigitValue() {
        var p=new StyledParagraph(List.of(gray("Doubles the pet's base stats during the ")
            .append(Component.literal("Fishing Festival").withStyle(ChatFormatting.AQUA)).append(gray("."))));
        var result=p.dictionaryEncoding("Fishing Festival中は、このペットの基本ステータスが2倍になる。");
        assertTrue(result.accepted(),result.reason());
        assertNull(p.encodeDictionaryTranslation("Fishing Festival中は、このペットの基本ステータスが3倍になる。"));
        assertNull(p.encodeDictionaryTranslation("Fishing Festival中は、このペットの基本ステータスが2倍になる。追加で50を得る。"));
    }
}
