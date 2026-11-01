package local.skyblockjp;

import java.util.regex.Pattern;

/** One source of truth for immutable player headers and HUD sender/body boundaries. */
final class ChatEnvelope {
    record Header(String channel, int nameStart, int nameEnd, int bodyStart) {}
    private static final Pattern PLAYER = Pattern.compile(
        "^\\s*(?:(?i:(Party|Guild|Officer|Co-op|Coop))\\s*>\\s*|(?i:(From|To))\\s+)?"
        + "(?:(?:\\[[^\\]\\r\\n]{1,60}\\]|[^A-Za-z0-9\\s<>\\[\\]]+)\\s*)*"
        + "([A-Za-z0-9_]{1,32})(?:\\s+\\[[^\\]\\r\\n]{1,60}\\])*:\\s*");
    private static final Pattern NON_PLAYER = Pattern.compile("(?i)^\\s*\\[(?:NPC|BOSS|JP)\\]");

    static Header player(String text) {
        if (text == null || NON_PLAYER.matcher(text).find() || NpcTranslation.optionPrompt(text)) return null;
        var match = PLAYER.matcher(text);
        if (!match.find()) return null;
        String channel = match.group(1) != null ? match.group(1) : match.group(2) != null ? match.group(2) : "Public";
        return new Header(channel, match.start(3), match.end(3), match.end());
    }
}
