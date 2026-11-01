package local.skyblockjp;

import java.util.*;
import java.util.regex.Pattern;

/** Occurrence identities are resolved before colors; equal colors do not imply equal roles. */
final class QuantityBindings {
    private QuantityBindings() {}

    private record Occurrence(int start, int end, String literal, String role) {}

    record Result(
            List<BundledDictionary.Binding> bindings, String reason, Map<String, Object> details) {
        Result {
            bindings = List.copyOf(bindings);
            details = Map.copyOf(details);
        }
    }

    private static final Pattern DIMENSION =
            Pattern.compile(
                    "(?<![A-Za-z0-9_])(\\d+(?:\\.\\d+)?)[xX×](\\d+(?:\\.\\d+)?)(?![A-Za-z0-9_])");

    static Result combine(
            String rawSource, String target, Collection<BundledDictionary.Binding> supplied) {
        var combined = new ArrayList<>(new LinkedHashSet<>(supplied));
        for (var inferred : derive(BundledDictionary.normalize(rawSource), target)) {
            boolean covered = false;
            for (var existing : combined) {
                boolean sourceOverlap =
                        overlap(
                                inferred.sourceStart(),
                                inferred.sourceEnd(),
                                existing.sourceStart(),
                                existing.sourceEnd());
                boolean targetOverlap =
                        overlap(
                                inferred.targetStart(),
                                inferred.targetEnd(),
                                existing.targetStart(),
                                existing.targetEnd());
                if (!sourceOverlap && !targetOverlap) continue;
                if (sourceOverlap
                        && targetOverlap
                        && inferred.targetStart() - inferred.sourceStart()
                                == existing.targetStart() - existing.sourceStart()) {
                    if (contains(
                                    existing.sourceStart(),
                                    existing.sourceEnd(),
                                    inferred.sourceStart(),
                                    inferred.sourceEnd())
                            && contains(
                                    existing.targetStart(),
                                    existing.targetEnd(),
                                    inferred.targetStart(),
                                    inferred.targetEnd())) covered = true;
                    continue;
                }
                return new Result(
                        List.of(),
                        "SEMANTIC_BINDING_CONFLICT",
                        Map.of(
                                "validationStage",
                                "SEMANTIC_BINDING",
                                "sourceStart",
                                inferred.sourceStart(),
                                "sourceEnd",
                                inferred.sourceEnd(),
                                "targetStart",
                                inferred.targetStart(),
                                "targetEnd",
                                inferred.targetEnd()));
            }
            if (!covered) combined.add(inferred);
        }
        return new Result(combined, "", Map.of());
    }

    private static boolean overlap(int a, int b, int c, int d) {
        return a < d && b > c;
    }

    private static boolean contains(int a, int b, int c, int d) {
        return a <= c && b >= d;
    }

    private static String role(String text, int start, int end, boolean target) {
        var dimensions = DIMENSION.matcher(text);
        while (dimensions.find())
            for (int group = 1; group <= 2; group++)
                if (dimensions.start(group) == start && dimensions.end(group) == end)
                    return "dimension:" + group;
        String after = text.substring(end), before = text.substring(0, start);
        if (target) {
            if (after.matches("(?s)^\\s*倍.*")) return "multiplier";
            if (after.matches("(?s)^\\s*(?:体|匹|回)(?!目).*")) return "count";
        }
        if (after.matches("(?is)^\\s*x(?![A-Za-z0-9_]).*")
                || before.matches("(?is).*?(?<![A-Za-z0-9_])x\\s*$")) return "multiplier";
        if (after.matches("(?is)^\\s*(?:kills?|enemies|mobs|times?)\\b.*")) return "count";
        return target
                ? QuantityRoles.target(text, start, end)
                : QuantityRoles.source(text, start, end);
    }

    private static List<Occurrence> numbers(String text, boolean target) {
        var result = new ArrayList<Occurrence>();
        var protectedRanges =
                ProtectedTerms.forText(text)
                        .matcher(text)
                        .results()
                        .map(m -> new int[] {m.start(), m.end()})
                        .toList();
        var matcher = StyledParagraph.NUMBER.matcher(text);
        while (matcher.find()) {
            int start = matcher.start(), end = matcher.end();
            if (protectedRanges.stream().anyMatch(r -> overlap(start, end, r[0], r[1]))) continue;
            result.add(new Occurrence(start, end, matcher.group(), role(text, start, end, target)));
        }
        return result;
    }

