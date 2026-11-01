package local.skyblockjp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/** A translation hint must never replace the information carried by a chat hover. */
public final class ChatHoverHint {
    private ChatHoverHint() {}
    public record Display(Component tooltip, Component separateHint) {}
    public static Display compose(Style hovered, String key) {
        Component hint = Component.literal(key + "で翻訳").withStyle(ChatFormatting.GRAY);
        HoverEvent event = hovered == null ? null : hovered.getHoverEvent();
        if (event instanceof HoverEvent.ShowText text) {
            // An empty parent prevents the old root's bold/color/click style from
            // leaking into the separator or the new instruction.
            return new Display(Component.empty().append(text.value().copy()).append(Component.literal("\n")).append(hint), null);
        }
        // Item/entity hover renderers own additional data and image components.
        // Keep their native tooltip intact and draw the instruction independently.
        return event == null ? new Display(hint, null) : new Display(null, hint);
    }
}
