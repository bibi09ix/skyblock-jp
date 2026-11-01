package local.skyblockjp;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** A six-hex RGB literal is one immutable atom; numbered # labels are separate. */
final class HexColorTokens {
    private static final Pattern LITERAL =
            Pattern.compile("(?<![A-Za-z0-9_#])#[0-9A-Fa-f]{6}(?![A-Za-z0-9_#])");

    private HexColorTokens() {}

    static Map<String, Integer> signature(String text) {
        Map<String, Integer> result = new HashMap<>();
        if (text != null)
            LITERAL.matcher(text)
                    .results()
                    .forEach(
                            m -> result.merge(m.group().toUpperCase(Locale.ROOT), 1, Integer::sum));
        return result;
    }

    static boolean preserved(String source, String target) {
        return signature(source).equals(signature(target));
    }

    /** Sanitization/counting only; normalization and the displayed source stay intact. */
    static String masked(String text) {
        var result = new StringBuilder(text);
        LITERAL.matcher(text)
                .results()
                .forEach(
                        m -> {
                            for (int i = m.start(); i < m.end(); i++) result.setCharAt(i, ' ');
                        });
        return result.toString();
    }

    static void addPatterns(String text, List<String> terms) {
        LITERAL.matcher(text).results().forEach(m -> terms.add(Pattern.quote(m.group())));
    }
}
