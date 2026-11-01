package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import org.junit.jupiter.api.Test;

class CommonStylePolicyRegressionTest {
    private static boolean flag(JsonObject row, String key) {
        return row.has(key) && row.get(key).getAsBoolean();
    }

    private static int number(JsonObject row, String key, int fallback) {
        return row.has(key) ? row.get(key).getAsInt() : fallback;
    }

    private static int occurrence(String text, String needle, int number) {
        int at = -1;
        for (int i = 0; i <= number; i++) {
            at = text.indexOf(needle, at + 1);
            assertTrue(at >= 0, needle + " in " + text);
        }
        return at;
    }

    private static List<Component> lines(JsonArray data) {
        var lines = new ArrayList<Component>();
        for (var rawLine : data) {
            var line = Component.empty();
            for (var raw : rawLine.getAsJsonArray()) {
                var row = raw.getAsJsonObject();
                var style = Style.EMPTY;
                if (row.has("color")) style = style.withColor(row.get("color").getAsInt());
                style =
                        style.withBold(flag(row, "bold"))
                                .withItalic(flag(row, "italic"))
                                .withUnderlined(flag(row, "underlined"))
                                .withStrikethrough(flag(row, "strikethrough"))
                                .withObfuscated(flag(row, "obfuscated"));
                line.append(Component.literal(row.get("text").getAsString()).setStyle(style));
            }
            lines.add(line);
        }
        return lines;
    }

    private static DictionaryAlignment.Plan annotation(
            JsonObject row, String source, String target) {
        if (!row.has("annotations")) return null;
        var spans = new ArrayList<DictionaryAlignment.Span>();
        int id = 0;
        source = BundledDictionary.normalize(source);
        for (var raw : row.getAsJsonArray("annotations")) {
            var value = raw.getAsJsonObject();
            String from = value.get("source").getAsString(), to = value.get("target").getAsString();
            String kind = value.has("kind") ? value.get("kind").getAsString() : "ordinary";
            int start = occurrence(source, from, number(value, "sourceOccurrence", 0)),
                    targetStart = occurrence(target, to, number(value, "targetOccurrence", 0));
            spans.add(
                    new DictionaryAlignment.Span(
                            "span-" + id++,
                            kind,
                            start,
                            start + from.length(),
                            targetStart,
                            targetStart + to.length(),
                            from,
                            to,
                            kind.equals("ordinary"),
                            false));
        }
        String hash =
                flag(row, "staleHash") ? "stale" : row.get("annotationSourceSha256").getAsString();
        return new DictionaryAlignment.Plan(
                row.get("id").getAsString(),
                row.get("channel").getAsString(),
                source,
                target,
                hash,
                row.get("annotationTargetSha256").getAsString(),
                true,
                List.of(),
                spans);
    }

    private static void color(Component component, JsonObject expected) {
        String text = expected.get("text").getAsString();
        int start = occurrence(component.getString(), text, number(expected, "occurrence", 0));
        int end = start + number(expected, "length", text.length()), offset = 0;
        for (var glyph : StyledParagraph.glyphs(component)) {
            if (offset < end && offset + glyph.text().length() > start) {
                assertNotNull(glyph.style().getColor(), text);
                assertEquals(
                        expected.get("color").getAsInt(),
                        glyph.style().getColor().getValue(),
                        text);
                if (expected.has("bold"))
                    assertEquals(flag(expected, "bold"), glyph.style().isBold(), text);
                if (expected.has("underlined"))
                    assertEquals(flag(expected, "underlined"), glyph.style().isUnderlined(), text);
            }
            offset += glyph.text().length();
        }
    }

