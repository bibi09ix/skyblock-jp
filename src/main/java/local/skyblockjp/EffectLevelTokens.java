package local.skyblockjp;

import java.util.*;
import java.util.regex.*;

/** A Roman tier belongs to its named effect, never to an arbitrary Roman-looking word. */
final class EffectLevelTokens {
    private EffectLevelTokens() {}

    private static final List<String> EFFECTS =
            List.of(
                    "Fire Resistance",
                    "Water Breathing",
                    "Night Vision",
                    "Health Boost",
                    "Jump Boost",
                    "Regeneration",
                    "Invisibility",
                    "Absorption",
                    "Resistance",
                    "Blindness",
                    "Slowness",
                    "Weakness",
                    "Strength",
                    "Hunger",
                    "Poison",
                    "Wither",
                    "Speed",
                    "Haste");
    private static final Pattern EFFECT =
            Pattern.compile(
                    "(?<![A-Za-z0-9_])(?:"
                            + EFFECTS.stream()
                                    .sorted(Comparator.comparingInt(String::length).reversed())
                                    .map(Pattern::quote)
                                    .reduce((a, b) -> a + "|" + b)
                                    .orElseThrow()
                            + ")[\\s\\p{Zs}]+([IVXLCDM]{1,15})(?![A-Za-z0-9_])");
    private static final Pattern ROMAN =
            Pattern.compile("M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})");
    private static final Pattern PREVIOUS_WORD =
            Pattern.compile("([A-Z][A-Za-z'-]*)[\\s\\p{Zs}]+$");
    private static final Set<String> PROSE_PREFIX =
            Set.of(
                    "Gain",
                    "Gains",
                    "Give",
                    "Gives",
                    "Grant",
                    "Grants",
                    "Get",
                    "Gets",
                    "Apply",
                    "Applies",
                    "Permanent",
                    "Receive",
                    "Receives",
                    "With",
                    "Without",
                    "And",
                    "Or",
                    "The",
                    "A",
                    "An",
                    "Your",
                    "This",
                    "That",
                    "Requires",
                    "Required",
                    "Have",
                    "Has",
                    "Use",
                    "Using",
                    "Consume");
    private static final Pattern MARKUP = Pattern.compile("</?[ts]\\d+>|<n\\d+/>");

    record Token(int start, int end, String effect, String tier, int tierStart, int tierEnd) {
        String role() {
            return effect + "=" + tier;
        }
    }

    static List<Token> parse(String text) {
        if (text == null || text.isEmpty()) return List.of();
        // Mask markup, commands and sender headers in place so offsets stay UTF-16.
        var masked = new StringBuilder(text);
        MARKUP.matcher(text).results().forEach(m -> mask(masked, m.start(), m.end()));
        CommandTokens.parse(text).forEach(t -> mask(masked, t.start(), t.end()));
        var header = ChatEnvelope.player(text);
        if (header != null) mask(masked, 0, header.bodyStart());
        if (text.matches("(?s)^\\s*\\[(?:NPC|BOSS)\\].*")) {
            int colon = text.indexOf(':');
            if (colon >= 0) mask(masked, 0, colon + 1);
        }
        var result = new ArrayList<Token>();
        var matcher = EFFECT.matcher(masked);
        while (matcher.find()) {
            String tier = matcher.group(1);
            if (!ROMAN.matcher(tier).matches()) continue;
            // Growth Speed, Raw Rabbit, etc. are longer names, not the suffix effect.
            var previous = PREVIOUS_WORD.matcher(masked.substring(0, matcher.start()));
            if (previous.find() && !PROSE_PREFIX.contains(previous.group(1))) continue;
            String effect = text.substring(matcher.start(), matcher.start(1)).strip();
            // Markup between a name and its tier is masked for matching, not part of the name.
            effect = MARKUP.matcher(effect).replaceAll("").strip();
            result.add(
                    new Token(
                            matcher.start(),
                            matcher.end(),
                            effect,
                            tier,
                            matcher.start(1),
                            matcher.end(1)));
        }
        return List.copyOf(result);
    }

    private static void mask(StringBuilder text, int from, int to) {
        for (int i = from; i < to; i++) text.setCharAt(i, ' ');
    }

    private static List<String> roles(String text) {
        return parse(text).stream().map(Token::role).sorted().toList();
    }

    static boolean preserved(String source, String target) {
        return roles(source).equals(roles(target));
    }

    static TypedDictionaryTokens.Validation inspect(String source, String target) {
        var original = roles(source);
        var translated = roles(target);
        return original.equals(translated)
                ? new TypedDictionaryTokens.Validation("", Map.of())
                : new TypedDictionaryTokens.Validation(
                        "TYPED_EFFECT_LEVEL_MISMATCH",
                        Map.of(
                                "validationStage",
                                "TYPED_EFFECT_LEVELS",
                                "sourceEffectLevels",
                                original,
                                "targetEffectLevels",
                                translated));
    }

    static void addPatterns(String text, List<String> patterns) {
        for (var token : parse(text))
            patterns.add(
                    TypedDictionaryTokens.boundary(text.substring(token.start(), token.end())));
    }

    static List<BundledDictionary.Binding> bindings(String source, String target) {
        var originals = parse(source);
        var translated = parse(target);
        var result = new ArrayList<BundledDictionary.Binding>();
        for (var original : originals) {
            var matches =
                    translated.stream().filter(t -> t.role().equals(original.role())).toList();
            if (matches.size() != 1
                    || originals.stream().filter(t -> t.role().equals(original.role())).count()
                            != 1) continue;
            var next = matches.getFirst();
            String from = source.substring(original.start(), original.end()),
                    to = target.substring(next.start(), next.end());
            if (!from.equals(to))
                continue; // no speculative whitespace/translation alias for immutable effect names
            result.add(
                    new BundledDictionary.Binding(
                            "effect_level_" + original.start(),
                            "name",
                            original.start(),
                            original.end(),
                            next.start(),
                            next.end(),
                            from,
                            to));
        }
        return List.copyOf(result);
    }
}
