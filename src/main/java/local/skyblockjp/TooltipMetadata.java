package local.skyblockjp;

import java.util.List;
import java.util.regex.Pattern;

/** Complete metadata grammars and source sections, never a generic English-prose skip. */
final class TooltipMetadata {
    record Match(String role, String reason) {}

    private static final String NUMBER =
            "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?(?:[kKmMbBtT])?";
    private static final String VALUE = "[+−-]?" + NUMBER;
    private static final String GLYPHS = "[\\p{Co}\\p{So}❤☯⸙ꕮ\\s]*";
    // No clauses after the last unit; in particular, 'while ...' remains prose.
    private static final String DURATION =
            "(?=[0-9])(?:[0-9]+d\\s*)?(?:[0-9]+h\\s*)?(?:[0-9]+m\\s*)?(?:[0-9]+(?:\\.[0-9]+)?s\\s*)?";
    private static final Pattern DURATION_VALUE = Pattern.compile(DURATION);
    private static final String TITLE =
            "[A-Z][A-Za-z0-9'’_-]*(?: (?:[A-Z][A-Za-z0-9'’_-]*|of|the|and|a|an|in)){0,9}";
    private static final Pattern EVENT = Pattern.compile("(" + TITLE + ") (" + DURATION + ")");
    private static final Pattern SUMMON =
            Pattern.compile("- " + TITLE + " Lv[0-9]{1,3} \\(" + NUMBER + " Mana\\)");
    private static final Pattern CONTRIBUTION =
            Pattern.compile(NUMBER + "x from (?:Inventory|Sacks|Ender Chest #[1-9][0-9]?)");
    private static final Pattern INGREDIENT =
            Pattern.compile(
                    "[✔✖] " + NUMBER + "/" + NUMBER + " \\((?:" + NUMBER + "x|[+−-]" + NUMBER
                            + ")\\) " + TITLE);

    private record Rule(Pattern pattern, Match result) {}

    private static final Pattern RIFT_PERMANENT =
            Pattern.compile("Permanent: [+]" + NUMBER + "\\uE020 Rift Time");
    private static final List<Rule> VALUES =
            List.of(
                    rule(
                            "STAT",
                            "STAT_POINT_ALLOCATION_VALUE",
                            "(?:Stat has: " + NUMBER + " points|Points left: " + NUMBER + "!?)"),
                    rule(
                            "STAT",
                            "CURRENT_NUMERIC_STAT_VALUE",
                            "You have: " + VALUE + "(?: \\+ " + NUMBER + ")*" + GLYPHS),
                    rule(
                            "PROGRESS",
                            "SKILL_AVERAGE_VALUE",
                            NUMBER + " Skill Avg\\. \\(non-cosmetic\\)"),
                    rule(
                            "STAT",
                            "TRAINING_EXPERIENCE_VALUE",
                            "Increased EXP: [+]" + NUMBER + "% EXP"),
                    rule(
                            "ITEM_METADATA",
                            "SKYBLOCK_CALENDAR_DATE_VALUE",
                            "Date: [0-9]{1,2}(?:st|nd|rd|th) (?:Early |Late )?(?:Spring|Summer|Autumn|Winter) [0-9]{1,4}"),
                    rule(
                            "PROGRESS",
                            "LEVEL_TRANSITION_WITH_FRACTION",
                            "Level [0-9]+ (?:->|[➜→›»]) [0-9]+\\s*\\("
                                    + NUMBER
                                    + "/"
                                    + NUMBER
                                    + "\\)"),
                    rule(
                            "STAT",
                            "HEALTH_REGEN_FORMULA_VALUE",
                            "(?:Base regen ticks: "
                                    + NUMBER
                                    + "% of Max ❤ \\+ "
                                    + NUMBER
                                    + "❤|Regen interval: Every "
                                    + NUMBER
                                    + " seconds?)"),
                    rule("ITEM_METADATA", "BOOSTER_SOCKET_GLYPHS", "Boosters: [⸙ꕮ](?:/[⸙ꕮ])*"),
                    rule("ITEM_METADATA", "EMPTY_FISHING_COMPONENT", "ථ Hook NONE"),
                    rule(
                            "ITEM_METADATA",
                            "COOKIE_PREVIEW_BITS_VALUE",
                            "Bits available to gain after: " + NUMBER),
                    rule(
                            "ITEM_METADATA",
                            "SACK_PER_ITEM_CAPACITY_VALUE",
                            "Capacity: " + NUMBER + " of each item"),
                    rule("STAT", "SKELETOR_KILL_COUNTER", "Skeletor Kills: " + NUMBER));

