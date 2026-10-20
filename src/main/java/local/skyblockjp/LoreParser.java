package local.skyblockjp;

import java.util.*;
import java.util.regex.Pattern;

/** Conservative classification: ambiguous lines stay in English. No external mod code. */
public final class LoreParser {
    public record Block(int start, int end, String text) {}
    private static final Pattern STAT = Pattern.compile("(?i)^(?:[✦✧❤❈☠⚔✎☣♣♠α❁⫽⚕✯☘⚡❂❥❣\\s]*)(?:damage|strength|crit(?:ical)? (?:chance|damage)|health|defen[sc]e|true defense|intelligence|speed|attack speed|bonus attack speed|ferocity|vitality|mending|magic find|pet luck|sea creature chance|fishing speed|mining speed|mining fortune|farming fortune|foraging fortune|pristine|breaking power|swing range|sweep|mana(?: cost)?|soulflow cost|cooldown|ability damage|gear score|quality|rarity|item score|combat wisdom|farming wisdom|mining wisdom|fishing wisdom|foraging wisdom|alchemy wisdom|enchanting wisdom|rift time|rift damage|rift health|rift mana|rift speed|rift intelligence|rift strength|rift defense)\\b.*");
    private static final Pattern META = Pattern.compile("(?i)^(?:ability:|item ability:|full set bonus:|tiered bonus:|piece bonus:|bonus:|.*(?:RIGHT|LEFT) CLICK|(?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL|SUPREME)(?: DUNGEON)?(?: .*)?|(?:SELL|BUY) PRICE:.*|(?:NPC|BAZAAR|AH|LOWEST BIN|MUSEUM|SOULBOUND|CO-OP SOULBOUND|REFORGE|ENCHANTMENTS?|UPGRADES?|REQUIRES|REQUIREMENT).*|(?:Click|Right-click|Left-click|Shift-click) to .*|minecraft:.*|skyblock:.*)$");
    private static final Pattern ENCHANT = Pattern.compile("(?:[A-Za-z'’ -]+ [IVXLCDM]+)(?:, ?[A-Za-z'’ -]+ [IVXLCDM]+)*,?");
    private static final Pattern SENTENCE = Pattern.compile("(?i)\\b(?:you|your|the|this|that|these|those|it|its|a|an|of|to|with|for|from|while|when|and|or|is|are|in|on|by|per|each|every|nearby|enemies|enemy|deals?|grants?|gain|gives?|increases?|reduces?|heals?|restores?|shoots?|teleports?|summons?|consumes?|applies?|take|takes|receive|receives|can|will|has|have)\\b");
    public static String clean(String s) { return s.replaceAll("§.", "").strip(); }
    public static boolean protectedLine(String s) {
        s = clean(s);
        if (s.matches("^[\\p{Co}\\s]+[A-Z][A-Za-z]+$")) return true;
        if (s.matches("(?i)^[A-Za-z' -]+ Slayer: (?:LVL|Level) \\d+$")) return true;
        if (s.matches("^(?:Instructions|Click for details!)$")) return true;
        if (s.matches("^\\d+ Score:.*") || s.matches("^(?:Fig|Mangrove|Helix) Tree:.*")) return true;
        if (s.matches("^[+−-][\\d,.]+[%\\p{Co}☯\\s]*[A-Za-z ]+$") && !SENTENCE.matcher(s).find()) return true;
        if (s.matches("^[A-Z][A-Za-z' -]*(?:Fuel Tank|Drill Engine|Goblin Egg Part)$")) return true;
        if (s.matches("(?i)^Next Upgrade:.*")) return true;
        if (s.matches("(?i)^(?:pickaxe|drill|axe|sword|bow|wand|pet|weapon) ability:.*")) return true;
        String bulletless = s.replaceFirst("^[‣•●]\\s*", "");
        if (!bulletless.equals(s) && bulletless.matches("^[+−-]?[\\d,.]+[%\\p{Co}✦✧❤❈☠⚔✎☣♣♠α❁⫽⚕✯☘⚡❂❥❣\\s]*[A-Za-z ]+$")
            && !SENTENCE.matcher(bulletless).find()) return true;
        if (s.matches("(?i)^[A-Za-z' -]+ \\((?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL)\\):$")) return true;
        if (s.matches("(?i)^(Gemstones|Fuel|Current Bonus|Applicable on|Apply Cost):.*") || s.matches("(?i)^Breaking Power [0-9]+$")) return true;
        if (s.matches("(?i)^(?:Held Item|Fuel Tank|Drill Engine|Upgrade Module|Secured):.*")) return true;
        if (s.matches("(?i)^(?:Combat|Mining|Farming|Fishing|Foraging|Alchemy|Enchanting|All Skills) Pet$")
            || s.matches("(?:Minion Fuel|Minion Upgrade|Brewing Ingredient|Collection Item|Precursor Relic|Reforge Stone)")) return true;
        if (s.matches("^(?:Berserk|Healer|Mage|Tank|Archer):.*")) return true;
        if (s.matches("^(?:Total Chocolate Production Boosts|Your SkyBlock Level:.*|Progress to Level \\d+:.*)$")) return true;
        if (s.matches("^[+−-][\\d,.]+x?[%\\p{Co}\\s]*[A-Za-z ]+(?: per second)?$") && !s.contains(" when ")) return true;
        if (s.matches("(?i)^[A-Za-z '-]+ Bonus$") || s.matches("(?i)^.*\\b(?:CO-OP SOULBOUND|SOULBOUND)\\b.*$")) return true;
        if (s.matches("^(?:a\\s+)?(?:COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL)\\s+[A-Z ]+(?:\\s+a)?$")) return true;
        if (s.matches("^[+−-]?[\\d,.]+[%\\p{Co}✦✧❤❈☠⚔✎☣♣♠α❁⫽⚕✯☘⚡❂❥❣\\s]*[A-Za-z ]+$") && !SENTENCE.matcher(s).find()) return true;
        if (s.matches("[A-Z_]{4,}")) return true;
        if (s.matches("(?i)^(ability|item ability|full set bonus|tiered bonus|piece bonus|bonus):.*")) return true;
        // Metadata prefixes must not swallow prose such as "special Zealot",
        // "upgrade. This will..." or "Requires an open area...".
        boolean prosePrefix = s.matches("(?i)^(?:common|uncommon|rare|epic|legendary|mythic|divine|special|very special|supreme|upgrades?|requires|requirement)\\b.*");
        boolean metadata = META.matcher(s).matches() && !prosePrefix;
        if (s.matches("(?i)^Requires (?:[A-Za-z]+ )?(?:Level |Tier )?[IVXLCDM0-9]+[.!]?$")) metadata = true;
        if (s.matches("(?i)^(?:Shift )?(?:Left|Right)-click to .*$")) metadata = true;
        if (s.isBlank() || (STAT.matcher(s).matches() && s.matches(".*:\\s*[+−-]?[\\d.].*")) || metadata || ENCHANT.matcher(s).matches()) return true;
        if (!s.matches(".*[A-Za-z].*")) return true;
        // Generic numerical stat rows, including stats unknown to this version.
        if (s.matches("^[^:]{1,42}:\\s*[+−-]?[\\d,.]+(?:%|s)?(?:\\s*\\([^)]*\\))*\\s*$")) return true;
        if (s.matches("^[+\\-]?[\\d,.]+(?:%|s)?(?:\\s*\\([^)]*\\))?$") || s.matches(".*(?:✪|★){2,}.*")) return true;
        return false;
    }
    public static List<Block> parse(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        // First line is always the item name.
        for (int i = 1; i < lines.size();) {
            if (boundary(lines, i, false)) { i++; continue; }
            int start = i;
            List<String> paragraph = new ArrayList<>();
            while (i < lines.size() && !boundary(lines, i, i > start)) {
                if (i > start && (clean(lines.get(i)).matches("^[‣•●]\\s*.*")
                    || clean(lines.get(i)).matches("^\\d+ Combo \\(lasts [\\d.]+s\\)$")
                    || clean(lines.get(start)).matches("^\\d+ Combo \\(lasts [\\d.]+s\\)$"))) break;
                paragraph.add(clean(lines.get(i++)));
            }
            String text = String.join(" ", paragraph).replaceAll("\\s+", " ");
            if (text.matches(".*[a-z].*") && text.replaceAll("[^A-Za-z]", "").length() >= 3) blocks.add(new Block(start, i, text));
        }
        return blocks;
    }
    private static boolean boundary(List<String> lines, int index, boolean continuation) {
        String s = clean(lines.get(index));
        // A numeric-only wrapped tail belongs to the sentence already in progress.
        if (continuation && s.matches("#?[+−-]?[\\d,.]+(?:%|s)?[.!]?")) return false;
        if (protectedLine(s)) return true;
        // Pet abilities and fishing parts have title-only rows before their prose.
        boolean sectionStart = index == 1 || clean(lines.get(index - 1)).isBlank();
        boolean titled = s.matches("(?:[^A-Za-z0-9\\s]+\\s+)?[A-Z][A-Za-z'-]*(?: (?:[A-Z][A-Za-z'-]*|of|the|and)){0,5}");
        if (sectionStart && titled && index + 1 < lines.size()) {
            String next = clean(lines.get(index + 1));
            boolean pet = lines.stream().skip(1).map(LoreParser::clean).anyMatch(line -> line.matches("(?i)^(?:Combat|Mining|Farming|Fishing|Foraging|Alchemy|Enchanting|All Skills) Pet$"));
            if (pet && !next.isBlank()) return true;
            if (next.matches("(?i)^(?:grants?|gives?|increases?|decreases?|reduces?|take|gain|buffs|while|killing|your|materializes|tarantula\\b).*")) return true;
        }
        return false;
    }
    public static boolean numbersPreserved(String original, String translated) {
        return numbers(original).equals(numbers(translated));
    }
    private static List<String> numbers(String text) {
        List<String> values = new ArrayList<>();
        var m = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?").matcher(text);
        while (m.find()) values.add(m.group().replace(",", ""));
        Collections.sort(values);
        return values;
    }
}
