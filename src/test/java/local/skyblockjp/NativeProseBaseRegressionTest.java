package local.skyblockjp;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native skill rewards: a long colored stat name must not elect the prose color. */
class NativeProseBaseRegressionTest {
    private static Component c(String text, int rgb) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(rgb).withItalic(false));
    }
    private static List<Component> fixtureLines(JsonObject row) {
        var result = new ArrayList<Component>();
        for (var rawLine : row.getAsJsonArray("lines")) {
            var line = Component.empty();
            for (var rawRun : rawLine.getAsJsonArray()) {
                var run = rawRun.getAsJsonObject();
                var style = Style.EMPTY;
                if (run.has("color")) style = style.withColor(run.get("color").getAsInt());
                style = style.withBold(run.get("bold").getAsBoolean()).withItalic(run.get("italic").getAsBoolean())
                    .withUnderlined(run.get("underlined").getAsBoolean()).withStrikethrough(run.get("strikethrough").getAsBoolean())
                    .withObfuscated(run.get("obfuscated").getAsBoolean());
                line.append(Component.literal(run.get("text").getAsString()).setStyle(style));
            }
            result.add(line);
        }
        return result;
    }
    private static Component render(StyledParagraph paragraph, String target) {
        var encoded = paragraph.dictionaryEncoding(target);
        assertTrue(encoded.accepted(), () -> encoded.reason() + encoded.details());
        assertTrue(StyledParagraph.validMarkers(paragraph.encoded(), encoded.text()));
        assertTrue(StyledParagraph.validNumbers(paragraph.encoded(), encoded.text()));
        return paragraph.decode(encoded.text());
    }
    private static List<StyledParagraph.Glyph> phrase(Component text, String phrase) {
        int start = text.getString().indexOf(phrase);
        assertTrue(start >= 0, text.getString());
        int offset = 0;
        var result = new ArrayList<StyledParagraph.Glyph>();
        for (var glyph : StyledParagraph.glyphs(text)) {
            if (offset >= start && offset < start + phrase.length()) result.add(glyph);
            offset += glyph.text().length();
        }
        return result;
    }
    private static void color(Component text, String phrase, int rgb) {
        assertTrue(phrase(text, phrase).stream().allMatch(g -> g.style().getColor() != null && g.style().getColor().getValue() == rgb), phrase);
    }

    @Test void exactNativeFishingRewardUsesWhiteProseAndRetainsEveryNativeValueColor() throws Exception {
        try (var input = getClass().getResourceAsStream("/native-prose-base-fixtures.json")) {
            assertNotNull(input);
            var fixtures = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
            assertEquals(1, fixtures.size());
            var row = fixtures.get(0).getAsJsonObject();
            var paragraph = new StyledParagraph(fixtureLines(row));
            assertEquals(row.get("source").getAsString(), paragraph.sourceText());
            var found = BundledDictionary.loadBundled().inspect("ui", paragraph.sourceText(), "").match();
            assertTrue(found.isPresent());
            assertEquals(row.get("target").getAsString(), found.get().text());
            assertTrue(found.get().sourceIds().contains("derived/skill_body/006b9d4d7ee2e08320736575"));
            var encoding = paragraph.dictionaryEncoding(found.get());
            assertTrue(encoding.accepted(), () -> encoding.reason() + encoding.details());
            var output = paragraph.decode(encoding.text());
            assertEquals(row.get("target").getAsString(), output.getString());
            color(output, "+", 0x55FF55);
            color(output, "3.4", 0x555555);
            color(output, "➜", 0x555555);
            color(output, "3.5", 0x55FF55);
            color(output, "\ue025", 0xFFAA00);
            color(output, "Treasure Chance", 0xFFAA00);
            color(output, "を獲得する。", 0xFFFFFF);
            assertEquals("Grants +3.4➜3.5 \ue025 Treasure Chance.", paragraph.decode(paragraph.encoded()).getString());
            assertFalse(output.getString().contains("<s"));
        }
    }

    @Test void longLeadingProtectedNamesAndQuantitiesDoNotOutvoteWhiteOrGrayProse() {
        for (int prose : List.of(0xFFFFFF, 0xAAAAAA)) {
            var source = c("Mining Fortune", 0xFFAA00).copy().append(c(" improves by ", prose))
                .append(c("+500,000", 0x55FF55)).append(c(".", prose));
            var output = render(new StyledParagraph(List.of(source)), "Mining Fortuneが+500,000増加する。");
            color(output, "Mining Fortune", 0xFFAA00);
            color(output, "+500,000", 0x55FF55);
            color(output, "が", prose);
            color(output, "増加する。", prose);
        }
    }

    @Test void grayOnlyOnProtectedArrowCannotElectTheColorOfAquaProse() {
        var source = c("Gain ", 0x55FFFF).copy().append(c("+5", 0x55FF55)).append(c("➜", 0xAAAAAA))
            .append(c("10", 0x55FF55)).append(c(" Mining Fortune", 0xFFAA00));
        var output = render(new StyledParagraph(List.of(source)), "Mining Fortuneが+5➜10増加する");
        color(output, "が", 0x55FFFF);
        color(output, "増加する", 0x55FFFF);
        color(output, "➜", 0xAAAAAA);
        color(output, "Mining Fortune", 0xFFAA00);
    }

    @Test void whiteProtectedNameCannotOutvoteAnOtherwiseColoredProseBody() {
        var source = c("Mining Fortune", 0xFFFFFF).copy().append(c(" grants ", 0x55FFFF)).append(c("+5", 0x55FF55));
        var output = render(new StyledParagraph(List.of(source)), "Mining Fortuneを+5獲得する");
        color(output, "Mining Fortune", 0xFFFFFF);
        color(output, "を", 0x55FFFF);
        color(output, "獲得する", 0x55FFFF);
    }

    @Test void defaultProseDoesNotInheritTheColorOfItsLongProtectedName() {
        // Use an empty root so the default prose does not inherit the name's gold.
        var source = Component.empty().append(c("Mining Fortune", 0xFFAA00)).append(Component.literal(" grants "))
            .append(c("+5", 0x55FF55));
        var output = render(new StyledParagraph(List.of(source)), "Mining Fortuneを+5獲得する");
        color(output, "Mining Fortune", 0xFFAA00);
        assertTrue(phrase(output, "を").stream().allMatch(g -> g.style().getColor() == null));
    }

    @Test void genuineLongWhiteAccentRemainsWhiteWithGrayProseAndConnectors() {
        var source = c("Hit ", 0xAAAAAA).copy().append(c("nearby enemies", 0xFFFFFF)).append(c(".", 0xAAAAAA));
        var output = render(new StyledParagraph(List.of(source)), "周囲の敵に命中する。");
        color(output, "周囲の敵", 0xFFFFFF);
        color(output, "に", 0xAAAAAA);
        color(output, "命中する。", 0xAAAAAA);
    }

    @Test void grayItalicFlavorProseOutranksARegularColoredUnknownName() {
        var source = Component.empty()
            .append(c("Use ", 0xAAAAAA).copy().withStyle(s -> s.withItalic(true)))
            .append(c("Power Crystal", 0x5555FF))
            .append(c(" to finish.", 0xAAAAAA).copy().withStyle(s -> s.withItalic(true)));
        var output = render(new StyledParagraph(List.of(source)), "Power Crystalで仕上げる。");
        color(output, "Power Crystal", 0x5555FF);
        assertTrue(phrase(output, "Power Crystal").stream().noneMatch(g -> g.style().isItalic()));
        color(output, "で仕上げる。", 0xAAAAAA);
        assertTrue(phrase(output, "で仕上げる。").stream().allMatch(g -> g.style().isItalic()));
    }

    @Test void aLongUnknownWhiteBoldAccentDoesNotElectTheBaseOfRegularBlueProse() {
        var source = c("Gain ", 0x5555FF).copy()
            .append(c("curiously humming beyond comprehension", 0xFFFFFF).copy().withStyle(s -> s.withBold(true)))
            .append(c(" power while mining.", 0x5555FF));
        var paragraph = new StyledParagraph(List.of(source));
        var rejected = paragraph.dictionaryEncoding("採掘中に未知の力を得る。");
        assertFalse(rejected.accepted());
        assertEquals("STYLE_ALIGNMENT_MISSING", rejected.reason());
        assertTrue(paragraph.encoded().contains("curiously humming beyond comprehension</s"));
    }

    @Test void unknownColoredOrBoldAccentStillRequiresAnExplicitMapping() {
        for (int prose : List.of(0xFFFFFF, 0xAAAAAA)) {
            for (boolean bold : List.of(false, true)) {
                var accent = c("curiously humming", bold ? 0xAAAAAA : 0xFF5555).copy().withStyle(s -> s.withBold(bold));
                var source = c("Gain ", prose).copy().append(accent).append(c(" power while mining.", prose));
                var paragraph = new StyledParagraph(List.of(source));
                var rejected = paragraph.dictionaryEncoding("採掘中に不思議な力を得る。");
                assertFalse(rejected.accepted());
                assertEquals("STYLE_ALIGNMENT_MISSING", rejected.reason());
                assertTrue(paragraph.encoded().contains("curiously humming</s"));
            }
        }
    }

    @Test void valueOnlyParagraphKeepsItsNativeStylesAndLegacyFallback() {
        var source = c("10", 0x55FF55).copy().append(c(" ➜ ", 0xAAAAAA)).append(c("20", 0xFF5555));
        var paragraph = new StyledParagraph(List.of(source));
        var output = paragraph.decode(paragraph.encoded());
        assertEquals("10 ➜ 20", output.getString());
        color(output, "10", 0x55FF55);
        color(output, "➜", 0xAAAAAA);
        color(output, "20", 0xFF5555);
    }

    @Test void nativeSourceSnapshotKeepsWhitespaceColorsAndExplicitJoinSemantics() {
        var original = Component.empty().append(c("  ", 0xAA00AA)).append(c("✆ ", 0xAAAAAA)).append(c("If left", 0xFFFFFF));
        var paragraph = new StyledParagraph(List.of(original, Component.empty(),
            Component.empty().append(c("  ", 0x55FF55)).append(c("unchecked.  ", 0xFFFFFF))));
        assertEquals("✆ If left unchecked.", paragraph.sourceText());
        var nativeGlyphs = paragraph.sourceGlyphs();
        assertEquals(paragraph.sourceText(), nativeGlyphs.stream().map(StyledParagraph.Glyph::text).collect(java.util.stream.Collectors.joining()));
        assertEquals("✆", nativeGlyphs.get(0).text());
        assertEquals(" ", nativeGlyphs.get(1).text());
        assertEquals(0xAAAAAA, nativeGlyphs.get(0).style().getColor().getValue());
        assertEquals(0xAAAAAA, nativeGlyphs.get(1).style().getColor().getValue());
        assertTrue(nativeGlyphs.subList(2, nativeGlyphs.size()).stream().allMatch(g -> g.style().getColor().getValue() == 0xFFFFFF));
        // Caller mutation cannot change the retained diagnostic source.
        original.append(c(" mutated", 0xFF5555));
        assertEquals("✆ If left unchecked.", paragraph.sourceGlyphs().stream().map(StyledParagraph.Glyph::text).collect(java.util.stream.Collectors.joining()));
        assertThrows(UnsupportedOperationException.class, () -> nativeGlyphs.add(new StyledParagraph.Glyph("x", Style.EMPTY)));
    }
}
