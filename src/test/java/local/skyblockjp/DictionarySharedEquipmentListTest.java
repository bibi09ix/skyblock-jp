package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class DictionarySharedEquipmentListTest {
    private static StyledParagraph boots() {
        return new StyledParagraph(
                List.of(
                        Component.literal("Decreases the mana cost of ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(
                                        Component.literal(
                                                        "Spider, Tarantula, Primordial and Spirit Boots")
                                                .withStyle(ChatFormatting.GOLD))
                                .append(Component.literal(" by ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal("36%").withStyle(ChatFormatting.GREEN))
                                .append(Component.literal(".").withStyle(ChatFormatting.GRAY))));
    }

    @Test
    void completeExpandedListKeepsTheOriginalTermAndItsColor() {
        var p = boots();
        var result =
                p.dictionaryEncoding(
                        "Spider Boots、Tarantula Boots、Primordial Boots、Spirit BootsのMana消費が36%減少する。");
        assertTrue(result.accepted(), result.reason());
        var display = p.decode(result.text());
        assertTrue(display.getString().contains("Spider, Tarantula, Primordial and Spirit Boots"));
        assertEquals(
                0xFFAA00,
                StyledParagraph.glyphs(display).stream()
                        .filter(g -> g.text().equals("S"))
                        .findFirst()
                        .orElseThrow()
                        .style()
                        .getColor()
                        .getValue());
        assertTrue(StyledParagraph.validNumbers(p.encoded(), result.text()));
    }

    @Test
    void threeMembersCanShareAnExplicitEquipmentCategory() {
        var p =
                new StyledParagraph(
                        List.of(Component.literal("Grants power to Red, Blue and Green Swords.")));
        // Plural/unknown categories are deliberately not inferred.
        assertNull(p.encodeDictionaryTranslation("Red Swords、Blue Swords、Green Swordsに力を付与する。"));
        var known =
                new StyledParagraph(
                        List.of(Component.literal("Grants power to Red, Blue and Green Sword.")));
        assertNotNull(
                known.encodeDictionaryTranslation("Red Sword、Blue Sword、Green Swordに力を付与する。"));
    }

    @Test
    void missingReorderedDuplicateOrExtraMembersAreRejected() {
        var p = boots();
        for (String list :
                List.of(
                        "Spider Boots、Tarantula Boots、Spirit Boots",
                        "Tarantula Boots、Spider Boots、Primordial Boots、Spirit Boots",
                        "Spider Boots、Tarantula Boots、Primordial Boots、Primordial Boots、Spirit Boots",
                        "Spider Boots、Tarantula Boots、Primordial Boots、Spirit Boots、Zombie Boots",
                        "Zombie Boots、Spider Boots、Tarantula Boots、Primordial Boots、Spirit Boots",
                        "Spider Boots、Tarantula Helmet、Primordial Boots、Spirit Boots",
                        "Spider Boots Boots、Tarantula Boots、Primordial Boots、Spirit Boots"))
            assertNull(p.encodeDictionaryTranslation(list + "のMana消費が36%減少する。"), list);
    }

    @Test
    void numbersAndMixedSourceCategoriesStillFailClosed() {
        assertNull(
                boots().encodeDictionaryTranslation(
                                "Spider Boots、Tarantula Boots、Primordial Boots、Spirit BootsのMana消費が37%減少する。"));
        var p =
                new StyledParagraph(
                        List.of(
                                Component.literal(
                                        "Grants power to Red Helmet, Blue and Green Boots.")));
        assertNull(
                p.encodeDictionaryTranslation("Red Helmet Boots、Blue Boots、Green Bootsに力を付与する。"));
    }
}