    @Test
    void verifiedNativeAndCounterexampleFixturesUseTheProductionPath() throws Exception {
        try (var input = getClass().getResourceAsStream("/common-style-policy-fixtures.json")) {
            assertNotNull(input);
            var fixtures =
                    JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonArray();
            assertEquals(40, fixtures.size());
            for (var raw : fixtures) {
                var row = raw.getAsJsonObject();
                String id = row.get("id").getAsString(), target = row.get("target").getAsString();
                var paragraph = new StyledParagraph(lines(row.getAsJsonArray("lines")));
                var plan = annotation(row, paragraph.sourceText(), target);
                var result =
                        paragraph.dictionaryEncoding(
                                new BundledDictionary.Match(
                                        target, false, List.of("fixture"), List.of(), plan));
                boolean expected = row.get("expectedPrototype").getAsString().equals("ACCEPTED");
                assertEquals(
                        expected,
                        result.accepted(),
                        () -> id + ": " + result.reason() + " " + result.details());
                if (!result.accepted()) continue;
                var decoded = paragraph.decode(result.text());
                assertEquals(target, decoded.getString(), id);
                assertTrue(StyledParagraph.validNumbers(paragraph.encoded(), result.text()), id);
                if (row.has("colorChecks"))
                    for (var check : row.getAsJsonArray("colorChecks"))
                        color(decoded, check.getAsJsonObject());
                if (flag(row, "identityGlyphs"))
                    assertEquals(paragraph.sourceGlyphs(), StyledParagraph.glyphs(decoded), id);
            }
        }
    }

    @Test
    void identicalBindingsRetainMixedUsernameStylesAndRepeatedDigits() {
        var source =
                Component.literal("Hello ")
                        .withStyle(ChatFormatting.WHITE)
                        .append(Component.literal("Probe_").withStyle(ChatFormatting.BLUE))
                        .append(Component.literal("12").withStyle(ChatFormatting.RED))
                        .append(Component.literal("_").withStyle(ChatFormatting.BLUE))
                        .append(Component.literal("12").withStyle(ChatFormatting.GOLD))
                        .append(Component.literal("!").withStyle(ChatFormatting.WHITE));
        var paragraph = new StyledParagraph(List.of(source));
        String target = "こんにちは、Probe_12_12！", name = "Probe_12_12";
        int start = paragraph.sourceText().indexOf(name), at = target.indexOf(name);
        var binding =
                new BundledDictionary.Binding(
                        "local-player",
                        "name",
                        start,
                        start + name.length(),
                        at,
                        at + name.length(),
                        name,
                        name);
        var result =
                paragraph.dictionaryEncoding(
                        new BundledDictionary.Match(
                                target, false, List.of("fixture"), List.of(binding)));
        assertTrue(result.accepted(), result.reason() + result.details());
        var output = paragraph.decode(result.text());
        var expected = StyledParagraph.glyphs(source).subList(start, start + name.length());
        var actual = StyledParagraph.glyphs(output).subList(at, at + name.length());
        assertEquals(expected, actual);
    }

    @Test
    void explicitAndInferredBindingsMustAgreeOnOccurrenceIdentity() {
        String source = "Lasts 4 seconds and stacks 4 times.", target = "最大4回重複し、4秒続く。";
        int sourceStart = source.indexOf('4'), wrongTarget = target.indexOf('4');
        var wrong =
                new BundledDictionary.Binding(
                        "wrong",
                        "number",
                        sourceStart,
                        sourceStart + 1,
                        wrongTarget,
                        wrongTarget + 1,
                        "4",
                        "4");
        assertEquals(
                "SEMANTIC_BINDING_CONFLICT",
                QuantityBindings.combine(source, target, List.of(wrong)).reason());
        int correctTarget = target.lastIndexOf('4');
        var correct =
                new BundledDictionary.Binding(
                        "right",
                        "number",
                        sourceStart,
                        sourceStart + 1,
                        correctTarget,
                        correctTarget + 1,
                        "4",
                        "4");
        assertEquals("", QuantityBindings.combine(source, target, List.of(correct)).reason());
    }

