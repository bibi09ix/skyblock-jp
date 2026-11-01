package local.skyblockjp;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import java.util.List;
public interface ChatAccess {
    List<GuiMessage> skyblockjp$messages();
    List<GuiMessage.Line> skyblockjp$lines();
    void skyblockjp$visit(net.minecraft.client.gui.components.ChatComponent.ChatGraphicsAccess visitor, int height,
        int tick, net.minecraft.client.gui.components.ChatComponent.DisplayMode mode);
}
