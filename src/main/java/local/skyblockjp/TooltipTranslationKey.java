package local.skyblockjp;

import java.util.List;

/** Request identity excludes unrelated stats, costs and presentation-only footer rows. */
final class TooltipTranslationKey {
    static String of(
            TooltipContext.Decision context,
            String name,
            List<String> channels,
            List<StyledParagraph> paragraphs) {
        StringBuilder key = new StringBuilder();
        part(key, context.kind().name());
        part(key, new com.google.gson.Gson().toJson(new java.util.TreeMap<>(context.evidence())));
        part(key, String.valueOf(context.evidence().getOrDefault("skyblockItemId", "")));
        part(key, name.replaceFirst("\\[Lvl \\d+\\]\\s*", ""));
        for (int i = 0; i < paragraphs.size(); i++) {
            part(key, channels.get(i));
            // Encoded numbers retain their slots: new live values use the same translation,
            // while an added/replaced ability or a different protected name changes identity.
            part(key, paragraphs.get(i).encoded());
        }
        return key.toString();
    }

    private static void part(StringBuilder key, String value) {
        key.append(value.length()).append(':').append(value);
    }
}
