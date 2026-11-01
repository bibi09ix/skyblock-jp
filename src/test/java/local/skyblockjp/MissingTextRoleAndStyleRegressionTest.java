package local.skyblockjp;

import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MissingTextRoleAndStyleRegressionTest {
    private static final String GIANT="Increases damage dealt by 0.9% for each percent of extra health that your target has above you up to 45%.";
    private static final String GIANT_JA="対象の体力が自分を上回る割合の各パーセントにつき、与えるダメージが0.9%増加する（上限45%）。";
    private static final String BONZO="Instead of dying, gain damage immunity and +33.6  Strength for 3s and fully replenish your health. The cooldown and Strength bonus you receive improve based on your Dungeoneering Skill level. This ability only works while in The Catacombs!";
    private static final String BONZO_JA="死亡する代わりに、3秒間のダメージ無効化と+33.6  Strengthを得て、体力が全回復する。クールダウンとStrengthボーナスはDungeoneering Skillのレベルに応じて改善する。この能力はThe Catacombs内でのみ発動する！";
    private static final String KAT="Kat will take care of your Frog for 2 hours then its rarity will be upgraded!";
    private static final String KAT_JA="KatがFrogを2時間預かると、レアリティが上がる！";
    private static final String KAT_ID="observed/item/runtime079_8a082ff135a521365d";
    private static Component t(String text,ChatFormatting color){return Component.literal(text).withStyle(color);}
    private static void color(Component output,String part,int rgb){
        int start=output.getString().indexOf(part);assertTrue(start>=0,output.getString());int offset=0,count=0;
        for(var glyph:StyledParagraph.glyphs(output)){
            if(offset>=start&&offset<start+part.length()){assertNotNull(glyph.style().getColor());assertEquals(rgb,glyph.style().getColor().getValue());count++;}
            offset+=glyph.text().length();
        }assertEquals(part.length(),count);
    }
    @Test void extraHealthAndReplenishHealthAreOrdinaryOnlyForTheirExactReviewedSources(){
        assertTrue(OrdinaryTokenRoles.stats().loaded(),OrdinaryTokenRoles.stats().error());
        for(String source:List.of(GIANT,BONZO)){
            assertEquals(Set.of("health"),OrdinaryTokenRoles.wordsForSource(source));
            assertTrue(OrdinaryTokenRoles.wordsForSource(source+"!").isEmpty());
            assertTrue(OrdinaryTokenRoles.wordsForSource(source.replace("health","Health")).isEmpty());
            assertTrue(OrdinaryTokenRoles.wordsForSource(source.replace("0.9%","0.8%").replace("+33.6","+33.7")).isEmpty());
        }
        for(String source:List.of("Gain +10 Health.","Gain +10 health.","Your health stat increases.","Restore your health."))
            assertTrue(OrdinaryTokenRoles.wordsForSource(source).isEmpty());
    }
    @Test void nativeGiantKillerPercentColorsAndMeaningRemainIntact(){
        var line=t("Increases damage dealt by ",ChatFormatting.GRAY).copy().append(t("0.9%",ChatFormatting.GREEN))
            .append(t(" for each percent of extra health that your target has above you up to ",ChatFormatting.GRAY))
            .append(t("45%",ChatFormatting.GREEN)).append(t(".",ChatFormatting.GRAY));
        var paragraph=new StyledParagraph(List.of(line));var result=paragraph.dictionaryEncoding(GIANT_JA);
        assertTrue(result.accepted(),result.reason()+result.details());
        var decoded=paragraph.decode(result.text());assertEquals(GIANT_JA,decoded.getString());
        color(decoded,"0.9%",0x55FF55);color(decoded,"45%",0x55FF55);color(decoded,"体力",0xAAAAAA);
        assertFalse(paragraph.dictionaryEncoding(GIANT_JA.replace("45%","46%")).accepted());
        assertTrue(ProtectedTerms.forText("Gain +10 Health.").matcher("Health").find());
    }
    @Test void bonzoOrdinaryHealthDoesNotPermitDroppingNamedStatsValuesOrLocations(){
        var paragraph=new StyledParagraph(List.of(t(BONZO,ChatFormatting.GRAY)));
        assertTrue(paragraph.dictionaryEncoding(BONZO_JA).accepted());
        for(String changed:List.of(BONZO_JA.replace("33.6","33.7"),BONZO_JA.replace("Strength","強さ"),
            BONZO_JA.replace("The Catacombs","地下墓地")))assertFalse(paragraph.dictionaryEncoding(changed).accepted(),changed);
    }
    @Test void katRarityAlignmentIsSourceIdAndTargetBoundAndKeepsNativeColors(){
        var plan=DictionaryAlignment.lookup("item",KAT,KAT_JA,List.of(KAT_ID));assertNotNull(plan);
        assertEquals("",DictionaryAlignment.validate(plan,KAT,KAT_JA));
        assertNull(DictionaryAlignment.lookup("item",KAT,KAT_JA,List.of("foreign-source")));
        assertNull(DictionaryAlignment.lookup("ui",KAT,KAT_JA,List.of(KAT_ID)));
        assertNull(DictionaryAlignment.lookup("item",KAT.replace("2 hours","3 hours"),KAT_JA,List.of(KAT_ID)));
        assertNull(DictionaryAlignment.lookup("item",KAT,KAT_JA+"!",List.of(KAT_ID)));
        for(int rgb:List.of(0x5555FF,0x1289AB)){
            var rarity=Component.literal("rarity").setStyle(Style.EMPTY.withColor(rgb).withBold(true));
            var line=t("Kat",ChatFormatting.GREEN).copy().append(t(" will take care of your ",ChatFormatting.GRAY))
                .append(t("Frog",ChatFormatting.GOLD)).append(t(" for ",ChatFormatting.GRAY))
                .append(t("2",ChatFormatting.AQUA)).append(t(" hours then its ",ChatFormatting.GRAY))
                .append(rarity).append(t(" will be upgraded!",ChatFormatting.GRAY));
            var paragraph=new StyledParagraph(List.of(line));
            var result=paragraph.dictionaryEncoding(new BundledDictionary.Match(KAT_JA,false,List.of(KAT_ID),List.of(),plan),false);
            assertTrue(result.accepted(),result.reason()+result.details());var decoded=paragraph.decode(result.text());
            color(decoded,"レアリティ",rgb);color(decoded,"Kat",0x55FF55);color(decoded,"Frog",0xFFAA00);color(decoded,"2",0x55FFFF);
            assertTrue(StyledParagraph.glyphs(decoded).stream().filter(g->g.text().equals("レ")).findFirst().orElseThrow().style().isBold());
        }
    }
    @Test void unreviewedKatAccentCannotPiggybackOnRarityAlignment(){
        var plan=DictionaryAlignment.lookup("item",KAT,KAT_JA,List.of(KAT_ID));assertNotNull(plan);
        var line=t("Kat will ",ChatFormatting.GRAY).copy().append(t("take",ChatFormatting.RED))
            .append(t(" care of your Frog for 2 hours then its ",ChatFormatting.GRAY))
            .append(t("rarity",ChatFormatting.BLUE)).append(t(" will be upgraded!",ChatFormatting.GRAY));
        var paragraph=new StyledParagraph(List.of(line));
        var result=paragraph.dictionaryEncoding(new BundledDictionary.Match(KAT_JA,false,List.of(KAT_ID),List.of(),plan),true);
        assertFalse(result.accepted());assertEquals("STYLE_ALIGNMENT_MISSING",result.reason());
    }
}
