package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class DisplayedTooltipsTest {
    @Test
    void generatedProbesCannotReplaceTheActuallyDisplayedItem() {
        var observed = new DisplayedTooltips<String>();
        var screen = new Object();
        var hovered = List.<Component>of(Component.literal("Sword"));
        var probe = List.<Component>of(Component.literal("Menu"));
        observed.put(screen, hovered, "hovered sword");
        observed.put(screen, probe, "background probe");
        assertEquals("hovered sword", observed.displayed(screen, hovered));
        assertNull(observed.displayed(screen, List.of(Component.literal("Sword"))));
        assertNull(observed.displayed(new Object(), hovered));
        observed.clear();
        assertNull(observed.displayed(screen, hovered));
    }
}
