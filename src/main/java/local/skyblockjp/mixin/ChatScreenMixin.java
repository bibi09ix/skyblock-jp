package local.skyblockjp.mixin;
import local.skyblockjp.SkyBlockJp;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
abstract class ChatScreenMixin {
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void skyblockjp$scroll(double x, double y, double horizontal, double vertical, CallbackInfoReturnable<Boolean> ci) {
        var mod = SkyBlockJp.instance();
        if (mod != null && mod.scrollHud(x, y, vertical)) ci.setReturnValue(true);
    }
}
