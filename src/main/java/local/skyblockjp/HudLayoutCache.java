package local.skyblockjp;

import java.util.List;
import java.util.function.ToIntFunction;
import net.minecraft.network.chat.Component;

/** Layout depends on content, available logical width and the current font/resource generation. */
final class HudLayoutCache {
    private List<HudRows.Message> source = List.of();
    private int width = -1;
    private Object font;
    private HudRows.Layout layout;

    HudRows.Layout layout(List<HudRows.Message> messages, int width, Object font, ToIntFunction<Component> measure) {
        if (layout != null && this.width == width && this.font == font && source.equals(messages)) return layout;
        this.layout = HudRows.layout(messages, width, measure);
        this.source = messages.stream().map(m -> new HudRows.Message(snapshot(m.name()), snapshot(m.text()))).toList();
        this.width = width;
        this.font = font;
        return layout;
    }
    void invalidate() { layout = null; source = List.of(); font = null; width = -1; }
    private static Component snapshot(Component source) {
        var copy = source.plainCopy().setStyle(source.getStyle());
        for (var sibling : source.getSiblings()) copy.append(snapshot(sibling));
        return copy;
    }
}
