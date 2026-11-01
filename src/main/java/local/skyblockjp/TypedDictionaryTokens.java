package local.skyblockjp;

import java.util.*;
import java.util.regex.*;

/** Semantic atoms shared by dictionary validation and native Component reconstruction. */
final class TypedDictionaryTokens {
    private TypedDictionaryTokens() {}

    // Deliberately reviewed inflections, not stemming: changing an unknown item name is unsafe.
    private static final Map<String, String> ENTITIES = entityAliases();
    private static Map<String, String> entityAliases() {
        var result = new LinkedHashMap<String, String>();
        for (String name : List.of("Candy", "Chocolate", "Silverfish", "Chocolate Factory", "Kat", "Fann",
            "Greenhouse", "The Forge", "Mithril Powder", "Gemstone Powder", "Glacite Powder", "Sweep",
            "Toughness", "Style", "Sugar Cane", "Cactus")) result.put(name, name);
        for (String pair : List.of("Gifts=Gift", "Talismans=Talisman", "Rings=Ring", "Artifacts=Artifact",
            "Orbs=Orb", "Spiders=Spider", "Cave Spiders=Cave Spider", "Squids=Squid", "Guardians=Guardian",
            "Elder Guardians=Elder Guardian", "Creepers=Creeper", "Magma Cubes=Magma Cube", "Slimes=Slime",
            "Minions=Minion", "Aging Items=Aging Item", "Ender Dragons=Ender Dragon", "Endermen=Enderman",
            "Endermites=Endermite", "Helpers=Helper", "Moderators=Moderator", "Admins=Admin",
            "Dead Bushes=Dead Bush", "Axes=Axe", "Scythes=Scythe", "Daggers=Dagger", "Katanas=Katana")) {
            String[] parts = pair.split("="); result.put(parts[0], parts[1]);
        }
        return Collections.unmodifiableMap(result);
    }
    private static final String SKILLS = "SkyBlock|Combat|Mining|Taming|Farming|Foraging|Fishing|Enchanting|Alchemy|Carpentry";
    private static final String SPACE = "[\\s\\p{Zs}]";
    private static final Pattern LEVEL_SOURCE = Pattern.compile("\\b(" + SKILLS + ")" + SPACE + "+(Level|Skill)" + SPACE + "+(\\d+|[IVXLCDM]+)(?![A-Za-z0-9])");
    private static final Pattern LEVEL_TARGET = Pattern.compile("(?<![A-Za-z])(" + SKILLS
        + ")" + SPACE + "*(?:の" + SPACE + "*)?(?:(?:Skill" + SPACE + "*)?レベル(?:を)?|SkillのLevel|Level|Skill)" + SPACE + "*(\\d+|[IVXLCDM]+)(?![A-Za-z0-9])");
    private static final String NAME = "[A-Z][A-Za-z'-]*(?: [A-Z][A-Za-z'-]*)*";
    private static final Pattern NAMED_LIST = Pattern.compile("(?<![A-Za-z])(?!(?:Between|The|Both|Includes|Grants|Gain|With|For|To|And|Or|While|In|From|Visit|About|Farming|Harvesting|Killing|Mining|Fishing|Collecting)\\b)"
        + NAME + "(?:, " + NAME + ")+(?:,? and " + NAME + ")");
    private static final Pattern WORD = Pattern.compile("[A-Za-z]+(?:'[A-Za-z]+)?");
    private static final Pattern ENTITY = Pattern.compile("(?<![A-Za-z])(?:"
        + java.util.stream.Stream.concat(ENTITIES.keySet().stream(), ENTITIES.values().stream()).distinct()
            .sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote)
            .reduce((a, b) -> a + "|" + b).orElse("(?!)") + ")(?![A-Za-z])", Pattern.CASE_INSENSITIVE);

    static String boundary(String atom) {
        return "(?<![A-Za-z0-9_])" + Pattern.quote(atom) + "(?![A-Za-z0-9_])";
    }

    /** Unknown lists stay indivisible. Only fully recognized lists are split into native atoms. */
    static List<String> protectedListPatterns(String text) {
        var result = new ArrayList<String>();
        NAMED_LIST.matcher(text).results().forEach(match -> {
            var members = listMembers(match.group());
            if (members.isEmpty()) result.add(Pattern.quote(match.group()));
            else members.forEach(member -> result.add(boundary(member)));
        });
        return result;
    }

    private static List<String> listMembers(String literal) {
        var members = new ArrayList<>(Arrays.stream(literal.split(",\\s*(?:and\\s+)?|\\s+and\\s+"))
            .map(String::strip).toList());
        if (members.size() < 3) return List.of();
        members.set(0, members.getFirst().replaceFirst("^(?:Increases|Impacts|Requires)\\s+", ""));
        return members.stream().allMatch(ENTITIES::containsKey) ? List.copyOf(members) : List.of();
    }

    static void addLevelPatterns(String text, List<String> terms) {
        EffectLevelTokens.addPatterns(text, terms);
        LEVEL_SOURCE.matcher(text).results().forEach(match -> {
            terms.add(boundary(match.group(1)));
            // Arabic quantities already have a numeric atom. Roman values need one explicitly.
            if (!match.group(3).matches("\\d+")) terms.add(boundary(match.group(3)));
        });
    }

    static List<String> englishAliases(String literal) {
        String alias = ENTITIES.get(literal);
        return alias != null && !alias.equals(literal) ? List.of(alias) : List.of();
    }

    record Validation(String reason, Map<String, Object> details) {
        Validation { details = Map.copyOf(details); }
    }
    static String validate(String source, String target) { return inspect(source, target).reason(); }

    static Validation inspect(String source, String target) {
        var rateCheck = DictionaryRateTokens.inspect(source, target);
        if (!rateCheck.reason().isEmpty()) return rateCheck;
        var sourceCommands = CommandTokens.parse(source);
        var targetCommands = CommandTokens.parse(target);
        var originalCommands = sourceCommands.stream().map(c -> c.text().toLowerCase(Locale.ROOT)).sorted().toList();
        var translatedCommands = targetCommands.stream().map(c -> c.text().toLowerCase(Locale.ROOT)).sorted().toList();
        if (!originalCommands.equals(translatedCommands)) return new Validation("COMMAND_ARGUMENT_MISMATCH",
            Map.of("validationStage", "TYPED_COMMAND_ARGUMENTS", "sourceCommands", originalCommands, "targetCommands", translatedCommands));
        var effectLevels = EffectLevelTokens.inspect(source, target);
        if (!effectLevels.reason().isEmpty()) return effectLevels;
        var sourceLevels = levelPairs(source, false);
        var targetLevels = levelPairs(target, true);
        if (!sourceLevels.isEmpty() && !sourceLevels.equals(targetLevels)) return new Validation("TYPED_LEVEL_ROLE_MISMATCH",
            Map.of("validationStage", "TYPED_LEVEL_ROLES", "sourceRoles", sourceLevels, "targetRoles", targetLevels));
        boolean convertedList = NAMED_LIST.matcher(source).results().anyMatch(m -> !listMembers(m.group()).isEmpty());
        if (convertedList) {
            var sourceMembers = entityCounts(source); var targetMembers = entityCounts(target);
            if (!sourceMembers.equals(targetMembers)) return new Validation("TYPED_LIST_MEMBER_MISMATCH",
                Map.of("validationStage", "TYPED_LIST_MEMBERS", "sourceMembers", sourceMembers, "targetMembers", targetMembers));
            // Unknown additions must not pass merely because all recognized members survived.
            var allowed = new HashMap<String, Integer>();
            WORD.matcher(source).results().forEach(m -> allowed.merge(m.group().toLowerCase(Locale.ROOT), 1, Integer::sum));
            ENTITIES.forEach((plural, singular) -> {
                if (plural.equals(singular)) return;
                int occurrences = (int) Pattern.compile(boundary(plural), Pattern.CASE_INSENSITIVE).matcher(source).results().count();
                if (occurrences > 0) WORD.matcher(singular).results()
                    .forEach(m -> allowed.merge(m.group().toLowerCase(Locale.ROOT), occurrences, Integer::sum));
            });
            // Reviewed prose inflections elsewhere in the same paragraph do not identify new entities.
            for (var pair : Map.of("creatures", "creature", "rabbits", "rabbit", "wands", "wand").entrySet())
                if (allowed.containsKey(pair.getKey())) allowed.merge(pair.getValue(), allowed.get(pair.getKey()), Integer::sum);
            var unknown = WORD.matcher(target).results().map(MatchResult::group)
                .filter(word -> !allowed.containsKey(word.toLowerCase(Locale.ROOT))).distinct().toList();
            if (!unknown.isEmpty()) return new Validation("TYPED_LIST_UNKNOWN_TARGET_WORD",
                Map.of("validationStage", "TYPED_LIST_ADDITIONS", "unknownTargetWords", unknown));
            var targetWords = new TreeMap<String, Integer>();
            WORD.matcher(target).results().forEach(m -> targetWords.merge(m.group().toLowerCase(Locale.ROOT), 1, Integer::sum));
            var excess = new TreeMap<String, Object>();
            targetWords.forEach((word, count) -> {
                int limit = allowed.getOrDefault(word, 0);
                if (count > limit) excess.put(word, Map.of("sourceOrApprovedAliasCount", limit, "targetCount", count));
            });
            if (!excess.isEmpty()) return new Validation("TYPED_LIST_TARGET_WORD_COUNT",
                Map.of("validationStage", "TYPED_LIST_REPEATED_ADDITIONS", "excessTargetWords", excess));
        }
        return new Validation("", Map.of());
    }

    private static Map<String, Integer> entityCounts(String text) {
        var counts = new TreeMap<String, Integer>();
        ENTITY.matcher(text).results().forEach(m -> {
            String canonical = ENTITIES.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(m.group()) || e.getValue().equalsIgnoreCase(m.group()))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
            counts.merge(canonical, 1, Integer::sum);
        });
        return counts;
    }

    private static List<String> levelPairs(String text, boolean target) {
        var result = new ArrayList<String>();
        (target ? LEVEL_TARGET : LEVEL_SOURCE).matcher(text).results()
            .forEach(m -> result.add(m.group(1) + "=" + m.group(target ? 2 : 3)));
        Collections.sort(result); return result;
    }

    /** Source offsets use normalized UTF16; target offsets refer to the literal Match text. */
    static List<BundledDictionary.Binding> bindings(String source, String target) {
        source = BundledDictionary.normalize(source);
        var originals = LEVEL_SOURCE.matcher(source).results().toList();
        var translated = LEVEL_TARGET.matcher(target).results().toList();
        var result = new ArrayList<BundledDictionary.Binding>();
        for (var original : originals) {
            var candidates = translated.stream().filter(t -> original.group(1).equals(t.group(1))
                && original.group(3).equals(t.group(2))).toList();
            long count = originals.stream().filter(s -> s.group(1).equals(original.group(1))
                && s.group(3).equals(original.group(3))).count();
            if (candidates.size() != 1 || count != 1) continue;
            var translatedRole = candidates.getFirst();
            result.add(new BundledDictionary.Binding("level_name_" + original.start(), "name",
                original.start(1), original.end(1), translatedRole.start(1), translatedRole.end(1), original.group(1), translatedRole.group(1)));
            result.add(new BundledDictionary.Binding("level_value_" + original.start(), "number",
                original.start(3), original.end(3), translatedRole.start(2), translatedRole.end(2), original.group(3), translatedRole.group(2)));
        }
        result.addAll(DictionaryRateTokens.bindings(source, target));
        result.addAll(EffectLevelTokens.bindings(source, target));
        return List.copyOf(result);
    }
}
