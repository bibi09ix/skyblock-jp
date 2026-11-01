package local.skyblockjp;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import java.util.regex.Pattern;

final class ChatText {
    private static final Pattern HEADER = Pattern.compile("^<t\\d+>[^<>]*:\\s*</t\\d+>");
    static String body(String source) { return HEADER.matcher(source).replaceFirst(""); }
    static String rejoin(String source, String result) {
        if (result == null || result.isBlank()) return result;
        var header = HEADER.matcher(source);
        if (!header.find()) return result;
        return header.group() + result.replace(header.group(), "");
    }
    static String validate(String source, String result) {
        String error = NpcTranslation.validate(source, result);
        if (error != null) return error;
        String prose = result.replaceAll("<t\\d+>[^<>]*</t\\d+>", " ").replaceAll("</?s\\d+>", "");
        if (prose.matches("(?is).*\\b(?:lf|lfg|u|wanna|want to buy|p me)\\b.*"))
            return "CHAT_UNTRANSLATED: ordinary chat shorthand remains";
        return null;
    }
    static String unwrap(String result) {
        if (result == null) return null;
        String text = result.strip();
        if (text.startsWith("```")) text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        if (!text.startsWith("{")) return result;
        try {
            var obj = JsonParser.parseString(text).getAsJsonObject();
            if (obj.has("dialogue") && obj.get("dialogue").isJsonPrimitive() && obj.get("dialogue").getAsJsonPrimitive().isString())
                return obj.get("dialogue").getAsString();
        } catch (Exception ignored) {}
        return result;
    }
    static boolean coordinatesOnly(String source) {
        String plain = source.replaceAll("<t\\d+>([^<>]*)</t\\d+>", "$1").replaceAll("</?s\\d+>", "");
        return plain.matches("(?is).*(?:^|: )x: <n\\d+/>,?\\s*y: <n\\d+/>,?\\s*z: <n\\d+/>(?:\\s+[A-Za-z][A-Za-z _-]*)?");
    }
    /** Fixed server template: preserve both styled names and never swap killer/victim. */
    static Component death(Component source) {
        var paragraph = NpcTranslation.whole(source);
        Component normalized = paragraph.decode(paragraph.encoded());
        String plain = normalized.getString();
        var m = Pattern.compile("^(☠\\s*)(.+?) was killed by (.+?)\\.$").matcher(plain);
        if (!m.matches()) return null;
        return slice(normalized, m.start(1), m.end(2)).copy().append(Component.literal(" は "))
            .append(slice(normalized, m.start(3), m.end(3))).append(Component.literal(" に倒された。"));
    }
    private static Component slice(Component source, int start, int end) {
        var out = Component.empty(); int offset = 0;
        for (var glyph : StyledParagraph.glyphs(source)) {
            if (offset >= start && offset < end) out.append(Component.literal(glyph.text()).setStyle(glyph.style()));
            offset += glyph.text().length();
        }
        return out;
    }
}
