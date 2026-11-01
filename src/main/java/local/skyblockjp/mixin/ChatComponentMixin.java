package local.skyblockjp.mixin;
import local.skyblockjp.SkyBlockJp;
import local.skyblockjp.ChatAccess;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import java.util.List;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin implements ChatAccess {
    @Accessor("allMessages") public abstract List<GuiMessage> skyblockjp$messages();
    @Accessor("trimmedMessages") public abstract List<GuiMessage.Line> skyblockjp$lines();
    @Invoker("extractRenderState") public abstract void skyblockjp$visit(ChatComponent.ChatGraphicsAccess visitor, int height, int tick, ChatComponent.DisplayMode mode);
    @ModifyVariable(method="addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V", at=@At("HEAD"), argsOnly=true, ordinal=0)
    private Component skyblockjp$button(Component message) {
        var mod = SkyBlockJp.instance();
        return mod == null ? message : mod.chats().decorate(message);
    }
}
