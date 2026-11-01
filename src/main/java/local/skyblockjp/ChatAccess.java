package local.skyblockjp;

import java.util.List;
import net.minecraft.client.multiplayer.chat.GuiMessage;

public interface ChatAccess {
    List<GuiMessage> skyblockjp$messages();

    List<GuiMessage.Line> skyblockjp$lines();

    void skyblockjp$visit(
            net.minecraft.client.gui.components.ChatComponent.ChatGraphicsAccess visitor,
            int height,
            int tick,
            net.minecraft.client.gui.components.ChatComponent.DisplayMode mode);
}
