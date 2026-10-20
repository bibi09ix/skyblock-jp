package local.skyblockjp;
import java.util.*;
import java.util.function.Function;
import net.minecraft.network.chat.Component;

/** Prepare every replacement before making any visible change. */
public final class ItemTranslation {
    public record Result(List<Component> lines, int complete, int total, String error) {
        public boolean ready() { return total > 0 && complete == total && error == null; }
    }
    public static Result prepare(List<Component> original, List<LoreParser.Block> blocks,
            List<StyledParagraph> paragraphs, Function<String, String> cache,
            Function<Component, List<Component>> wrap) {
        List<List<Component>> replacements = new ArrayList<>();
        int complete = 0;
        String error = null;
        for (StyledParagraph paragraph : paragraphs) {
            String text = cache.apply(paragraph.encoded());
            if (text == null) { replacements.add(null); continue; }
            try { replacements.add(wrap.apply(paragraph.decodeForDisplay(text))); complete++; }
            catch (IllegalArgumentException e) { replacements.add(null); error = "色・数値の復元に失敗しました"; }
        }
        List<Component> result = new ArrayList<>(original);
        if (complete == paragraphs.size() && error == null) {
            for (int i = blocks.size() - 1; i >= 0; i--) {
                result.subList(blocks.get(i).start(), blocks.get(i).end()).clear();
                result.addAll(blocks.get(i).start(), replacements.get(i));
            }
        }
        return new Result(result, complete, paragraphs.size(), error);
    }
}
