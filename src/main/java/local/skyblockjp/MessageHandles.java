package local.skyblockjp;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import java.util.*;

final class MessageHandles {
    private final int capacity;
    private final LinkedHashMap<String, Component> messages = new LinkedHashMap<>();
    MessageHandles(int capacity) { this.capacity = capacity; }
    String put(Component original) {
        String id = UUID.randomUUID().toString();
        messages.put(id, original.copy());
        while (messages.size() > capacity) messages.remove(messages.keySet().iterator().next());
        return id;
    }
    Component get(String id) { return messages.get(id); }
    void clear() { messages.clear(); }
    static Component original(Component message) {
        var parts = message.getSiblings();
        if (parts.size() == 3) {
            if (isButton(parts.getFirst())) return parts.getLast().copy();
            if (isButton(parts.getLast())) return parts.getFirst().copy();
        }
        return message.copy();
    }
    private static boolean isButton(Component c) {
        return c.getString().equals("[訳]") && c.getStyle().getClickEvent() instanceof ClickEvent.RunCommand command && command.command().startsWith("/sbjp translate ");
    }
    static Component decorate(Component original, String id, boolean first) {
        Component button = Component.literal("[訳]").setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent.RunCommand("/sbjp translate " + id))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("この行を日本語に翻訳（チャットを開いてクリック）"))));
        // Keep existing links, click actions, siblings and hover text on the source.
        return first ? Component.empty().append(button).append(" ").append(original.copy())
            : Component.empty().append(original.copy()).append(" ").append(button);
    }
}
