package local.skyblockjp;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryEncodingRegressionTest {
    private static Component t(String text, ChatFormatting color) { return Component.literal(text).withStyle(color); }
    private static StyledParagraph p(Component text) { return new StyledParagraph(List.of(text)); }
    private static Component translated(StyledParagraph paragraph, String target) {
        var result = paragraph.dictionaryEncoding(target);
        assertTrue(result.accepted(), result.reason() + ": " + target);
        assertEquals("ACCEPTED", result.reason());
        assertTrue(StyledParagraph.validNumbers(paragraph.encoded(), result.text()));
        return paragraph.decode(result.text());
    }
    private static void color(Component output, String phrase, int expected) {
        int at = output.getString().indexOf(phrase);
        assertTrue(at >= 0, output.getString());
        var glyphs = StyledParagraph.glyphs(output);
        int offset = 0;
        for (var glyph : glyphs) {
            if (offset >= at && offset < at + phrase.length()) assertEquals(expected, glyph.style().getColor().getValue());
            offset += glyph.text().length();
        }
    }

    @Test void menuLabelPrefixesDoNotBecomePlayerNames() {
        for (String prefix : List.of("Rewards", "Progress", "Preview", "Ability", "Cost", "Requirements")) {
            var paragraph = p(Component.literal(prefix + ": Collect your reward."));
            assertFalse(paragraph.encoded().contains("<t0>" + prefix + ":"));
            assertEquals("報酬を受け取る。", translated(paragraph, "報酬を受け取る。").getString());
        }
    }
    @Test void actualPlayerContextKeepsPlainUsernameAndNumericUsernameWhole() {
        for (String source : List.of("Rewards: Collect your reward.", "[VIP] Player123: Collect your reward.")) {
            var paragraph = new StyledParagraph(List.of(Component.literal(source)), true);
            String header = source.substring(0, source.indexOf(':') + 2);
            assertTrue(paragraph.encoded().contains("<t0>" + header + "</t0>"));
            assertEquals(header + "報酬を受け取る。", translated(paragraph, header + "報酬を受け取る。").getString());
            assertFalse(paragraph.dictionaryEncoding("報酬を受け取る。").accepted());
        }
    }
    @Test void ordinaryLowercaseStatHomonymsCanBeTranslated() {
        assertEquals("生命力を持つ樹液。", translated(p(Component.literal("Sap with vitality.")), "生命力を持つ樹液。").getString());
        assertEquals("効果を強化する。", translated(p(Component.literal("Increase its strength.")), "効果を強化する。").getString());
        assertEquals("Minionの速度が増加する。", translated(p(Component.literal("Improves minion speed.")), "Minionの速度が増加する。").getString());
    }
    @Test void actualLowercaseStatQuantityIsStillImmutable() {
        var paragraph = p(Component.literal("Gain 5 health."));
        assertFalse(paragraph.dictionaryEncoding("体力が5増加する。").accepted());
        assertEquals("healthが5増加する。", translated(paragraph, "Healthが5増加する。").getString());
    }
    @Test void termCaseAliasesRestoreNativeNameAndColor() {
        var paragraph = p(t("Consumes ", ChatFormatting.GRAY).copy().append(t("mana", ChatFormatting.AQUA)).append(t(".", ChatFormatting.GRAY)));
        Component output = translated(paragraph, "Manaを消費する。");
        assertEquals("manaを消費する。", output.getString());
        color(output, "mana", 0x55FFFF);
    }
    @Test void completeNamedListAcceptsJapaneseSeparatorsButCannotLoseAnEntry() {
        String names = "Life Steal, Drain, and Vampirism";
        var paragraph = p(t("Adds +1 Level to ", ChatFormatting.GRAY).copy().append(t(names, ChatFormatting.GREEN))
            .append(t(" enchantments.", ChatFormatting.GRAY)));
        var output = translated(paragraph, "Life Steal、Drain、Vampirismエンチャントのレベルが+1上がる。");
        assertTrue(output.getString().contains(names));
        color(output, names, 0x55FF55);
        assertFalse(paragraph.dictionaryEncoding("Life Steal、Vampirismエンチャントのレベルが+1上がる。").accepted());
        assertEquals("PROTECTED_VALUE_EXTRA", paragraph.dictionaryEncoding("Life Steal、Drain、Vampirismエンチャントのレベルが+1上がり、Drainも増える。").reason());
    }
    @Test void conventionalPluralNameKeepsItsColoredEnglishEntity() {
        var paragraph = p(t("Bring to ", ChatFormatting.GRAY).copy().append(t("Alcina", ChatFormatting.LIGHT_PURPLE))
            .append(t(" in the Hub to craft it into ", ChatFormatting.GRAY)).append(t("Stews", ChatFormatting.LIGHT_PURPLE))
            .append(t("!", ChatFormatting.GRAY)));
        var output = translated(paragraph, "HubにいるAlcinaに渡すと、Stewを作れる。");
        color(output, "Alcina", 0xFF55FF); color(output, "Stew", 0xFF55FF);
        assertFalse(paragraph.dictionaryEncoding("HubにいるAlcinaに渡すと、Soupを作れる。").accepted());
    }
    @Test void coloredEnglishCountPrefixMovesToItsJapaneseCountUnit() {
        var paragraph = p(t("Combine ", ChatFormatting.GRAY).copy().append(t("x9", ChatFormatting.GREEN))
            .append(t(" of this item.", ChatFormatting.GRAY)));
        var output = translated(paragraph, "このアイテムを9個組み合わせる。");
        assertEquals("このアイテムを9個組み合わせる。", output.getString());
        color(output, "9個", 0x55FF55);
        assertFalse(paragraph.dictionaryEncoding("このアイテムを8個組み合わせる。").accepted());
        assertFalse(paragraph.dictionaryEncoding("このアイテムを9個と10個組み合わせる。").accepted());
    }
    @Test void reviewedOrdinaryPhrasesKeepSemanticAccent() {
        var nearby = p(t("Hit ", ChatFormatting.GRAY).copy().append(t("nearby enemies", ChatFormatting.RED)).append(t(".", ChatFormatting.GRAY)));
        color(translated(nearby, "周囲の敵に命中する。"), "周囲の敵", 0xFF5555);
        var rewards = p(t("Claim your ", ChatFormatting.GRAY).copy().append(t("rewards", ChatFormatting.GOLD)).append(t("!", ChatFormatting.GRAY)));
        color(translated(rewards, "報酬を受け取ろう！"), "報酬", 0xFFAA00);
        var menu = p(t("View and manage all your ", ChatFormatting.GRAY).copy().append(t("Pets", ChatFormatting.GREEN)).append(t(".", ChatFormatting.GRAY)));
        color(translated(menu, "所持するすべてのペットを確認・管理する。"), "ペット", 0x55FF55);
    }
    @Test void unknownOrMultipleSemanticSpansRemainRejected() {
        var unknown = p(t("It works ", ChatFormatting.GRAY).copy().append(t("mysteriously", ChatFormatting.BLUE)).append(t(".", ChatFormatting.GRAY)));
        assertEquals("STYLE_ALIGNMENT_MISSING", unknown.dictionaryEncoding("不思議に働く。").reason());
        var duplicate = p(t("Hit ", ChatFormatting.GRAY).copy().append(t("nearby enemies", ChatFormatting.RED)).append(t(".", ChatFormatting.GRAY)));
        assertEquals("STYLE_ALIGNMENT_AMBIGUOUS", duplicate.dictionaryEncoding("周囲の敵と近くの敵に命中する。").reason());
    }
    @Test void duplicateValueWithNonUniqueStatAnchorDoesNotGuess() {
        var paragraph = p(t("Gain ", ChatFormatting.GRAY).copy().append(t("10", ChatFormatting.GREEN)).append(t(" Health and ", ChatFormatting.GRAY))
            .append(t("10", ChatFormatting.RED)).append(t(" Health.", ChatFormatting.GRAY)));
        assertEquals("AMBIGUOUS_VALUE_STYLE", paragraph.dictionaryEncoding("Healthが10とHealthが10増加する。").reason());
    }
    @Test void numericalSignsGroupingSuffixAndIconRemainExact() {
        var paragraph = p(Component.literal("Gain +1,000 ☘ Mining Fortune for 60M coins."));
        for (String target : List.of("Mining Fortuneが1000 ☘、60M coins増加する。", "60M coinsでMining Fortuneが+1000 ☘増加する。",
            "60m coinsでMining Fortuneが+1,000 ☘増加する。", "60M coinsでMining Fortuneが+1,000増加する。",
            "60M coinsでMining Fortuneが+1,000 ♣増加する。", "60M coinsでMining Fortuneが+1,000 ☘☘増加する。"))
            assertFalse(paragraph.dictionaryEncoding(target).accepted(), target);
        assertTrue(paragraph.dictionaryEncoding("60M coinsでMining Fortuneが+1,000 ☘増加する。").accepted());
    }
    @Test void fingerprintDistinguishesStylesContextAndText() {
        var green = p(t("Gain ", ChatFormatting.GRAY).copy().append(t("10", ChatFormatting.GREEN)).append(t(" Health.", ChatFormatting.GRAY)));
        var red = p(t("Gain ", ChatFormatting.GRAY).copy().append(t("10", ChatFormatting.RED)).append(t(" Health.", ChatFormatting.GRAY)));
        assertEquals(green.encoded(), red.encoded());
        assertNotEquals(green.dictionaryFingerprint(), red.dictionaryFingerprint());
        assertEquals(green.dictionaryFingerprint(), p(t("Gain ", ChatFormatting.GRAY).copy().append(t("10", ChatFormatting.GREEN)).append(t(" Health.", ChatFormatting.GRAY))).dictionaryFingerprint());
        assertNotEquals(new StyledParagraph(List.of(Component.literal("Rewards: Hello"))).dictionaryFingerprint(),
            new StyledParagraph(List.of(Component.literal("Rewards: Hello")), true).dictionaryFingerprint());
    }
    @Test void dictionaryReasonsAreStructuredAndDoNotContainSourceText() {
        var paragraph = p(Component.literal("Gain 5 Health."));
        assertEquals("INVALID_TARGET", paragraph.dictionaryEncoding("<n0/>").reason());
        assertEquals("PROTECTED_VALUE_MISSING", paragraph.dictionaryEncoding("Healthが6増加する。").reason());
        assertEquals("PROTECTED_VALUE_EXTRA", paragraph.dictionaryEncoding("Healthが5増加しDefenseも増加する。").reason());
        assertEquals("PROTECTED_VALUE_EXTRA", paragraph.dictionaryEncoding("Healthが5増加しdefenseも増加する。").reason());
        assertEquals("PROTECTED_VALUE_EXTRA", paragraph.dictionaryEncoding("Healthが5増加しsPeEdも増加する。").reason());
    }
    @Test void lowercaseOrdinaryWordQuotaDoesNotPermitNewOrDuplicatedStats() {
        var paragraph = p(Component.literal("Improve its speed."));
        assertTrue(paragraph.dictionaryEncoding("速度を高める。").accepted());
        assertTrue(paragraph.dictionaryEncoding("Speedを高める。").accepted());
        assertEquals("PROTECTED_VALUE_EXTRA", paragraph.dictionaryEncoding("speedとspeedを高める。").reason());
        assertEquals("PROTECTED_VALUE_EXTRA", paragraph.dictionaryEncoding("speedとdefenseを高める。").reason());
    }
    @Test void spatialUnitAliasRequiresItsOwnAdjacentSourceQuantity() {
        var paragraph = p(t("Teleport ", ChatFormatting.GRAY).copy().append(t("5", ChatFormatting.GREEN))
            .append(t(" blocks", ChatFormatting.YELLOW)).append(t(" ahead.", ChatFormatting.GRAY)));
        color(translated(paragraph, "前方へ5ブロック移動する。"), "ブロック", 0xFFFF55);
        var unanchored = p(t("It ", ChatFormatting.GRAY).copy().append(t("blocks", ChatFormatting.YELLOW))
            .append(t(" attacks.", ChatFormatting.GRAY)));
        assertEquals("STYLE_ALIGNMENT_MISSING", unanchored.dictionaryEncoding("ブロックへの攻撃を防ぐ。").reason());
    }
}
