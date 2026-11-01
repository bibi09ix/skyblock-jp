package local.skyblockjp.mixin;

import local.skyblockjp.SkyBlockJp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenClickMixin {
    // Consume our handle locally before any command can be sent to the server.
    @Inject(
            method = {"defaultHandleGameClickEvent", "defaultHandleClickEvent"},
            at = @At("HEAD"),
            cancellable = true)
    private static void skyblockjp$translate(
            ClickEvent event, Minecraft minecraft, Screen screen, CallbackInfo ci) {
        if (event instanceof ClickEvent.RunCommand command
                && command.command().startsWith("/sbjp translate ")) {
            var mod = SkyBlockJp.instance();
            if (mod != null)
                mod.chats().request(command.command().substring("/sbjp translate ".length()));
            ci.cancel();
        }
    }
}