    private static List<BundledDictionary.Binding> derive(String source, String target) {
        var result = new ArrayList<BundledDictionary.Binding>();
        var sourceNumbers = numbers(source, false);
        var targetNumbers = numbers(target, true);
        var groups = new LinkedHashMap<String, List<Occurrence>>();
        for (var value : sourceNumbers)
            groups.computeIfAbsent(value.literal(), ignored -> new ArrayList<>()).add(value);
        int id = 0;
        for (var group : groups.values()) {
            if (group.size() < 2) continue;
            var candidates =
                    targetNumbers.stream()
                            .filter(v -> v.literal().equals(group.getFirst().literal()))
                            .toList();
            if (group.size() != candidates.size()
                    || group.stream().anyMatch(v -> v.role().isEmpty())
                    || group.stream().map(Occurrence::role).distinct().count() != group.size())
                continue;
            var selected = new ArrayList<Occurrence>();
            for (var value : group) {
                var matches =
                        candidates.stream().filter(v -> v.role().equals(value.role())).toList();
                if (matches.size() != 1) {
                    selected.clear();
                    break;
                }
                selected.add(matches.getFirst());
            }
            if (selected.size() != group.size()
                    || selected.stream().map(Occurrence::start).distinct().count()
                            != selected.size()) continue;
            for (int at = 0; at < group.size(); at++) {
                var from = group.get(at);
                var to = selected.get(at);
                result.add(
                        new BundledDictionary.Binding(
                                "quantity-role-" + id++,
                                "number",
                                from.start(),
                                from.end(),
                                to.start(),
                                to.end(),
                                from.literal(),
                                to.literal()));
            }
        }
        // A parenthetical cap/reset and its main clause are distinct occurrences,
        // including the icon attached to the stat. Ambiguous scopes remain unbound.
        if (!balanced(source) || !balanced(target)) return result;
        var sourceTerms =
                ProtectedTerms.forText(source)
                        .matcher(source)
                        .results()
                        .map(
                                m ->
                                        new Occurrence(
                                                m.start(),
                                                m.end(),
                                                m.group(),
                                                scope(source, m.start())))
                        .toList();
        var targetTerms =
                ProtectedTerms.forText(source)
                        .matcher(target)
                        .results()
                        .map(
                                m ->
                                        new Occurrence(
                                                m.start(),
                                                m.end(),
                                                m.group(),
                                                scope(target, m.start())))
                        .toList();
        var terms = new LinkedHashMap<String, List<Occurrence>>();
        for (var term : sourceTerms)
            terms.computeIfAbsent(term.literal(), ignored -> new ArrayList<>()).add(term);
        for (var group : terms.values()) {
            if (group.size() != 2
                    || group.stream().anyMatch(v -> v.role().isEmpty())
                    || group.stream().map(Occurrence::role).distinct().count() != 2) continue;
            var candidates =
                    targetTerms.stream()
                            .filter(v -> v.literal().equals(group.getFirst().literal()))
                            .toList();
            if (candidates.size() != 2) continue;
            for (var from : group) {
                var matches =
                        candidates.stream().filter(v -> v.role().equals(from.role())).toList();
                if (matches.size() != 1) continue;
                var to = matches.getFirst();
                int sourceStart = atomStart(source, from.start()),
                        targetStart = atomStart(target, to.start());
                if (!source.substring(sourceStart, from.end())
                        .equals(target.substring(targetStart, to.end()))) {
                    sourceStart = from.start();
                    targetStart = to.start();
                }
                result.add(
                        new BundledDictionary.Binding(
                                "stat-scope-" + id++,
                                "name",
                                sourceStart,
                                from.end(),
                                targetStart,
                                to.end(),
                                source.substring(sourceStart, from.end()),
                                target.substring(targetStart, to.end())));
            }
        }
        return result;
    }

    private static int atomStart(String text, int start) {
        var matcher = Pattern.compile("[\\p{Co}☘⸕⸎]\\s*$").matcher(text.substring(0, start));
        return matcher.find() ? matcher.start() : start;
    }

    private static String scope(String text, int offset) {
        int depth = 0;
        for (int at = 0; at < offset; at++) {
            char c = text.charAt(at);
            if (c == '(' || c == '（') depth++;
            if (c == ')' || c == '）') depth--;
            if (depth < 0) return "";
        }
        return depth == 0 ? "outside" : "parenthetical:" + depth;
    }

    private static boolean balanced(String text) {
        var stack = new ArrayDeque<Character>();
        for (int at = 0; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '(' || c == '（') stack.push(c);
            else if (c == ')' || c == '）') {
                if (stack.isEmpty() || stack.pop() != (c == ')' ? '(' : '（')) return false;
            }
        }
        return stack.isEmpty();
    }

    static boolean dimensionOperator(
            String source,
            int sourceStart,
            int sourceEnd,
            String target,
            int targetStart,
            int targetEnd) {
        String left = dimensionAt(source, sourceStart, sourceEnd);
        return !left.isEmpty() && left.equals(dimensionAt(target, targetStart, targetEnd));
    }

    private static String dimensionAt(String text, int start, int end) {
        var matcher = DIMENSION.matcher(text);
        while (matcher.find())
            if (matcher.end(1) == start && matcher.start(2) == end)
                return matcher.group(1) + "x" + matcher.group(2);
        return "";
    }

    static boolean insideDimension(String text, int start, int end) {
        return !dimensionAt(text, start, end).isEmpty();
    }
}
