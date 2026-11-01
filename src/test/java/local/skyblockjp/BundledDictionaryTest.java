package local.skyblockjp;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BundledDictionaryTest {
    private static final Gson JSON = new Gson();

    @Test void foldsOnlyFormattingAndUnicodeWhitespace() {
        var dictionary = dictionary(entry("item", "", "Gain 50 Health.", "Healthが50増加する。", false));
        assertTrue(dictionary.stats().loaded());
        assertEquals("Healthが50増加する。", dictionary.lookup("item", " §aGain\n 50\u00a0Health.\u2003", "").orElseThrow().text());
        assertTrue(dictionary.lookup("item", "gain 50 Health.", "").isEmpty());
        assertTrue(dictionary.lookup("item", "Gain 500 Health.", "").isEmpty());
        assertTrue(dictionary.lookup("item", "Gain 50 Health!", "").isEmpty());
        assertEquals("a b c", BundledDictionary.normalize("\u00a0a\t\u2007b\u202fc\n"));
    }

    @Test void sameNpcWordsHaveIndependentSpeakerContexts() {
        var dictionary = dictionary(
                entry("npc", "Jerry", "Hello!", "こんにちは！", false),
                entry("npc", "Elle", "Hello!", "やあ！", false),
                entry("common", "", "Hello!", "どうも！", false));
        assertEquals("こんにちは！", dictionary.lookup("npc", "Hello!", "§e[NPC] Jerry").orElseThrow().text());
        assertEquals("やあ！", dictionary.lookup("npc", "Hello!", "Elle").orElseThrow().text());
        assertEquals("やあ！", dictionary.lookup("npc", "Hello!", "§c[BOSS] Elle").orElseThrow().text());
        assertTrue(dictionary.lookup("npc", "Hello!", "Unknown").isEmpty());
        assertTrue(dictionary.lookup("npc", "Hello!", "").isEmpty());
        assertTrue(dictionary.lookup("npc", "Hello!", "jerry").isEmpty());
    }

    @Test void playerChatCannotHitNpcSystemOrCommonEntries() {
        var dictionary = dictionary(
                entry("npc", "Jerry", "Hello!", "こんにちは！", false),
                entry("system", "", "Hello!", "システム通知！", false),
                entry("common", "", "Hello!", "共通文！", false));
        for (String channel : List.of("chat", "player", "player_chat", "NPC", ""))
            assertTrue(dictionary.lookup(channel, "Hello!", "Jerry").isEmpty(), channel);
        assertTrue(dictionary.lookup(null, "Hello!", "Jerry").isEmpty());
        assertTrue(dictionary.lookup("item", null, "").isEmpty());
    }

    @Test void itemDoesNotBorrowUiOrSystemTranslations() {
        var dictionary = dictionary(
                entry("ui", "", "Click to view!", "クリックして表示！", false),
                entry("system", "", "Hello!", "こんにちは！", false));
        assertTrue(dictionary.lookup("item", "Click to view!", "").isEmpty());
        assertTrue(dictionary.lookup("item", "Hello!", "").isEmpty());
        assertEquals("クリックして表示！", dictionary.lookup("ui", "Click to view!", "").orElseThrow().text());
    }

    @Test void collisionsStayAmbiguousEvenAfterAnotherMatchingDuplicate() {
        var dictionary = dictionary(
                entry("item", "", "Hello!", "こんにちは！", false),
                entry("item", "", "§aHello!", "やあ！", false),
                entry("item", "", "Hello!", "こんにちは！", false),
                entry("common", "", "Hello!", "こんにちは！", false));
        assertTrue(dictionary.lookup("item", "Hello!", "").isEmpty());
        assertEquals(1, dictionary.stats().conflictingKeys());
        assertEquals(1, dictionary.stats().availableKeys());
    }

    @Test void disagreementAcrossCommonAndSpecificContextIsAlsoAMiss() {
        var dictionary = dictionary(
                entry("item", "", "Hello!", "こんにちは！", false),
                entry("common", "", "Hello!", "やあ！", false));
        assertTrue(dictionary.lookup("item", "Hello!", "").isEmpty());
        assertEquals("やあ！", dictionary.lookup("ui", "Hello!", "").orElseThrow().text());
    }

    @Test void matchingDuplicatesMergeAuditIdsAndExposeImmutableData() {
        var first = entry("item", "", "Hello!", "こんにちは！", false);
        Map<String, Object> second = Map.of("channel", "item", "speaker", "", "source", "Hello!", "target", "こんにちは！",
                "keep_original", false, "source_ids", List.of("second"));
        var dictionary = dictionary(first, second);
        var match = dictionary.lookup("item", "Hello!", "").orElseThrow();
        assertEquals(List.of("test-source", "second"), match.sourceIds());
        assertEquals(1, dictionary.stats().availableKeys());
        assertThrows(UnsupportedOperationException.class, () -> match.sourceIds().add("other"));
        assertThrows(UnsupportedOperationException.class, () -> dictionary.stats().rejectionReasons().put("other", 1));
    }

    @Test void intentionalPreservationRequiresAnIdentityTarget() {
        var dictionary = dictionary(
                entry("item", "", "Aspect of the End", "Aspect of the End", true),
                entry("item", "", "Hello!", "こんにちは！", true));
        var match = dictionary.lookup("item", "Aspect of the End", "").orElseThrow();
        assertTrue(match.keepOriginal());
        assertEquals("Aspect of the End", match.text());
        assertTrue(dictionary.lookup("item", "Hello!", "").isEmpty());
        assertEquals(1, dictionary.stats().rejectionReasons().get("invalid_preservation"));
    }

    @Test void unresolvedPlaceholdersTagsScriptAndControlsAreRejected() {
        List<String> unsafe = List.of("Gain {0} Health.", "Gain # Health.", "<s0>Hello</s0>",
                "&lt;Hello&gt;", "javascript:alert(1)", "window.alert(1)", "Hello\u202e!", "Hello\u0000!", "Hello§z!");
        for (String source : unsafe) {
            var dictionary = dictionary(entry("item", "", source, source, true));
            assertTrue(dictionary.stats().loaded(), source);
            assertEquals(1, dictionary.stats().rejectedEntries(), source);
            assertTrue(dictionary.lookup("item", source, "").isEmpty(), source);
        }
        var prose = dictionary(entry("npc", "Jerry", "Look outside my window. Before dawn!", "夜明け前に窓の外を見て！", false));
        assertEquals(1, prose.stats().availableKeys());
    }

    @Test void alteredNumbersSignsUnitsAndProtectedGlyphsAreRejected() {
        var dictionary = dictionary(
                entry("item", "", "Gain 50 Health.", "Healthが500増加する。", false),
                entry("item", "", "Gain +50 Health.", "Healthが50増加する。", false),
                entry("item", "", "Costs 10k Coins.", "費用は10K Coins。", false),
                entry("item", "", "Gain 50 ❤.", "50増加する。", false),
                entry("item", "", "Gain 50 \ue001.", "50増加する。", false),
                entry("item", "", "Gain 50 ❤.", "❤が50増加する。", false));
        assertEquals(5, dictionary.stats().rejectedEntries());
        assertEquals("❤が50増加する。", dictionary.lookup("item", "Gain 50 ❤.", "").orElseThrow().text());
        assertEquals(3, dictionary.stats().rejectionReasons().get("number_mismatch"));
        assertEquals(2, dictionary.stats().rejectionReasons().get("symbol_mismatch"));
    }

    @Test void invalidEntriesAreSkippedButDocumentErrorsDiscardEverything() {
        var dictionary = dictionary(
                entry("player", "", "Hello!", "こんにちは！", false),
                entry("npc", "", "Hello!", "こんにちは！", false),
                entry("item", "Jerry", "Hello!", "こんにちは！", false),
                entry("item", "", "Valid!", "有効！", false));
        assertEquals(3, dictionary.stats().rejectedEntries());
        assertEquals("有効！", dictionary.lookup("item", "Valid!", "").orElseThrow().text());
        String valid = document(List.of(entry("item", "", "Valid!", "有効！", false)));
        for (String invalid : List.of(valid.substring(0, valid.length() - 2), valid + " trailing",
                valid.replace("\"candidate\"", "\"verified\""), valid.replace("\"schema_version\":1", "\"schema_version\":2"),
                valid.replace("\"tier\":", "\"tier\":\"candidate\",\"tier\":"),
                valid.replace("Valid!", "Valid\n!"), valid.replace("\"candidate\"", "'candidate'"))) {
            var failed = load(invalid);
            assertFalse(failed.stats().loaded());
            assertEquals(0, failed.stats().availableKeys());
            assertTrue(failed.lookup("item", "Valid!", "").isEmpty());
            assertTrue(failed.stats().error().length() < 128);
        }
    }

    @Test void allResourceLimitsFailClosedOrRejectTheOversizedEntry() {
        String twoEntries = document(List.of(entry("item", "", "Hello!", "こんにちは！", false),
                entry("item", "", "Valid!", "有効！", false)));
        for (var limits : List.of(new BundledDictionary.Limits(32, 20, 100, 20, 100),
                new BundledDictionary.Limits(4096, 1, 100, 20, 100))) {
            var failed = BundledDictionary.load(stream(twoEntries), limits);
            assertFalse(failed.stats().loaded());
            assertTrue(failed.lookup("item", "Hello!", "").isEmpty());
            assertEquals(limits.resourceBytes() == 32 ? "resource_byte_limit" : "entry_limit", failed.stats().error());
        }
        var tooLong = BundledDictionary.load(stream(twoEntries), new BundledDictionary.Limits(4096, 20, 3, 20, 100));
        assertTrue(tooLong.stats().loaded());
        assertEquals(2, tooLong.stats().rejectedEntries());
        String tooManyIds = document(List.of(Map.of("channel", "item", "speaker", "", "source", "Hello!",
                "target", "こんにちは！", "keep_original", false, "source_ids", List.of("one", "two"))));
        assertFalse(BundledDictionary.load(stream(tooManyIds), new BundledDictionary.Limits(4096, 20, 100, 1, 100)).stats().loaded());
        var longId = BundledDictionary.load(stream(twoEntries), new BundledDictionary.Limits(4096, 20, 100, 20, 3));
        assertEquals(2, longId.stats().rejectedEntries());
    }

    @Test void missingResourceBadUtf8AndIoErrorsCannotPreventStartup() {
        assertFalse(BundledDictionary.load(null).stats().loaded());
        assertTrue(BundledDictionary.empty().lookup("item", "Hello!", "").isEmpty());
        assertFalse(BundledDictionary.load(new ByteArrayInputStream(new byte[]{(byte) 0xc3, (byte) 0x28})).stats().loaded());
        assertFalse(BundledDictionary.load(new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("untrusted resource contents"); }
        }).stats().loaded());
    }

    @Test void bundledCandidatePassesTheSameRuntimeDefenses() throws Exception {
        var dictionary = BundledDictionary.loadBundled();
        assertTrue(dictionary.stats().loaded(), dictionary.stats().error());
        assertTrue(dictionary.stats().availableKeys() > 0);
        assertEquals(0, dictionary.stats().rejectedEntries(), dictionary.stats().rejectionReasons().toString());
        try (var input = getClass().getResourceAsStream(BundledDictionary.RESOURCE);
                var reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8)) {
            var resource = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            assertEquals(resource.getAsJsonArray("blocked_keys").size(), dictionary.stats().conflictingKeys());
        }
    }

    @SafeVarargs private static BundledDictionary dictionary(Map<String, Object>... entries) {
        return load(document(List.of(entries)));
    }

    private static Map<String, Object> entry(String channel, String speaker, String source, String target, boolean keep) {
        return Map.of("channel", channel, "speaker", speaker, "source", source, "target", target,
                "keep_original", keep, "source_ids", List.of("test-source"));
    }

    private static String document(List<? extends Map<String, ?>> entries) {
        return JSON.toJson(Map.of("schema_version", 1, "build_id", "test-build", "tier", "candidate", "entries", entries));
    }

    private static BundledDictionary load(String json) { return BundledDictionary.load(stream(json)); }
    private static ByteArrayInputStream stream(String json) { return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)); }
}
