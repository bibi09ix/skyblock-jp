package local.skyblockjp;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.util.*;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/** Speaker and body remain separate, including after styled line wrapping. */
final class HudRows {
    record Message(Component name, Component text) {}
    record Row(Component name, Component text, Component speaker, int message) {}
    record Layout(int nameWidth, int textWidth, List<Row> rows) {}
    static Message message(String speaker, Component text) {
        if (speaker.equals("チャット")) {
            var m = Pattern.compile("^(?:(?:\\[[^\\]]+\\]|[^A-Za-z0-9_\\s])\\s*)*([A-Za-z0-9_]{1,32}):\\s*").matcher(text.getString());
            if (m.find()) return new Message(slice(text, m.start(1), m.end(1)), slice(text, m.end(), text.getString().length()));
        }
        return new Message(Component.literal(speaker.replace("[NPC] ", "")).withStyle(ChatFormatting.AQUA), text.copy());
    }
    private static Component slice(Component component, int start, int end) {
        var result = Component.empty(); int offset = 0;
        for (var glyph : StyledParagraph.glyphs(component)) {
            if (offset >= start && offset < end) result.append(Component.literal(glyph.text()).setStyle(glyph.style()));
            offset += glyph.text().length();
        }
        return result;
    }
    static Layout layout(List<Message> messages, int width, ToIntFunction<Component> measure) {
        int nameWidth = Math.min(Math.max(24, (width - 22) / 3), Math.max(24, messages.stream().mapToInt(m -> measure.applyAsInt(m.name())).max().orElse(24)));
        int textWidth = Math.max(12, width - nameWidth - 22);
        var rows = new ArrayList<Row>(); int id = 0;
        for (var m : messages) {
            var names = TooltipWrap.wrap(m.name(), nameWidth, measure);
            var body = TooltipWrap.wrap(m.text(), textWidth, measure);
            for (int i = 0; i < Math.max(names.size(), body.size()); i++)
                rows.add(new Row(i < names.size() ? names.get(i) : Component.empty(), i < body.size() ? body.get(i) : Component.empty(), m.name(), id));
            id++;
        }
        return new Layout(nameWidth, textWidth, List.copyOf(rows));
    }
    static List<Row> window(Layout layout, int start, int end, ToIntFunction<Component> measure) {
        var rows = new ArrayList<>(layout.rows().subList(start, end));
        // A scrolled/clipped continuation still identifies its speaker.
        if (!rows.isEmpty() && start > 0 && layout.rows().get(start - 1).message() == rows.getFirst().message()) {
            var first = rows.getFirst();
            var names = TooltipWrap.wrap(first.speaker(), layout.nameWidth(), measure);
            for (int i = 0; i < names.size() && i < rows.size() && rows.get(i).message() == first.message(); i++) {
                var row = rows.get(i);
                rows.set(i, new Row(names.get(i), row.text(), row.speaker(), row.message()));
            }
        }
        return rows;
    }
}
