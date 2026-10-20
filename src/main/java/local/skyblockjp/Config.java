package local.skyblockjp;

import com.google.gson.GsonBuilder;
import java.nio.file.*;

public final class Config {
    public boolean enabled = true;
    public boolean npcTranslation = true;
    public boolean loreTranslation = true;
    public boolean hypixelOnly = true;
    public String provider = "GROQ";
    public boolean initialSetupShown = false;
    public String groqModel = "openai/gpt-oss-120b";
    public String groqApiKey = "";
    public int wrapWidth = 260;
    public int translateKey = 71;
    public boolean translationHud = false;
    public boolean chatButtons = true;
    public boolean chatButtonFirst = true;
    public double hudX = 1, hudY = 1;
    public int hudWidth = 480, hudLines = 13, hudSeconds = 30;
    public double hudOpacity = 0.69;
    public double hudTextScale = 1.0;
    public boolean backgroundTranslation = true;
    public boolean autoUpdate = true;
    public String updateRepository = "";
    public int backgroundIdleSeconds = 120, backgroundIntervalSeconds = 180;
    public void resetHudLayout() {
        var defaults = new Config();
        hudX = defaults.hudX; hudY = defaults.hudY;
        hudWidth = defaults.hudWidth; hudLines = defaults.hudLines;
        hudTextScale = defaults.hudTextScale;
    }
    public static void save(Path file, Config config) throws Exception {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(config));
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
    public static Config load(Path file) throws Exception {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        if (Files.exists(file)) {
            var json = com.google.gson.JsonParser.parseString(Files.readString(file));
            Config config = gson.fromJson(json, Config.class);
            if (config == null) throw new IllegalArgumentException("Empty configuration");
            // An existing pre-onboarding config belongs to an already configured user.
            if (!json.getAsJsonObject().has("initialSetupShown")) config.initialSetupShown = true;
            config.wrapWidth = Math.clamp(config.wrapWidth, 120, 500);
            config.hudX = Double.isFinite(config.hudX) ? Math.clamp(config.hudX, 0, 1) : 1;
            config.hudY = Double.isFinite(config.hudY) ? Math.clamp(config.hudY, 0, 1) : 1;
            config.hudWidth = Math.clamp(config.hudWidth, 160, 600);
            config.hudLines = Math.clamp(config.hudLines, 3, 20);
            config.hudSeconds = config.hudSeconds == 0 ? 0 : Math.clamp(config.hudSeconds, 5, 300);
            config.hudOpacity = Double.isFinite(config.hudOpacity) ? Math.clamp(config.hudOpacity, 0, 1) : 0.69;
            config.hudTextScale = Double.isFinite(config.hudTextScale) ? Math.clamp(config.hudTextScale, 0.75, 2.5) : 1.0;
            config.backgroundIdleSeconds = Math.clamp(config.backgroundIdleSeconds, 60, 600);
            config.backgroundIntervalSeconds = Math.clamp(config.backgroundIntervalSeconds, 120, 900);
            if (!"GOOGLE".equals(config.provider) && !"GROQ".equals(config.provider)) throw new IllegalArgumentException("Provider must be GOOGLE or GROQ");
            return config;
        }
        Config config = new Config();
        Files.createDirectories(file.getParent());
        Files.writeString(file, gson.toJson(config));
        return config;
    }
}
