package local.skyblockjp;
import java.util.*;
import java.util.regex.Pattern;

final class ProtectedTerms {
    static final String[] NAMES = {"Breaking Power", "Mining Fortune", "Mining Speed", "Mining Spread", "Mining Wisdom", "Farming Fortune", "Foraging Fortune", "Crit Damage", "Crit Chance", "True Defense", "Magic Find", "Pet Luck", "Attack Speed", "Ability Damage", "Sea Creature Chance", "Fishing Speed", "Swing Range", "Overflow Mana", "Health", "Defense", "Strength", "Intelligence", "Speed", "Mana", "Ferocity", "Vitality", "Mending", "Pristine", "Sweep", "Respiration", "Titanium Ore", "Chocolate Factory", "Perfect Gemstone", "Forge Timers", "Mining Islands", "Galatea"};
    static final Pattern PATTERN = Pattern.compile("(?i)(?<![A-Za-z])(?:" + String.join("|", Arrays.stream(NAMES).sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).toList()) + ")(?![A-Za-z])");
    // An explicit named target list is indivisible: losing even one entry changes
    // which equipment/mobs the effect applies to. Infer it from the source text.
    static Pattern forText(String text) {
        String name = "[A-Z][A-Za-z'-]*(?: [A-Z][A-Za-z'-]*)*";
        var lists = new ArrayList<>(Pattern.compile("(?<![A-Za-z])(?!(?:Between|The|Both|Includes|Grants|Gain|With|For|To|And|Or|While|In|From|Visit|About)\\b)" + name + "(?:, " + name + ")+(?:,? and " + name + ")")
            .matcher(text).results().map(m -> Pattern.quote(m.group())).toList());
        // A level and its skill name are one semantic value, including their native colors.
        Pattern.compile("\\b(?:Taming Skill|Mining Skill|SkyBlock Level|Combat Level)\\s+(?:\\d+|[IVXLCDM]+)(?![A-Za-z0-9])")
            .matcher(text).results().forEach(m -> lists.add(Pattern.quote(m.group())));
        // Keep the entire chat header, including icons and numeric usernames, as one styled term.
        var player = Pattern.compile("^(?:(?:\\[[^\\]\\r\\n]{1,60}\\]|[^A-Za-z0-9\\s<>\\[\\]]+)\\s*)*[A-Za-z0-9_]{1,32}: ?").matcher(text);
        boolean playerChat = player.find() && !text.startsWith("[NPC]") && !NpcTranslation.optionPrompt(text);
        if (playerChat) {
            lists.add(Pattern.quote(player.group()));
            Pattern.compile("(?i)(?:^|: )([A-Za-z0-9_]{1,32})(?= (?:u|you) )|(?<=buy )([A-Za-z0-9_]{1,3})(?![A-Za-z0-9_])")
                .matcher(text).results().filter(m -> m.group(1) == null || !m.group(1).matches("(?i)if|have|do|did|can|could|would|will|are|were|thank|when|what|why|how"))
                .forEach(m -> lists.add(Pattern.quote(m.group(1) == null ? m.group(2) : m.group(1))));
        }
        Pattern.compile("(?i)(?<![A-Za-z0-9_])(?:l[1-5]|bs)(?![A-Za-z0-9_])").matcher(text).results()
            .forEach(m -> lists.add(Pattern.quote(m.group())));
        Pattern.compile("https?://[^\\s<>]+|(?<![A-Za-z0-9])/(?:visit|p)\\s+(?!(?:or|and|then)\\b)[A-Za-z0-9_]{1,32}(?![A-Za-z0-9_])|(?<![A-Za-z0-9])/[A-Za-z][A-Za-z0-9_-]*|\\b(?:[A-Za-z0-9-]+\\.)+(?:net|com|org)(?:/[^\\s<>]*)?")
            .matcher(text).results().forEach(m -> lists.add(Pattern.quote(m.group())));
        String extra = "(?<![A-Za-z])(?:Auto-pickup|Coal|Honeycomb Collection|Fun-Sized Pot of Honeycomb|Honeyhives|Mithril Powder|Mushroom|Tasty Cheese|Dung|The Catacombs|Haunted Skull|Safari Belt|Safari Essence|Anvil|special Zealot|Crystal Hollows|Magma Fields|Tarantula Broodfather|Griffin Feathers|Mythos Fragments|Torrhus Canyon|Miria’s Contests|Critter Safari|Torrhus Sea Creatures|Collections|Launch Pad|Moonglade Marsh|Trade Center|Wheat Minion|Mithril Plate|fleece|Chocolate Shop|Chocolate Rabbits|Chocobit Rabbits|Hoppity's Hunt|Elusive|Trevor|Jake|Mage Faction)(?![A-Za-z])";
        return Pattern.compile((lists.isEmpty() ? "" : String.join("|", lists) + "|")
            + "(?i:Transmission abilities|Nest Endermite|Draconic Altar|Combat XP|Deployables)|" + extra + "|" + PATTERN.pattern());
    }
}
