package local.skyblockjp.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import local.skyblockjp.ChatHover;
import local.skyblockjp.ChatHoverHint;
import local.skyblockjp.SkyBlockJp;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
abstract class ChatScreenMixin extends Screen {
    @Shadow private ChatComponent.DisplayMode displayMode;
    @Unique private boolean skyblockjp$held;
    @Unique private String skyblockjp$character;
    @Unique private long skyblockjp$characterDeadline;
    @Unique private int skyblockjp$mouseX = -1, skyblockjp$mouseY = -1;

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void skyblockjp$hint(
            GuiGraphicsExtractor g, int x, int y, float delta, CallbackInfo ci) {
        skyblockjp$mouseX = x;
        skyblockjp$mouseY = y;
        var hit = ChatHover.findHit(minecraft, displayMode, x, y);
        if (hit == null) return;
        String key =
                InputConstants.Type.KEYSYM
                        .getOrCreate(SkyBlockJp.instance().configuration().translateKey)
                        .getDisplayName()
                        .getString();
        var hint = ChatHoverHint.compose(hit.style(), key);
        if (hint.tooltip() != null) {
            // Match vanilla SHOW_TEXT wrapping, preserving explicit newlines and styles.
            g.setTooltipForNextFrame(
                    font, font.split(hint.tooltip(), Math.max(width / 2, 200)), x, y);
        } else {
            // A second deferred tooltip would replace SHOW_ITEM/SHOW_ENTITY. Keep
            // the native tooltip and place the small hint above the chat input.
            int hintWidth = font.width(hint.separateHint());
            int hintX = Math.max(4, width - hintWidth - 8), hintY = Math.max(4, height - 28);
            g.fill(
                    hintX - 3,
                    hintY - 2,
                    hintX + hintWidth + 3,
                    hintY + font.lineHeight + 2,
                    0xC0000000);
            g.text(font, hint.separateHint(), hintX, hintY, -1);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void skyblockjp$translate(KeyEvent event, CallbackInfoReturnable<Boolean> ci) {
        var mod = SkyBlockJp.instance();
        if (mod == null || event.key() != mod.configuration().translateKey) return;
        // Screen rendering can use a different cursor from MouseHandler (keyboard navigation/mods).
        // Resolve again at the displayed cursor, so scrolling/new chat cannot leave a stale
        // message.
        Component source =
                ChatHover.find(minecraft, displayMode, skyblockjp$mouseX, skyblockjp$mouseY);
        if (source == null && !skyblockjp$held) return;
        skyblockjp$character = GLFW.glfwGetKeyName(event.key(), 0);
        skyblockjp$characterDeadline = System.nanoTime() + 250_000_000L;
        if (!skyblockjp$held && source != null) mod.chats().request(source);
        skyblockjp$held = true;
        ci.setReturnValue(true);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        var mod = SkyBlockJp.instance();
        if (mod != null && event.key() == mod.configuration().translateKey) skyblockjp$held = false;
        return super.keyReleased(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (skyblockjp$character != null
                && System.nanoTime() <= skyblockjp$characterDeadline
                && event.codepointAsString().equalsIgnoreCase(skyblockjp$character)) {
            skyblockjp$character = null;
            return true;
        }
        skyblockjp$character = null;
        return super.charTyped(event);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void skyblockjp$scroll(
            double x,
            double y,
            double horizontal,
            double vertical,
            CallbackInfoReturnable<Boolean> ci) {
        var mod = SkyBlockJp.instance();
        if (mod != null && mod.scrollHud(x, y, vertical)) ci.setReturnValue(true);
    }
}