    private TooltipMetadata() {}

    static Match classify(String source) {
        String s = LoreParser.clean(source);
        if (s.length() > 400) return null;
        if (labelDuration(s, "Duration:")
                || labelDuration(s, "Duration after:")
                || labelDuration(s, "Starting in:"))
            return keep("DURATION_OR_COST", "COMPOUND_DURATION_VALUE");
        for (Rule rule : VALUES) if (rule.pattern().matcher(s).matches()) return rule.result();
        if (RIFT_PERMANENT.matcher(s).matches()
                || s.startsWith("Use cost:")
                        && riftDurationCost(s.substring("Use cost:".length()).strip()))
            return keep("DURATION_OR_COST", "RIFT_TIME_METRIC_VALUE");
        return null;
    }

    static Match classify(List<String> lines, int index) {
        if (lines == null || index < 0 || index >= lines.size()) return null;
        String source = LoreParser.clean(lines.get(index));
        Match direct = classify(source);
        if (direct != null) return direct;
        if (source.length() > 400) return null;
        if (EVENT.matcher(source).matches() && inSection(lines, index, "Active Events:", EVENT))
            return keep("ITEM_METADATA", "ACTIVE_EVENT_COUNTDOWN_VALUE");
        if (source.matches("Next Event: [0-9]{1,4}(?:st|nd|rd|th) " + TITLE) && calendar(lines))
            return keep("ITEM_METADATA", "UPCOMING_EVENT_ORDINAL_VALUE");
        if (SUMMON.matcher(source).matches() && inSection(lines, index, "Absorbed Souls:", SUMMON))
            return keep("ITEM_METADATA", "ABSORBED_SOUL_ENTRY");
        if (source.equals("Absorbed Souls:")
                && index + 1 < lines.size()
                && SUMMON.matcher(LoreParser.clean(lines.get(index + 1))).matches())
            return keep("ITEM_METADATA", "ABSORBED_SOUL_SECTION");
        if (source.matches("[+]" + NUMBER + "h (?:Default|Alchemy Level)")
                && durationBreakdown(lines, index))
            return keep("DURATION_OR_COST", "DURATION_BREAKDOWN_VALUE");
        if (source.startsWith("Location:")
                && riftEye(lines)
                && source.matches("Location: [\\p{L}0-9'’ -]{1,70}"))
            return keep("ITEM_METADATA", "RIFT_EYE_LOCATION_VALUE");
        if (loadout(lines) && loadoutValue(source))
            return keep("ITEM_METADATA", "LOADOUT_SELECTED_COMPONENT_VALUE");
        if (supercraft(lines)) {
            if (INGREDIENT.matcher(source).matches())
                return keep("COST_VALUE", "SUPERCRAFT_INGREDIENT_TOTAL");
            if (CONTRIBUTION.matcher(source).matches() && ingredientSection(lines, index))
                return keep("COST_VALUE", "SUPERCRAFT_INGREDIENT_SOURCE");
        }
        return null;
    }

    private static boolean labelDuration(String source, String label) {
        if (!source.startsWith(label)) return false;
        String value = source.substring(label.length()).strip();
        return !value.isBlank() && DURATION_VALUE.matcher(value).matches();
    }

