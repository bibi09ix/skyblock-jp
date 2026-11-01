package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Native 0.7.7 session: separate ability names from their wrapped body before dictionary lookup. */
class NativePetHeadingRegressionTest {
    @TempDir Path temp;

    private static final class Dictionary {
        static final BundledDictionary VALUE = BundledDictionary.loadBundled();
    }

    private static TooltipContext.Decision item() {
        return new TooltipContext.Decision(TooltipContext.Kind.ITEM, "TEST_PET", Map.of());
    }

    private static Component text(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(color).withStyle(style -> style.withItalic(false));
    }

    private static boolean label(String title) {
        return Dictionary.VALUE
                .lookup("item", title, "")
                .filter(BundledDictionary.Match::keepOriginal)
                .filter(
                        match ->
                                match.sourceIds().stream()
                                        .anyMatch(id -> id.startsWith("item/label/")))
                .isPresent();
    }

    private static List<String> sources(LoreParser.Analysis result) {
        return result.paragraphs().stream().map(p -> p.block().text()).toList();
    }

    private static void heading(LoreParser.Analysis result, int index) {
        assertEquals("ABILITY_NAME", result.lines().get(index).role());
        assertEquals("KEEP", result.lines().get(index).action());
        assertEquals("STRUCTURED_ABILITY_HEADING", result.lines().get(index).reason());
    }

    @Test
    void nativeMegalodonDoublesBodyNoLongerContainsItsHeading() {
        var source =
                List.of(
                        "[Lvl 85] Megalodon",
                        "Fishing Pet",
                        "",
                        "Strength: +42.5",
                        "",
                        "Enhanced Scales",
                        "Doubles the pet's base stats during",
                        "the Fishing Festival.");
        var result = LoreParser.analyze(source, item(), NativePetHeadingRegressionTest::label);
        heading(result, 5);
        assertEquals(
                List.of("Doubles the pet's base stats during the Fishing Festival."),
                sources(result));
        assertEquals("STAT", result.lines().get(3).role());
    }

    @Test
    void verifiedActionWordAbilityNamesAreNotTreatedAsProse() {
        var source =
                List.of(
                        "[Lvl 100] Enderman",
                        "Combat Pet",
                        "",
                        "Teleport Savvy",
                        "Buffs Transmission abilities, granting",
                        "50 weapon damage for 5s on use");
        assertTrue(label("Teleport Savvy"));
        var result = LoreParser.analyze(source, item(), NativePetHeadingRegressionTest::label);
        heading(result, 3);
        assertEquals(
                List.of("Buffs Transmission abilities, granting 50 weapon damage for 5s on use"),
                sources(result));
        // Without verified label evidence the ambiguous action phrase remains intact.
        assertEquals(
                List.of(
                        "Teleport Savvy Buffs Transmission abilities, granting 50 weapon damage for 5s on use"),
                sources(LoreParser.analyze(source, item(), s -> false)));
    }

    @Test
    void effectLeadRecognitionIsSharedByUnrelatedNamesAndMultipliers() {
        for (String lead :
                List.of(
                        "Doubles",
                        "Triples",
                        "Quadruples",
                        "Halves",
                        "Multiplies",
                        "Divides",
                        "Buffs")) {
            var source =
                    List.of(
                            "Example Item",
                            "",
                            "Resonant Shell",
                            lead + " your base stats while equipped.");
            var result = LoreParser.analyze(source, item());
            heading(result, 2);
            assertEquals(List.of(lead + " your base stats while equipped."), sources(result));
        }
    }

    @Test
    void titleCaseActionsAndWrappedOrdinaryProseAreNotDiscarded() {
        for (var body :
                List.of(
                        List.of("Gain Health", "while standing still."),
                        List.of("Doubles Health", "while standing still."),
                        List.of("Teleport", "to the target and gain 5 Strength."),
                        List.of("Your Strength", "Doubles while holding this item."))) {
            var source = new ArrayList<>(List.of("Example Pet", "Combat Pet", ""));
            source.addAll(body);
            var result = LoreParser.analyze(source, item(), NativePetHeadingRegressionTest::label);
            assertEquals(List.of(String.join(" ", body)), sources(result));
            assertTrue(result.lines().stream().noneMatch(r -> r.role().equals("ABILITY_NAME")));
        }
        var unverified =
                List.of(
                        "Example Pet",
                        "Combat Pet",
                        "",
                        "Harvest Mastery",
                        "Doubles your Strength.");
        assertEquals(
                List.of("Harvest Mastery Doubles your Strength."),
                sources(LoreParser.analyze(unverified, item(), s -> false)));
        heading(LoreParser.analyze(unverified, item(), s -> s.equals("Harvest Mastery")), 3);
    }

