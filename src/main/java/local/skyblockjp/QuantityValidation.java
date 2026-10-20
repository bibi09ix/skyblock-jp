package local.skyblockjp;

import java.util.*;
import java.util.regex.*;

/** Protected stat placeholders remain exact; prose quantities may use Arabic digits. */
final class QuantityValidation {
    private static final Map<String, String> WORDS = Map.ofEntries(
        Map.entry("zero", "0"), Map.entry("one", "1"), Map.entry("two", "2"), Map.entry("three", "3"),
        Map.entry("four", "4"), Map.entry("five", "5"), Map.entry("six", "6"), Map.entry("seven", "7"),
        Map.entry("eight", "8"), Map.entry("nine", "9"), Map.entry("ten", "10"), Map.entry("double", "2"),
        Map.entry("doubled", "2"), Map.entry("twice", "2"), Map.entry("triple", "3"), Map.entry("thrice", "3"), Map.entry("quadruple", "4"));
    private static List<String> numbers(String text) {
        return new ArrayList<>(StyledParagraph.NUMBER.matcher(text).results().map(m -> m.group().replace(",", "")).toList());
    }
    static boolean preserved(String source, String result) {
        // Keep a separator for protected values, so "every <n0/> seconds" is not
        // mistaken for "every second" after stripping markup.
        String input = source.replaceAll("<t\\d+>[^<>]*</t\\d+>", "term").replaceAll("<n\\d+/>", "#").replaceAll("</?s\\d+>", "");
        String output = StyledParagraph.MARKER.matcher(result).replaceAll(" ");
        List<String> actual = numbers(output);
        for (String required : numbers(input)) if (!actual.remove(required)) return false;
        List<String> allowance = new ArrayList<>();
        // "second" as an ordinal is distinct from the unit in "per second".
        Pattern.compile("(?i)\\b(?:the|a|an) (first|second|third|fourth|fifth)\\b").matcher(input).results().forEach(m ->
            allowance.add(switch (m.group(1).toLowerCase(Locale.ROOT)) { case "first" -> "1"; case "second" -> "2"; case "third" -> "3"; case "fourth" -> "4"; default -> "5"; }));
        Pattern.compile("(?i)\\b(?:a|one) century\\b").matcher(input).results().forEach(m -> allowance.add("100"));
        Pattern.compile("(?i)\\b[a-z]+\\b").matcher(input).results().forEach(m -> {
            String value = WORDS.get(m.group().toLowerCase(Locale.ROOT));
            if (value != null) allowance.add(value);
        });
        Pattern.compile("(?i)\\b(?:per|each|every|a|an)\\s+(?=[a-z])").matcher(input).results().forEach(m -> allowance.add("1"));
        for (String added : actual) if (!allowance.remove(added)) return false;
        return true;
    }
}
