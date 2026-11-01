package local.skyblockjp;

import java.util.*;
import java.util.regex.*;

/** Source-derived command signatures. Unknown command arguments remain one immutable native span. */
final class CommandTokens {
    private CommandTokens() {}
    record Token(int start, int end, String text, String grammar) {}
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>]+|\\b(?:[A-Za-z0-9-]+\\.)+(?:net|com|org)(?:/[^\\s<>]*)?");
    private static final Pattern COMMAND = Pattern.compile("(?<![A-Za-z0-9_/:])/[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern ARGUMENT = Pattern.compile("\\s+([A-Za-z0-9_][A-Za-z0-9_-]{0,63})(?![A-Za-z0-9_-])");
    private static final Pattern NAME_ARGUMENT = Pattern.compile("[ \\t]+<(?:name|username|id)>");
    private static final Pattern LITERAL_NAME = Pattern.compile("(?<![A-Za-z0-9_/:])/[A-Za-z][A-Za-z0-9_-]*(?:[ \\t]+[A-Za-z0-9_-]+){0,2}[ \\t]+(<(?:name|username|id)>)");
    private static final Pattern NUMBERED_LABEL = Pattern.compile("(?<![A-Za-z0-9_])#[0-9]+(?![A-Za-z0-9_])");
    private static final Set<String> USER = Set.of("invite", "visit", "msg", "message", "w", "whisper", "tell",
        "trade", "duel", "report", "wdr", "watchdogreport", "sbkick", "coopadd", "coopkick", "coopinvite", "stats", "ah");
    private static final Set<String> FREE_MESSAGE = Set.of("msg", "message", "w", "whisper", "tell", "r", "reply");
    private static final Set<String> GROUP = Set.of("p", "party", "f", "friend", "g", "guild", "ignore");
    private static final Set<String> GROUP_USER = Set.of("invite", "add", "remove", "kick", "promote", "demote", "transfer",
        "leader", "accept", "deny", "join", "unignore", "block", "unblock", "list");
    private static final Set<String> GROUP_ZERO = Set.of("leave", "disband", "warp", "allinvite", "list", "online", "help");
    private static final Set<String> MENU = Set.of("accessorybag", "anvil", "av", "bags", "bait", "baits", "bank",
        "bazaar", "boostercookie", "bz", "call", "centauri", "collection", "craft", "dialogue", "dragons", "dyes",
        "effects", "enchantments", "equipment", "et", "etable", "farmingtoolkit", "fishingbag", "guides", "handyblockguide",
        "hex", "honey", "huntingtoolkit", "legacytrades", "pets", "potionbag", "quests", "quiver", "rankcolor",
        "recipes", "rejoin", "rodparts", "sbkickall", "scg", "show", "skills", "skyblockxp", "social", "storage",
        "calendar", "levels", "loadouts", "reclaim", "wiki", "chocolatefactory",
        "timepocket", "viewsettings", "wandinfo", "wardrobe", "wikithis");

    static void addPatterns(String text, List<String> terms) {
        URL.matcher(text).results().forEach(m -> terms.add(Pattern.quote(m.group())));
        parse(text).forEach(token -> terms.add(TypedDictionaryTokens.boundary(token.text())));
    }

    static List<Token> parse(String text) {
        if (text == null || text.indexOf('/') < 0) return List.of();
        var urls = URL.matcher(text).results().toList();
        var result = new ArrayList<Token>(); var commands = COMMAND.matcher(text); int coveredEnd = -1;
        while (commands.find()) {
            int start = commands.start(), commandEnd = commands.end();
            if (start < coveredEnd || urls.stream().anyMatch(u -> start >= u.start() && start < u.end())) continue;
            if (DictionaryRateTokens.isRateSuffix(text, start, commandEnd)) continue;
            String command = commands.group().substring(1).toLowerCase(Locale.ROOT);
            int end = commandEnd; String grammar = "command";
            String tail = text.substring(commandEnd);
            var nameArgument = NAME_ARGUMENT.matcher(tail);
            if (nameArgument.lookingAt()) {
                end = commandEnd + nameArgument.end(); grammar = "literal_name_placeholder";
            } else if (reviewedProse(command, text.substring(0, start), tail)) {
                grammar = "reviewed_prose";
            } else if (FREE_MESSAGE.contains(command)) {
                end = unknownEnd(text, commandEnd); grammar = "message_arguments";
            } else if (GROUP.contains(command)) {
                var first = argument(text, commandEnd);
                if (first != null) {
                    String subcommand = first.group(1).toLowerCase(Locale.ROOT);
                    if (GROUP_ZERO.contains(subcommand)) { end = first.end(); grammar = "group_action"; }
                    else if (GROUP_USER.contains(subcommand)) {
                        var user = argument(text, first.end());
                        end = user == null ? first.end() : user.end(); grammar = "group_username";
                    } else {
                        var second = argument(text, first.end());
                        end = second == null ? first.end() : unknownEnd(text, commandEnd);
                        grammar = second == null ? "group_direct_username" : "unknown_group_arguments";
                    }
                }
            } else if (USER.contains(command) || command.equals("warp") || command.equals("chapters") || command.equals("lobby")) {
                var arg = argument(text, commandEnd);
                if (arg != null) { end = arg.end(); grammar = USER.contains(command) ? "username" : "destination"; }
            } else if (command.equals("coop")) {
                var user = argument(text, commandEnd);
                while (user != null) { end = user.end(); user = argument(text, end); }
                grammar = "multiple_usernames";
            } else if (!MENU.contains(command)) {
                end = unknownEnd(text, commandEnd); grammar = "unknown_arguments";
            }
            // Group commands can also end in a literal argument: /ignore add <username>.
            var trailingPlaceholder = NAME_ARGUMENT.matcher(text); trailingPlaceholder.region(end, text.length());
            if (trailingPlaceholder.lookingAt()) { end = trailingPlaceholder.end(); grammar = "literal_name_placeholder"; }
            // A known signature does not license extra unknown arguments. Keep the
            // complete invocation unless the remainder is a reviewed usage explanation.
            if (!grammar.equals("reviewed_prose") && !grammar.startsWith("unknown_")
                && !grammar.equals("message_arguments") && !grammar.equals("multiple_usernames")) {
                String remainder = text.substring(end);
                if (remainder.matches("(?s)^\\s+[A-Za-z0-9_/-].*") && !explanatorySuffix(command, text.substring(0, start), remainder)) {
                    end = unknownEnd(text, commandEnd); grammar = "unknown_extra_arguments";
                }
            }
            result.add(new Token(start, end, text.substring(start, end), grammar)); coveredEnd = end;
        }
        return List.copyOf(result);
    }

    /** Mask angle brackets only for the checked native command-help argument. */
    static String withoutLiteralCommandPlaceholders(String text) {
        var masked = new StringBuilder(text);
        var matches = LITERAL_NAME.matcher(text);
        while (matches.find()) {
            masked.setCharAt(matches.start(1), ' ');
            masked.setCharAt(matches.end(1) - 1, ' ');
        }
        return masked.toString();
    }

    static String withoutNumberedLabels(String text) {
        var masked = new StringBuilder(text);
        var matches = NUMBERED_LABEL.matcher(text);
        while (matches.find()) masked.setCharAt(matches.start(), ' ');
        return masked.toString();
    }

    private static boolean explanatorySuffix(String command, String before, String remainder) {
        if (command.equals("coopadd") && remainder.startsWith(" to play with friends!")) return true;
        if (command.equals("ignore") && remainder.equals(" to prevent a specific player from joining.")) return true;
        if (command.equals("wiki") && remainder.equals(" to search via an item ID.")) return true;
        if (remainder.matches("(?s)^\\s+to (?:ban|visit|invite|join|warp|send|view|open)\\b.*")) return true;
        if (command.equals("visit") && before.toLowerCase(Locale.ROOT).contains("lowballing")
            && remainder.matches("(?s)^\\s+\\d+(?:\\.\\d+)?[kKmMbBtT]\\+?(?:\\s.*)?$")) return true;
        if (!MENU.contains(command)) return false;
        return (command.equals("sbkickall") && before.endsWith("/sbkick & ") && remainder.startsWith(" commands or clicking on them"))
            || remainder.matches("(?s)^\\s+(?:and|or)\\s+/.*")
            || remainder.startsWith(" as a shortcut") || remainder.startsWith(" anywhere")
            || remainder.startsWith(" to edit its dialogue") || remainder.startsWith(" to access your equipment menu")
            || remainder.startsWith(" to change the color and view all options")
            || remainder.startsWith(" command to warp back") || remainder.startsWith(" instead of looking in his Fishing Skill menu")
            || remainder.startsWith(" whilst holding an item")
            || (command.equals("social") && remainder.startsWith(" Commands"));
    }

    private static Matcher argument(String text, int at) {
        var match = ARGUMENT.matcher(text); match.region(at, text.length());
        return match.lookingAt() ? match : null;
    }

    /** These are checked-in English usage sentences, not heuristics inferred from a proposed translation. */
    private static boolean reviewedProse(String command, String before, String after) {
        return switch (command) {
            case "visit" -> (before.matches("(?s).*\\bAllow (?:anyone|extra guests|friends|guild members) to $")
                    && after.matches("(?s)^\\s+your island\\b.*"))
                || (before.endsWith("Learn all about how to ") && after.matches("(?s)^\\s+players across the SkyBlock universe\\b.*"));
            case "invite" -> before.endsWith("If disabled, you may still ") && after.matches("(?s)^\\s+them[.!]?.*");
            case "coop" -> after.startsWith(" followed by the name of all the friends you want to invite");
            case "coopadd" -> before.endsWith("Allows your co-op to ") && after.startsWith(" extra members into the profile");
            case "report" -> (before.endsWith("the ") || before.endsWith("The ")) && after.matches("(?s)^\\s+command(?:[., !]|$).*");
            case "sbkick" -> after.startsWith(" & /sbkickall") || (before.endsWith("Command: ") && after.startsWith(" Click to kick!"));
            case "lobby" -> after.startsWith(" Protection") || after.startsWith(" command twice to avoid accidents");
            case "ah" -> after.startsWith(" and /bazaar") || after.startsWith(", /bazaar");
            case "reclaim" -> before.endsWith("Use ") && after.equals(" to take back what's rightfully yours!");
            case "wiki" -> before.endsWith("Visit the Wiki using ") && after.equals(" and browse the many pages and utilities.");
            default -> false;
        };
    }

    /** Keep every argument of an unrecognized grammar, including flags and quoted values. */
    private static int unknownEnd(String text, int commandEnd) {
        // Japanese suffixes immediately after a command are prose, not command arguments.
        // An actual Unicode argument starts after whitespace and remains fully protected.
        if (commandEnd == text.length() || !Character.isWhitespace(text.charAt(commandEnd))) return commandEnd;
        int end = commandEnd; char quote = 0;
        for (int i = commandEnd; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < text.length()) { end = ++i + 1; continue; }
                if (c == quote) quote = 0;
                end = i + 1; continue;
            }
            if (c == '\n' || c == '\r' || c == ';' || c == '!' || c == '?' || c == '。' || c == '、' || c == '；') break;
            if (c == '.' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))) break;
            if (c == ',' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))) break;
            if (c == '"' || c == '\'' || c == '`') quote = c;
            end = i + 1;
        }
        while (end > commandEnd && Character.isWhitespace(text.charAt(end - 1))) end--;
        return end;
    }
}
