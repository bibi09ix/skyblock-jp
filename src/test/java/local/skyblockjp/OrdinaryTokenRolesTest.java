package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class OrdinaryTokenRolesTest {
    private static final String SOURCE = "Your Health stat increases your maximum health.";
    private static final String TARGET = "Healthのステータスは、最大体力を増やす。";
    @TempDir Path dir;

    private static Component t(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color);
    }

    private static Map<String, Object> rule(String source, String literal, int start) {
        return new LinkedHashMap<>(
                Map.of(
                        "id",
                        "test.role",
                        "approved",
                        true,
                        "source",
                        source,
                        "source_sha256",
                        DictionaryAlignment.sha256(source),
                        "reason",
                        "Reviewed ordinary wording.",
                        "ordinary_spans",
                        List.of(
                                Map.of(
                                        "start",
                                        start,
                                        "end",
                                        start + literal.length(),
                                        "text",
                                        literal))));
    }

    private static String resource(List<Map<String, Object>> rules) {
        return new Gson()
                .toJson(
                        Map.of(
                                "schema_version",
                                1,
                                "normalization",
                                "bundled-source-v1",
                                "rules",
                                rules));
    }

    private static OrdinaryTokenRoles.Index load(String text) {
        return OrdinaryTokenRoles.load(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void color(Component output, String fragment, ChatFormatting expected) {
        int start = output.getString().indexOf(fragment);
        assertTrue(start >= 0);
        int offset = 0;
        for (var glyph : StyledParagraph.glyphs(output)) {
            if (offset >= start && offset < start + fragment.length())
                assertEquals(t("x", expected).getStyle().getColor(), glyph.style().getColor());
            offset += glyph.text().length();
        }
    }

    @Test
    void bundledSourceRoleExemptsOnlyTheReviewedLowercaseOccurrence() {
        assertTrue(OrdinaryTokenRoles.stats().loaded(), OrdinaryTokenRoles.stats().error());
        assertEquals(Set.of("health"), OrdinaryTokenRoles.wordsForSource(SOURCE));
        assertEquals(
                Set.of("health"),
                OrdinaryTokenRoles.wordsForSource(SOURCE.replace("Your ", "Your\u00a0")));
        for (String other :
                List.of(
                        SOURCE + "!",
                        SOURCE.replace("Health", "Defense"),
                        "Your maximum health increases.",
                        "Gain +10 health."))
            assertTrue(OrdinaryTokenRoles.wordsForSource(other).isEmpty(), other);
        var paragraph = new StyledParagraph(List.of(t(SOURCE, ChatFormatting.GRAY)));
        assertEquals(SOURCE, paragraph.decode(paragraph.encoded()).getString());
        assertTrue(paragraph.encoded().contains("<t0>Health</t0>"));
        assertFalse(paragraph.encoded().contains(">health</t"));
        assertTrue(paragraph.dictionaryEncoding(TARGET).accepted());
    }

    @Test
    void formalStatAndOrdinaryNounKeepTheirSeparateNativeColors() {
        var source =
                t("Your ", ChatFormatting.GRAY)
                        .copy()
                        .append(t("Health", ChatFormatting.RED))
                        .append(t(" stat increases your maximum ", ChatFormatting.GRAY))
                        .append(t("health", ChatFormatting.BLUE))
                        .append(t(".", ChatFormatting.GRAY));
        var paragraph = new StyledParagraph(List.of(source));
        var result = paragraph.dictionaryEncoding(TARGET);
        assertTrue(result.accepted(), result.reason() + result.details());
        var output = paragraph.decode(result.text());
        assertEquals(TARGET, output.getString());
        color(output, "Health", ChatFormatting.RED);
        color(output, "体力", ChatFormatting.BLUE);
        assertFalse(paragraph.dictionaryEncoding(TARGET.replace("Health", "体力")).accepted());
        assertFalse(paragraph.dictionaryEncoding(TARGET.replace("Health", "Defense")).accepted());
        assertFalse(
                paragraph.dictionaryEncoding(TARGET.replace("Health", "HealthとHealth")).accepted());
    }

    @Test
    void actualDictionaryLookupAndDisabledDictionaryPathsRemainCompatible() throws Exception {
        var paragraph = new StyledParagraph(List.of(t(SOURCE, ChatFormatting.GRAY)));
        var match = BundledDictionary.loadBundled().inspect("ui", SOURCE, "").match();
        assertTrue(match.isPresent());
        assertTrue(paragraph.dictionaryEncoding(match.get()).accepted());
        var config = new Config();
        config.candidateDictionary = false;
        config.apiTranslation = true;
        config.provider = "GOOGLE";
        var calls = new AtomicInteger();
        try (var translator =
                new Translator(
                        config,
                        dir.resolve("api.json"),
                        LoggerFactory.getLogger("ordinary-role-test"),
                        text -> {
                            calls.incrementAndGet();
                            return "<t0>Health</t0>のステータスは、最大体力を増やす。";
                        })) {
            assertEquals(
                    TARGET,
                    paragraph
                            .decode(
                                    translator
                                            .request(paragraph, "scope=ui; item=Health")
                                            .get(3, TimeUnit.SECONDS))
                            .getString());
            assertEquals(1, calls.get());
        }
        config.apiTranslation = false;
        try (var translator =
                new Translator(
                        config,
                        dir.resolve("disabled.json"),
                        LoggerFactory.getLogger("ordinary-role-test"),
                        text -> {
                            fail("Disabled translation must not invoke the provider");
                            return "";
                        })) {
            assertNull(translator.request(paragraph, "scope=ui; item=Health").join());
        }
    }

    @Test
    void staleHashWrongOffsetAndUnapprovedMetadataCannotRelaxProtection() {
        var valid = rule(SOURCE, "health", 40);
        var loaded = load(resource(List.of(valid)));
        assertTrue(loaded.stats().loaded());
        assertEquals(Set.of("health"), loaded.words(SOURCE));
        var stale = new LinkedHashMap<>(valid);
        stale.put("source_sha256", "0".repeat(64));
        assertEquals("SOURCE_HASH_MISMATCH", load(resource(List.of(stale))).stats().error());
        var offset = rule(SOURCE, "health", 39);
        assertEquals("SPAN_MISMATCH", load(resource(List.of(offset))).stats().error());
        var unapproved = new LinkedHashMap<>(valid);
        unapproved.put("approved", false);
        assertTrue(load(resource(List.of(unapproved))).words(SOURCE).isEmpty());
        assertTrue(OrdinaryTokenRoles.load(null).sources().isEmpty());
    }

    @Test
    void uppercaseNamesNumbersGlyphsAndIdentifierFragmentsCannotBeOrdinary() {
        for (var sample :
                Map.of(
                                "Health",
                                SOURCE,
                                "Jake",
                                "Meet Jake.",
                                "10",
                                "Gain 10 Health.",
                                "❤",
                                "Gain 10❤ Health.")
                        .entrySet()) {
            var data =
                    rule(
                            sample.getValue(),
                            sample.getKey(),
                            sample.getValue().indexOf(sample.getKey()));
            assertEquals("UNSAFE_ORDINARY_WORD", load(resource(List.of(data))).stats().error());
        }
        assertEquals(
                "SPAN_NOT_WHOLE_WORD",
                load(resource(List.of(rule("health_123", "health", 0)))).stats().error());
    }

    @Test
    void mixedLowercaseRolesAndDuplicateFieldsFailClosed() {
        String source = "Your health stat increases your maximum health.";
        assertEquals(
                "MIXED_OR_UNANNOTATED_OCCURRENCES",
                load(resource(List.of(rule(source, "health", source.lastIndexOf("health")))))
                        .stats()
                        .error());
        String duplicate =
                resource(List.of(rule(SOURCE, "health", 40)))
                        .replace(
                                "\"schema_version\":1",
                                "\"schema_version\":1,\"schema_version\":1");
        assertEquals("DUPLICATE_FIELD", load(duplicate).stats().error());
        var duplicateSource = rule(SOURCE, "health", 40);
        duplicateSource.put("id", "another.id");
        assertEquals(
                "DUPLICATE_SOURCE",
                load(resource(List.of(rule(SOURCE, "health", 40), duplicateSource)))
                        .stats()
                        .error());
        var empty = rule(SOURCE, "health", 40);
        empty.put("ordinary_spans", List.of());
        assertEquals("MISSING_ORDINARY_SPANS", load(resource(List.of(empty))).stats().error());
    }
}
