package local.skyblockjp;

import java.util.*;
import java.util.regex.*;

/** Exact source grammar rules. Never guesses absent conditions or numerical values. */
final class SemanticTranslation {
    private static final String N = "(<n\\d+/>)";
    private static final String V = "(<n\\d+/>(?:\\s*<n\\d+/>)*)\\s*";
    private static final String T = "(<t\\d+>[^<>]+</t\\d+>)";

    private record Rule(Pattern pattern, String replacement) {}

    private static Rule rule(String source, String target) {
        return new Rule(Pattern.compile(source, Pattern.CASE_INSENSITIVE), target);
    }

    private static final List<Rule> RULES =
            List.of(
                    rule(
                            "Reduces the ability " + T + " cost of this item by " + N + "\\.",
                            "このアイテムのアビリティの$1消費を$2軽減する。"),
                    rule(
                            "Regenerate " + N + " extra " + T + ", doubled when near or in water",
                            "$2の回復量が$1増加し、水辺または水中ではこの効果が2倍になる。"),
                    rule(
                            "While in (?:the )?"
                                    + T
                                    + ", killing mobs reduces your (<n\\d+/>(?:\\s*<n\\d+/>)*|)\\s*Heat by "
                                    + N
                                    + "\\.",
                            "$1でモブを倒すと、$2Heatが$3減少する。"),
                    rule(
                            "Grants " + V + T + " while in (?:the )?" + T + "\\.",
                            "$3にいる間、$2が$1増加する。"),
                    rule("Grants " + V + T + " from all sources\\.", "すべての入手元から得る$2の量が$1増加する。"),
                    rule(
                            "You have a " + N + " chance to drop a " + T + " when farming crops\\.",
                            "作物の収穫時に$1の確率で$2がドロップする。"),
                    rule(
                            "You have a "
                                    + N
                                    + " chance to find "
                                    + T
                                    + " or "
                                    + T
                                    + " when farming crops\\.",
                            "作物の収穫時に$1の確率で$2または$3が見つかる。"),
                    rule("Grants " + V + T + " while mining " + T + "\\.", "$3の採掘中、$2が$1増加する。"),
                    rule(
                            "Grants " + V + T + " for every " + V + T + " you have\\.",
                            "所持する$4が$3増えるごとに、$2が$1増加する。"),
                    rule(
                            "Zaps your enemies for "
                                    + N
                                    + "x your "
                                    + V
                                    + T
                                    + " every "
                                    + N
                                    + "s\\.",
                            "$4秒ごとに、$2$3の$1倍のダメージを敵に与える。"),
                    rule("Gain " + N + "x " + T + " against ([A-Za-z]+)\\.", "$3から得る$2が$1倍になる。"),
                    rule(
                            "Consume to boost your " + V + T + " for " + N + "\\.",
                            "使用すると、$3間$1$2が上昇する。"),
                    rule(N + " Combo \\(lasts " + N + "s\\)", "$1コンボ（持続時間$2秒）"),
                    rule(
                            "While in "
                                    + T
                                    + ", summon a "
                                    + T
                                    + " that slowly revolves around you every "
                                    + N
                                    + " seconds or when you kill a mob with melee damage\\.",
                            "$1にいる間、$3秒ごと、または近接攻撃でモブを倒すと、自分の周囲をゆっくり回る$2を召喚する。"),
                    rule(
                            "When an enemy is hit by a "
                                    + T
                                    + ", they are stunned for "
                                    + N
                                    + " seconds and deal "
                                    + N
                                    + " less damage\\.",
                            "$1が命中した敵は$2秒間スタンし、与えるダメージが$3減少する。"),
                    rule(
                            "Start the " + T + " with " + N + " additional Critter Capsules\\.",
                            "追加のCritter Capsulesを$2個持った状態で$1を開始する。"),
                    rule(
                            "Highlight a random zone each time you enter the "
                                    + T
                                    + "\\. Gain "
                                    + V
                                    + "Hunting Fortune while in it\\.",
                            "$1に入るたびにランダムなゾーンを強調表示する。そのゾーン内では$2Hunting Fortuneが増加する。"),
                    rule("Combinable in " + T, "$1で組み合わせ可能。"));

    static String repair(String source, String result) {
        String plain = source.replaceAll("</?s\\d+>", "").replaceAll("\\s+", " ").strip();
        for (Rule rule : RULES) {
            var match = rule.pattern.matcher(plain);
            if (!match.matches()) continue;
            String corrected = match.replaceFirst(rule.replacement);
            if (immutable(source).equals(immutable(corrected)))
                return corrected.replaceAll("(?<=/>)\\s+(?=<[nt]\\d+[/>])", "");
        }
        if (plain.equals("Eh, why don't I just show you a visual?")) return "ねえ、実際に見せようか？";
        if (plain.equals("You'll find it already equipped once you enter - for your convenience."))
            return "入場すると自動で装備されるよ。";
        return result;
    }

    private static List<String> immutable(String text) {
        return Pattern.compile("<n\\d+/>|<t\\d+>[^<>]*</t\\d+>")
                .matcher(text)
                .results()
                .map(MatchResult::group)
                .sorted()
                .toList();
    }
}
