package local.skyblockjp;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

final class TooltipWrap {
    static List<Component> wrap(Component text, int width, ToIntFunction<Component> measure) {
        var glyphs = StyledParagraph.glyphs(text);
        int count = glyphs.size();
        StringBuilder plain = new StringBuilder();
        int[] offsets = new int[count + 1], sizes = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = plain.length(); plain.append(glyphs.get(i).text());
            sizes[i] = measure.applyAsInt(Component.literal(glyphs.get(i).text()).setStyle(glyphs.get(i).style()));
        }
        offsets[count] = plain.length();
        boolean[] locked = new boolean[plain.length() + 1];
        for (Pattern pattern : List.of(ProtectedTerms.forText(plain.toString()),
            Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?/\\d[\\d,]*(?:\\.\\d+)?[kKmMbB]?"),
            Pattern.compile("/?[A-Za-z][A-Za-z0-9_'/-]*"),
            Pattern.compile("[+−-]?\\d[\\d,]*(?:\\.\\d+)?(?:\\s*(?:秒間|分間|時間|秒|分|日|回|体|個|つ|倍|%|[A-Za-z]+))?[\\p{Co}❤☯]*"),
            Pattern.compile("[ァ-ヺー]+[ぁ-ゖ]*|[\\p{IsHan}々]+[ぁ-ゖ]*|[ぁ-ゖ]+"))) {
            pattern.matcher(plain).results().forEach(m -> { for (int k = m.start() + 1; k < m.end(); k++) locked[k] = true; });
        }
        List<Component> result = new ArrayList<>();
        for (int start = 0; start < count;) {
            int end = start, used = 0;
            while (end < count && !glyphs.get(end).text().equals("\n") && (end == start || used + sizes[end] <= width)) used += sizes[end++];
            int cut = end;
            if (end < count && !glyphs.get(end).text().equals("\n")) {
                while (cut > start && !allowed(glyphs, offsets, locked, cut)) cut--;
                if (cut == start) cut = end; // A token wider than the viewport must be split.
                // Avoid a final line consisting of only a few characters when a nearby
                // legal boundary can give it some context without an excessive gap.
                if (count - cut <= 3 && count - cut > 0) {
                    for (int candidate = cut - 1; candidate > start; candidate--) {
                        if (allowed(glyphs, offsets, locked, candidate)) {
                            if (candidate - start >= (cut - start) * 0.65) cut = candidate;
                            break;
                        }
                    }
                }
                // Hanging punctuation is preferable to a closing mark alone on the next line.
                while (cut < count && closing(glyphs.get(cut).text())) cut++;
            }
            var line = Component.empty();
            for (int i = start; i < cut; i++) line.append(Component.literal(glyphs.get(i).text()).setStyle(glyphs.get(i).style()));
            result.add(line);
            start = cut;
            if (start < count && glyphs.get(start).text().equals("\n")) start++;
        }
        if (count > 0 && glyphs.getLast().text().equals("\n")) result.add(Component.empty());
        return result;
    }
    private static boolean closing(String s) { return "、。，．！？!?：:；;)]}）］｝」』】".contains(s); }
    private static boolean allowed(List<StyledParagraph.Glyph> glyphs, int[] offsets, boolean[] locked, int cut) {
        return !locked[offsets[cut]] && !closing(glyphs.get(cut).text()) && !"([{（［｛「『【".contains(glyphs.get(cut - 1).text());
    }
}
