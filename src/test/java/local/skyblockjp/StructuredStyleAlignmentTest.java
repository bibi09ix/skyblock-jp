package local.skyblockjp;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructuredStyleAlignmentTest {
    private static Component t(String text, ChatFormatting color) { return Component.literal(text).withStyle(color); }
    private static StyledParagraph paragraph(Component line) { return new StyledParagraph(List.of(line)); }
    private static List<Integer> colors(Component line, String needle) {
        int start = line.getString().indexOf(needle), offset = 0;
        assertTrue(start >= 0, line.getString());
        var colors = new ArrayList<Integer>();
        for (var glyph : StyledParagraph.glyphs(line)) {
            if (offset >= start && offset < start + needle.length()) colors.add(glyph.style().getColor().getValue());
            offset += glyph.text().length();
        }
        return colors;
    }
    private static Component translated(StyledParagraph p, String target) {
        var result = p.dictionaryEncoding(target);
        assertTrue(result.accepted(), () -> result.reason() + ": " + result.details() + " / " + p.sourceText());
        assertTrue(StyledParagraph.validNumbers(p.encoded(), result.text()));
        return p.decode(result.text());
    }
    @Test void actualNativeStyledSessionFailuresAreTranslatedWithoutDroppingAtoms() throws Exception {
        try (var input = getClass().getResourceAsStream("/styled-dictionary-session-fixtures.json")) {
            assertNotNull(input);
            var fixtures = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
            assertEquals(11, fixtures.size());
            for (var fixture : fixtures) {
                var row = fixture.getAsJsonObject();
                var lines = new ArrayList<Component>();
                for (var sourceLine : row.getAsJsonArray("lines")) {
                    var line = Component.empty();
                    for (var raw : sourceLine.getAsJsonArray()) {
                        var segment = raw.getAsJsonObject(); var style = Style.EMPTY;
                        if (segment.has("color")) style = style.withColor(segment.get("color").getAsInt());
                        style = style.withBold(segment.get("bold").getAsBoolean()).withItalic(segment.get("italic").getAsBoolean())
                            .withUnderlined(segment.get("underlined").getAsBoolean()).withStrikethrough(segment.get("strikethrough").getAsBoolean())
                            .withObfuscated(segment.get("obfuscated").getAsBoolean());
                        line.append(Component.literal(segment.get("text").getAsString()).setStyle(style));
                    }
                    lines.add(line);
                }
                var p = new StyledParagraph(lines);
                assertEquals(row.get("source").getAsString(), p.sourceText());
                var output = translated(p, row.get("target").getAsString());
                assertFalse(output.getString().contains("<s"));
                assertFalse(output.getString().contains("<n"));
            }
        }
    }
    @Test void equalNumbersAreAssignedByOrdinalAndDurationRolesRatherThanTargetOrder() {
        var p = paragraph(t("Every ", ChatFormatting.GRAY).copy().append(t("4", ChatFormatting.GREEN))
            .append(t("th hit lasts ", ChatFormatting.GRAY)).append(t("4", ChatFormatting.RED)).append(t(" seconds.", ChatFormatting.GRAY)));
        var output = translated(p, "効果は4秒続き、4回目の攻撃で発動する。");
        assertEquals(List.of(0xFF5555), colors(output, "4秒").subList(0, 1));
        assertEquals(List.of(0x55FF55), colors(output, "4回目").subList(0, 1));
        assertFalse(p.dictionaryEncoding("4と4で発動する。").accepted());
    }
    @Test void namedTemplateBindingsPreserveEqualValuesWithoutAStatOrUnitAnchor() {
        var p = paragraph(t("Gain ", ChatFormatting.GRAY).copy().append(t("10", ChatFormatting.GREEN))
            .append(t(" alpha and ", ChatFormatting.GRAY)).append(t("10", ChatFormatting.RED)).append(t(" beta.", ChatFormatting.GRAY)));
        String target = "betaが10、alphaが10増加する。";
        int a = p.sourceText().indexOf("10"), b = p.sourceText().lastIndexOf("10");
        var match = new BundledDictionary.Match(target, false, List.of("fixture"), List.of(
            new BundledDictionary.Binding("v0", "number", a, a + 2, target.lastIndexOf("10"), target.lastIndexOf("10") + 2, "10", "10"),
            new BundledDictionary.Binding("v1", "number", b, b + 2, target.indexOf("10"), target.indexOf("10") + 2, "10", "10")));
        assertEquals("AMBIGUOUS_VALUE_STYLE", p.dictionaryEncoding(target).reason());
        var encoded = p.dictionaryEncoding(match);
        assertTrue(encoded.accepted(), encoded.reason());
        var output = p.decode(encoded.text());
        assertEquals(List.of(0xFF5555, 0xFF5555), colors(output, "10"));
        var all = StyledParagraph.glyphs(output).stream().filter(g -> g.text().equals("1") || g.text().equals("0")).toList();
        assertEquals(List.of(0xFF5555, 0xFF5555, 0x55FF55, 0x55FF55), all.stream().map(g -> g.style().getColor().getValue()).toList());
    }
    @Test void malformedOrOverlappingBindingsCannotOverrideNativeValues() {
        var p = paragraph(Component.literal("Gain 10 Health."));
        String target = "Healthが10増加する。";
        for (var binding : List.of(
            new BundledDictionary.Binding("v0", "number", 5, 7, 7, 9, "11", "10"),
            new BundledDictionary.Binding("v0", "number", -1, 7, 7, 9, "10", "10"),
            new BundledDictionary.Binding("v0", "number", 5, 7, 7, 9, "10", "11")))
            assertEquals("INVALID_BINDING", p.dictionaryEncoding(new BundledDictionary.Match(target, false, List.of("fixture"), List.of(binding))).reason());
    }
    @Test void romanVariableBindingsDisambiguateWholeNativeNameSpans() {
        var p = paragraph(t("Gain ", ChatFormatting.GRAY).copy().append(t("Haste IV", ChatFormatting.BLUE))
            .append(t(", then ", ChatFormatting.GRAY)).append(t("Haste IV", ChatFormatting.RED)).append(t(".", ChatFormatting.GRAY)));
        String source = p.sourceText(), target = "後でHaste IV、先にHaste IVを得る。";
        int a = source.indexOf("IV"), b = source.lastIndexOf("IV");
        var match = new BundledDictionary.Match(target, false, List.of("fixture"), List.of(
            new BundledDictionary.Binding("v0", "roman", a, a + 2, target.lastIndexOf("IV"), target.lastIndexOf("IV") + 2, "IV", "IV"),
            new BundledDictionary.Binding("v1", "roman", b, b + 2, target.indexOf("IV"), target.indexOf("IV") + 2, "IV", "IV")));
        assertFalse(p.dictionaryEncoding(target).accepted());
        var result = p.dictionaryEncoding(match);
        assertTrue(result.accepted(), result.reason());
        assertEquals(Collections.nCopies(8, 0xFF5555), colors(p.decode(result.text()), "Haste IV"));
    }
    @Test void normalizedTemplateOffsetsMapBackAcrossNbspThinSpacesAndWrappedLines() {
        var p = new StyledParagraph(List.of(t("Gain\u00a0\u2009", ChatFormatting.GRAY).copy().append(t("10%", ChatFormatting.GREEN))
            .append(t(" alpha and", ChatFormatting.GRAY)), t("  ", ChatFormatting.GRAY).copy().append(t("10%", ChatFormatting.RED)).append(t(" beta.", ChatFormatting.GRAY))));
        String source = BundledDictionary.normalize(p.sourceText()), target = "betaが10%、alphaが10%増加する。";
        int a = source.indexOf("10"), b = source.lastIndexOf("10");
        var match = new BundledDictionary.Match(target, false, List.of("fixture"), List.of(
            new BundledDictionary.Binding("v0", "number", a, a + 2, target.lastIndexOf("10"), target.lastIndexOf("10") + 2, "10", "10"),
            new BundledDictionary.Binding("v1", "number", b, b + 2, target.indexOf("10"), target.indexOf("10") + 2, "10", "10")));
        var encoded = p.dictionaryEncoding(match);
        assertTrue(encoded.accepted(), encoded.reason() + encoded.details());
        assertEquals(List.of(0xFF5555, 0xFF5555, 0xFF5555), colors(p.decode(encoded.text()), "10%"));
    }
    @Test void leadingNbspCannotMoveTheFirstVariablePastItsSingleCharacterSpan() {
        var p = paragraph(t("\u00a0", ChatFormatting.GRAY).copy().append(t("V", ChatFormatting.BLUE))
            .append(t(" and ", ChatFormatting.GRAY)).append(t("V", ChatFormatting.RED)).append(t(" become active.", ChatFormatting.GRAY)));
        String target = "固定のVと可変のVが有効になる。";
        int targetStart = target.lastIndexOf("V");
        var match = new BundledDictionary.Match(target, false, List.of("fixture"), List.of(
            new BundledDictionary.Binding("v0", "roman", 0, 1, targetStart, targetStart + 1, "V", "V")));
        var result = p.dictionaryEncoding(match);
        assertTrue(result.accepted(), result.reason() + result.details());
        var values = StyledParagraph.glyphs(p.decode(result.text())).stream().filter(g -> g.text().equals("V")).toList();
        assertEquals(List.of(0xFF5555, 0x5555FF), values.stream().map(g -> g.style().getColor().getValue()).toList());
    }
    @Test void nativeStatPunctuationIsProtectedAndShortUnitUsesItsQuantity() {
        var p = paragraph(t("Gain ", ChatFormatting.GRAY).copy().append(t("1⸕", ChatFormatting.RED))
            .append(t(" and ", ChatFormatting.GRAY)).append(t("2⸕", ChatFormatting.RED))
            .append(t(" for ", ChatFormatting.GRAY)).append(t("10s", ChatFormatting.GREEN)).append(t(".", ChatFormatting.GRAY)));
        var output = translated(p, "10秒間、1⸕と2⸕を得る。");
        assertEquals(List.of(0x55FF55, 0x55FF55, 0x55FF55), colors(output, "10秒"));
        assertEquals(List.of(0xFF5555), colors(output, "⸕"));
        assertFalse(p.dictionaryEncoding("10秒間、1と2を得る。").accepted());
    }
    @Test void repeatedIconsKeepTheirOwnStatColorsWhenStatsAreReordered() {
        var p = paragraph(t("Gain ", ChatFormatting.GRAY).copy().append(t("☘", ChatFormatting.GREEN))
            .append(t(" Mining Fortune and ", ChatFormatting.GRAY)).append(t("☘", ChatFormatting.RED))
            .append(t(" Farming Fortune.", ChatFormatting.GRAY)));
        var output = translated(p, "Farming Fortune☘とMining Fortune☘が増加する。");
        assertEquals(List.of(0xFF5555, 0x55FF55), StyledParagraph.glyphs(output).stream()
            .filter(g -> g.text().equals("☘")).map(g -> g.style().getColor().getValue()).toList());
        assertFalse(p.dictionaryEncoding("☘と☘が増加する。").accepted());
    }
    @Test void prefixMultiplierCanRemainBeforeValueOrMoveAfterIt() {
        var p = paragraph(t("Deals ", ChatFormatting.GRAY).copy().append(t("x2", ChatFormatting.GREEN)).append(t(" damage.", ChatFormatting.GRAY)));
        assertEquals(List.of(0x55FF55, 0x55FF55), colors(translated(p, "ダメージがx2になる。"), "x2"));
        assertEquals(List.of(0x55FF55, 0x55FF55), colors(translated(p, "ダメージが2倍になる。"), "2倍"));
        assertFalse(p.dictionaryEncoding("ダメージがx3になる。").accepted());
    }
    @Test void sameColorRepeatedAccentsNeedEqualOccurrenceCountAndDifferentColorsStayAmbiguous() {
        var p = paragraph(t("Grants ", ChatFormatting.GRAY).copy().append(t("rewards", ChatFormatting.GOLD))
            .append(t(" and bonus ", ChatFormatting.GRAY)).append(t("rewards", ChatFormatting.GOLD)).append(t(".", ChatFormatting.GRAY)));
        var output = translated(p, "報酬と追加の報酬を得る。");
        assertEquals(4, StyledParagraph.glyphs(output).stream().filter(g -> (g.text().equals("報") || g.text().equals("酬")) && g.style().getColor().getValue() == 0xFFAA00).count());
        assertFalse(p.dictionaryEncoding("報酬を得る。").accepted());
        var ambiguous = paragraph(t("Gain ", ChatFormatting.GRAY).copy().append(t("rewards", ChatFormatting.GOLD))
            .append(t(" and bonus ", ChatFormatting.GRAY)).append(t("rewards", ChatFormatting.BLUE)).append(t(".", ChatFormatting.GRAY)));
        assertEquals("STYLE_ALIGNMENT_AMBIGUOUS", ambiguous.dictionaryEncoding("rewardsと追加の報酬を得る。").reason());
    }
    @Test void expandedScalarGrammarKeepsSignsDecimalMagnitudeAndPermilleExact() {
        var p = paragraph(t("Gain ", ChatFormatting.GRAY).copy().append(t("+.5T‰", ChatFormatting.GREEN)).append(t(" Health.", ChatFormatting.GRAY)));
        var output = translated(p, "Healthが+.5T‰増加する。");
        assertEquals(Collections.nCopies(5, 0x55FF55), colors(output, "+.5T‰"));
        assertFalse(p.dictionaryEncoding("Healthが+.5T%増加する。").accepted());
        assertFalse(p.dictionaryEncoding("Healthが.5T‰増加する。").accepted());
    }
}
