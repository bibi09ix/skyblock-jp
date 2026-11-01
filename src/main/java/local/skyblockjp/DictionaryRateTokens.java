package local.skyblockjp;

import java.util.*;
import java.util.regex.*;

/** Explicit health-per-second atoms, separate from slash commands and plain durations. */
final class DictionaryRateTokens {
    private DictionaryRateTokens() {}
    private static final String NUMBER = StyledParagraph.NUMBER.pattern();
    private static final String SPACE = "[\\s\\p{Zs}]*";
    private static final Pattern SOURCE = Pattern.compile("(?<value>" + NUMBER + ")(?<glyph>❤)(?<unit>/s)(?![A-Za-z])");
    private static final Pattern PREFIX = Pattern.compile("(?<unit>毎秒|秒あたり)" + SPACE
        + "(?:(?<before>❤)" + SPACE + "(?:を|が)?" + SPACE + ")?(?<value>" + NUMBER + ")" + SPACE + "(?<after>❤)?");
    private static final Pattern SUFFIX = Pattern.compile("(?<value>" + NUMBER + ")" + SPACE + "(?<glyph>❤)" + SPACE
        + "(?:ずつ" + SPACE + ")?(?<unit>毎秒|秒あたり)");
    record Span(int start, int end) {}
    private record Rate(String value, String glyph, String role, Span valueSpan, Span glyphSpan, Span unitSpan) {
        String key() { return role + ":" + value + glyph; }
    }

    static boolean isRateSuffix(String text, int start, int end) {
        return source(text).stream().anyMatch(rate -> rate.unitSpan.start == start && rate.unitSpan.end == end);
    }
    private static String role(String text, int start, int end) {
        String before = text.substring(0, start).stripTrailing(); String after = text.substring(end).stripLeading();
        return before.matches("(?is).*(?:\\bup to|\\bmaximum(?: of)?|上限(?:は|が|：|:)?|最大(?:は|が|：|:)?)$")
            || after.matches("(?s)^(?:まで|が上限|を上限).*" ) ? "cap" : "rate";
    }
    private static List<Rate> source(String text) {
        var result = new ArrayList<Rate>(); var matcher = SOURCE.matcher(text);
        while (matcher.find()) result.add(new Rate(matcher.group("value"), matcher.group("glyph"), role(text, matcher.start(), matcher.end()),
            new Span(matcher.start("value"), matcher.end("value")), new Span(matcher.start("glyph"), matcher.end("glyph")),
            new Span(matcher.start("unit"), matcher.end("unit"))));
        return result;
    }
    private static List<Rate> target(String text) {
        var result = new ArrayList<>(source(text)); var prefix = PREFIX.matcher(text);
        while (prefix.find()) {
            boolean before = prefix.group("before") != null, after = prefix.group("after") != null;
            if (before == after) continue; // Exactly one native stat glyph belongs to the rate.
            String glyphGroup = before ? "before" : "after";
            result.add(new Rate(prefix.group("value"), prefix.group(glyphGroup), role(text, prefix.start(), prefix.end()),
                new Span(prefix.start("value"), prefix.end("value")), new Span(prefix.start(glyphGroup), prefix.end(glyphGroup)),
                new Span(prefix.start("unit"), prefix.end("unit"))));
        }
        var suffix = SUFFIX.matcher(text);
        while (suffix.find()) {
            var unit = new Span(suffix.start("unit"), suffix.end("unit"));
            if (result.stream().anyMatch(rate -> rate.unitSpan.equals(unit))) continue;
            result.add(new Rate(suffix.group("value"), suffix.group("glyph"), role(text, suffix.start(), suffix.end()),
                new Span(suffix.start("value"), suffix.end("value")), new Span(suffix.start("glyph"), suffix.end("glyph")), unit));
        }
        return result;
    }
    private static Map<String, Integer> counts(List<Rate> rates) {
        var counts = new TreeMap<String, Integer>(); rates.forEach(rate -> counts.merge(rate.key(), 1, Integer::sum)); return counts;
    }
    static TypedDictionaryTokens.Validation inspect(String source, String target) {
        var original = source(source); if (original.isEmpty()) return new TypedDictionaryTokens.Validation("", Map.of());
        var before = counts(original); var after = counts(target(target));
        return before.equals(after) ? new TypedDictionaryTokens.Validation("", Map.of())
            : new TypedDictionaryTokens.Validation("RATE_QUANTITY_MISMATCH", Map.of("validationStage", "TYPED_RATE_QUANTITIES",
                "sourceRates", before, "targetRates", after));
    }
    private static Rate uniqueTarget(Rate rate, List<Rate> originals, List<Rate> translated) {
        if (originals.stream().filter(r -> r.key().equals(rate.key())).count() != 1) return null;
        var candidates = translated.stream().filter(r -> r.key().equals(rate.key())).toList();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }
    static List<BundledDictionary.Binding> bindings(String source, String target) {
        var originals = source(BundledDictionary.normalize(source)); var translated = target(target);
        var result = new ArrayList<BundledDictionary.Binding>();
        for (var original : originals) {
            var match = uniqueTarget(original, originals, translated); if (match == null) continue;
            result.add(new BundledDictionary.Binding("rate_value_" + original.valueSpan.start, "number", original.valueSpan.start,
                original.valueSpan.end, match.valueSpan.start, match.valueSpan.end, original.value, match.value));
            result.add(new BundledDictionary.Binding("rate_glyph_" + original.glyphSpan.start, "glyph", original.glyphSpan.start,
                original.glyphSpan.end, match.glyphSpan.start, match.glyphSpan.end, original.glyph, match.glyph));
        }
        return List.copyOf(result);
    }
    /** Source positions are native raw offsets; returned target positions use literal dictionary text. */
    static List<Span> styleSpans(String source, String target, int start, int end) {
        if (start < 0 || end > source.length() || start >= end || !source.substring(start, end).strip().equals("/s")) return List.of();
        var originals = source(source); var translated = target(target); var result = new ArrayList<Span>();
        for (var original : originals) if (original.unitSpan.start >= start && original.unitSpan.end <= end) {
            var match = uniqueTarget(original, originals, translated); if (match != null) result.add(match.unitSpan);
        }
        return List.copyOf(result);
    }
}
