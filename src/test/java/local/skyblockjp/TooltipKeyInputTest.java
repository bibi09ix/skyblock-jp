package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TooltipKeyInputTest {
    @Test
    void aPressAndReleaseBetweenFramesStillProducesOneRequest() {
        Object screen = new Object();
        var input = new TooltipKeyInput();
        input.observe(screen, "item", 40, 50, 1_000);
        assertEquals(TooltipKeyInput.Press.FIRST, input.press(screen, "item", 40, 50, 2_000, true));
        input.release(); // No render or tick was needed between the press and release.
        assertEquals(TooltipKeyInput.Press.FIRST, input.press(screen, "item", 40, 50, 3_000, true));
    }

    @Test
    void heldKeyDoesNotTranslateAgainWhenPointerMovesToAnotherItem() {
        Object screen = new Object();
        var input = new TooltipKeyInput();
        input.observe(screen, "first", 40, 50, 1);
        assertEquals(TooltipKeyInput.Press.FIRST, input.press(screen, "first", 40, 50, 2, true));
        input.observe(screen, "second", 70, 50, 3);
        assertEquals(TooltipKeyInput.Press.REPEAT, input.press(screen, "second", 70, 50, 4, true));
        input.release();
        assertEquals(TooltipKeyInput.Press.FIRST, input.press(screen, "second", 70, 50, 5, true));
    }

    @Test
    void typingOrPressingAwayFromAValidTooltipCannotQueueAFutureTranslation() {
        Object screen = new Object();
        var input = new TooltipKeyInput();
        input.observe(screen, "item", 40, 50, 1);
        assertEquals(TooltipKeyInput.Press.NONE, input.press(screen, "item", 40, 50, 2, false));
        assertEquals(TooltipKeyInput.Press.NONE, input.press(screen, "item", 40, 50, 3, true));
        input.release();
        input.clearHover();
        assertEquals(TooltipKeyInput.Press.NONE, input.press(screen, "item", 40, 50, 4, true));
        input.observe(screen, "item", 40, 50, 5);
        assertEquals(TooltipKeyInput.Press.NONE, input.press(screen, "item", 40, 50, 6, true));
    }

    @Test
    void staleItemScreenAndMouseCoordinatesAreRejected() {
        Object screen = new Object();
        var input = new TooltipKeyInput();
        input.observe(screen, "item", 40, 50, 100);
        assertEquals(
                TooltipKeyInput.Press.NONE, input.press(new Object(), "item", 40, 50, 101, true));
        input.release();
        assertEquals(TooltipKeyInput.Press.NONE, input.press(screen, "other", 40, 50, 101, true));
        input.release();
        assertEquals(TooltipKeyInput.Press.NONE, input.press(screen, "item", 41, 50, 101, true));
        input.release();
        assertEquals(
                TooltipKeyInput.Press.NONE,
                input.press(
                        screen,
                        "item",
                        40,
                        50,
                        100 + TooltipKeyInput.MAX_HOVER_AGE_NANOS + 1,
                        true));
    }

    @Test
    void resettingForScreenOrConnectionChangeDropsHoverAndHeldState() {
        Object screen = new Object();
        var input = new TooltipKeyInput();
        input.observe(screen, "item", 40, 50, 1);
        assertEquals(TooltipKeyInput.Press.FIRST, input.press(screen, "item", 40, 50, 2, true));
        input.reset();
        assertEquals(TooltipKeyInput.Press.NONE, input.press(screen, "item", 40, 50, 3, true));
        input.release();
        input.observe(screen, "new", 40, 50, 4);
        assertEquals(TooltipKeyInput.Press.FIRST, input.press(screen, "new", 40, 50, 5, true));
    }

    @Test
    void repeatAfterScreenResetCannotBeginANewTranslationWithoutANewPress() {
        Object firstScreen = new Object(), nextScreen = new Object();
        var input = new TooltipKeyInput();
        input.observe(firstScreen, "first", 40, 50, 1);
        assertEquals(
                TooltipKeyInput.Press.FIRST, input.press(firstScreen, "first", 40, 50, 2, true));
        assertEquals(TooltipKeyInput.Press.REPEAT, input.repeat());
        input.reset(); // The physical key may still be down when the screen or service changes.
        input.observe(nextScreen, "next", 40, 50, 3);
        assertEquals(TooltipKeyInput.Press.NONE, input.repeat());
        assertEquals(TooltipKeyInput.Press.NONE, input.repeat());
        input.release();
        assertEquals(TooltipKeyInput.Press.FIRST, input.press(nextScreen, "next", 40, 50, 4, true));
    }
}
