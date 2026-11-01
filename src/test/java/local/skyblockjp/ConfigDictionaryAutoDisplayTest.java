package local.skyblockjp;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigDictionaryAutoDisplayTest {
    @TempDir Path dir;

    @Test void newConfigOffersInstantDisplayWithoutEnablingTheCandidateDictionary() throws Exception {
        Path file = dir.resolve("new.json");
        Config config = Config.load(file);
        assertTrue(config.dictionaryAutoDisplay);
        assertFalse(config.candidateDictionary);
        assertTrue(Config.load(file).dictionaryAutoDisplay);
    }

    @Test void legacyConfigGetsTheNewDefaultAndKeepsExistingPreferences() throws Exception {
        Path file = dir.resolve("legacy.json");
        Files.writeString(file, """
            {"candidateDictionary":true,"provider":"GOOGLE","backgroundTranslation":false,
             "hudX":0.3,"hudY":0.7,"hudTextScale":1.5,"groqApiKey":"test-placeholder"}
            """);
        Config config = Config.load(file);
        assertTrue(config.dictionaryAutoDisplay);
        assertTrue(config.candidateDictionary);
        assertEquals("GOOGLE", config.provider);
        assertEquals(0.3, config.hudX); assertEquals(0.7, config.hudY);
        assertEquals(1.5, config.hudTextScale);
        assertEquals("test-placeholder", config.groqApiKey);
    }

    @Test void explicitOffSurvivesSavingAndDisablingTheDictionary() throws Exception {
        Path file = dir.resolve("saved.json");
        Config config = new Config();
        config.candidateDictionary = true; config.dictionaryAutoDisplay = false;
        config.groqApiKey = "test-placeholder"; config.hudTextScale = 1.5;
        Config.save(file, config);
        Config restored = Config.load(file);
        assertFalse(restored.dictionaryAutoDisplay);
        assertTrue(restored.candidateDictionary);
        restored.candidateDictionary = false;
        Config.save(file, restored);
        restored = Config.load(file);
        assertFalse(restored.dictionaryAutoDisplay);
        assertFalse(restored.candidateDictionary);
        assertEquals("test-placeholder", restored.groqApiKey);
        assertEquals(1.5, restored.hudTextScale);
    }
}