    @Test
    void verifiedLabelsStillRequireASectionBoundaryAndFollowingEffect() {
        var continuation =
                List.of(
                        "Example Pet",
                        "Combat Pet",
                        "",
                        "This effect improves",
                        "Teleport Savvy",
                        "Buffs Transmission abilities.");
        assertEquals(
                List.of("This effect improves Teleport Savvy Buffs Transmission abilities."),
                sources(
                        LoreParser.analyze(
                                continuation, item(), NativePetHeadingRegressionTest::label)));
        var noEffect = List.of("Example Pet", "Combat Pet", "", "Teleport Savvy", "Strength: +20");
        var result = LoreParser.analyze(noEffect, item(), NativePetHeadingRegressionTest::label);
        assertEquals(List.of("Teleport Savvy"), sources(result));
        assertEquals("STAT", result.lines().get(4).role());
    }

    @Test
    void explicitTierAndSkillRewardBoundariesRemainUnchanged() {
        var belt =
                List.of(
                        "Backwater Belt",
                        "Fishing Speed: +2",
                        "",
                        "Tiered Bonus: Swamp Soldier (4/8)",
                        "Increases damage dealt to Sea",
                        "Creatures by 20%.",
                        "",
                        "UNCOMMON BELT");
        var result = LoreParser.analyze(belt, item(), NativePetHeadingRegressionTest::label);
        assertEquals(List.of("Increases damage dealt to Sea Creatures by 20%."), sources(result));
        assertEquals("EXPLICIT_ABILITY_HEADING", result.lines().get(3).reason());
        var skill =
                List.of(
                        "Farming XXXVI",
                        "",
                        "Level XXXVII Rewards:",
                        "Farmhand XXXVII",
                        "Grants +144➜148 \ue051 Farming",
                        "Fortune, which increases your",
                        "chance for multiple crops.",
                        "+5\ue010 Health");
        var ui = new TooltipContext.Decision(TooltipContext.Kind.UI, "TEST_MENU", Map.of());
        var parsed = LoreParser.analyze(skill, ui, NativePetHeadingRegressionTest::label);
        assertTrue(
                sources(parsed)
                        .contains(
                                "Grants +144➜148 \ue051 Farming Fortune, which increases your chance for multiple crops."));
        assertTrue(sources(parsed).stream().noneMatch(s -> s.contains("Farmhand")));
        assertEquals("STAT", parsed.lines().get(7).role());
    }

    @Test
    void actualNativeBodyStylesMatchAndRenderWithoutNewDictionaryLiterals() {
        var bodies =
                List.of(
                        List.of(
                                text("Doubles the pet's base stats during", ChatFormatting.GRAY),
                                text("the ", ChatFormatting.GRAY)
                                        .copy()
                                        .append(text("Fishing Festival", ChatFormatting.AQUA))
                                        .append(text(".", ChatFormatting.GRAY))),
                        List.of(
                                text("Buffs Transmission abilities, granting", ChatFormatting.GRAY),
                                text("50 ", ChatFormatting.GREEN)
                                        .copy()
                                        .append(
                                                text(
                                                        "weapon damage for 5s on use",
                                                        ChatFormatting.GRAY))));
        for (var lines : bodies) {
            var paragraph = new StyledParagraph(lines);
            var match = Dictionary.VALUE.lookup("item", paragraph.sourceText(), "").orElseThrow();
            var result = paragraph.dictionaryEncoding(match, true);
            assertTrue(result.accepted(), () -> result.reason() + result.details());
            var decoded = paragraph.decode(result.text()).getString();
            assertTrue(decoded.matches(".*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}].*"));
            if (paragraph.sourceText().startsWith("Buffs")) {
                assertTrue(decoded.contains("50"));
                assertTrue(decoded.contains("5"));
                assertTrue(decoded.contains("Transmission abilities"));
            } else {
                assertTrue(decoded.contains("2"));
                assertTrue(decoded.contains("Fishing Festival"));
            }
        }
    }

    @Test
    void translatorLabelPredicateRequiresLiveEnabledApprovedItemLabel() {
        var config = new Config();
        config.candidateDictionary = true;
        config.apiTranslation = false;
        var translator =
                new Translator(
                        config,
                        temp.resolve("cache.json"),
                        LoggerFactory.getLogger(getClass()),
                        text -> {
                            throw new AssertionError("No external translation allowed");
                        },
                        Dictionary.VALUE);
        try {
            assertTrue(translator.knownItemLabel("Teleport Savvy"));
            assertTrue(translator.knownItemLabel("Enhanced Scales"));
            for (String other :
                    List.of(
                            "Gain Health",
                            "Pets",
                            "Click to view!",
                            "Doubles the pet's base stats during the Fishing Festival."))
                assertFalse(translator.knownItemLabel(other), other);
            config.candidateDictionary = false;
            assertFalse(translator.knownItemLabel("Teleport Savvy"));
            config.candidateDictionary = true;
        } finally {
            translator.close();
        }
        assertFalse(translator.knownItemLabel("Teleport Savvy"));
    }
}
