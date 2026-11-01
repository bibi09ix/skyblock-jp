package local.skyblockjp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class ApiDisabledDisplayIntegrationTest {
    @TempDir Path dir;

    private static final String KNOWN = "Restores 50 Health.";
    private static final String UNKNOWN = "A recently discovered effect.";
    private static final String KNOWN_JA = "Healthを50回復する。";
    private static final String UNKNOWN_JA = "最近発見された効果。";
    private static final TooltipContext.Decision CONTEXT = new TooltipContext.Decision(
        TooltipContext.Kind.ITEM, "SKYBLOCK_ITEM_ID", Map.of("skyblockItemId", "API_POLICY_FIXTURE"));
    private static final Function<StyledParagraph, String> NO_API_CACHE = paragraph -> {
        throw new AssertionError("API OFF must not consult saved/completed API results: " + paragraph.sourceText());
    };
    private static final Function<StyledParagraph, String> NO_DICTIONARY = paragraph -> {
        throw new AssertionError("Dictionary display policy must prevent this lookup: " + paragraph.sourceText());
    };

    private record Fixture(List<Component> original, LoreParser.Analysis analysis,
            List<LoreParser.Block> blocks, List<StyledParagraph> paragraphs) {}

    private static Fixture fixture() {
        var explanation = Component.literal("Restores ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("50").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" Health.").withStyle(ChatFormatting.GRAY));
        List<Component> original = List.of(Component.literal("Fixture"), Component.literal("Damage: +100"),
            Component.empty(), explanation, Component.empty(), Component.literal(UNKNOWN));
        var analysis = LoreParser.analyze(original.stream().map(Component::getString).toList(), CONTEXT);
        var blocks = analysis.paragraphs().stream().map(LoreParser.Paragraph::block).toList();
        assertEquals(List.of(KNOWN, UNKNOWN), blocks.stream().map(LoreParser.Block::text).toList());
        return new Fixture(original, analysis, blocks, blocks.stream()
            .map(block -> new StyledParagraph(original.subList(block.start(), block.end()))).toList());
    }

    private static Config config(boolean automatic) {
        var config = new Config();
        config.apiTranslation = false;
        config.candidateDictionary = true;
        config.dictionaryAutoDisplay = automatic;
        return config;
    }

    private static String encode(StyledParagraph paragraph, String target) {
        String encoded = paragraph.encodeDictionaryTranslation(target);
        assertNotNull(encoded, "Fixture translation must preserve source values and styles");
        assertTrue(StyledParagraph.validNumbers(paragraph.encoded(), encoded));
        return encoded;
    }

    private static String knownOnly(StyledParagraph paragraph) {
        return paragraph.sourceText().equals(KNOWN) ? encode(paragraph, KNOWN_JA) : null;
    }

    private static String allKnown(StyledParagraph paragraph) {
        return encode(paragraph, paragraph.sourceText().equals(KNOWN) ? KNOWN_JA : UNKNOWN_JA);
    }

    private static ItemTooltipTranslation.Result display(Fixture fixture, Config config, boolean requested,
            Function<StyledParagraph, String> dictionary, Function<StyledParagraph, String> completed) {
        return ItemTooltipTranslation.prepare(fixture.original(), fixture.blocks(), fixture.paragraphs(), config,
            requested, dictionary, completed, List::of);
    }

    @Test void newAndLegacyConfigurationsKeepApiEnabledUntilExplicitlyDisabled() throws Exception {
        Path fresh = dir.resolve("fresh.json");
        assertTrue(new Config().apiTranslation);
        assertTrue(Config.load(fresh).apiTranslation);
        assertTrue(Config.load(fresh).apiTranslation);

        Path legacy = dir.resolve("legacy.json");
        Files.writeString(legacy, """
            {"candidateDictionary":true,"dictionaryAutoDisplay":false,"provider":"GOOGLE",
             "groqApiKey":"fixture-key","groqModel":"fixture-model","translateKey":72,
             "translationHud":true,"hudX":0.25,"hudY":0.8,"hudTextScale":1.5}
            """);
        var loaded = Config.load(legacy);
        assertTrue(loaded.apiTranslation, "A missing new field must retain pre-upgrade API behavior");
        assertTrue(loaded.candidateDictionary);
        assertFalse(loaded.dictionaryAutoDisplay);
        assertEquals("GOOGLE", loaded.provider);
        assertEquals("fixture-key", loaded.groqApiKey);
        assertEquals("fixture-model", loaded.groqModel);
        assertEquals(72, loaded.translateKey);
        assertTrue(loaded.translationHud);
        assertEquals(0.25, loaded.hudX);
        assertEquals(0.8, loaded.hudY);
        assertEquals(1.5, loaded.hudTextScale);
    }

    @Test void explicitOffRoundTripsWithoutResettingCredentialsKeyBindingsOrHud() throws Exception {
        Path file = dir.resolve("off.json");
        var config = config(false);
        config.provider = "GROQ";
        config.groqApiKey = "fixture-off-key";
        config.groqModel = "fixture-custom-model";
        config.translateKey = 82;
        config.translationHud = true;
        config.hudX = 0.3; config.hudY = 0.7;
        config.hudWidth = 520; config.hudLines = 16;
        config.hudOpacity = 0.35; config.hudTextScale = 1.75;
        config.hudSeconds = 0;
        Config.save(file, config);

        var restored = Config.load(file);
        assertFalse(restored.apiTranslation);
        assertTrue(restored.candidateDictionary);
        assertFalse(restored.dictionaryAutoDisplay);
        assertEquals("GROQ", restored.provider);
        assertEquals("fixture-off-key", restored.groqApiKey);
        assertEquals("fixture-custom-model", restored.groqModel);
        assertEquals(82, restored.translateKey);
        assertTrue(restored.translationHud);
        assertEquals(0.3, restored.hudX); assertEquals(0.7, restored.hudY);
        assertEquals(520, restored.hudWidth); assertEquals(16, restored.hudLines);
        assertEquals(0.35, restored.hudOpacity); assertEquals(1.75, restored.hudTextScale);
        assertEquals(0, restored.hudSeconds);
        restored.apiTranslation = true;
        Config.save(file, restored);
        assertTrue(Config.load(file).apiTranslation, "The API switch must also be reversible");
    }

    @Test void partialDictionaryIsImmediateAndNeverPendingEvenAfterManualRequest() {
        var fixture = fixture();
        for (boolean requested : List.of(false, true)) {
            AtomicInteger lookups = new AtomicInteger();
            var result = display(fixture, config(true), requested, paragraph -> {
                lookups.incrementAndGet();
                return knownOnly(paragraph);
            }, NO_API_CACHE);
            assertEquals(ItemTooltipTranslation.Mode.DICTIONARY, result.mode());
            assertEquals(1, result.complete()); assertEquals(2, result.total());
            assertEquals(1, result.dictionaryMatches());
            assertEquals(2, lookups.get(), "Each local paragraph should be looked up once per display pass");
            assertEquals(List.of(KNOWN_JA, UNKNOWN), result.paragraphs());
            assertEquals(KNOWN_JA, result.lines().get(3).getString());
            assertEquals(UNKNOWN, result.lines().get(5).getString());
            assertEquals(fixture.original().get(1), result.lines().get(1), "Stat rows must stay original");
            var number = StyledParagraph.glyphs(result.lines().get(3)).stream()
                .filter(glyph -> glyph.text().equals("5")).findFirst().orElseThrow();
            assertEquals(0x55FF55, number.style().getColor().getValue());
        }
    }

    @Test void unknownManualRequestRetainsOriginalInsteadOfWaitingForAnImpossibleApiResult() {
        var fixture = fixture();
        var result = display(fixture, config(true), true, paragraph -> null, NO_API_CACHE);
        assertEquals(ItemTooltipTranslation.Mode.ORIGINAL, result.mode());
        assertEquals(0, result.complete()); assertEquals(0, result.dictionaryMatches());
        assertEquals(2, result.total());
        assertEquals(fixture.original(), result.lines());
        assertEquals(List.of(KNOWN, UNKNOWN), result.paragraphs());
    }

    @Test void automaticDisplayOffSkipsBothLookupsUntilTheUserRequestsTranslation() {
        var fixture = fixture();
        var untouched = display(fixture, config(false), false, NO_DICTIONARY, NO_API_CACHE);
        assertEquals(ItemTooltipTranslation.Mode.ORIGINAL, untouched.mode());
        assertEquals(fixture.original(), untouched.lines());
        assertEquals(0, untouched.dictionaryMatches());

        var requested = display(fixture, config(false), true,
            ApiDisabledDisplayIntegrationTest::knownOnly, NO_API_CACHE);
        assertEquals(ItemTooltipTranslation.Mode.DICTIONARY, requested.mode());
        assertEquals(List.of(KNOWN_JA, UNKNOWN), requested.paragraphs());
        assertEquals(KNOWN_JA, requested.lines().get(3).getString());
    }

    @Test void dictionaryMasterOffKeepsOriginalForAutomaticAndManualDisplay() {
        var fixture = fixture();
        for (boolean automatic : List.of(false, true)) {
            var config = config(automatic);
            config.candidateDictionary = false;
            for (boolean requested : List.of(false, true)) {
                var result = display(fixture, config, requested, NO_DICTIONARY, NO_API_CACHE);
                assertEquals(ItemTooltipTranslation.Mode.ORIGINAL, result.mode());
                assertEquals(fixture.original(), result.lines());
                assertEquals(List.of(KNOWN, UNKNOWN), result.paragraphs());
                assertEquals(0, result.dictionaryMatches());
            }
        }
    }

    @Test void disablingApiHidesPreviouslyAvailableCompletedResultsOnTheNextDisplayPass() {
        var fixture = fixture();
        var config = config(true);
        config.apiTranslation = true;
        AtomicInteger completedLookups = new AtomicInteger();
        Function<StyledParagraph, String> savedApi = paragraph -> {
            completedLookups.incrementAndGet();
            return allKnown(paragraph);
        };
        var before = display(fixture, config, true, paragraph -> null, savedApi);
        assertEquals(ItemTooltipTranslation.Mode.FULL, before.mode());
        assertEquals(List.of(KNOWN_JA, UNKNOWN_JA), before.paragraphs());
        assertEquals(2, completedLookups.get());

        config.apiTranslation = false;
        var after = display(fixture, config, true, paragraph -> null, savedApi);
        assertEquals(ItemTooltipTranslation.Mode.ORIGINAL, after.mode());
        assertEquals(fixture.original(), after.lines());
        assertEquals(2, completedLookups.get(), "OFF must not even read otherwise valid saved API results");
    }

    @Test void completeLocalDictionaryStillUsesDictionaryModeWhenApiIsDisabled() {
        var fixture = fixture();
        var result = display(fixture, config(false), true,
            ApiDisabledDisplayIntegrationTest::allKnown, NO_API_CACHE);
        assertEquals(ItemTooltipTranslation.Mode.DICTIONARY, result.mode());
        assertEquals(2, result.complete()); assertEquals(2, result.dictionaryMatches());
        assertEquals(List.of(KNOWN_JA, UNKNOWN_JA), result.paragraphs());
    }

    private JsonObject audit(Fixture fixture, Config config, boolean requested,
            ItemTooltipTranslation.Result result, boolean hasKnownEntry, String fileName) throws Exception {
        var decisions = fixture.paragraphs().stream().map(paragraph -> {
            String encoded = hasKnownEntry ? knownOnly(paragraph) : null;
            return encoded == null
                ? new Translator.DictionaryDecision(null, "not_found", "none", "item", List.of(),
                    Map.of("stage", "DICTIONARY_LOOKUP"))
                : new Translator.DictionaryDecision(encoded, "matched", "exact", "item", List.of("api-off-fixture"),
                    Map.of("stage", "ACCEPTED"));
        }).toList();
        Path path = dir.resolve(fileName);
        boolean dictionaryAllowed = config.candidateDictionary && (config.dictionaryAutoDisplay || requested);
        try (var audit = new TooltipAudit(path, LoggerFactory.getLogger("api-off-audit-test"), "")) {
            audit.recordDetailed(fileName, fixture.original(), result.lines(), result.paragraphs(), 260,
                config.provider, config.groqModel, "item", "Storage", fixture.blocks(), decisions,
                result.mode().name(), dictionaryAllowed, CONTEXT, fixture.analysis(),
                fixture.paragraphs().stream().map(paragraph -> new Translator.CacheInspection("API_DISABLED", "")).toList(),
                requested, config.candidateDictionary && config.dictionaryAutoDisplay);
        }
        return JsonParser.parseString(Files.readAllLines(path).getFirst()).getAsJsonObject();
    }

    @Test void unknownManualRequestLogsApiPolicySeparatelyFromTheDictionaryMiss() throws Exception {
        var fixture = fixture();
        var config = config(true);
        var result = display(fixture, config, true, paragraph -> null, NO_API_CACHE);
        var row = audit(fixture, config, true, result, false, "unknown.jsonl");
        assertEquals("ORIGINAL", row.get("provider").getAsString());
        assertFalse(row.get("apiTranslationEnabled").getAsBoolean());
        assertTrue(row.get("manualRequested").getAsBoolean());
        for (var element : row.getAsJsonArray("paragraphDecisions")) {
            var paragraph = element.getAsJsonObject();
            assertEquals("DICTIONARY_LOOKUP", paragraph.get("dictionaryStage").getAsString());
            assertEquals("POLICY", paragraph.get("stopStage").getAsString());
            assertEquals("API_TRANSLATION_DISABLED", paragraph.get("displayReason").getAsString());
            assertEquals("API_DISABLED", paragraph.get("apiState").getAsString());
            assertFalse(paragraph.get("apiTranslationEnabled").getAsBoolean());
            assertEquals("ORIGINAL", paragraph.get("supplier").getAsString());
        }
    }

    @Test void localTranslationAndDisabledApiMissHaveDifferentSuppliersInTheSameAudit() throws Exception {
        var fixture = fixture();
        var config = config(false);
        var result = display(fixture, config, true, ApiDisabledDisplayIntegrationTest::knownOnly, NO_API_CACHE);
        var row = audit(fixture, config, true, result, true, "mixed.jsonl");
        assertEquals("MIXED", row.get("provider").getAsString());
        assertFalse(row.get("apiTranslationEnabled").getAsBoolean());
        var local = row.getAsJsonArray("paragraphDecisions").get(0).getAsJsonObject();
        assertEquals("DICTIONARY", local.get("supplier").getAsString());
        assertEquals("NONE", local.get("stopStage").getAsString());
        assertEquals("TRANSLATION_APPLIED", local.get("displayReason").getAsString());
        assertEquals("API_DISABLED", local.get("apiState").getAsString());
        assertFalse(local.get("apiTranslationEnabled").getAsBoolean());
        assertEquals(KNOWN_JA, local.get("displayedText").getAsString());
        var missing = row.getAsJsonArray("paragraphDecisions").get(1).getAsJsonObject();
        assertEquals("ORIGINAL", missing.get("supplier").getAsString());
        assertEquals("DICTIONARY_LOOKUP", missing.get("dictionaryStage").getAsString());
        assertEquals("POLICY", missing.get("stopStage").getAsString());
        assertEquals("API_TRANSLATION_DISABLED", missing.get("displayReason").getAsString());
        assertEquals(UNKNOWN, missing.get("displayedText").getAsString());
    }

    @Test void idleKnownEntryWithAutomaticDisplayOffLogsDisplayPolicyRatherThanApiFailure() throws Exception {
        var fixture = fixture();
        var config = config(false);
        var result = display(fixture, config, false, NO_DICTIONARY, NO_API_CACHE);
        var row = audit(fixture, config, false, result, true, "automatic-off.jsonl");
        assertEquals("ORIGINAL", row.get("provider").getAsString());
        var local = row.getAsJsonArray("paragraphDecisions").get(0).getAsJsonObject();
        assertTrue(local.get("dictionaryAccepted").getAsBoolean());
        assertEquals("DISPLAY_POLICY", local.get("stopStage").getAsString());
        assertEquals("DICTIONARY_AUTO_DISPLAY_OFF", local.get("displayReason").getAsString());
        assertEquals("ORIGINAL", local.get("supplier").getAsString());
        assertEquals(KNOWN, local.get("displayedText").getAsString());
        var missing = row.getAsJsonArray("paragraphDecisions").get(1).getAsJsonObject();
        assertEquals("DICTIONARY_LOOKUP", missing.get("stopStage").getAsString());
        assertEquals("not_found", missing.get("displayReason").getAsString());
    }
}
