package local.skyblockjp;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TooltipContextTest {
    @Test void menuControlsUseUiScopeAcrossMenuFamiliesAndPagination() {
        for (String title : List.of("SkyBlock Menu", "(1/2) Pets", "(1/3) Loadouts", "Your Skills", "Mining Collection",
                "Recipe Book", "Storage", "Ender Chest 7", "Backpack 12", "Heart of the Mountain", "The Forge",
                "Auction House", "Bazaar", "Community Shop", "Garden", "Bestiary", "SkyBlock Levels", "Fast Travel")) {
            assertEquals(TooltipContext.Kind.UI, TooltipContext.classify(title, false, "", List.of("Pets", "View your pets.")), title);
        }
    }

    @Test void actualItemsStayItemsInEveryMenu() {
        for (String menu : List.of("Pets", "Storage", "Loadouts", "Wardrobe", "Bazaar")) {
            assertEquals(TooltipContext.Kind.ITEM, TooltipContext.classify(menu, false, "PET", List.of("Silverfish")), menu);
            assertEquals(TooltipContext.Kind.ITEM, TooltipContext.classify(menu, false, null,
                List.of("[Lvl 100] Silverfish", "Mining Pet", "Grants Mining Wisdom.")), menu);
            assertEquals(TooltipContext.Kind.ITEM, TooltipContext.classify(menu, false, null,
                List.of("Pure Moogma Leggings", "Health: +279", "Defense: +199", "RARE LEGGINGS")), menu);
            assertEquals(TooltipContext.Kind.ITEM, TooltipContext.classify(menu, false, null,
                List.of("Glacite Chunk", "Combine these to unlock a power.", "LEGENDARY POWER STONE")), menu);
            assertEquals(TooltipContext.Kind.ITEM, TooltipContext.classify(menu, false, null,
                List.of("Dirty Fingernails", "Drops from mobs.", "skyblock:DIRTY_FINGERNAILS")), menu);
        }
    }

    @Test void playerInventoryWinsAndUnknownContainersNeedPositiveControlEvidence() {
        assertEquals(TooltipContext.Kind.ITEM, TooltipContext.classify("SkyBlock Menu", true, null, List.of("Sword", "Description.")));
        for (String title : List.of("Chest", "Inventory", "A player's custom chest", "", "Private notes")) {
            // The legacy boolean API models a matching container slot when false.
            // A menu title alone is not required when the tooltip itself proves it is a control.
            assertEquals(TooltipContext.Kind.UI, TooltipContext.classify(title, false, null, List.of("Next Page", "Click to view!")), title);
            assertEquals(TooltipContext.Kind.UNKNOWN, TooltipContext.classify(title, false, null, List.of("Unknown object", "A curious description.")), title);
        }
        assertEquals(TooltipContext.Kind.UNKNOWN, TooltipContext.classify(null, false, null, List.of()));
    }
}
