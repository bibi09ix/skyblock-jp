package local.skyblockjp.mixin;

import local.skyblockjp.SkyBlockJp;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void skyblockjp$key(long window, int action, KeyEvent event, CallbackInfo ci) {
        var mod = SkyBlockJp.instance();
        if (mod != null && mod.handleKey(window, action, event)) ci.cancel();
    }
}
