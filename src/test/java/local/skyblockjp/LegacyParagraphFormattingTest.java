package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class LegacyParagraphFormattingTest {
    private static Component c(String text, Style style) {
        return Component.literal(text).setStyle(style);
    }

    private static StyledParagraph paragraph(Component... lines) {
        return new StyledParagraph(List.of(lines));
    }

    private static Style style(int rgb) {
        return Style.EMPTY.withColor(rgb);
    }

    private static void color(Component component, String phrase, int rgb) {
        int start = component.getString().indexOf(phrase);
        assertTrue(start >= 0, component.getString());
        int at = 0;
        for (var glyph : StyledParagraph.glyphs(component)) {
            if (at >= start && at < start + phrase.length())
                assertEquals(rgb, glyph.style().getColor().getValue(), phrase);
            at += glyph.text().length();
        }
    }

    private static Style at(StyledParagraph paragraph, String phrase) {
        int start = paragraph.sourceText().indexOf(phrase);
        assertTrue(start >= 0, paragraph.sourceText());
        int at = 0;
        for (var glyph : paragraph.sourceGlyphs()) {
            if (at == start) return glyph.style();
            at += glyph.text().length();
        }
        throw new AssertionError(phrase);
    }

    @Test
    void actualThreeSkyHanniLegacyParagraphsHaveVisibleSourceAndNativeColors() {
        var inherited = style(0xAA00AA).withItalic(true);
        var attribution = paragraph(c("§8(From SkyHanni)", inherited));
        assertEquals("(From SkyHanni)", attribution.sourceText());
        assertEquals(0x555555, at(attribution, "(From").getColor().getValue());
        assertFalse(at(attribution, "(From").isItalic());
        var command =
                paragraph(c("§7Click here to run", inherited), c("§e/chocolatefactory", inherited));
        assertEquals("Click here to run /chocolatefactory", command.sourceText());
        assertEquals(0xAAAAAA, at(command, "Click").getColor().getValue());
        assertEquals(0xFFFF55, at(command, "/chocolatefactory").getColor().getValue());
        assertFalse(command.encoded().contains("§"));
        assertFalse(
                command.encoded().contains("<n"),
                "Formatting digits are never protected numeric values");
        var control = paragraph(c("§7Ctrl + Click to open config", inherited));
        assertEquals("Ctrl + Click to open config", control.sourceText());
        assertEquals(0xAAAAAA, at(control, "Ctrl").getColor().getValue());
        assertFalse(at(control, "Ctrl").isItalic());
    }

    @Test
    void actualLegacyCommandsReachPlainDictionaryEntriesAndPreserveCommandColor() {
        var inherited = style(0xAA00AA).withItalic(true);
        var paragraphs =
                List.of(
                        paragraph(c("§8(From SkyHanni)", inherited)),
                        paragraph(
                                c("§7Click here to run", inherited),
                                c("§e/chocolatefactory", inherited)),
                        paragraph(c("§7Ctrl + Click to open config", inherited)));
        for (int i = 0; i < paragraphs.size(); i++) {
            var p = paragraphs.get(i);
            String channel = i == 0 ? "item" : "ui";
            var match =
                    BundledDictionary.loadBundled()
                            .lookup(channel, p.sourceText(), "")
                            .orElseThrow(() -> new AssertionError(p.sourceText()));
            var encoded = p.dictionaryEncoding(match, false);
            assertTrue(
                    encoded.accepted(),
                    p.sourceText() + ": " + encoded.reason() + encoded.details());
            var output = p.decode(encoded.text());
            assertEquals(match.text(), output.getString());
            if (i == 1) color(output, "/chocolatefactory", 0xFFFF55);
        }
    }

    @Test
    void allSixteenLegacyColorsAreCaseInsensitiveAndClearLegacyEmphasis() {
        int[] palette = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
        };
        for (char code : "0123456789abcdef".toCharArray())
            for (char candidate : new char[] {code, Character.toUpperCase(code)}) {
                var p =
                        paragraph(
                                c(
                                        "§" + candidate + "text",
                                        style(0x123456)
                                                .withBold(true)
                                                .withItalic(true)
                                                .withUnderlined(true)
                                                .withStrikethrough(true)
                                                .withObfuscated(true)));
                assertEquals("text", p.sourceText());
                var actual = at(p, "text");
                assertEquals(
                        palette["0123456789abcdef".indexOf(code)], actual.getColor().getValue());
                assertFalse(actual.isBold());
                assertFalse(actual.isItalic());
                assertFalse(actual.isUnderlined());
                assertFalse(actual.isStrikethrough());
                assertFalse(actual.isObfuscated());
            }
    }

    @Test
    void cumulativeLegacyEmphasisAndResetRetainTheInheritedFontRgbAndMetadata() {
        var font =
                new FontDescription.Resource(
                        Identifier.fromNamespaceAndPath("skyblock_jp", "test_font"));
        var inherited =
                style(0x123456)
                        .withFont(font)
                        .withInsertion("native-insertion")
                        .withShadowColor(0x334455);
        var p = paragraph(c("§kO§lB§mS§nU§oI§rN", inherited));
        assertEquals("OBS UIN".replace(" ", ""), p.sourceText());
        assertTrue(at(p, "O").isObfuscated());
        assertTrue(at(p, "B").isBold());
        assertTrue(at(p, "S").isStrikethrough());
        assertTrue(at(p, "U").isUnderlined());
        assertTrue(at(p, "I").isItalic());
        var reset = at(p, "N");
        assertEquals(inherited, reset);
        for (var glyph : p.sourceGlyphs()) {
            assertEquals(font, glyph.style().getFont());
            assertEquals("native-insertion", glyph.style().getInsertion());
            assertEquals(0x334455, glyph.style().getShadowColor());
            assertEquals(0x123456, glyph.style().getColor().getValue());
        }
    }

    @Test
    void colorResetReturnsToInheritedNativeStyleRatherThanEmptyOrLastLegacyColor() {
        var inherited = style(0x112233).withBold(true).withItalic(true).withUnderlined(true);
        var p = paragraph(c("before §cred §lbold§r native", inherited));
        assertEquals(0xFF5555, at(p, "red").getColor().getValue());
        assertFalse(at(p, "red").isBold());
        assertFalse(at(p, "red").isItalic());
        assertTrue(at(p, "bold").isBold());
        assertEquals(inherited, at(p, "native"));
    }

    @Test
    void nativeSiblingRunsOverridePreviousLegacyStateAndPreserveInheritedStyles() {
        var blue = style(0x234567).withItalic(true);
        var component =
                c("§cparent", blue)
                        .copy()
                        .append(Component.literal(" inherited"))
                        .append(c(" explicit", style(0x55FF55)));
        var p = paragraph(component);
        assertEquals("parent inherited explicit", p.sourceText());
        assertEquals(0xFF5555, at(p, "parent").getColor().getValue());
        assertFalse(at(p, "parent").isItalic());
        assertEquals(0x234567, at(p, "inherited").getColor().getValue());
        assertTrue(at(p, "inherited").isItalic());
        assertEquals(0x55FF55, at(p, "explicit").getColor().getValue());
        assertTrue(at(p, "explicit").isItalic());
    }

    @Test
    void legacyStateDoesNotLeakAcrossSeparateTooltipLines() {
        var p = paragraph(c("§cred", style(0xAAAAAA)), c("normal", style(0xAAAAAA)));
        assertEquals("red normal", p.sourceText());
        assertEquals(0xFF5555, at(p, "red").getColor().getValue());
        assertEquals(0xAAAAAA, at(p, "normal").getColor().getValue());
    }

    @Test
    void unknownTruncatedAndLiteralSectionSignsAreNotDiscarded() {
        for (String text :
                List.of(
                        "literal §z sign",
                        "unsupported §x text",
                        "literal §# sign",
                        "trailing §",
                        "§",
                        "paragraph § 2",
                        "unicode §K is literal")) {
            var p = paragraph(c(text, style(0x123456)));
            assertEquals(text, p.sourceText());
            assertEquals(text, p.decode(p.encoded()).getString());
            assertTrue(
                    p.sourceGlyphs().stream()
                            .allMatch(g -> g.style().getColor().getValue() == 0x123456));
        }
    }

    @Test
    void unknownLiteralCodesRemainAlongsideValidColorChangesAndReset() {
        var p = paragraph(c("§z literal §a10§r native §", style(0x112233)));
        assertEquals("§z literal 10 native §", p.sourceText());
        assertEquals(0x55FF55, at(p, "10").getColor().getValue());
        assertEquals(0x112233, at(p, "native").getColor().getValue());
        assertTrue(p.encoded().contains("§z"));
    }

    @Test
    void normalizedNumbersAndNamesKeepTheirActualLegacyColorsDuringJapaneseRendering() {
        var p =
                paragraph(
                        c(
                                "§7Gain §a10 §cHealth§7 and §b20 §aDefense§7.",
                                style(0xAA00AA).withItalic(true)));
        assertEquals("Gain 10 Health and 20 Defense.", p.sourceText());
        var result = p.dictionaryEncoding("Healthが10、Defenseが20増加する。");
        assertTrue(result.accepted(), result.reason());
        assertTrue(StyledParagraph.validNumbers(p.encoded(), result.text()));
        var output = p.decode(result.text());
        color(output, "10", 0x55FF55);
        color(output, "Health", 0xFF5555);
        color(output, "20", 0x55FFFF);
        color(output, "Defense", 0x55FF55);
        assertFalse(p.encoded().contains("§"));
    }

    @Test
    void nativeRgbComponentsWithoutLegacyCodesRemainByteForByteStyledGlyphEquivalent() {
        var component =
                c("Gain ", style(0xAAAAAA))
                        .copy()
                        .append(c("10", style(0x12AB34).withBold(true)))
                        .append(c(" Health.", style(0xFF5555).withItalic(true)));
        var p = paragraph(component);
        assertEquals(component.getString(), p.sourceText());
        assertEquals(StyledParagraph.glyphs(component), p.sourceGlyphs());
    }
}
