package local.skyblockjp;

import java.util.Locale;

/** Semantic unit of a literal quantity; used only to disambiguate equal native values. */
final class QuantityRoles {
    private QuantityRoles() {}
    static String source(String text, int start, int end) {
        String after = text.substring(end).toLowerCase(Locale.ROOT);
        if (after.matches("(?s)^(?:st|nd|rd|th)\\b.*")) return "ordinal";
        if (after.matches("(?s)^\\s*(?:seconds?|secs?|s)\\b.*")) return "seconds";
        if (after.matches("(?s)^\\s*(?:minutes?|mins?)\\b.*")) return "minutes";
        if (after.matches("(?s)^\\s*(?:hours?|hrs?)\\b.*")) return "hours";
        if (after.matches("(?s)^\\s*days?\\b.*")) return "days";
        if (after.matches("(?s)^\\s*blocks?\\b.*")) return "distance";
        return "";
    }
    static String target(String text, int start, int end) {
        String after = text.substring(end).stripLeading();
        if (after.matches("(?s)^(?:回目|番目|度目).*" ) || text.substring(0, start).stripTrailing().endsWith("第")) return "ordinal";
        if (after.startsWith("秒")) return "seconds";
        if (after.startsWith("分")) return "minutes";
        if (after.startsWith("時間")) return "hours";
        if (after.startsWith("日")) return "days";
        if (after.startsWith("ブロック")) return "distance";
        return source(text, start, end);
    }
}
