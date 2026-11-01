package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class DebugTooltipMetadataTest {
    private static final class Dictionary {
        static final BundledDictionary VALUE = BundledDictionary.loadBundled();
    }

    @Test
    void onlyStandaloneBrandLabelsAreMetadata() {
        for (String label :
                List.of(
                        "Minecraft",
                        "Hypixel_skyblock",
                        "minecraft",
                        "HYPIXEL_SKYBLOCK",
                        " §8Minecraft§r ")) {
            assertTrue(LoreParser.protectedLine(label), label);
        }
        for (String prose :
                List.of(
                        "Explore Minecraft with your friends.",
                        "The Hypixel_skyblock server offers many adventures.",
                        "Minecraft grants no extra bonuses.",
                        "Visit Hypixel_skyblock to begin your adventure.")) {
            assertFalse(LoreParser.protectedLine(prose), prose);
            var blocks = LoreParser.parse(List.of("Example item", prose));
            assertEquals(1, blocks.size(), prose);
            assertEquals(prose, blocks.getFirst().text());
        }
    }

    @Test
    void selectedClassLabelKeepsOnlyKnownDisplayValues() {
        for (String value : List.of("Berserk", "Healer", "Mage", "Tank", "Archer", "None")) {
            assertTrue(LoreParser.protectedLine("Selected class: " + value));
        }
        assertTrue(LoreParser.protectedLine("§7Selected class: §6Archer"));
        for (String prose :
                List.of(
                        "Selected class: Archer grants an additional bonus.",
                        "Your selected class determines this bonus.",
                        "Selected class: Custom Class")) {
            assertFalse(LoreParser.protectedLine(prose), prose);
        }
    }

    @Test
    void recordedGardenScytheCompletesFromOneDictionaryParagraphAndRetainsFooter() {
        // Actual tooltip recorded before metadata filtering, including the debug footer.
        List<String> lines =
                List.of(
                        "Garden Scythe",
                        "Cleans a 5x5 area of grass, leaves",
                        "and flowers.",
                        "",
                        "* Co-op Soulbound *",
                        "UNCOMMON",
                        "minecraft:iron_hoe",
                        "skyblock:GARDEN_SCYTHE",
                        "23個のコンポーネント",
                        "Hypixel_skyblock");
        var result = dictionaryOnly(lines, "Cleans a 5x5 area of grass, leaves and flowers.");
        assertEquals("5x5の範囲の草、葉、花を片付ける。", result.lines().get(1).getString());
        List<String> displayed = result.lines().stream().map(Component::getString).toList();
        assertEquals(lines.subList(4, lines.size()), displayed.subList(3, displayed.size()));
    }

    @Test
    void recordedAdaptiveBeltRequiresNoTranslationOfClassOrBrandRows() {
        // Actual 0.7.0 client tooltip from 2026-10-29. Stat rows must remain English.
        List<String> lines =
                List.of(
                        " Waxed Adaptive Belt ✪✪✪✪✪",
                        "Health: +119.75 (+10)",
                        "Defense: +47.9",
                        "Strength: +47.9",
                        "Crit Chance: +10.78% (+5%)",
                        "Crit Damage: +23.95%",
                        "",
                        "Grants additional bonuses based on your",
                        "selected dungeon class!",
                        "",
                        "Berserk: +10 Strength",
                        "Healer: +10 Health, +5 Mending",
                        "Mage: +25 Intelligence",
                        "Tank: +5 Health, +10 Defense",
                        "Archer: +2 Crit Chance, +5 Crit Damage",
                        "",
                        "Selected class: Archer",
                        "",
                        "Right-click to equip!",
                        "",
                        "EPIC DUNGEON BELT",
                        "minecraft:paper",
                        "skyblock:STARRED_ADAPTIVE_BELT",
                        "15個のコンポーネント",
                        "Hypixel_skyblock");
        var result =
                dictionaryOnly(
                        lines, "Grants additional bonuses based on your selected dungeon class!");
        List<String> displayed = result.lines().stream().map(Component::getString).toList();
        assertTrue(displayed.contains("選択中のDungeon Classに応じた追加ボーナスを与える。"));
        assertTrue(displayed.containsAll(lines.subList(10, lines.size())));
        assertEquals(lines.subList(0, 7), displayed.subList(0, 7));
        assertEquals("Hypixel_skyblock", displayed.getLast());
    }

    @Test
    void recordedShopTooltipDoesNotSubmitMinecraftAsAnExtraParagraph() {
        List<String> lines =
                List.of(
                        "Sell Item",
                        "Click items in your inventory to sell",
                        "them to this Shop!",
                        "minecraft:hopper",
                        "14個のコンポーネント",
                        "Minecraft");
        var blocks = LoreParser.parse(lines);
        assertEquals(1, blocks.size());
        assertEquals(
                "Click items in your inventory to sell them to this Shop!",
                blocks.getFirst().text());
        assertEquals(1, blocks.getFirst().start());
        assertEquals(3, blocks.getFirst().end());
    }

    private static ItemTranslation.Result dictionaryOnly(
            List<String> lines, String expectedSource) {
        var original = lines.stream().map(Component::literal).map(Component.class::cast).toList();
        var blocks =
                LoreParser.analyze(
                                lines,
                                new TooltipContext.Decision(
                                        TooltipContext.Kind.ITEM,
                                        "ITEM_FIXTURE",
                                        java.util.Map.of()))
                        .paragraphs()
                        .stream()
                        .filter(p -> p.channel().equals("item"))
                        .map(LoreParser.Paragraph::block)
                        .toList();
        assertEquals(1, blocks.size(), blocks.toString());
        assertEquals(expectedSource, blocks.getFirst().text());
        var paragraphs =
                blocks.stream()
                        .map(b -> new StyledParagraph(original.subList(b.start(), b.end())))
                        .toList();
        AtomicInteger dictionaryQueries = new AtomicInteger();
        var result =
                ItemTranslation.prepareParagraphs(
                        original,
                        blocks,
                        paragraphs,
                        p -> {
                            dictionaryQueries.incrementAndGet();
                            var match =
                                    Dictionary.VALUE
                                            .lookup("item", p.sourceText(), "")
                                            .orElseThrow();
                            String translated = p.encodeDictionaryTranslation(match.text());
                            assertNotNull(translated);
                            return translated;
                        },
                        List::of);
        assertTrue(result.ready());
        assertEquals(1, result.complete());
        assertEquals(1, result.total());
        assertEquals(1, dictionaryQueries.get());
        return result;
    }
}
