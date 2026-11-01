package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigDictionaryAutoDisplayTest {
    @TempDir Path dir;

    @Test
    void newConfigEnablesBundledInstantDisplayWithoutAnApiKey() throws Exception {
        Path file = dir.resolve("new.json");
        Config config = Config.load(file);
        assertTrue(config.dictionaryAutoDisplay);
        assertTrue(config.candidateDictionary);
        assertEquals("", config.groqApiKey);
        Config restored = Config.load(file);
        assertTrue(restored.dictionaryAutoDisplay);
        assertTrue(restored.candidateDictionary);
    }

    @Test
    void legacyConfigGetsTheNewDefaultAndKeepsExistingPreferences() throws Exception {
        Path file = dir.resolve("legacy.json");
        Files.writeString(
                file,
                """
            {"candidateDictionary":true,"provider":"GOOGLE","backgroundTranslation":false,
             "hudX":0.3,"hudY":0.7,"hudTextScale":1.5,"groqApiKey":"test-placeholder"}
            """);
        Config config = Config.load(file);
        assertTrue(config.dictionaryAutoDisplay);
        assertTrue(config.candidateDictionary);
        assertEquals("GOOGLE", config.provider);
        assertEquals(0.3, config.hudX);
        assertEquals(0.7, config.hudY);
        assertEquals(1.5, config.hudTextScale);
        assertEquals("test-placeholder", config.groqApiKey);
    }

    @Test
    void explicitOffSurvivesSavingAndDisablingTheDictionary() throws Exception {
        Path file = dir.resolve("saved.json");
        Config config = new Config();
        config.candidateDictionary = true;
        config.dictionaryAutoDisplay = false;
        config.groqApiKey = "test-placeholder";
        config.hudTextScale = 1.5;
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

    @Test
    void anExistingExplicitDictionaryOffIsNotOverriddenByNewInstallDefaults() throws Exception {
        Path file = dir.resolve("existing-off.json");
        Files.writeString(
                file,
                """
            {"candidateDictionary":false,"dictionaryAutoDisplay":false,"apiTranslation":true,
             "provider":"GOOGLE","groqApiKey":"test-placeholder","hudTextScale":1.5}
            """);
        Config loaded = Config.load(file);
        assertFalse(loaded.candidateDictionary);
        assertFalse(loaded.dictionaryAutoDisplay);
        assertTrue(loaded.apiTranslation);
        Config.save(file, loaded);
        Config saved = Config.load(file);
        assertFalse(saved.candidateDictionary);
        assertFalse(saved.dictionaryAutoDisplay);
        assertEquals("GOOGLE", saved.provider);
        assertEquals("test-placeholder", saved.groqApiKey);
        assertEquals(1.5, saved.hudTextScale);
    }

    @Test
    void dictionaryOnlyChoiceStopsApiUseWithoutDiscardingCredentialsOrOtherPreferences()
            throws Exception {
        Path file = dir.resolve("local-choice.json");
        Config config = new Config();
        config.enabled = false;
        config.candidateDictionary = false;
        config.dictionaryAutoDisplay = false;
        config.provider = "GOOGLE";
        config.groqApiKey = "test-placeholder";
        config.groqModel = "custom-model";
        config.hudX = 0.3;
        config.hudTextScale = 1.5;
        config.autoUpdate = false;
        config.useDictionaryOnly();
        Config.save(file, config);
        Config restored = Config.load(file);
        assertTrue(restored.enabled);
        assertTrue(restored.candidateDictionary);
        assertTrue(restored.dictionaryAutoDisplay);
        assertFalse(restored.apiTranslation);
        assertEquals("GOOGLE", restored.provider);
        assertEquals("test-placeholder", restored.groqApiKey);
        assertEquals("custom-model", restored.groqModel);
        assertEquals(0.3, restored.hudX);
        assertEquals(1.5, restored.hudTextScale);
        assertFalse(restored.autoUpdate);
    }
}
