package local.skyblockjp;

import com.google.gson.Gson;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

public final class TranslationDiagnostics {
    private TranslationDiagnostics() {}

    public static String validate(String input, String output) {
        if (output == null || output.isBlank()) return "EMPTY_RESPONSE: APIの訳文が空です";
        Map<String, Integer> expected = markers(input), actual = markers(output);
        if (!expected.equals(actual)) {
            Map<String, Integer> missing = new LinkedHashMap<>(), extra = new LinkedHashMap<>();
            expected.forEach(
                    (k, v) -> {
                        if (v > actual.getOrDefault(k, 0))
                            missing.put(k, v - actual.getOrDefault(k, 0));
                    });
            actual.forEach(
                    (k, v) -> {
                        if (v > expected.getOrDefault(k, 0))
                            extra.put(k, v - expected.getOrDefault(k, 0));
                    });
            return "MARKER_MISMATCH: missing=" + missing + ", extra=" + extra;
        }
        if (!StyledParagraph.validMarkers(input, output)) return "MARKER_NESTING: 色マーカーの閉じ順が不正です";
        for (String name :
                java.util.regex.Pattern.compile("<t\\d+>([^<>]+)</t\\d+>")
                        .matcher(input)
                        .results()
                        .map(m -> m.group(1))
                        .distinct()
                        .toList()) {
            var word =
                    java.util.regex.Pattern.compile(
                            "(?i)(?<![A-Za-z0-9])"
                                    + java.util.regex.Pattern.quote(name)
                                    + "(?![A-Za-z0-9])");
            if (word.matcher(output).results().count() > word.matcher(input).results().count())
                return "DUPLICATE_TERM: repeated protected name=" + name;
        }
        String original = StyledParagraph.MARKER.matcher(input).replaceAll("");
        String translated = StyledParagraph.MARKER.matcher(output).replaceAll("");
        if (!QuantityValidation.preserved(input, output))
            return "NUMBER_MISMATCH: original="
                    + numbers(original)
                    + ", translated="
                    + numbers(translated);
        if (!balanced(translated)) return "PUNCTUATION_MISMATCH: 括弧の対応が不正です";
        if (!output.matches("(?s).*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}].*")
                && original.matches("(?s).*[A-Za-z].*")) return "NO_JAPANESE: 訳文に日本語がありません";
        String missing = TranslationContentChecks.missing(input, output);
        if (missing != null) return "CONTENT_MISSING: required meaning=" + missing;
        if (translated.matches("(?s).*(?:するする|させるする|割るする).*"))
            return "JAPANESE_GRAMMAR: duplicated verb ending; use natural Japanese";
        if (input.matches("(?is).*\\blanding\\s+(?:<n\\d+/>|\\d+)\\s+hits\\b.*")
                && (output.contains("着地") || output.matches("(?is).*\\b(?:landing|hits)\\b.*")))
            return "MEANING_MISMATCH: translate landing hits as 攻撃を命中させる, never 着地 or English landing hits";
        if (translated.matches("(?is).*\\b(?:per|max)\\b.*")
                && original.matches("(?is).*\\b(?:per|max)\\b.*"))
            return "UNTRANSLATED_PROSE: translate per/max into Japanese; retain protected names";
        return null;
    }

    static boolean balanced(String text) {
        Deque<Character> stack = new ArrayDeque<>();
        String opening = "(（[［{｛「『【", closing = ")）]］}｝」』】";
        for (char ch : text.toCharArray()) {
            int i = opening.indexOf(ch), j = closing.indexOf(ch);
            if (i >= 0) stack.push(closing.charAt(i));
            else if (j >= 0 && (stack.isEmpty() || stack.pop() != ch)) return false;
        }
        return stack.isEmpty();
    }

    private static Map<String, Integer> markers(String text) {
        Map<String, Integer> out = new TreeMap<>();
        StyledParagraph.MARKER
                .matcher(text)
                .results()
                .forEach(m -> out.merge(m.group(), 1, Integer::sum));
        return out;
    }

    private static List<String> numbers(String text) {
        return Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?")
                .matcher(text)
                .results()
                .map(MatchResult::group)
                .toList();
    }

    public static String redact(String text, String secret) {
        if (text == null) return null;
        if (secret != null && !secret.isBlank()) text = text.replace(secret, "[REDACTED]");
        return text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+", "Bearer [REDACTED]")
                .replaceAll("gsk_[A-Za-z0-9_-]+", "[REDACTED]");
    }

    public static synchronized void append(Path path, Map<String, Object> record, String secret)
            throws Exception {
        Files.createDirectories(path.getParent());
        if (Files.exists(path) && Files.size(path) > 2_000_000)
            Files.move(
                    path,
                    path.resolveSibling(path.getFileName() + ".1"),
                    StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("time", Instant.now().toString());
        record.forEach((k, v) -> safe.put(k, v instanceof String s ? redact(s, secret) : v));
        Files.writeString(
                path,
                redact(new Gson().toJson(safe), secret) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
