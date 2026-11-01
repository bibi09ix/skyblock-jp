package local.skyblockjp;

import java.util.*;
import java.util.regex.*;

/** Reviewed font variants are equivalent only next to their stat name. */
final class DictionaryGlyphs {
    private record Rule(String stat, char legacy, char modern, Pattern pattern) {
        Rule(String stat, char legacy, char modern) {
            this(stat, legacy, modern, Pattern.compile("[" + legacy + modern + "](?=\\s*" + Pattern.quote(stat) + "(?![A-Za-z]))", Pattern.CASE_INSENSITIVE));
        }
    }
    private static final List<Rule> RULES = List.of(
        new Rule("Farming Fortune", '☘', '\ue051'),
        new Rule("Mining Fortune", '☘', '\ue053'),
        new Rule("Mining Speed", '⸕', '\ue015'),
        new Rule("Intelligence", '✎', '\ue003'),
        new Rule("Strength", '❁', '\ue00d'),
        new Rule("Defense", '❈', '\ue008'));
    // These pairs are used only by a separate not_found fallback. Existing alias keys
    // and reviewed source hashes deliberately keep the v1 six-rule normalization.
    private static final List<Rule> EXTRA_RULES=List.of(new Rule("Health",'❤','\ue010'),
        new Rule("Fishing Speed",'☂','\ue00c'));
    private static final List<Rule> FALLBACK_RULES=java.util.stream.Stream.concat(RULES.stream(),EXTRA_RULES.stream()).toList();
    private record Occurrence(Rule rule, int offset, char glyph) { }
    private static List<Occurrence> occurrences(String text) {
        return occurrences(text,RULES);
    }
    private static List<Occurrence> occurrences(String text,List<Rule> rules) {
        List<Occurrence> result = new ArrayList<>();
        for (var rule : rules) {
            var matcher = rule.pattern.matcher(text);
            while (matcher.find()) result.add(new Occurrence(rule, matcher.start(), text.charAt(matcher.start())));
        }
        result.sort(Comparator.comparingInt(Occurrence::offset));
        return result;
    }
    static String canonical(String text) {
        return canonical(text,RULES);
    }
    static String canonicalFallback(String text) {return canonical(text,FALLBACK_RULES);}
    static boolean fallbackRelevant(String text) {return EXTRA_RULES.stream().anyMatch(rule->rule.pattern.matcher(text).find());}
    private static String canonical(String text,List<Rule> rules) {
        var result = new StringBuilder(text);
        for (var occurrence : occurrences(text,rules)) result.setCharAt(occurrence.offset, occurrence.rule.modern);
        return result.toString();
    }
    /** The matched translation uses the live font's glyphs, with explicit unique span bindings. */
    static BundledDictionary.Match bind(String source, BundledDictionary.Match match) {
        return bind(source,match,RULES);
    }
    static BundledDictionary.Match bindFallback(String source,BundledDictionary.Match match) {return bind(source,match,FALLBACK_RULES);}
    private static BundledDictionary.Match bind(String source,BundledDictionary.Match match,List<Rule> rules) {
        var live = occurrences(source,rules);
        var target = occurrences(match.text(),rules);
        var text = new StringBuilder(match.text());
        var bindings = new ArrayList<>(match.bindings());
        for (var rule : rules) {
            var from = live.stream().filter(o -> o.rule.equals(rule)).toList();
            var to = target.stream().filter(o -> o.rule.equals(rule)).toList();
            if (from.size() != to.size()) return null;
            if (from.isEmpty()) continue;
            // Multiple renderings for the same stat have no proved correspondence.
            if (from.stream().map(Occurrence::glyph).distinct().count() > 1) return null;
            char glyph = from.getFirst().glyph;
            for (var occurrence : to) text.setCharAt(occurrence.offset, glyph);
            if (from.size() == 1) bindings.add(new BundledDictionary.Binding("glyph:" + rule.stat,
                "glyph", from.getFirst().offset, from.getFirst().offset + 1,
                to.getFirst().offset, to.getFirst().offset + 1, String.valueOf(glyph), String.valueOf(glyph)));
        }
        return new BundledDictionary.Match(text.toString(), match.keepOriginal(), match.sourceIds(), bindings);
    }
}
