package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DictionaryMatchingTest {
    private static final Gson JSON = new Gson();

    private static Map<String, Object> entry(String channel, String source, String target) {
        return Map.of(
                "channel",
                channel,
                "speaker",
                "",
                "source",
                source,
                "target",
                target,
                "keep_original",
                false,
                "source_ids",
                List.of("fixture"));
    }

    private static Map<String, Object> template(
            String channel, String source, String target, String... types) {
        var vars = new java.util.ArrayList<Map<String, String>>();
        for (int i = 0; i < types.length; i++) vars.add(Map.of("name", "v" + i, "type", types[i]));
        return Map.of(
                "channel",
                channel,
                "speaker",
                "",
                "source",
                source,
                "target",
                target,
                "variables",
                vars,
                "source_ids",
                List.of("template"));
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static BundledDictionary base(List<Map<String, Object>> entries) {
        return BundledDictionary.load(
                stream(
                        JSON.toJson(
                                Map.of(
                                        "schema_version",
                                        1,
                                        "tier",
                                        "candidate",
                                        "build_id",
                                        "matching-fixture",
                                        "entries",
                                        entries))));
    }

    private static String sidecar(
            List<Map<String, Object>> aliases, List<Map<String, Object>> templates) {
        return JSON.toJson(
                Map.of(
                        "schema_version",
                        1,
                        "build_id",
                        "matching-fixture",
                        "normalization",
                        "ascii_case_single_period",
                        "aliases",
                        aliases,
                        "templates",
                        templates));
    }

    private static BundledDictionary matching(
            List<Map<String, Object>> entries,
            List<Map<String, Object>> aliases,
            List<Map<String, Object>> templates) {
        var d = base(entries).withMatching(stream(sidecar(aliases, templates)));
        assertTrue(d.matchingStats().loaded(), d.matchingStats().error());
        return d;
    }

    @Test
    void normalizesPresentationOnlyAndDoesNotGeneralizeLiteralNumbers() {
        var d =
                matching(
                        List.of(entry("item", "Gain 50 Health.", "Healthが50増加する。")),
                        List.of(),
                        List.of());
        assertEquals("Healthが50増加する。", d.lookup("item", "GAIN 50 health", "").orElseThrow().text());
        assertEquals("normalized", d.inspect("item", "gain 50 Health", "").mode());
        assertTrue(d.lookup("item", "Gain 500 Health.", "").isEmpty());
        assertTrue(d.lookup("item", "Gain 50 Health!", "").isEmpty());
        assertTrue(d.lookup("item", "Gain 50 ❤ Health", "").isEmpty());
        assertEquals("exact", d.inspect("item", "Gain 50 Health.", "").mode());
    }

    @Test
    void literalLoaderAcceptsOnlyKnownQuantityWordsAndPreservesEveryLiteralValue() {
        var good =
                base(
                        List.of(
                                entry(
                                        "item",
                                        "Doubles the pet's base stats during the Fishing Festival.",
                                        "Fishing Festivalの間、ペットの基本ステータスが2倍になる。")));
        assertEquals(0, good.stats().rejectedEntries());
        assertTrue(
                good.lookup("item", "Doubles the pet's base stats during the Fishing Festival.", "")
                        .isPresent());
        for (String target :
                List.of(
                        "30sの間、3倍になる。",
                        "31sの間、2倍になる。",
                        "30sの間、+2倍になる。",
                        "30sの間、2%増える。",
                        "30sの間、2と2を得る。",
                        "2倍になる。")) {
            var bad = base(List.of(entry("item", "Doubles the bonus for 30s.", target)));
            assertEquals(1, bad.stats().rejectedEntries(), target);
        }
        assertFalse(
                BundledDictionary.quantitiesPreserved("Doubles the 1k bonus.", "1Kのボーナスが2倍になる。"));
        assertFalse(
                BundledDictionary.quantitiesPreserved(
                        "Doubles the 1,000 bonus.", "1000のボーナスが2倍になる。"));
        assertFalse(BundledDictionary.quantitiesPreserved("Double-Layered bonus.", "2倍になる。"));
    }

    @Test
    void templateQuantityAllowanceDoesNotGeneralizeFixedOrBoundNumbers() {
        var d =
                matching(
                        List.of(),
                        List.of(),
                        List.of(
                                template(
                                        "item",
                                        "Doubles the bonus for {v0}s.",
                                        "{v0}sの間、ボーナスが2倍になる。",
                                        "number")));
        assertEquals(
                "30sの間、ボーナスが2倍になる。",
                d.lookup("item", "Doubles the bonus for 30s.", "").orElseThrow().text());
        assertTrue(d.lookup("item", "Triples the bonus for 30s.", "").isEmpty());
        var invalid =
                base(List.of())
                        .withMatching(
                                stream(
                                        sidecar(
                                                List.of(),
                                                List.of(
                                                        template(
                                                                "item",
                                                                "Doubles the bonus for {v0}s.",
                                                                "{v0}sの間、ボーナスが3倍になる。",
                                                                "number")))));
        assertFalse(invalid.matchingStats().loaded());
        var literal =
                matching(
                        List.of(entry("item", "Doubles the bonus for 30s.", "30sの間、ボーナスが2倍になる。")),
                        List.of(),
                        List.of());
        assertTrue(literal.lookup("item", "doubles the bonus for 31s", "").isEmpty());
    }

    @Test
    void caseOrPunctuationCollisionsFailClosedButExactSpellingsRemainAvailable() {
        var d =
                matching(
                        List.of(
                                entry("item", "Useful text.", "便利な説明。"),
                                entry("item", "useful text", "別の説明。")),
                        List.of(),
                        List.of());
        assertEquals("ambiguous", d.inspect("item", "USEFUL TEXT", "").reason());
        assertEquals("便利な説明。", d.lookup("item", "Useful text.", "").orElseThrow().text());
    }

    @Test
    void normalizationNeverChangesSignUnitCaseOrGlyph() {
        var d =
                matching(
                        List.of(
                                entry("item", "Gain +50 Health.", "Healthが+50増加する。"),
                                entry("item", "Spend 10k Coins.", "10k Coinsを消費する。")),
                        List.of(),
                        List.of());
        assertTrue(d.lookup("item", "gain 50 health", "").isEmpty());
        assertTrue(d.lookup("item", "spend 10K coins", "").isEmpty());
        assertTrue(d.lookup("item", "spend 10m coins", "").isEmpty());
    }

    @Test
    void verifiedAliasIsScopedAndDoesNotEnableArbitraryRewording() {
        var d =
                matching(
                        List.of(),
                        List.of(entry("ui", "View and manage all of your Pets.", "ペットを管理する。")),
                        List.of());
        assertEquals(
                "ペットを管理する。",
                d.lookup("ui", "view and manage all of your pets", "").orElseThrow().text());
        assertTrue(d.lookup("item", "View and manage all of your Pets.", "").isEmpty());
        assertTrue(d.lookup("ui", "Manage your pet collection.", "").isEmpty());
    }

    @Test
    void numericRomanTemplatePreservesLiveValueSpellingAndCanReorder() {
        var t =
                template(
                        "item",
                        "Grants +{v0} Mining Speed and permanent Haste {v1}.",
                        " Mining Speedが+{v0}増加し、常時Haste {v1}を得る。",
                        "number",
                        "roman");
        var d = matching(List.of(), List.of(), List.of(t));
        assertEquals(
                " Mining Speedが+150増加し、常時Haste IIIを得る。",
                d.lookup("item", "Grants +150 Mining Speed and permanent Haste III.", "")
                        .orElseThrow()
                        .text());
        assertEquals(
                "template",
                d.inspect("item", "Grants +12.5 Mining Speed and permanent Haste IV", "").mode());
        for (String level : List.of("iii", "21", "XXI", "Jerry"))
            assertTrue(
                    d.lookup(
                                    "item",
                                    "Grants +150 Mining Speed and permanent Haste " + level + ".",
                                    "")
                            .isEmpty(),
                    level);
        assertTrue(
                d.lookup("npc", "Grants +150 Mining Speed and permanent Haste III.", "Bob")
                        .isEmpty());
    }

    @Test
    void onlyDeclaredHolesVaryAndFixedNumbersStillHaveToMatch() {
        var d =
                matching(
                        List.of(),
                        List.of(),
                        List.of(
                                template(
                                        "common",
                                        "Gain {v0} Health for 30s.",
                                        "30sの間、Healthを{v0}得る。",
                                        "number")));
        assertEquals(
                "30sの間、Healthを1,000得る。",
                d.lookup("item", "Gain 1,000 Health for 30s.", "").orElseThrow().text());
        assertTrue(d.lookup("item", "Gain 1,000 Health for 31s.", "").isEmpty());
        assertTrue(d.lookup("item", "Gain Jerry Health for 30s.", "").isEmpty());
        assertTrue(d.lookup("item", "Gain 1234567890123 Health for 30s.", "").isEmpty());
    }

    @Test
    void repeatedVariableRequiresSameLiveValue() {
        var d =
                matching(
                        List.of(),
                        List.of(),
                        List.of(
                                template(
                                        "item",
                                        "Gain {v0} Health and restore {v0} Mana.",
                                        "Healthを{v0}得てManaを{v0}回復する。",
                                        "number")));
        assertTrue(d.lookup("item", "Gain 50 Health and restore 50 Mana.", "").isPresent());
        assertTrue(d.lookup("item", "Gain 50 Health and restore 60 Mana.", "").isEmpty());
    }

    @Test
    void uiVariablesAreExplicitlyWhitelistedAndCannotReachItemsOrPlayerChat() {
        var d =
                matching(
                        List.of(),
                        List.of(),
                        List.of(
                                template("ui", "Selected pet: {v0}", "選択中のペット: {v0}", "pet_name"),
                                template(
                                        "ui",
                                        "Progress to Level {v0}: {v1}%",
                                        "レベル{v0}までの進捗: {v1}%",
                                        "number",
                                        "number")));
        assertEquals(
                "選択中のペット: Skeleton",
                d.lookup("ui", "Selected pet: Skeleton", "").orElseThrow().text());
        assertEquals(
                "レベル99までの進捗: 43.9%",
                d.lookup("ui", "Progress to Level 99: 43.9%", "").orElseThrow().text());
        assertTrue(d.lookup("item", "Selected pet: Skeleton", "").isEmpty());
        assertTrue(d.lookup("player_chat", "Selected pet: Skeleton", "").isEmpty());
        assertTrue(d.lookup("ui", "Selected pet: Jerry: hi!", "").isEmpty());
        var bad =
                base(List.of())
                        .withMatching(
                                stream(
                                        sidecar(
                                                List.of(),
                                                List.of(
                                                        template(
                                                                "ui",
                                                                "Message: {v0}",
                                                                "文章: {v0}",
                                                                "pet_name")))));
        assertFalse(bad.matchingStats().loaded());
        assertEquals("unreviewed_ui_template", bad.matchingStats().error());
    }

    @Test
    void commonAndSpecificTemplatesMustAgree() {
        var d =
                matching(
                        List.of(),
                        List.of(),
                        List.of(
                                template("item", "Grants {v0} Health.", "Healthを{v0}得る。", "number"),
                                template(
                                        "common",
                                        "Grants {v0} Health.",
                                        "Healthが{v0}増える。",
                                        "number")));
        assertEquals("conflict", d.inspect("item", "Grants 10 Health.", "").reason());
    }

    @Test
    void malformedSidecarNeverWeakensLiteralValidationOrReplacesLiteralDictionary() {
        var entries = List.of(entry("item", "Useful text.", "便利な説明。"));
        String valid = sidecar(List.of(), List.of());
        for (String invalid :
                List.of(
                        valid + " trailing",
                        valid.replace("matching-fixture", "other"),
                        valid.replace("\"templates\":", "\"unknown\":"),
                        valid.replace("\"aliases\":", "\"aliases\":[],\"aliases\":"))) {
            var d = base(entries).withMatching(stream(invalid));
            assertFalse(d.matchingStats().loaded(), invalid);
            assertEquals("便利な説明。", d.lookup("item", "Useful text.", "").orElseThrow().text());
            assertTrue(d.lookup("item", "USEFUL TEXT", "").isEmpty());
        }
        var bad =
                base(List.of())
                        .withMatching(
                                stream(
                                        sidecar(
                                                List.of(),
                                                List.of(
                                                        template(
                                                                "item",
                                                                "Grants {v0} Health.",
                                                                "Healthを{v1}得る。",
                                                                "number")))));
        assertFalse(bad.matchingStats().loaded());
    }

    @Test
    void lookupReportsMissingScopeAndSpeakerWithoutSideEffects() {
        var d = matching(List.of(), List.of(), List.of());
        assertEquals("invalid_scope", d.inspect("player_chat", "text", "").reason());
        assertEquals("missing_speaker", d.inspect("npc", "Hello", "").reason());
        assertEquals("not_found", d.inspect("item", "Unknown body", "").reason());
    }

    @Test
    void bundledSidecarLoadsAndIncludesDeclaredPetAndUiTemplates() {
        var d = BundledDictionary.loadBundled();
        assertTrue(d.matchingStats().loaded(), d.matchingStats().error());
        assertTrue(d.matchingStats().templates() > 100);
        assertEquals(
                "☯ Mining Wisdomが+30増加する。",
                d.lookup("item", "Grants +30☯ Mining Wisdom.", "").orElseThrow().text());
        assertEquals(
                "選択中のペット: Skeleton",
                d.lookup("ui", "Selected pet: Skeleton", "").orElseThrow().text());
    }
}
