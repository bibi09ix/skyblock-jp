package local.skyblockjp;

import java.util.*;

/** A collision-checked extra glyph index; it never changes existing alias/hash identity. */
final class DictionaryGlyphFallback {
    private record Key(String channel, String speaker, String source) {}

    private final Map<Key, List<DictionaryMatching.Seed>> index;
    private final Set<Key> blocked;

    private DictionaryGlyphFallback(
            Map<Key, List<DictionaryMatching.Seed>> index, Set<Key> blocked) {
        this.index = Map.copyOf(index);
        this.blocked = Set.copyOf(blocked);
    }

    static DictionaryGlyphFallback create(List<DictionaryMatching.Seed> seeds) {
        var index = new HashMap<Key, List<DictionaryMatching.Seed>>();
        var blocked = new HashSet<Key>();
        for (var seed : seeds) {
            if (!DictionaryGlyphs.fallbackRelevant(seed.source())) continue;
            var key = new Key(seed.channel(), seed.speaker(), key(seed.source()));
            if (seed.match() == null) blocked.add(key);
            else index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(seed);
        }
        index.replaceAll((k, v) -> List.copyOf(v));
        return new DictionaryGlyphFallback(index, blocked);
    }

    BundledDictionary.Lookup inspect(String channel, String source, String speaker) {
        if (!DictionaryGlyphs.fallbackRelevant(source)) return miss("not_found");
        var first = inspectScope(channel, source, speaker);
        if (!channel.equals("npc") && !channel.equals("common")) {
            var common = inspectScope("common", source, "");
            if (!first.reason().equals("not_found") && first.match().isEmpty()) return first;
            if (!common.reason().equals("not_found") && common.match().isEmpty()) return common;
            if (first.match().isPresent() && common.match().isPresent())
                return combine(first.match().get(), common.match().get());
            if (common.match().isPresent()) return common;
        }
        return first;
    }

    private BundledDictionary.Lookup inspectScope(String channel, String source, String speaker) {
        var key = new Key(channel, speaker, key(source));
        if (blocked.contains(key)) return miss("ambiguous");
        BundledDictionary.Match match = null;
        for (var seed : index.getOrDefault(key, List.of())) {
            if (!HexColorTokens.preserved(source, seed.source())
                    || !BundledDictionary.numbers(source)
                            .equals(BundledDictionary.numbers(seed.source()))
                    || !BundledDictionary.symbols(DictionaryGlyphs.canonicalFallback(source))
                            .equals(
                                    BundledDictionary.symbols(
                                            DictionaryGlyphs.canonicalFallback(seed.source()))))
                continue;
            var next = DictionaryGlyphs.bindFallback(source, seed.match());
            if (next == null) return miss("ambiguous_glyph_binding");
            if (match != null) {
                var combined = combine(match, next);
                if (combined.match().isEmpty()) return combined;
                match = combined.match().get();
            } else match = next;
        }
        return match == null
                ? miss("not_found")
                : new BundledDictionary.Lookup(Optional.of(match), "matched", "glyph_fallback");
    }

    private static BundledDictionary.Lookup combine(
            BundledDictionary.Match first, BundledDictionary.Match next) {
        if (!first.text().equals(next.text())
                || first.keepOriginal() != next.keepOriginal()
                || !first.bindings().equals(next.bindings())) return miss("ambiguous");
        var ids = new LinkedHashSet<>(first.sourceIds());
        ids.addAll(next.sourceIds());
        return new BundledDictionary.Lookup(
                Optional.of(
                        new BundledDictionary.Match(
                                first.text(),
                                first.keepOriginal(),
                                List.copyOf(ids),
                                first.bindings())),
                "matched",
                "glyph_fallback");
    }

    private static String key(String source) {
        return DictionaryMatching.aliasKey(DictionaryGlyphs.canonicalFallback(source));
    }

    private static BundledDictionary.Lookup miss(String reason) {
        return new BundledDictionary.Lookup(Optional.empty(), reason, "glyph_fallback");
    }
}
