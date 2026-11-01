package local.skyblockjp.mixin;

import local.skyblockjp.ContainerScreenAccess;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenMixin extends ContainerScreenAccess {
    @Override @Accessor("hoveredSlot") Slot skyblockjp$hoveredSlot();
    @Override @Accessor("leftPos") int skyblockjp$leftPos();
    @Override @Accessor("topPos") int skyblockjp$topPos();
}
