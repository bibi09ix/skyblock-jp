package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.Test;

class HexColorLiteralRegressionTest {
    private static final String SOURCE = "Changes the color of an armor piece to #7FFFD4!";
    private static final String TARGET = "防具の色を#7FFFD4に変更する！";

    private static BundledDictionary dictionary(String source, String target) {
        var entries =
                List.of(
                        Map.of(
                                "channel",
                                "item",
                                "speaker",
                                "",
                                "source",
                                source,
                                "target",
                                target,
                                "keep_original",
                                false,
                                "source_ids",
                                List.of("observed/item/armor_dye_rgb")));
        var json =
                new Gson()
                        .toJson(
                                Map.of(
                                        "schema_version",
                                        1,
                                        "build_id",
                                        "hex-color-fixture",
                                        "tier",
                                        "candidate",
                                        "entries",
                                        entries));
        var matching =
                new Gson()
                        .toJson(
                                Map.of(
                                        "schema_version",
                                        1,
                                        "build_id",
                                        "hex-color-fixture",
                                        "normalization",
                                        "ascii_case_single_period_stat_glyphs_v1",
                                        "aliases",
                                        List.of(),
                                        "templates",
                                        List.of()));
        return BundledDictionary.load(
                        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                .withMatching(new ByteArrayInputStream(matching.getBytes(StandardCharsets.UTF_8)));
    }

    private static StyledParagraph paragraph(String code) {
        var gray = Style.EMPTY.withColor(0xAAAAAA);
        var color = Style.EMPTY.withColor(0x7FFFD4).withBold(true);
        return new StyledParagraph(
                List.of(
                        Component.literal("Changes the color of an armor piece").setStyle(gray),
                        Component.empty()
                                .append(Component.literal("to ").setStyle(gray))
                                .append(Component.literal(code).setStyle(color))
                                .append(Component.literal("!").setStyle(gray))));
    }

    private static BundledDictionary.Match match(String target) {
        return new BundledDictionary.Match(target, false, List.of("observed/item/armor_dye_rgb"));
    }

    @Test
    void exactSixHexTokensHaveStrongBoundariesAndDoNotReplaceNumberedLabels() {
        for (String code : List.of("#7FFFD4", "#abcdef", "#000000", "#123456"))
            assertEquals(
                    Map.of(code.toUpperCase(java.util.Locale.ROOT), 1),
                    HexColorTokens.signature(code),
                    code);
        for (String source :
                List.of(
                        "#ABC",
                        "#ABCDEF0",
                        "#ABCDEF01",
                        "#7FFFD4G",
                        "x#7FFFD4",
                        "_#7FFFD4",
                        "0#7FFFD4",
                        "##7FFFD4",
                        "#7FFFD4_",
                        "#RRGGBB",
                        "#123",
                        "#12345678"))
            assertTrue(HexColorTokens.signature(source).isEmpty(), source);
    }

    @Test
    void sanitizerKeepsRealRgbLiteralsAndRejectsUnknownOrAttachedHashes() {
        assertTrue(BundledDictionary.safeText(SOURCE));
        assertEquals(SOURCE, BundledDictionary.normalize(SOURCE));
        for (String source :
                List.of(
                        "Armor #ABC",
                        "Armor #ABCDEF0",
                        "Armor #ABCDEF01",
                        "Armor #RRGGBB",
                        "Armor x#ABCDEF",
                        "Armor #ABCDEFx",
                        "Armor ##ABCDEF",
                        "Armor #ABCDEF_",
                        "Armor #{color}",
                        "Armor <#ABCDEF>")) assertFalse(BundledDictionary.safeText(source), source);
        for (String source :
                List.of(
                        "Rabbit #5",
                        "Your Ranking: #000",
                        "labeled #1 to #33333!",
                        "Position: #12345678")) {
            assertTrue(BundledDictionary.safeText(source), source);
            assertTrue(HexColorTokens.signature(source).isEmpty(), source);
        }
    }

    @Test
    void dictionaryLoadsTheActualDyeParagraphWithTheHashInItsKey() {
        var dictionary = dictionary(SOURCE, TARGET);
        assertTrue(dictionary.stats().loaded());
        assertEquals(0, dictionary.stats().rejectedEntries());
        assertEquals(TARGET, dictionary.lookup("item", SOURCE, "").orElseThrow().text());
        assertTrue(dictionary.lookup("item", SOURCE.replace("#", ""), "").isEmpty());
    }

    @Test
    void loaderRejectsChangedRgbLettersEvenWhenNumericChunksHaveNotChanged() {
        String target = TARGET.replace("7FFFD4", "7AAAD4");
        assertEquals(BundledDictionary.numbers(SOURCE), BundledDictionary.numbers(target));
        assertFalse(BundledDictionary.quantitiesPreserved(SOURCE, target));
        var dictionary = dictionary(SOURCE, target);
        assertEquals(1, dictionary.stats().rejectedEntries());
        assertEquals(1, dictionary.stats().rejectionReasons().get("rgb_color_mismatch"));
        assertTrue(dictionary.lookup("item", SOURCE, "").isEmpty());
    }

    @Test
    void entireCodeIsOneNativeAtomAndItsRgbAndBoldStyleFollowJapaneseWordOrder() {
        var paragraph = paragraph("#7FFFD4");
        assertEquals(SOURCE, paragraph.sourceText());
        assertTrue(paragraph.encoded().contains("<t0>#7FFFD4</t0>"), paragraph.encoded());
        assertFalse(paragraph.encoded().contains("<n"), paragraph.encoded());
        var encoded = paragraph.dictionaryEncoding(match(TARGET));
        assertTrue(encoded.accepted(), encoded.reason());
        var rendered = paragraph.decode(encoded.text());
        assertEquals(TARGET, rendered.getString());
        var glyphs = StyledParagraph.glyphs(rendered);
        int at = TARGET.indexOf('#');
        for (int i = at; i < at + 7; i++) {
            assertEquals(0x7FFFD4, glyphs.get(i).style().getColor().getValue());
            assertTrue(glyphs.get(i).style().isBold());
        }
    }

    @Test
    void semanticColorCaseVariantsRenderOriginalSourceSpellingAndNativeStyle() {
        var dictionary = dictionary(SOURCE, TARGET);
        String source = SOURCE.replace("7FFFD4", "7fffd4");
        assertTrue(HexColorTokens.preserved(source, TARGET));
        var lookedUp = dictionary.lookup("item", source, "").orElseThrow();
        var paragraph = paragraph("#7fffd4");
        var encoded = paragraph.dictionaryEncoding(lookedUp);
        assertTrue(encoded.accepted(), encoded.reason());
        var rendered = paragraph.decode(encoded.text());
        assertEquals(TARGET.replace("7FFFD4", "7fffd4"), rendered.getString());
        var glyphs = StyledParagraph.glyphs(rendered);
        int at = rendered.getString().indexOf('#');
        for (int i = at; i < at + 7; i++)
            assertEquals(0x7FFFD4, glyphs.get(i).style().getColor().getValue());
    }

    @Test
    void plainFallbackCannotDropAddOrChangeAFullColorAtom() {
        var paragraph = paragraph("#7FFFD4");
        for (String target :
                List.of(
                        TARGET.replace("#7FFFD4", ""),
                        TARGET + "#7FFFD4",
                        TARGET.replace("#7FFFD4", "#7FFFE4"),
                        TARGET.replace("#7FFFD4", "#ABC"),
                        TARGET.replace("#7FFFD4", "#7FFFD40"))) {
            for (boolean plain : List.of(false, true)) {
                var encoded = paragraph.dictionaryEncoding(match(target), plain);
                assertFalse(encoded.accepted(), target);
                assertEquals("PROTECTED_RGB_COLOR_CHANGED", encoded.reason());
                assertEquals("RGB_LITERAL_FIDELITY", encoded.details().get("validationStage"));
            }
        }
    }

    @Test
    void numericDigitsInsideColorsAreNotMistakenForOrdinaryQuantities() {
        assertTrue(
                BundledDictionary.quantitiesPreserved(
                        "Use 5 items with #ABCD12.", "#abcd12に5個使う。"));
        assertFalse(
                BundledDictionary.quantitiesPreserved(
                        "Use 5 items with #ABCD12.", "#ABCD12に6個使う。"));
        assertFalse(
                BundledDictionary.quantitiesPreserved(
                        "Use 5 items with #ABCD12.", "#ABCD12に5個と12個使う。"));
        assertFalse(
                BundledDictionary.numbers("#ABCD12").isEmpty(),
                "generic numeric utility retains raw behavior");
        var gray = Style.EMPTY.withColor(0xAAAAAA);
        var source =
                Component.empty()
                        .append(
                                Component.literal("Animates the color of an armor piece between ")
                                        .setStyle(gray))
                        .append(Component.literal("#034150").withStyle(ChatFormatting.RED))
                        .append(Component.literal(" and ").setStyle(gray))
                        .append(Component.literal("#009295").withStyle(ChatFormatting.BLUE))
                        .append(Component.literal("!").setStyle(gray));
        var paragraph = new StyledParagraph(List.of(source));
        assertFalse(paragraph.encoded().contains("<n"), paragraph.encoded());
        var encoded = paragraph.dictionaryEncoding(match("防具の色を#034150と#009295の間で変化させる！"));
        assertTrue(encoded.accepted(), encoded.reason());
        assertEquals("防具の色を#034150と#009295の間で変化させる！", paragraph.decode(encoded.text()).getString());
    }
}
