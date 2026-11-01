package local.skyblockjp.mixin;
import local.skyblockjp.SkyBlockJp;
import local.skyblockjp.TooltipObservation;
import net.minecraft.client.gui.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

@Mixin(GuiGraphicsExtractor.class)
public class TooltipDisplayMixin {
    @Shadow private Runnable deferredTooltip;
    @Unique private final List<Runnable> skyblockjp$previous = new ArrayList<>();
    @Inject(method="setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",at=@At("HEAD"))
    private void skyblockjp$itemTooltip(Font font,List<Component> lines,Optional<TooltipComponent> image,int x,int y,Identifier texture,CallbackInfo ci) {
        skyblockjp$previous.add(deferredTooltip);
    }
    @Inject(method="setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",at=@At("RETURN"))
    private void skyblockjp$itemChosen(Font font,List<Component> lines,Optional<TooltipComponent> image,int x,int y,Identifier texture,CallbackInfo ci) {
        skyblockjp$trackSelected(lines,x,y);
    }
    @Inject(method="setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/resources/Identifier;)V",at=@At("HEAD"))
    private void skyblockjp$componentTooltip(Font font,List<Component> lines,int x,int y,Identifier texture,CallbackInfo ci) {
        skyblockjp$previous.add(deferredTooltip);
    }
    @Inject(method="setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/resources/Identifier;)V",at=@At("RETURN"))
    private void skyblockjp$componentChosen(Font font,List<Component> lines,int x,int y,Identifier texture,CallbackInfo ci) {
        skyblockjp$trackSelected(lines,x,y);
    }
    @Unique private void skyblockjp$trackSelected(List<Component> lines,int x,int y) {
        Runnable before = skyblockjp$previous.removeLast();
        List<Component> snapshot = TooltipObservation.snapshot(lines);
        // Vanilla may reject later candidates or replace this one. Observe only the
        // deferred callback which is actually executed, not every tooltip setter.
        deferredTooltip = TooltipObservation.selected(before, deferredTooltip, () -> {
            if (SkyBlockJp.instance() != null) SkyBlockJp.instance().tooltipDisplayed(snapshot,x,y,lines);
        });
    }
}
