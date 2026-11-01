package local.skyblockjp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryStyleTest {
    private static Component text(String value, ChatFormatting color) {
        return Component.literal(value).withStyle(color);
    }
    private static void colored(Component output, String fragment, int rgb) {
        int start = output.getString().indexOf(fragment);
        assertTrue(start >= 0, () -> "Missing " + fragment + " in " + output.getString());
        int offset = 0;
        for (var glyph : StyledParagraph.glyphs(output)) {
            if (offset >= start && offset < start + fragment.length()) {
                assertNotNull(glyph.style().getColor());
                assertEquals(rgb, glyph.style().getColor().getValue(), fragment);
            }
            offset += glyph.text().length();
        }
    }
    private static Component decode(StyledParagraph paragraph, String target) {
        String encoded = paragraph.encodeDictionaryTranslation(target);
        assertNotNull(encoded, target);
        assertTrue(StyledParagraph.validMarkers(paragraph.encoded(), encoded));
        assertTrue(StyledParagraph.validNumbers(paragraph.encoded(), encoded));
        return paragraph.decode(encoded);
    }
    @Test void joinsWrappedLinesAndRetainsQuantityIconStatAndUnitColors() {
        var paragraph = new StyledParagraph(List.of(
            text("  Gain ", ChatFormatting.GRAY).copy()
                .append(text("+25", ChatFormatting.GREEN)).append(text(" ☘ ", ChatFormatting.GOLD))
                .append(text("Mining Fortune", ChatFormatting.GOLD)).append(text(" while mining.  ", ChatFormatting.GRAY)),
            Component.empty(),
            text(" Lasts ", ChatFormatting.GRAY).copy().append(text("10", ChatFormatting.GREEN))
                .append(text(" seconds", ChatFormatting.YELLOW)).append(text(". ", ChatFormatting.GRAY))));
        assertEquals("Gain +25 ☘ Mining Fortune while mining. Lasts 10 seconds.", paragraph.sourceText());
        String target = "採掘中はMining Fortuneが+25 ☘増加する。効果は10秒続く。";
        Component output = decode(paragraph, target);
        assertEquals(target, output.getString());
        colored(output, "+25", 0x55FF55);
        colored(output, "☘", 0xFFAA00);
        colored(output, "Mining Fortune", 0xFFAA00);
        colored(output, "10", 0x55FF55);
        colored(output, "秒", 0xFFFF55);
        colored(output, "採掘中は", 0xAAAAAA);
    }
    @Test void rejectsChangedMissingOrAddedValuesTermsAndIcons() {
        var paragraph = new StyledParagraph(List.of(Component.literal("Gain +25 ☘ Mining Fortune.")));
        assertNull(paragraph.encodeDictionaryTranslation("Mining Fortuneが+26 ☘増加する。"));
        assertNull(paragraph.encodeDictionaryTranslation("Mining Fortuneが25 ☘増加する。"));
        assertNull(paragraph.encodeDictionaryTranslation("Mining Fortuneが+25増加する。"));
        assertNull(paragraph.encodeDictionaryTranslation("採掘運が+25 ☘増加する。"));
        assertNull(paragraph.encodeDictionaryTranslation("Mining Fortuneが+25 ☘増加しDefenseも増加する。"));
        assertNull(paragraph.encodeDictionaryTranslation("Mining Fortuneが+25 ☘増加し1回発動する。"));
        assertNull(paragraph.encodeDictionaryTranslation("Mining Fortuneが+25 ☘☘増加する。"));
        assertNull(paragraph.encodeDictionaryTranslation("Mining Fortuneが+25 ☘増加しMining Fortuneも増加する。"));
    }
    @Test void keepsNumericSpellingExactWhileRestoringOriginalEnglishTermCase() {
        var paragraph = new StyledParagraph(List.of(Component.literal("Gain 1,000 Mining Speed for 60M coins.")));
        assertNotNull(paragraph.encodeDictionaryTranslation("60M coinsでMining Speedが1,000増加する。"));
        assertNull(paragraph.encodeDictionaryTranslation("60M coinsでMining Speedが1000増加する。"));
        assertNull(paragraph.encodeDictionaryTranslation("60m coinsでMining Speedが1,000増加する。"));
        assertEquals("60M coinsでMining Speedが1,000増加する。",
            decode(paragraph, "60M coinsでmining speedが1,000増加する。").getString());
        var word = new StyledParagraph(List.of(Component.literal("Take one apple.")));
        assertNull(word.encodeDictionaryTranslation("リンゴを1個取る。"));
    }
    @Test void rejectsAllLiteralInternalMarkup() {
        var paragraph = new StyledParagraph(List.of(Component.literal("Gain 5 Health.")));
        for (String injected : List.of("<n0/>", "<s0>5</s0>", "<t0>Health</t0>", "</s0>", "<N0/>"))
            assertNull(paragraph.encodeDictionaryTranslation("Healthが5増加する。" + injected));
        assertNull(paragraph.encodeDictionaryTranslation(null));
        assertNull(paragraph.encodeDictionaryTranslation("  "));
    }
    @Test void duplicateValuesWithDifferentColorsUseDistinctAdjacentStatAnchors() {
        var paragraph = new StyledParagraph(List.of(text("Gain ", ChatFormatting.GRAY).copy()
            .append(text("10", ChatFormatting.GREEN)).append(text(" Health and ", ChatFormatting.GRAY))
            .append(text("10", ChatFormatting.RED)).append(text(" Defense.", ChatFormatting.GRAY))));
        Component output = decode(paragraph, "Defenseが10、Healthが10増加する。");
        var values = StyledParagraph.glyphs(output).stream().filter(g -> g.text().equals("1") || g.text().equals("0")).toList();
        assertEquals(List.of(0xFF5555, 0xFF5555, 0x55FF55, 0x55FF55),
            values.stream().map(g -> g.style().getColor().getValue()).toList());
        var ambiguous = new StyledParagraph(List.of(text("Gain ", ChatFormatting.GRAY).copy()
            .append(text("10", ChatFormatting.GREEN)).append(text(" and ", ChatFormatting.GRAY))
            .append(text("10", ChatFormatting.RED)).append(text(" points.", ChatFormatting.GRAY))));
        assertEquals("AMBIGUOUS_VALUE_STYLE", ambiguous.dictionaryEncoding("10と10ポイントを得る。").reason());
        assertEquals("Gain 10 Health and 10 Defense.", paragraph.decode(paragraph.encoded()).getString());
    }
    @Test void duplicateValuesWithIdenticalComponentsCanBeReorderedSafely() {
        var paragraph = new StyledParagraph(List.of(text("Gain ", ChatFormatting.GRAY).copy()
            .append(text("10", ChatFormatting.GREEN)).append(text(" Health and ", ChatFormatting.GRAY))
            .append(text("10", ChatFormatting.GREEN)).append(text(" Defense.", ChatFormatting.GRAY))));
        Component output = decode(paragraph, "Defenseが10、Healthが10増加する。");
        assertEquals("Defenseが10、Healthが10増加する。", output.getString());
        assertTrue(StyledParagraph.glyphs(output).stream().filter(g -> g.text().equals("1") || g.text().equals("0"))
            .allMatch(g -> g.style().getColor().getValue() == 0x55FF55));
    }
    @Test void unchangedColoredNameIsAlignedAsAWholeLiteral() {
        var paragraph = new StyledParagraph(List.of(text("Works with ", ChatFormatting.GRAY).copy()
            .append(text("Drill Engine ", ChatFormatting.GREEN)).append(text("while mining.", ChatFormatting.GRAY))));
        Component output = decode(paragraph, "採掘中にDrill Engineを利用する。");
        colored(output, "Drill Engine", 0x55FF55);
        colored(output, "を利用する。", 0xAAAAAA);
    }
    @Test void reviewedColoredProseAlignmentPreservesColorAndExistingApiMarkers() {
        var paragraph = new StyledParagraph(List.of(text("Hit ", ChatFormatting.GRAY).copy()
            .append(text("nearby enemies", ChatFormatting.RED)).append(text(".", ChatFormatting.GRAY))));
        colored(decode(paragraph, "周囲の敵に命中する。"), "周囲の敵", 0xFF5555);
        String api = "<s0>周囲の敵</s0>に命中する。";
        assertTrue(StyledParagraph.validNumbers(paragraph.encoded(), api));
        colored(paragraph.decode(api), "周囲の敵", 0xFF5555);
        assertFalse(StyledParagraph.validNumbers(paragraph.encoded(), "周囲の敵に命中する。"));
    }
    @Test void ordinaryColoredWordsDoNotMatchInsideDifferentEnglishWords() {
        var paragraph = new StyledParagraph(List.of(text("Turn ", ChatFormatting.GRAY).copy()
            .append(text("on", ChatFormatting.RED)).append(text(" the switch.", ChatFormatting.GRAY))));
        assertNull(paragraph.encodeDictionaryTranslation("Protectionを切り替える。"));
        assertNull(paragraph.encodeDictionaryTranslation("onからonに切り替える。"));
    }
    @Test void unitColorsFollowTheirOriginalQuantityWhenJapaneseOrderChanges() {
        var paragraph = new StyledParagraph(List.of(text("Wait ", ChatFormatting.GRAY).copy()
            .append(text("5", ChatFormatting.GREEN)).append(text(" seconds", ChatFormatting.RED))
            .append(text(" and ", ChatFormatting.GRAY)).append(text("10", ChatFormatting.GREEN))
            .append(text(" seconds", ChatFormatting.BLUE)).append(text(".", ChatFormatting.GRAY))));
        Component output = decode(paragraph, "10秒と5秒待つ。");
        var seconds = StyledParagraph.glyphs(output).stream().filter(g -> g.text().equals("秒")).toList();
        assertEquals(0x5555FF, seconds.getFirst().style().getColor().getValue());
        assertEquals(0xFF5555, seconds.getLast().style().getColor().getValue());
    }
    @Test void doesNotApplyAUnitAliasWithoutItsAdjacentSourceQuantity() {
        var paragraph = new StyledParagraph(List.of(text("Wait ", ChatFormatting.GRAY).copy()
            .append(text("seconds", ChatFormatting.YELLOW)).append(text(" before leaving.", ChatFormatting.GRAY))));
        assertNull(paragraph.encodeDictionaryTranslation("出発まで秒待つ。"));
    }
    @Test void originalMinuteKeyRemainsDistinctFromItsLocalizedDisplay() {
        var paragraph = new StyledParagraph(List.of(text("Lasts ", ChatFormatting.GRAY).copy()
            .append(text("60m", ChatFormatting.GREEN)).append(text(".", ChatFormatting.GRAY))));
        assertEquals("Lasts 60m.", paragraph.sourceText());
        assertEquals("Lasts 60分.", paragraph.decode(paragraph.encoded()).getString());
        Component output = decode(paragraph, "60m持続する。");
        assertEquals("60分持続する。", output.getString());
        colored(output, "60分", 0x55FF55);
        assertNull(paragraph.encodeDictionaryTranslation("60分持続する。"));
        var coins = new StyledParagraph(List.of(Component.literal("Costs 60m coins.")));
        assertEquals("60m coinsかかる。", decode(coins, "60m coinsかかる。").getString());
    }
    @Test void supplementaryPrivateUseIconIsCopiedWithoutSplittingItsSurrogatePair() {
        String icon = new String(Character.toChars(0xF0000));
        var paragraph = new StyledParagraph(List.of(text("Gain ", ChatFormatting.GRAY).copy()
            .append(text(icon, ChatFormatting.GOLD)).append(text(" 3 Health.", ChatFormatting.GRAY))));
        String target = "Healthが3" + icon + "増加する。";
        Component output = decode(paragraph, target);
        assertEquals(target, output.getString());
        colored(output, icon, 0xFFAA00);
        assertEquals(1, StyledParagraph.glyphs(output).stream().filter(g -> g.text().equals(icon)).count());
        assertNull(paragraph.encodeDictionaryTranslation("Healthが3増加する。"));
    }
    @Test void levelNameLabelAndValueRetainTheirNativeColorsAfterDecomposition() {
        var paragraph = new StyledParagraph(List.of(text("Requires ", ChatFormatting.GRAY).copy()
            .append(text("SkyBlock Level ", ChatFormatting.GOLD)).append(text("6", ChatFormatting.GREEN))
            .append(text(" to use.", ChatFormatting.GRAY))));
        Component output = decode(paragraph, "使うにはSkyBlock Level 6が必要だよ。");
        colored(output, "SkyBlock", 0xFFAA00);
        colored(output, "Level", 0xFFAA00);
        colored(output, "6", 0x55FF55);
        Component japanese = decode(paragraph, "使うにはSkyBlockのレベル6が必要だよ。");
        colored(japanese, "SkyBlock", 0xFFAA00);
        colored(japanese, "レベル", 0xFFAA00);
        colored(japanese, "6", 0x55FF55);
        assertNull(paragraph.encodeDictionaryTranslation("使うにはSkyBlock Levelが6必要だよ。"));
    }
    @Test void npcBodyUsesOriginalPlainDialogueWithoutItsSpeakerMetadata() {
        var message = text("[NPC] Duke: ", ChatFormatting.GOLD).copy()
            .append(text("Hello there! I have been waiting.", ChatFormatting.WHITE));
        var paragraph = NpcTranslation.paragraph(message);
        assertEquals("Hello there! I have been waiting.", paragraph.sourceText());
        Component output = decode(paragraph, "やあ！君が来るのを待っていたよ。");
        assertEquals("やあ！君が来るのを待っていたよ。", output.getString());
        colored(output, "やあ！", 0xFFFFFF);
    }
}
