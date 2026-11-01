package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/** Offline corpus/recorded-text checks. This class does not construct a client or API transport. */
class DictionaryCorpusIntegrationTest {
    private static final Set<String> ACTIVE_CHANNELS =
            Set.of("item", "npc", "common", "ui", "system");
    // These source packs already contain two approved but different Japanese versions.
    // Keep both provenance records; only a hash-bound explicit review can override
    // these conflicts. Without it, item/UI must remain unavailable.
    private static final Set<String> REVIEWED_COMMON_CONFLICTS =
            Set.of(
                    "Blazes are ephemeral creatures, constantly consuming themselves. Their ashes are said to bring good fortune.",
                    "Bundling what bees find attractive together in an industrial production process is definitely what nature planned.",
                    "Dropped by a Shark during Fishing Festival.",
                    "Hold this item in your inventory to use the soul in the infested house.",
                    "Ideal for fine woodworking, this fig-derived wood is fully impervious, and can even be used to craft see-through items.",
                    "⸮morf emoc ti did erehw dna ,gniht siht si htrae no tahW");
    private static final Set<String> REVIEWED_QUARANTINE_EQUIVALENTS =
            Set.of(
                    "Consume this item to add its destination to your fast travel options.",
                    "Quest item");

    private static final class Corpus {
        static final JsonObject DOCUMENT = object(BundledDictionary.RESOURCE);
        static final JsonArray ENTRIES = DOCUMENT.getAsJsonArray("entries");
        static final JsonArray BLOCKED = DOCUMENT.getAsJsonArray("blocked_keys");
        static final BundledDictionary DICTIONARY = BundledDictionary.loadBundled();
    }

