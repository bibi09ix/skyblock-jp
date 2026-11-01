package local.skyblockjp;

import java.util.*;
import java.util.regex.*;

/** Only repairs unambiguous formatting omissions; validation still runs afterwards. */
final class TranslationRepair {
    static String repair(String source, String result) {
        String proposed = candidate(source, result);
        return worsensStructure(source, result, proposed) ? result : proposed;
    }

    /** A rule may improve prose, but must never destroy already-valid span/value bindings. */
    static boolean worsensStructure(String source, String original, String proposed) {
        return original != null
                && StyledParagraph.validNumbers(source, original)
                && (proposed == null || !StyledParagraph.validNumbers(source, proposed));
    }

    /** Keep the proposal separate so the service can audit rejected repairs as well as applied ones. */
    static String candidate(String source, String result) {
        if (result == null) return null;
        result = result.replaceAll("(?i)<br\\s*/?>", "\n");
        result = SemanticTranslation.repair(source, result);
        // There is no source color to lose when no style spans were supplied.
        // Numeric and immutable term markers are still validated afterwards.
        if (!Pattern.compile("</?s\\d+>").matcher(source).find())
            result = result.replaceAll("</?s\\d+>", "");
        result = result.replace("させるする", "させる").replace("するする", "する").replace("割るする", "割る");
        result = result.replace("手伝うする", "手伝う");
        for (String verb : List.of("上がる", "下がる", "増える", "減る", "高まる", "与える"))
            result = result.replace(verb + "する", verb);
        Matcher petExp =
                Pattern.compile(
                                "Gives (<n\\d+/>) pet exp for (Combat|Mining|Farming|Fishing|Foraging|Alchemy|Enchanting)\\.")
                        .matcher(source);
        if (petExp.matches() && StyledParagraph.validMarkers(source, result))
            result = petExp.group(2) + "で得るペット経験値が" + petExp.group(1) + "増加する。";
        Matcher vitality =
                Pattern.compile(
                                "(<t\\d+>[^<>]+</t\\d+>)'s ((?:<n\\d+/>\\s*)?<t\\d+>Vitality</t\\d+>) reduction is (<n\\d+/>) less effective against you\\.")
                        .matcher(source);
        if (vitality.matches() && StyledParagraph.validMarkers(source, result))
            result =
                    "あなたに対する"
                            + vitality.group(1)
                            + "の"
                            + vitality.group(2)
                            + "減少効果が"
                            + vitality.group(3)
                            + "弱まる。";
        // Restore an omitted immutable term wrapper only when its unchanged English
        // text occurs exactly once, outside all other protected spans.
        Matcher terms = Pattern.compile("<t(\\d+)>([^<>]+)</t\\1>").matcher(source);
        while (terms.find()) {
            String open = "<t" + terms.group(1) + ">", close = "</t" + terms.group(1) + ">";
            // Restore a damaged or translated immutable span using its source ID.
            if (result.contains(open)) {
                String span = "(?s)" + Pattern.quote(open) + "[^<>]*" + Pattern.quote(close);
                if (Pattern.compile(span).matcher(result).find())
                    result = result.replaceAll(span, Matcher.quoteReplacement(terms.group()));
                else if (!result.contains(close)
                        && result.indexOf(open) == result.lastIndexOf(open))
                    result = result.replace(open, terms.group());
                continue;
            }
            if (result.contains(close)) continue;
            var matches =
                    Pattern.compile(
                                    "(?i)(?<![A-Za-z])"
                                            + Pattern.quote(terms.group(2))
                                            + "(?![A-Za-z])")
                            .matcher(result)
                            .results()
                            .toList();
            if (matches.size() != 1) continue;
            var match = matches.getFirst();
            String prefix = result.substring(0, match.start());
            if (Pattern.compile("<[st]\\d+>").matcher(prefix).results().count()
                    != Pattern.compile("</[st]\\d+>").matcher(prefix).results().count()) continue;
            result = prefix + open + terms.group(2) + close + result.substring(match.end());
        }
        // Normalize after recovering wrappers, so a provider that omitted the
        // mana wrapper receives the same grammar correction.
        Matcher cost =
                Pattern.compile(
                                "(?i)^(?:Reduces|Decreases) the (<t\\d+>mana</t\\d+>) cost of (abilities|<t\\d+>[^<>]+</t\\d+>) by (<n\\d+/>)\\.$")
                        .matcher(source);
        if (cost.matches() && StyledParagraph.validMarkers(source, result)) {
            String target = cost.group(2).equalsIgnoreCase("abilities") ? "アビリティ" : cost.group(2);
            result = target + "の" + cost.group(1) + "消費を" + cost.group(3) + "軽減する。";
        }
        result = result.replaceAll("特別な(?=<t\\d+>special Zealot</t\\d+>)", "");
        if (source.matches("(?is).*\\bLogs\\b.*")) {
            result = result.replaceAll("\\bLogs\\b", "原木").replaceAll("(<n\\d+/>)\\s*原木", "原木$1個");
        }
        if (source.startsWith("Consume to boost")) result = result.replace("ブーストできる", "上昇する");
        if (source.matches("(?is)^Gains?\\b.*")) result = result.replace("増加できる", "増加する");
        if (source.matches("(?is).*\\blanding\\s+(?:<n\\d+/>|\\d+)\\s+hits\\b.*"))
            result =
                    result.replace("成功で", "")
                            .replace("キャストできる", "発動できる")
                            .replaceAll("敵を(?=(?:<n\\d+/>|\\d+)回命中)", "敵に攻撃を");
        Matcher tags = Pattern.compile("<s(\\d+)>([^<>]+)</s\\1>").matcher(source);
        while (tags.find()) {
            String open = "<s" + tags.group(1) + ">", close = "</s" + tags.group(1) + ">";
            if (result.contains(open) || result.contains(close)) continue;
            String phrase = tags.group(2).strip();
            List<String> candidates =
                    switch (phrase.toLowerCase(Locale.ROOT)) {
                        case "seconds", "s" -> List.of("秒");
                        case "x" -> List.of("倍", "個", "x");
                        case "evil force" -> List.of("邪悪な力");
                        case "goooold" -> List.of("金");
                        case "reforge" -> List.of("リフォージ");
                        case "trees" -> List.of("木々", "木");
                        case "tree protection order" -> List.of("樹木保護令");
                        default -> List.of(phrase);
                    };
            for (String candidate : candidates) {
                if (candidate.isBlank() || !candidate.matches(".*[A-Za-z\\p{IsHan}].*")) continue;
                int at = result.indexOf(candidate);
                if (at < 0 || result.indexOf(candidate, at + candidate.length()) >= 0) continue;
                // Do not repair inside another style span or in the middle of an English word.
                String prefix = result.substring(0, at);
                long opens = Pattern.compile("<s\\d+>").matcher(prefix).results().count();
                long closes = Pattern.compile("</s\\d+>").matcher(prefix).results().count();
                if (opens != closes) continue;
                if (candidate.matches("[A-Za-z ]+")
                        && ((at > 0
                                        && Character.isLetter(result.charAt(at - 1))
                                        && result.charAt(at - 1) < 128)
                                || (at + candidate.length() < result.length()
                                        && Character.isLetter(
                                                result.charAt(at + candidate.length()))
                                        && result.charAt(at + candidate.length()) < 128))) continue;
                result =
                        prefix
                                + open
                                + candidate
                                + close
                                + result.substring(at + candidate.length());
                break;
            }
        }
        return result;
    }
}
