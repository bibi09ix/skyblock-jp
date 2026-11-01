package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SettingsActionLayoutTest {
    @Test
    void exportActionFitsMinimumGuiAndAllExistingBottomActionsStayVisible() {
        for (int screenWidth : new int[] {320, 427, 640, 960, 1920}) {
            int panel = Math.min(440, screenWidth - 24), left = (screenWidth - panel) / 2;
            var row = SettingsActionLayout.bottomRow(left, 28, panel);
            assertEquals(left, row.retranslate().x());
            assertEquals(left + panel, row.chat().x() + row.chat().width());
            // Ten Japanese glyphs, ZIP, and the button's horizontal padding.
            assertTrue(row.share().width() >= 128);
            assertEquals(row.retranslate().x() + row.retranslate().width() + 6, row.share().x());
            assertEquals(row.share().x() + row.share().width() + 6, row.chat().x());
            for (var rect :
                    new SettingsActionLayout.Rect[] {row.retranslate(), row.share(), row.chat()}) {
                assertEquals(200, rect.y());
                assertEquals(20, rect.height());
                assertTrue(rect.width() > 0);
                assertTrue(rect.x() >= 12 && rect.x() + rect.width() <= screenWidth - 12);
                assertTrue(rect.y() + rect.height() < 240);
            }
        }
    }

    @Test
    void layoutSupportsTranslatedOriginAndRejectsUnusableWidth() {
        var row = SettingsActionLayout.bottomRow(17, 40, 440);
        assertEquals(212, row.share().y());
        assertThrows(
                IllegalArgumentException.class, () -> SettingsActionLayout.bottomRow(0, 0, 239));
    }
}
