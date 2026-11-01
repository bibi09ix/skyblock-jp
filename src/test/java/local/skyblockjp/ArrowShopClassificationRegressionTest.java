package local.skyblockjp;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArrowShopClassificationRegressionTest {
    private static LoreParser.Analysis item(List<String> lines) {
        return LoreParser.analyze(lines, new TooltipContext.Decision(TooltipContext.Kind.ITEM, "SHOP_ITEM_ID", Map.of()));
    }
    private static List<String> texts(LoreParser.Analysis result, String channel) {
        return result.paragraphs().stream().filter(p -> p.channel().equals(channel)).map(p -> p.block().text()).toList();
    }

    @Test void arrowShopCostsAndNamedPassivesDoNotLeakIntoEffects() {
        var redstone = item(List.of("Redstone-tipped Arrow x64", "Damage: +15", "", "Stats added when shot!", "UNCOMMON ARROW", "",
            "Added directly to your quiver!", "", "Cost", "Enchanted Redstone Dust", "", "Fill Quiver Cost", "Enchanted Redstone Dust x3", "",
            "Click to buy into quiver!", "Right-click to fill quiver!", "minecraft:arrow", "skyblock:REDSTONE_TIPPED_ARROW",
            "15個のコンポーネント", "Hypixel_skyblock"));
        assertEquals(List.of("Stats added when shot!", "Added directly to your quiver!"), texts(redstone, "item"));
        assertEquals(List.of("Click to buy into quiver!", "Right-click to fill quiver!"), texts(redstone, "ui"));
        assertEquals("COST_SECTION_START", redstone.lines().get(8).reason());
        assertEquals("MODIFIED_COST_SECTION_START", redstone.lines().get(11).reason());
        assertEquals("COST_SECTION_CONTENT", redstone.lines().get(12).reason());
        assertEquals("Enchanted Redstone Dust x3", redstone.lines().get(12).source());

        var nansorb = item(List.of("Nansorb Arrow x64", "Damage: +5", "", "Replaces the damage reduction from",
            "the Piercing enchant to Skewer.", "", "Passive: Skewer", "Hits through Piercing deal +7.5%", "damage than the previous one, up to",
            "5 victims.", "", "Stats added when shot!", "RARE ARROW", "", "Added directly to your quiver!", "", "Cost",
            "Enchanted Cactus Green x8", "", "Fill Quiver Cost", "Enchanted Cactus Green x24", "", "Click to buy into quiver!",
            "Right-click to fill quiver!", "minecraft:arrow", "skyblock:NANSORB_ARROW", "16個のコンポーネント", "Hypixel_skyblock"));
        assertEquals(List.of("Replaces the damage reduction from the Piercing enchant to Skewer.",
            "Hits through Piercing deal +7.5% damage than the previous one, up to 5 victims.",
            "Stats added when shot!", "Added directly to your quiver!"), texts(nansorb, "item"));
        assertEquals("EXPLICIT_ABILITY_HEADING", nansorb.lines().get(6).reason());
        assertEquals("MODIFIED_COST_SECTION_START", nansorb.lines().get(19).reason());
        assertEquals("COST_SECTION_CONTENT", nansorb.lines().get(20).reason());

        // A title-like Cost phrase is not enough to swallow a following effect sentence.
        var effect = item(List.of("Item", "Reduce Spell Cost", "Gain Speed while you are in water."));
        assertTrue(texts(effect, "item").stream().anyMatch(s -> s.contains("Gain Speed while you are in water.")));
        assertNotEquals("COST_SECTION_CONTENT", effect.lines().get(2).reason());
        assertFalse(LoreParser.protectedLine("Passive: Grants +5 Defense."));
        assertFalse(LoreParser.protectedLine("Active Ability: Deals 100 damage."));
        assertTrue(LoreParser.protectedLine("Active: Instant Transmission"));
    }
}
