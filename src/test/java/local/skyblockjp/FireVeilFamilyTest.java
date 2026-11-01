package local.skyblockjp;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FireVeilFamilyTest {
    private static final String SOURCE = "Creates a veil of fire around you for 5s, dealing %s damage per second to mobs within.";
    private static final String TARGET = "周囲に5秒間の炎の幕を作り、その中にいるモブに毎秒%sのダメージを与える。";

    @Test void unseenUnsignedDamageUsesOnlyTheReviewedFixedFiveSecondFamily() {
        var dictionary = BundledDictionary.loadBundled();
        for (String value : List.of("12,345.678", "0", "999999999999.999999", "25.25")) {
            String source = SOURCE.formatted(value);
            for (String channel : List.of("item", "ui")) {
                var decision = dictionary.inspect(channel, source, "");
                assertEquals("template", decision.mode(), source);
                var match = decision.match().orElseThrow();
                assertEquals(TARGET.formatted(value), match.text());
                assertEquals(1, match.bindings().size());
                assertEquals(value, match.bindings().getFirst().sourceText());
            }
            assertTrue(dictionary.lookup("npc", source, "Dean").isEmpty());
            assertTrue(dictionary.lookup("system", source, "").isEmpty());
        }
    }

    @Test void sourceColorsAndFixedDurationSurviveTypedDamageSubstitution() {
        var dictionary = BundledDictionary.loadBundled();
        var paragraph = new StyledParagraph(List.of(Component.empty()
            .append(Component.literal("Creates a veil of fire around you for ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("5s").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(", dealing ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("12,345.678").withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" damage per second to mobs within.").withStyle(ChatFormatting.GRAY))));
        var match = dictionary.lookup("item", paragraph.sourceText(), "").orElseThrow();
        var encoded = paragraph.dictionaryEncoding(match, false);
        assertTrue(encoded.accepted(), encoded.reason() + " " + encoded.details());
        var result = paragraph.decode(encoded.text());
        assertEquals(TARGET.formatted("12,345.678"), result.getString());
        int damageAt = result.getString().indexOf("12,345.678"), offset = 0;
        for (var glyph : StyledParagraph.glyphs(result)) {
            if (offset == result.getString().indexOf('5')) assertEquals(0xFFAA00, glyph.style().getColor().getValue());
            if (offset >= damageAt && offset < damageAt + "12,345.678".length())
                assertEquals(0x55FFFF, glyph.style().getColor().getValue());
            offset += glyph.text().length();
        }
    }

    @Test void signedCompactMalformedAndChangedMechanicsDoNotUseThisFamily() {
        var dictionary = BundledDictionary.loadBundled();
        for (String value : List.of("-12", "+12", "12k", ".5", "1,23", "12,345,67", "1234567890123", "1.1234567")) {
            var match = dictionary.lookup("item", SOURCE.formatted(value), "");
            assertTrue(match.isEmpty(), value);
        }
        for (String source : List.of(SOURCE.formatted("12,345.678").replace("5s", "6s"),
                SOURCE.formatted("12,345.678").replace("per second", "per minute"),
                SOURCE.formatted("12,345.678").replace("mobs", "players"),
                SOURCE.formatted("12,345.678") + " Costs 10 Mana."))
            assertTrue(dictionary.lookup("item", source, "").isEmpty(), source);
    }
}
