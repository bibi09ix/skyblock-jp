package local.skyblockjp;

import java.util.*;
import java.util.regex.*;

/** NPC-only corrections, preserving source markers and conversational context. */
final class NpcTranslationRepair {
    private static final String T = "(<t\\d+>[^<>]+</t\\d+>)";
    // Old caches can still contain a whole level term; current encoding separates
    // the skill name, label and exact value so dictionary Japanese can reorder them.
    private static final String L =
            "((?:<t\\d+>(?:Taming Skill|Mining Skill|SkyBlock Level|Combat Level)\\s+(?:\\d+|[IVXLCDM]+)</t\\d+>"
                    + "|<t\\d+>(?:SkyBlock|Combat|Mining|Taming|Farming|Foraging|Fishing|Enchanting|Alchemy|Carpentry)</t\\d+>\\s+(?:Level|Skill)\\s+(?:<n\\d+/>|<t\\d+>[IVXLCDM]+</t\\d+>)))";
    private static final Pattern CHEST_RIDDLE =
            Pattern.compile("(?i)They are both lying, the reward is in my chest[!.]?");

    private record Rule(Pattern source, String target) {}

    private static Rule rule(String source, String target) {
        return new Rule(Pattern.compile(source), target);
    }

    private static final List<Rule> RULES =
            List.of(
                    rule("Come back to me when you have " + L + "[.!]?", "$1に到達したら、また来てね。"),
                    rule("Come back once you have reached " + L + "!", "$1に到達したら、また来てね。"),
                    rule(
                            "It drives me insane, but at least you'll unlock "
                                    + T
                                    + " at "
                                    + L
                                    + "\\.",
                            "うんざりするけど、少なくとも$2になると$1が解放されるよ。"),
                    rule(
                            "Reforging usually costs Coins, but since I'm feeling friendly I can reforge your first item for "
                                    + T
                                    + " x(<n\\d+/>)\\.",
                            "普通はリフォージにCoinsがかかるけど、今日は特別に、最初のアイテムは$1を$2個払えばリフォージしてあげるよ。"),
                    rule(
                            "I'm the town Blacksmith! I can reforge items for you, for a price\\.",
                            "町の鍛冶屋だよ！料金を払えばアイテムをリフォージしてあげるよ。"),
                    rule(
                            "I sell advanced reforge items for advanced players!",
                            "上級者向けのリフォージアイテムを売っているよ！"));

    static String repair(String source, String result, String previous) {
        String proposed = candidate(source, result, previous);
        return TranslationRepair.worsensStructure(source, result, proposed) ? result : proposed;
    }

    static String candidate(String source, String result, String previous) {
        if (result == null || result.isBlank()) return result;
        String plain = source.replaceAll("</?s\\d+>", "").replaceAll("\\s+", " ").strip();
        // This exact dungeon riddle refers to a reward container, not the NPC's body.
        // Correct only the observed ambiguity; keep the response's clauses and markers.
        if (CHEST_RIDDLE.matcher(plain).matches() && immutable(source).equals(immutable(result))) {
            String visible = result.replaceAll("</?s\\d+>", "");
            if (visible.matches("(?s).*胸(?:の(?:中|なか|内)|に).*")
                    && result.indexOf('胸') == result.lastIndexOf('胸')) {
                String corrected = result.replace("胸", "チェスト");
                if (StyledParagraph.validNumbers(source, corrected)) return corrected;
            }
        }
        for (Rule rule : RULES) {
            var match = rule.source.matcher(plain);
            if (match.matches()) {
                String corrected = match.replaceFirst(rule.target);
                // Matching ignored color wrappers, but the split level label may
                // now own one. Restore its unique native span before returning.
                corrected = TranslationRepair.candidate(source, corrected);
                if (immutable(source).equals(immutable(corrected))) return corrected;
            }
        }
        // Only ordinary source words are normalized, never immutable names.
        if (plain.equals("An evil force took control of the graves."))
            result = result.replaceAll("(?i)\\bevil force\\b", "邪悪な力");
        if (plain.equals("I love goooold!")) result = result.replaceAll("(?i)\\bgoooold\\b", "金");
        if (plain.equals("My name?")) return "私の名前？";
        if (plain.equals("Blacksmith.")
                && previous.replaceAll("</?s\\d+>", "").strip().equals("My name?"))
            return "Blacksmithだよ。";
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
