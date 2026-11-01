package local.skyblockjp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextScaleTest {
    @TempDir Path dir;

    @Test
    void scalingAdjustsWrappingHeightAndVisibleHistoryTogether() {
        Config c = new Config();
        c.hudTextScale = 1.5;
        assertEquals(320, TranslationHud.logicalWidth(c, 480));
        assertEquals(248, TranslationHud.panelHeight(c));
        assertEquals(13, TranslationHud.lineLimit(c, 248));
        assertEquals(8, TranslationHud.lineLimit(c, 165));
        assertEquals(112, TranslationHud.y(c, 360, 248));
        assertEquals(160, TranslationHud.x(c, 640, 480));
        c.hudTextScale = 2.5;
        assertEquals(0, TranslationHud.lineLimit(c, 55));
    }

    @Test
    void savedScaleSurvivesReloadAndOldConfigsKeepOriginalTextSize() throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{\"provider\":\"GROQ\",\"hudWidth\":335,\"hudLines\":13}");
        Config c = Config.load(file);
        assertEquals(1, c.hudTextScale);
        assertEquals(335, c.hudWidth);
        c.hudTextScale = 1.5;
        Config.save(file, c);
        assertEquals(1.5, Config.load(file).hudTextScale);
        c.hudTextScale = 10;
        Config.save(file, c);
        assertEquals(2.5, Config.load(file).hudTextScale);
    }
}
