package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitialSetupTest {
    @TempDir Path dir;

    @Test
    void newInstallUsesGroqAndOneTimeGuideWithLargerBottomRightHud() throws Exception {
        Path file = dir.resolve("config.json");
        Config c = Config.load(file);
        assertEquals("GROQ", c.provider);
        assertTrue(c.candidateDictionary);
        assertTrue(c.dictionaryAutoDisplay);
        assertEquals("", c.groqApiKey);
        assertTrue(c.chatButtons);
        assertTrue(c.chatButtonFirst);
        assertFalse(c.initialSetupShown);
        assertEquals(480, c.hudWidth);
        assertEquals(165, TranslationHud.panelHeight(c));
        assertEquals(160, TranslationHud.x(c, 640, 480));
        assertEquals(195, TranslationHud.y(c, 360, 165));
        assertFalse(Config.load(file).initialSetupShown);
        c.initialSetupShown = true;
        Config.save(file, c);
        assertTrue(Config.load(file).initialSetupShown);
    }

    @Test
    void existingConfigKeepsValuesAndSkipsFirstTimeGuide() throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(
                file,
                "{\"provider\":\"GOOGLE\",\"hudX\":0.3,\"hudY\":0.7,\"hudWidth\":335,\"hudLines\":8,\"chatButtonFirst\":false,\"groqApiKey\":\"test-placeholder\"}");
        Config c = Config.load(file);
        assertTrue(c.initialSetupShown);
        assertEquals("GOOGLE", c.provider);
        assertEquals(.3, c.hudX);
        assertEquals(.7, c.hudY);
        assertEquals(335, c.hudWidth);
        assertEquals(8, c.hudLines);
        assertFalse(c.chatButtonFirst);
        assertEquals("test-placeholder", c.groqApiKey);
        c.resetHudLayout();
        assertEquals(1, c.hudX);
        assertEquals(1, c.hudY);
        assertEquals(480, c.hudWidth);
        assertEquals(13, c.hudLines);
    }
}
