package local.skyblockjp;
import java.util.*;
import java.util.regex.Pattern;

final class ProtectedTerms {
    static final String[] NAMES = {"Breaking Power", "Mining Fortune", "Mining Speed", "Mining Spread", "Mining Wisdom", "Farming Fortune", "Foraging Fortune", "Crit Damage", "Crit Chance", "True Defense", "Magic Find", "Pet Luck", "Attack Speed", "Ability Damage", "Sea Creature Chance", "Fishing Speed", "Swing Range", "Overflow Mana", "Health", "Defense", "Strength", "Intelligence", "Speed", "Mana", "Ferocity", "Vitality", "Mending", "Pristine", "Sweep", "Respiration", "Titanium Ore", "Chocolate Factory", "Perfect Gemstone", "Forge Timers", "Mining Islands", "Galatea"};
    static final Pattern PATTERN = Pattern.compile("(?i)(?<![A-Za-z])(?:" + String.join("|", Arrays.stream(NAMES).sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).toList()) + ")(?![A-Za-z])");
    // Reviewed lists have individual native atoms; unknown lists stay indivisible.
    static Pattern forText(String text) { return forText(text, false); }
    static Pattern forText(String text, boolean protectPlayerHeader) {
        var lists = new ArrayList<>(TypedDictionaryTokens.protectedListPatterns(text));
        TypedDictionaryTokens.addLevelPatterns(text, lists);
        // Keep the entire chat header, including icons and numeric usernames, as one styled term.
        var player = protectPlayerHeader ? ChatEnvelope.player(text) : null;
        boolean playerChat = player != null;
        if (playerChat) {
            lists.add(Pattern.quote(text.substring(0, player.bodyStart())));
            Pattern.compile("(?i)(?:^|: )([A-Za-z0-9_]{1,32})(?= (?:u|you) )|(?<=buy )([A-Za-z0-9_]{1,3})(?![A-Za-z0-9_])")
                .matcher(text).results().filter(m -> m.group(1) == null || !m.group(1).matches("(?i)if|have|do|did|can|could|would|will|are|were|thank|when|what|why|how"))
                .forEach(m -> lists.add(Pattern.quote(m.group(1) == null ? m.group(2) : m.group(1))));
        }
        Pattern.compile("(?i)(?<![A-Za-z0-9_])(?:l[1-5]|bs)(?![A-Za-z0-9_])").matcher(text).results()
            .forEach(m -> lists.add(Pattern.quote(m.group())));
        CommandTokens.addPatterns(text, lists);
        HexColorTokens.addPatterns(text, lists);
        String extra = "(?<![A-Za-z])(?:Auto-pickup|Coal|Honeycomb Collection|Fun-Sized Pot of Honeycomb|Honeyhives|Mithril Powder|Mushroom|Tasty Cheese|Dung|The Catacombs|Haunted Skull|Safari Belt|Safari Essence|Anvil|special Zealot|Crystal Hollows|Magma Fields|Tarantula Broodfather|Griffin Feathers|Mythos Fragments|Torrhus Canyon|Miria’s Contests|Critter Safari|Torrhus Sea Creatures|Collections|Launch Pad|Moonglade Marsh|Trade Center|Wheat Minion|Mithril Plate|fleece|Chocolate Shop|Chocolate Rabbits|Chocobit Rabbits|Hoppity's Hunt|Elusive|Trevor|Jake|Mage Faction)(?![A-Za-z])";
        // Lowercase strength/speed/vitality also describe ordinary prose (the speed of
        // a machine, an object's strength). Protect canonical stat names, and lowercase
        // forms only when the source explicitly identifies a player stat/quantity.
        Set<String> ordinary = Set.of("Strength", "Speed", "Vitality", "Defense", "Intelligence", "Health");
        Set<String> reviewedOrdinary = OrdinaryTokenRoles.wordsForSource(text);
        var statPatterns = new ArrayList<String>();
        for (String term : NAMES) {
            if (!ordinary.contains(term)) statPatterns.add("(?i:" + Pattern.quote(term) + ")");
            else {
                statPatterns.add(Pattern.quote(term));
                String lower = term.toLowerCase(Locale.ROOT);
                if (reviewedOrdinary.contains(lower)) continue;
                var uses = Pattern.compile("(?<![A-Za-z])" + lower + "(?![A-Za-z])").matcher(text);
                while (uses.find()) {
                    String before = text.substring(Math.max(0, uses.start() - 48), uses.start());
                    if (before.matches("(?is).*(?:\\b(?:your|base|max(?:imum)?|total|bonus|extra)\\s+|[+−-]?\\d[\\d,.%]*[\\p{Co}☯\\s]*)$")) {
                        statPatterns.add(Pattern.quote(lower)); break;
                    }
                }
            }
        }
        statPatterns.sort(Comparator.comparingInt(String::length).reversed());
        return Pattern.compile((lists.isEmpty() ? "" : String.join("|", lists) + "|")
            + "(?i:Transmission abilities|Nest Endermite|Draconic Altar|Combat XP|Deployables)|" + extra
            + "|(?<![A-Za-z])(?:" + String.join("|", statPatterns) + ")(?![A-Za-z])");
    }

    /** English inflections explicitly known to identify the same game entity/unit. */
    static List<String> dictionaryEnglishAliases(String literal) {
        var result = new ArrayList<String>(); result.add(literal);
        Map<String, String> plurals = Map.ofEntries(
            Map.entry("stews", "Stew"), Map.entry("critters", "Critter"), Map.entry("pets", "Pet"),
            Map.entry("minions", "Minion"), Map.entry("rabbits", "Rabbit"), Map.entry("chests", "Chest"),
            Map.entry("skills", "Skill"), Map.entry("islands", "Island"), Map.entry("buckets", "Bucket"),
            Map.entry("followers", "Follower"), Map.entry("abilities", "Ability"), Map.entry("deployables", "Deployable"),
            Map.entry("sharks", "Shark"), Map.entry("collections", "Collection"),
            Map.entry("pests", "Pest"), Map.entry("traps", "Trap"),
            Map.entry("quests", "Quest"), Map.entry("abiphones", "Abiphone"),
            Map.entry("potions", "Potion"), Map.entry("logs", "Log"),
            Map.entry("milestones", "Milestone"), Map.entry("vinyls", "Vinyl"), Map.entry("creatures", "Creature"));
        int space = literal.lastIndexOf(' ');
        String word = literal.substring(space + 1);
        String singular = plurals.get(word.toLowerCase(Locale.ROOT));
        if (singular != null) result.add(literal.substring(0, space + 1) + singular);
        result.addAll(TypedDictionaryTokens.englishAliases(literal));
        if (literal.equalsIgnoreCase("Forge Timers")) result.add("Forgeの残り時間");
        return result.stream().distinct().toList();
    }
}