    @Test
    void candidateCorpusSafelyRoundTripsPlainAndGrayParagraphs() {
        var dictionary = Corpus.DICTIONARY;
        assertTrue(dictionary.stats().loaded(), dictionary.stats().error());
        assertEquals(0, dictionary.stats().rejectedEntries());
        Map<String, JsonObject> commonEntries = new TreeMap<>();
        for (var value : Corpus.ENTRIES) {
            var entry = value.getAsJsonObject();
            if (entry.get("channel").getAsString().equals("common"))
                commonEntries.put(entry.get("source").getAsString(), entry);
        }
        Map<String, CorpusCounts> counts = new TreeMap<>();
        for (var value : Corpus.ENTRIES) {
            JsonObject entry = value.getAsJsonObject();
            String channel = entry.get("channel").getAsString();
            String source = entry.get("source").getAsString();
            String target = entry.get("target").getAsString();
            String speaker = entry.get("speaker").getAsString();
            var row = counts.computeIfAbsent(channel, ignored -> new CorpusCounts());
            row.total++;
            if (entry.get("keep_original").getAsBoolean()) row.keepOriginal++;
            if (!ACTIVE_CHANNELS.contains(channel)) continue;
            String context = channel + ": " + abbreviate(source);
            var lookup = dictionary.inspect(channel, source, speaker);
            assertTrue(
                    lookup.match().isPresent(),
                    "every emitted key must match after explicit review: " + context);
            row.lookupHits++;
            if (Set.of("item", "ui").contains(channel)
                    && REVIEWED_COMMON_CONFLICTS.contains(source)) {
                assertEquals("reviewed", lookup.mode(), context);
                row.commonConflicts.add(source);
            }
            if (Set.of("item", "ui").contains(channel)
                    && REVIEWED_QUARANTINE_EQUIVALENTS.contains(source)) {
                assertEquals("reviewed", lookup.mode(), context);
                row.quarantineEquivalents.add(source);
            }
            var matched = lookup.match().orElseThrow();
            for (boolean gray : new boolean[] {false, true}) {
                Component original =
                        gray
                                ? Component.literal(source).withStyle(ChatFormatting.GRAY)
                                : Component.literal(source);
                var paragraph =
                        assertDoesNotThrow(() -> new StyledParagraph(List.of(original)), context);
                String encoded =
                        assertDoesNotThrow(
                                () ->
                                        matched.keepOriginal()
                                                ? paragraph.encoded()
                                                : paragraph.dictionaryEncoding(matched).text(),
                                context);
                if (encoded == null) {
                    String reason =
                            paragraph.encoded().contains("<t")
                                    ? "protected_term_or_value_alignment"
                                    : "protected_value_alignment";
                    row.fallbacks.merge((gray ? "gray/" : "plain/") + reason, 1, Integer::sum);
                    if (!gray && row.examples.size() < 3)
                        row.examples.add(abbreviate(source) + " -> " + abbreviate(target));
                    continue;
                }
                verifyRoundTrip(paragraph, encoded, context);
                if (gray) row.graySuccess++;
                else row.plainSuccess++;
                if (!gray && !paragraph.decode(encoded).getString().equals(target))
                    row.localizedDisplay++;
            }
        }
        assertEquals(
                Corpus.ENTRIES.size(), counts.values().stream().mapToInt(row -> row.total).sum());
        for (var entry : counts.entrySet()) {
            var row = entry.getValue();
            System.out.printf(
                    "DICTIONARY_CORPUS channel=%s total=%d keepOriginal=%d active=%s exactLookup=%d commonConflicts=%d quarantineEquivalents=%d plainSuccess=%d graySuccess=%d localizedDisplay=%d fallbacks=%s examples=%s%n",
                    entry.getKey(),
                    row.total,
                    row.keepOriginal,
                    ACTIVE_CHANNELS.contains(entry.getKey()),
                    row.lookupHits,
                    row.commonConflicts.size(),
                    row.quarantineEquivalents.size(),
                    row.plainSuccess,
                    row.graySuccess,
                    row.localizedDisplay,
                    row.fallbacks,
                    row.examples);
            if (ACTIVE_CHANNELS.contains(entry.getKey())) {
                assertEquals(
                        Set.of("item", "ui").contains(entry.getKey())
                                ? REVIEWED_COMMON_CONFLICTS
                                : Set.of(),
                        row.commonConflicts,
                        "reviewed cross-scope conflicts in " + entry.getKey());
                assertEquals(
                        Set.of("item", "ui").contains(entry.getKey())
                                ? REVIEWED_QUARANTINE_EQUIVALENTS
                                : Set.of(),
                        row.quarantineEquivalents,
                        "reviewed quarantine equivalents in " + entry.getKey());
                assertEquals(
                        row.total,
                        row.lookupHits,
                        "every compiled key must match after explicit review in " + entry.getKey());
                assertTrue(row.plainSuccess > 0, entry.getKey());
                assertEquals(
                        row.total,
                        row.plainSuccess
                                + row.fallbacks.entrySet().stream()
                                        .filter(reason -> reason.getKey().startsWith("plain/"))
                                        .mapToInt(Map.Entry::getValue)
                                        .sum());
                assertEquals(
                        row.total,
                        row.graySuccess
                                + row.fallbacks.entrySet().stream()
                                        .filter(reason -> reason.getKey().startsWith("gray/"))
                                        .mapToInt(Map.Entry::getValue)
                                        .sum());
            }
        }
    }

    @Test
    void recordedGameTextReportsExactDictionaryCoverageWithoutGeneralizingNumbers() {
        Map<String, FixtureCounts> counts = new TreeMap<>();
        for (var value : array("/recent-translation-sources.json")) {
            JsonObject entry = value.getAsJsonObject();
            observe(counts, "recent/item", "item", entry.get("original").getAsString(), "");
        }
        for (var value : array("/npc-review-fixtures.json")) {
            JsonObject entry = value.getAsJsonObject();
            observe(
                    counts,
                    "npc-review/npc",
                    "npc",
                    entry.get("source").getAsString(),
                    entry.get("speaker").getAsString());
        }
        for (String fixture : List.of("latest-review", "october-review")) {
            var data = object("/" + fixture + "-fixtures.json");
            for (var value : data.getAsJsonArray("npc")) {
                JsonObject entry = value.getAsJsonObject();
                observe(
                        counts,
                        fixture + "/npc",
                        "npc",
                        entry.get("source").getAsString(),
                        entry.get("speaker").getAsString());
            }
            observeItems(counts, fixture + "/item", data.getAsJsonArray("items"));
        }
        observeItems(counts, "pet-review/item", array("/pet-review-fixtures.json"));
        observeItems(counts, "reviewed-display/item", array("/reviewed-display-fixtures.json"));
        for (var entry : counts.entrySet()) {
            var row = entry.getValue();
            assertTrue(row.observations > 0, entry.getKey());
            assertTrue(row.encoded <= row.hits && row.hits <= row.observations);
            System.out.printf(
                    "DICTIONARY_FIXTURE fixture=%s observations=%d exactHits=%d adapterSuccess=%d unique=%d uniqueHits=%d misses=%s%n",
                    entry.getKey(),
                    row.observations,
                    row.hits,
                    row.encoded,
                    row.unique.size(),
                    row.uniqueHits.size(),
                    row.missExamples);
        }
    }

