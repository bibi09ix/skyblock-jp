package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class EffectLevelTokensTest {
    private static StyledParagraph paragraph(String source) {
        return new StyledParagraph(
                List.of(Component.literal(source).withStyle(ChatFormatting.GRAY)));
    }

    @Test
    void effectTierIsOneImmutableTermAndItsOwnTypedRole() {
        var p = paragraph("Grants permanent Haste IV.");
        assertTrue(p.encoded().contains("Haste IV</t"), p.encoded());
        assertFalse(p.dictionaryEncoding("常にHaste Vを付与する。").accepted());
        assertEquals(
                "TYPED_EFFECT_LEVEL_MISMATCH", p.dictionaryEncoding("常にHaste Vを付与する。").reason());
        assertFalse(QuantityValidation.preserved("Grants Haste IV.", "Haste Vを付与する。"));
        assertFalse(QuantityValidation.preserved("<s0>Haste IV</s0>", "<s0>Haste V</s0>"));
        assertFalse(
                EffectLevelTokens.preserved(
                        "Haste IV and Jump Boost II", "Haste IIとJump Boost IV"));
        assertFalse(EffectLevelTokens.preserved("Haste IV and Haste IV", "Haste IV"));
        assertFalse(EffectLevelTokens.preserved("Your Haste IV effect.", "Haste Vの効果。"));
        assertTrue(EffectLevelTokens.preserved("Requires Haste IV.", "Haste IVが必要。"));
        assertTrue(p.dictionaryEncoding("常にHaste IVを付与する。").accepted());
    }

    @Test
    void everyExistingHasteCandidateRejectsAnIntentionalTierMutation() throws Exception {
        int cases = 0;
        try (var stream = getClass().getResourceAsStream(BundledDictionary.RESOURCE)) {
            assertNotNull(stream);
            var root =
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            for (var raw : root.getAsJsonArray("entries")) {
                var row = raw.getAsJsonObject();
                String source = row.get("source").getAsString(),
                        target = row.get("target").getAsString();
                if (!source.matches("(?s).*\\bHaste (?:I|II|III)\\b.*")) continue;
                String mutated =
                        target.replaceAll("Haste III\\b", "Haste IV")
                                .replaceAll("Haste II\\b", "Haste III")
                                .replaceAll("Haste I\\b", "Haste II");
                if (mutated.equals(target)) continue;
                cases++;
                assertEquals("", TypedDictionaryTokens.validate(source, target), source);
                assertEquals(
                        "TYPED_EFFECT_LEVEL_MISMATCH",
                        paragraph(source).dictionaryEncoding(mutated).reason(),
                        source);
                assertFalse(QuantityValidation.preserved(source, mutated), source);
            }
        }
        assertEquals(10, cases);
    }

    @Test
    void romanNamesCommandsAndLongerNamedUpgradesAreNotEffectLevels() {
        for (String text :
                List.of(
                        "King Emmor IV",
                        "Trial of Fire IV",
                        "Campfire Initiate Badge III",
                        "Growth Speed IV",
                        "[NPC] Haste IV: Hello!",
                        "/msg Haste IV",
                        "/custom Haste IV",
                        "Haste_IV: hello",
                        "Haste: IV",
                        "Haste IIII")) assertTrue(EffectLevelTokens.parse(text).isEmpty(), text);
        assertEquals("", TypedDictionaryTokens.validate("Growth Speed IV", "Growth Speedの段階 IV"));
        assertEquals(
                "COMMAND_ARGUMENT_MISMATCH",
                TypedDictionaryTokens.validate("/custom Haste IV", "/custom Haste V"));
        assertTrue(EffectLevelTokens.preserved("King Emmor IV", "King Emmor V"));
        assertEquals(
                "",
                TypedDictionaryTokens.validate("Grants permanent Haste III.", "常にHaste IIIを付与する。"));
    }

    @Test
    void everyBundledCandidateKeepsItsNamedEffectTier() throws Exception {
        int scanned = 0;
        try (var stream = getClass().getResourceAsStream(BundledDictionary.RESOURCE)) {
            assertNotNull(stream);
            var root =
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            for (var raw : root.getAsJsonArray("entries")) {
                var row = raw.getAsJsonObject();
                scanned++;
                String source = row.get("source").getAsString(),
                        target = row.get("target").getAsString();
                assertTrue(
                        EffectLevelTokens.preserved(source, target),
                        () -> source + " => " + target);
            }
        }
        assertTrue(scanned >= 75_542, Integer.toString(scanned));
    }
}