    @Test
    void nativeIdentityKeepsWhitespaceStylesAndExistingMinuteDisplayWithoutExposingSnapshot() {
        var original =
                Component.literal("Gain")
                        .withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(" ").withStyle(ChatFormatting.RED))
                        .append(Component.literal("power").withStyle(ChatFormatting.WHITE));
        var paragraph = new StyledParagraph(List.of(original));
        assertEquals(
                paragraph.sourceGlyphs(),
                StyledParagraph.glyphs(paragraph.decode(paragraph.encoded())));
        var first = (MutableComponent) paragraph.decode(paragraph.encoded());
        ((MutableComponent) first.getSiblings().getFirst()).append("mutated");
        assertEquals("Gain power", paragraph.decode(paragraph.encoded()).getString());
        var duration =
                new StyledParagraph(
                        List.of(Component.literal("Lasts 60m.").withStyle(ChatFormatting.GRAY)));
        assertEquals("Lasts 60分.", duration.decode(duration.encoded()).getString());
        assertEquals("Lasts 60m.", duration.sourceText());
    }

    @Test
    void reviewedBodyRolesRequireHashConsensusAndRejectUnknownRoles() {
        String source = "For my wares, you'll have to pay the iron price!",
                target = "鉄の代償を払ってもらうぞ！";
        var anchor = new DictionaryAlignment.BodyAnchor(0, 12, "For my wares", "prose");
        var plan =
                new DictionaryAlignment.Plan(
                        "body",
                        "npc",
                        source,
                        target,
                        DictionaryAlignment.sha256(source),
                        DictionaryAlignment.sha256(target),
                        true,
                        List.of(),
                        List.of(),
                        anchor);
        assertEquals("", DictionaryAlignment.validate(plan, source, target));
        assertEquals(anchor, DictionaryAlignment.agreedBody(source, List.of(plan)));
        var absent =
                new DictionaryAlignment.Plan(
                        "absent",
                        "npc",
                        source,
                        target,
                        DictionaryAlignment.sha256(source),
                        DictionaryAlignment.sha256(target),
                        true,
                        List.of(),
                        List.of(
                                new DictionaryAlignment.Span(
                                        "phrase",
                                        "ordinary",
                                        source.indexOf("iron price"),
                                        source.indexOf("iron price") + 10,
                                        0,
                                        4,
                                        "iron price",
                                        "鉄の代償",
                                        true,
                                        false)));
        assertNull(DictionaryAlignment.agreedBody(source, List.of(plan, absent)));
        var unknown =
                new DictionaryAlignment.Plan(
                        "unknown",
                        "npc",
                        source,
                        target,
                        DictionaryAlignment.sha256(source),
                        DictionaryAlignment.sha256(target),
                        true,
                        List.of(),
                        List.of(),
                        new DictionaryAlignment.BodyAnchor(0, 12, "For my wares", "unknown"));
        assertEquals(
                "INVALID_BODY_ALIGNMENT", DictionaryAlignment.validate(unknown, source, target));
        var stale =
                new DictionaryAlignment.Plan(
                        "stale",
                        "npc",
                        source,
                        target,
                        "stale",
                        DictionaryAlignment.sha256(target),
                        true,
                        List.of(),
                        List.of(),
                        anchor);
        assertNull(DictionaryAlignment.agreedBody(source, List.of(plan, stale)));
    }

    @Test
    void critInflectionRequiresItsNativeStatGlyphAndCannotSingularizeNames() {
        var good =
                new StyledParagraph(
                        List.of(
                                Component.literal("Grants  ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(
                                                Component.literal("Crits")
                                                        .withStyle(ChatFormatting.BLUE))
                                        .append(
                                                Component.literal(".")
                                                        .withStyle(ChatFormatting.GRAY))));
        assertTrue(good.dictionaryEncoding(" Critを付与する。").accepted());
        var unknown =
                new StyledParagraph(
                        List.of(
                                Component.literal("Grants ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(
                                                Component.literal("Crits")
                                                        .withStyle(ChatFormatting.BLUE))
                                        .append(
                                                Component.literal(".")
                                                        .withStyle(ChatFormatting.GRAY))));
        assertFalse(unknown.dictionaryEncoding("Critを付与する。").accepted());
    }
}
