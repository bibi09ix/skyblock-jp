package local.skyblockjp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NpcChestRiddleRegressionTest {
    private static final String SOURCE = "They are both lying, the reward is in my chest!";
    private static final String BAD = "二人とも嘘だ、報酬は私の胸の中にある！";

    @Test void correctsTheLoggedContainerSenseWithoutDroppingTheLyingClause() {
        String result = NpcTranslationRepair.repair(SOURCE, BAD, "");
        assertEquals("二人とも嘘だ、報酬は私のチェストの中にある！", result);
        assertTrue(StyledParagraph.validNumbers(SOURCE, result));
    }
    @Test void acceptsOnlyCaseWhitespaceAndFinalPunctuationVariations() {
        assertEquals("二人とも嘘だ、報酬は私のチェストの中にある！",
            NpcTranslationRepair.repair("THEY ARE BOTH LYING,  the reward is in my chest.", BAD, ""));
        assertEquals("二人とも嘘だ、報酬は私のチェストの中にある！",
            NpcTranslationRepair.repair("They are both lying, the reward is in my chest", BAD, ""));
        assertEquals(BAD, NpcTranslationRepair.repair("The reward is in my chest!", BAD, ""));
    }
    @Test void leavesCorrectTranslationsAndUnrelatedBodyReferencesAlone() {
        String correct = "二人とも嘘をついているよ。報酬は私のチェストの中だ！";
        assertEquals(correct, NpcTranslationRepair.repair(SOURCE, correct, ""));
        assertEquals("胸が高鳴る。", NpcTranslationRepair.repair("My heart is pounding in my chest.", "胸が高鳴る。", ""));
        assertEquals(BAD, NpcTranslationRepair.repair("They are both lying, the pain is in my chest!", BAD, ""));
        assertEquals("報酬は私の胸当ての中にある。", NpcTranslationRepair.repair(SOURCE, "報酬は私の胸当ての中にある。", ""));
    }
    @Test void preservesExistingStyleMarkersAroundTheContainerWord() {
        String source = "They are both lying, the reward is in <s0>my chest</s0>!";
        String response = "二人とも嘘だ。報酬は<s0>私の胸</s0>の中にある！";
        String result = NpcTranslationRepair.repair(source, response, "");
        assertEquals("二人とも嘘だ。報酬は<s0>私のチェスト</s0>の中にある！", result);
        assertTrue(StyledParagraph.validMarkers(source, result));
        assertTrue(StyledParagraph.validNumbers(source, result));
    }
    @Test void doesNotDiscardOrRewriteProtectedValuesAndNames() {
        String unexpectedValue = "二人とも嘘だ。報酬は私の胸の中に<n0/>個ある！";
        assertEquals(unexpectedValue, NpcTranslationRepair.repair(SOURCE, unexpectedValue, ""));
        String unexpectedName = "二人とも嘘だ。報酬は<t0>私の胸</t0>の中にある！";
        assertEquals(unexpectedName, NpcTranslationRepair.repair(SOURCE, unexpectedName, ""));
        String protectedSource = "They are both lying, the reward is in <t0>my chest</t0>!";
        assertEquals(unexpectedName, NpcTranslationRepair.repair(protectedSource, unexpectedName, ""));
    }
    @Test void leavesInvalidOrAmbiguousStyleResponsesForExistingValidation() {
        String source = "They are both lying, the reward is in <s0>my chest</s0>!";
        assertEquals(BAD, NpcTranslationRepair.repair(source, BAD, ""));
        String ambiguous = "胸が痛い。報酬は私の胸の中にある。";
        assertEquals(ambiguous, NpcTranslationRepair.repair(SOURCE, ambiguous, ""));
        assertNull(NpcTranslationRepair.repair(SOURCE, null, ""));
    }
}