    private static void observeItems(
            Map<String, FixtureCounts> counts, String fixture, JsonArray items) {
        for (var value : items) {
            var original = value.getAsJsonObject().getAsJsonArray("originalLines");
            List<String> lines = new ArrayList<>();
            original.forEach(line -> lines.add(line.getAsString()));
            for (var block : LoreParser.parse(lines)) {
                var components =
                        lines.subList(block.start(), block.end()).stream()
                                .map(Component::literal)
                                .map(Component.class::cast)
                                .toList();
                String source = new StyledParagraph(components).sourceText();
                observe(counts, fixture, "item", source, "");
            }
        }
    }

    private static void observe(
            Map<String, FixtureCounts> counts,
            String fixture,
            String channel,
            String source,
            String speaker) {
        var row = counts.computeIfAbsent(fixture, ignored -> new FixtureCounts());
        row.observations++;
        String key = speaker + "|" + BundledDictionary.normalize(source);
        row.unique.add(key);
        var match = Corpus.DICTIONARY.lookup(channel, source, speaker);
        if (match.isEmpty()) {
            if (row.missExamples.size() < 3)
                row.missExamples.add(abbreviate(speaker + " " + source));
            return;
        }
        row.hits++;
        row.uniqueHits.add(key);
        var paragraph =
                new StyledParagraph(
                        List.of(Component.literal(source).withStyle(ChatFormatting.GRAY)));
        String encoded =
                match.get().keepOriginal()
                        ? paragraph.encoded()
                        : paragraph.dictionaryEncoding(match.get()).text();
        if (encoded != null) {
            row.encoded++;
            verifyRoundTrip(paragraph, encoded, fixture + ": " + abbreviate(source));
        }
    }

    private static void verifyRoundTrip(StyledParagraph paragraph, String encoded, String context) {
        assertTrue(StyledParagraph.validMarkers(paragraph.encoded(), encoded), context);
        assertTrue(StyledParagraph.validNumbers(paragraph.encoded(), encoded), context);
        var decoded = assertDoesNotThrow(() -> paragraph.decode(encoded), context);
        // Compare with the original's *display* form. The existing duration rule turns 60m
        // into 60分 locally, so comparing against raw target digits/units would be misleading.
        String sourceDisplay = paragraph.decode(paragraph.encoded()).getString();
        assertTrue(
                BundledDictionary.quantitiesPreserved(sourceDisplay, decoded.getString()), context);
        assertFalse(decoded.getString().matches("(?s).*<(?:/?[st]\\d+|n\\d+/)>.*"), context);
    }

    private static Map<String, Long> numberCounts(String text) {
        return StyledParagraph.NUMBER
                .matcher(text)
                .results()
                .collect(
                        Collectors.groupingBy(
                                match -> match.group(), TreeMap::new, Collectors.counting()));
    }

    private static JsonObject object(String path) {
        return read(path).getAsJsonObject();
    }

    private static JsonArray array(String path) {
        return read(path).getAsJsonArray();
    }

    private static com.google.gson.JsonElement read(String path) {
        var input = DictionaryCorpusIntegrationTest.class.getResourceAsStream(path);
        assertNotNull(input, path);
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (java.io.IOException error) {
            throw new AssertionError("Unable to read fixture " + path, error);
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 140 ? text : text.substring(0, 137) + "...";
    }

    private static final class CorpusCounts {
        int total, keepOriginal, lookupHits, plainSuccess, graySuccess, localizedDisplay;
        final Set<String> commonConflicts = new HashSet<>();
        final Set<String> quarantineEquivalents = new HashSet<>();
        final Map<String, Integer> fallbacks = new TreeMap<>();
        final List<String> examples = new ArrayList<>();
    }

    private static final class FixtureCounts {
        int observations, hits, encoded;
        final Set<String> unique = new HashSet<>(), uniqueHits = new HashSet<>();
        final List<String> missExamples = new ArrayList<>();
    }
}
