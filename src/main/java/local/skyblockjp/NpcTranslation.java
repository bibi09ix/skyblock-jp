package local.skyblockjp;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

final class NpcTranslation {
    static final String PROMPT = TranslationPrompts.NPC.text();
    static final String CHAT_PROMPT = TranslationPrompts.CHAT.text();

    static String validate(String source, String result) {
        boolean serviceTopic =
                source.matches(
                        "(?is).*(?:language model|as an AI|translate|translation|NO_JAPANESE|言語モデル|翻訳).*");
        if (result != null
                && !serviceTopic
                && (result.matches(
                                "(?is).*(?:NO_JAPANESE|EMPTY_RESPONSE|MARKER_MISMATCH|NUMBER_MISMATCH|CONTENT_MISSING):.*")
                        || result.matches(
                                "(?is).*(?:language model|as an AI|provide (?:the |some )?(?:tooltip|text)|翻訳する(?:ツールチップの)?テキストが提供|正しいテキストを入力|訳文が提供されていません|翻訳対象.{0,15}(?:提供|入力)|言語モデル).*")))
            return "NPC_META_RESPONSE: response is translator commentary instead of dialogue";
        if (result != null && result.strip().startsWith("{") && result.contains("\"dialogue\""))
            return "CHAT_JSON_WRAPPER: JSON object instead of translated dialogue";
        String checked = TranslationDiagnostics.validate(source, result);
        if (checked != null) return checked;
        String prose = result.replaceAll("<t\\d+>[^<>]*</t\\d+>", " ").replaceAll("</?s\\d+>", "");
        if (prose.matches(
                "(?is).*\\b(?:between|the second island of|over in the|there'?s so much|did you craft|you can access)\\b.*"))
            return "UNTRANSLATED_PROSE: ordinary English dialogue remains";
        if (source.contains("evil force") && prose.matches("(?is).*\\bevil force\\b.*"))
            return "UNTRANSLATED_PROSE: evil force is ordinary dialogue";
        if (source.contains("goooold") && prose.matches("(?is).*\\bgoooold\\b.*"))
            return "UNTRANSLATED_PROSE: elongated gold is ordinary dialogue";
        return null;
    }

    static boolean optionPrompt(String text) {
        return LoreParser.clean(text).matches("(?is)^Select an option\\s*:.*");
    }

    static boolean automaticMessage(String text) {
        String plain = LoreParser.clean(text);
        int colon = plain.indexOf(':');
        return optionPrompt(plain)
                || (plain.startsWith("[NPC]") || plain.startsWith("[BOSS]"))
                        && colon >= 0
                        && colon + 1 < plain.length();
    }

    /** Slice the original component, preserving native styles and legacy section codes. */
    static StyledParagraph paragraph(Component message) {
        return paragraph(message, message.getString().indexOf(':') + 1);
    }

    static StyledParagraph whole(Component message) {
        return paragraph(message, 0);
    }

    static StyledParagraph wholeChat(Component message) {
        return paragraph(message, 0, true);
    }

    private static StyledParagraph paragraph(Component message, int begin) {
        return paragraph(message, begin, false);
    }

    private static StyledParagraph paragraph(Component message, int begin, boolean playerChat) {
        var glyphs = StyledParagraph.glyphs(message);
        var body = Component.empty();
        int offset = 0;
        Style legacy = null, previous = null;
        for (int i = 0; i < glyphs.size(); i++) {
            var g = glyphs.get(i);
            int start = offset;
            offset += g.text().length();
            Style nativeStyle =
                    g.style().getColor() == null
                            ? g.style().withColor(ChatFormatting.WHITE)
                            : g.style();
            nativeStyle =
                    nativeStyle
                            .withBold(nativeStyle.isBold())
                            .withItalic(nativeStyle.isItalic())
                            .withUnderlined(nativeStyle.isUnderlined())
                            .withStrikethrough(nativeStyle.isStrikethrough())
                            .withObfuscated(nativeStyle.isObfuscated());
            if (!nativeStyle.equals(previous)) {
                legacy = nativeStyle;
                previous = nativeStyle;
            }
            if (g.text().equals("§") && i + 1 < glyphs.size()) {
                var format = ChatFormatting.getByCode(glyphs.get(i + 1).text().charAt(0));
                if (format != null) {
                    legacy =
                            format == ChatFormatting.RESET
                                    ? nativeStyle
                                    : legacy.applyLegacyFormat(format);
                    offset += glyphs.get(++i).text().length();
                    continue;
                }
            }
            // Formatting before the body (for example §c[NPC] Elle:) still governs it.
            if (start >= begin)
                body.append(
                        Component.literal(g.text()).setStyle(legacy == null ? g.style() : legacy));
        }
        return new StyledParagraph(List.of(body), playerChat);
    }
}
