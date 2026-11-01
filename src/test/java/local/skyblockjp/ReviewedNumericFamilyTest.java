package local.skyblockjp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReviewedNumericFamilyTest {
    @Test void nativeBackwaterSentenceUsesTypedPercentageAndRetainsItsNativeStyles() {
        var dictionary = BundledDictionary.loadBundled();
        for (String amount : List.of("20", "17.5")) {
            String source = "Increases damage dealt to Sea Creatures by " + amount + "%.";
            var decision = dictionary.inspect("item", source, "");
            assertEquals("template", decision.mode());
            var match = decision.match().orElseThrow();
            assertEquals(1, match.bindings().size());
            assertEquals(amount, match.bindings().getFirst().sourceText());
            var first = Component.empty()
                .append(Component.literal("Increases damage dealt to ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("Sea").withStyle(ChatFormatting.DARK_AQUA));
            var second = Component.empty()
                .append(Component.literal("Creatures ").withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal("by ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(amount + "%").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(".").withStyle(ChatFormatting.GRAY));
            var paragraph = new StyledParagraph(List.of(first, second));
            var encoded = paragraph.dictionaryEncoding(match);
            assertTrue(encoded.accepted(), encoded.reason() + ": " + encoded.details());
            var decoded = paragraph.decode(encoded.text());
            // The reviewed 5/25/75% family uses the approved singular entity alias.
            assertEquals("Sea Creatureに与えるダメージが" + amount + "%増加する。", decoded.getString());
            for (var glyph : StyledParagraph.glyphs(decoded)) {
                if (glyph.text().matches("[0-9.%]")) assertEquals(0x55FF55, glyph.style().getColor().getValue());
                if (glyph.text().matches("[A-Za-z]")) assertEquals(0x00AAAA, glyph.style().getColor().getValue());
            }
            assertTrue(dictionary.lookup("ui", source, "").isPresent());
            assertTrue(dictionary.lookup("npc", source, "Farmhand").isEmpty());
        }
    }

    @Test void reviewedPercentageFamilyDoesNotGeneralizeSubjectUnitsOrAdditionalConditions() {
        var dictionary = BundledDictionary.loadBundled();
        for (String source : List.of(
            "Increases damage dealt to Players by 20%.",
            "Increases damage dealt to Sea Creatures by 20s.",
            "Increases damage dealt to Sea Creatures by 20.",
            "Increases damage dealt to Sea Creatures by 20% for 5s.",
            "Increases damage dealt to Sea Creatures by 20% and 25%."))
            assertTrue(dictionary.lookup("item", source, "").isEmpty(), source);
    }
}
