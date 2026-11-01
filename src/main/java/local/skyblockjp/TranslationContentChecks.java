package local.skyblockjp;

import java.util.regex.Pattern;

/** Conservative checks for omitted requirements/actions, not a semantic equivalence proof. */
final class TranslationContentChecks {
    private record Cue(String source, String translated, String name) {}

    private static final Cue[] CUES = {
        new Cue(
                "\\b(?:apply|applying) (?:the |this |an? )?item\\b",
                "適用|使用|使[ういえ]|装着|装備|取り付け|付与",
                "apply item"),
        new Cue(
                "\\b(?:must be|is required to be) visible\\b",
                "見え|見える|表示|可視|出現|召喚",
                "visibility requirement"),
        new Cue("\\b(?:swap|swapping)\\b", "交換|変更|切り替|切替|入れ替|入替|付け替|取り替", "swap"),
        new Cue("\\bmore unique\\b", "種類|異な", "distinct rabbit types")
    };

    static String missing(String source, String result) {
        String plain = StyledParagraph.MARKER.matcher(source).replaceAll("");
        for (Cue cue : CUES) {
            if (Pattern.compile(cue.source(), Pattern.CASE_INSENSITIVE).matcher(plain).find()
                    && !Pattern.compile(cue.translated()).matcher(result).find()) return cue.name();
        }
        String sourceText = source.replaceAll("</?[st]\\d+>", "");
        String outputText =
                result.replaceAll("<t\\d+>[^<>]*</t\\d+>", " ").replaceAll("</?s\\d+>", "");
        if (sourceText.matches("(?is).*\\bkilling mobs reduces\\b.*")
                && !outputText.matches("(?s).*(?:倒|討伐|撃破).*")) return "mob kill condition";
        if (sourceText.matches("(?is).*\\bnear or in water\\b.*")
                && !outputText.matches("(?s).*(?:水辺|水の近く|水のそば).*")) return "near water condition";
        if (sourceText.matches("(?is).*\\bstacks above\\s+<n\\d+/>.*")
                && !outputText.matches(".*(?:超|越|上回).*")) return "stacking beyond threshold";
        if (sourceText.matches("(?is).*\\bgive them\\s+<n\\d+/>x?\\s+duration.*")
                && !outputText.matches(
                        "(?s).*(?:<n\\d+/>\\s*(?:x\\s*|倍)?(?:に|とな)|<n\\d+/>\\s*(?:x\\s*|倍)?の(?:持続|効果)時間).*"))
            return "duration multiplier, not additive increase";
        if (sourceText.matches("(?is).*\\breduction\\b.*")
                && outputText.matches("(?is).*\\breduction\\b.*"))
            return "translate ordinary reduction prose";
        return null;
    }
}