    private static boolean riftDurationCost(String source) {
        String suffix = " \uE020 Rift Time";
        return source.endsWith(suffix)
                && DURATION_VALUE
                        .matcher(source.substring(0, source.length() - suffix.length()))
                        .matches();
    }

    private static boolean inSection(List<String> lines, int index, String header, Pattern entry) {
        for (int i = index - 1; i >= 0 && index - i <= 12; i--) {
            String previous = LoreParser.clean(lines.get(i));
            if (previous.equals(header)) return true;
            if (!entry.matcher(previous).matches()) return false;
        }
        return false;
    }

    private static boolean durationBreakdown(List<String> lines, int index) {
        for (int i = index - 1; i >= 0 && index - i <= 3; i--) {
            String previous = LoreParser.clean(lines.get(i));
            if (labelDuration(previous, "Duration:")) return true;
            if (!previous.matches("[+]" + NUMBER + "h (?:Default|Alchemy Level)")) return false;
        }
        return false;
    }

    private static boolean calendar(List<String> lines) {
        return !lines.isEmpty()
                && LoreParser.clean(lines.getFirst()).equals("Calendar and Events")
                && lines.stream().anyMatch(s -> LoreParser.clean(s).equals("Active Events:"));
    }

    private static boolean riftEye(List<String> lines) {
        return lines.stream()
                        .map(LoreParser::clean)
                        .anyMatch(s -> s.matches("Condition: (?:CALM|ROGUE)"))
                && lines.stream()
                        .map(LoreParser::clean)
                        .anyMatch(s -> RIFT_PERMANENT.matcher(s).matches())
                && lines.stream()
                        .map(LoreParser::clean)
                        .anyMatch(
                                s ->
                                        s.startsWith("Use cost:")
                                                && riftDurationCost(s.substring(9).strip()));
    }

    private static boolean loadout(List<String> lines) {
        return !lines.isEmpty()
                && LoreParser.clean(lines.getFirst()).matches("Loadout [1-9][0-9]?")
                && lines.stream()
                        .map(LoreParser::clean)
                        .anyMatch(s -> s.equals("Left-click to equip!"))
                && lines.stream()
                        .map(LoreParser::clean)
                        .anyMatch(s -> s.equals("Right-click to edit"));
    }

    private static boolean loadoutValue(String source) {
        if (!source.matches(
                "(?:Helmet|Chestplate|Leggings|Boots|Necklace|Cloak|Belt|Gloves/Bracelet|Pet|HOTM|HOTF|Power Stone|Tuning Template Slot|Favored Bait): .{1,160}"))
            return false;
        String value = source.substring(source.indexOf(':') + 1).strip();
        if (source.startsWith("Tuning Template Slot:")) return value.matches("[1-9][0-9]?");
        value =
                value.replaceFirst("^\\[Lvl [0-9]{1,3}\\] ", "")
                        .replaceFirst("^[\\p{Co}\\p{So}]+\\s*", "");
        return value.matches(TITLE + "(?: [✪★]{1,10})?");
    }

    private static boolean supercraft(List<String> lines) {
        return !lines.isEmpty()
                && LoreParser.clean(lines.getFirst()).matches("Supercraft \\([0-9]+x crafts\\)")
                && lines.stream()
                        .map(LoreParser::clean)
                        .anyMatch(s -> s.equals("Right-Click to set amount!"));
    }

    private static boolean ingredientSection(List<String> lines, int index) {
        for (int i = index - 1; i >= 0 && index - i <= 8; i--) {
            String previous = LoreParser.clean(lines.get(i));
            if (INGREDIENT.matcher(previous).matches()) return true;
            if (!CONTRIBUTION.matcher(previous).matches()) return false;
        }
        return false;
    }

    private static Match keep(String role, String reason) {
        return new Match(role, reason);
    }

    private static Rule rule(String role, String reason, String pattern) {
        return new Rule(Pattern.compile(pattern), keep(role, reason));
    }
}
