package local.skyblockjp;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.components.EditBox;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.*;

public final class SkyBlockJp implements ClientModInitializer {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("skyblock_jp");
    private static SkyBlockJp INSTANCE;
    private final TranslationHud hud = new TranslationHud();
    private final AutoUpdater updater = new AutoUpdater();
    AutoUpdater updater() { return updater; }
    private ChatTranslations chats;
    public static SkyBlockJp instance() { return INSTANCE; }
    public ChatTranslations chats() { return chats; }
    TranslationHud hud() { return hud; }
    private Config config;
    private Translator translator;
    private TooltipAudit audit;
    private Path folder;
    private final Set<String> requestedItems = new HashSet<>();
    private boolean translateHeld, settingsHeld;
    private ItemSource lastItem;
    private final IdleTranslations idleTranslations = new IdleTranslations();
    private Object idleConnection;
    private record ItemSource(String key, String name, List<LoreParser.Block> blocks, List<StyledParagraph> paragraphs) {}
    String lastItemName() { return lastItem == null ? null : lastItem.name(); }
    boolean retranslateLastItem() {
        if (lastItem == null || lastItem.paragraphs().stream().anyMatch(p -> translator.pending(p.encoded()))) return false;
        translateItem(lastItem, true);
        return true;
    }
    private void translateItem(ItemSource item, boolean force) {
        if (requestedItems.size() > 1000) requestedItems.clear();
        requestedItems.add(item.key());
        if (audit != null) audit.forget(item.key());
        Set<String> submitted = new HashSet<>();
        for (int n = 0; n < item.paragraphs().size(); n++) {
            var paragraph = item.paragraphs().get(n); var block = item.blocks().get(n);
            if (!submitted.add(paragraph.encoded())) continue;
            String context = "item=" + item.name() + "; lines=" + (block.start() + 1) + "-" + block.end() + "; original=" + block.text();
            if (force) translator.retranslate(paragraph.encoded(), context);
            else { translator.retry(paragraph.encoded()); translator.request(paragraph.encoded(), context); }
        }
    }
    Config configuration() { return config; }
    Translator service() { return translator; }
    void saveConfig(Config updated) throws Exception {
        Config.save(folder.resolve("config.json"), updated);
        reload();
        chats.refreshButtons();
    }
    void saveHudConfig(Config draft) throws Exception {
        var updated = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config), Config.class);
        updated.translationHud = draft.translationHud; updated.chatButtons = draft.chatButtons;
        updated.chatButtonFirst = draft.chatButtonFirst;
        updated.hudX = draft.hudX; updated.hudY = draft.hudY;
        updated.hudWidth = draft.hudWidth; updated.hudLines = draft.hudLines;
        updated.hudSeconds = draft.hudSeconds; updated.hudOpacity = draft.hudOpacity;
        updated.hudTextScale = draft.hudTextScale;
        Config.save(folder.resolve("config.json"), updated);
        config = updated;
        chats.refreshButtons();
        LOG.info("SkyBlock JP HUD settings saved: x={}, y={}, width={}, lines={}, seconds={}, opacity={}",
            config.hudX, config.hudY, config.hudWidth, config.hudLines, config.hudSeconds, config.hudOpacity);
    }
    void saveBackgroundConfig(Config draft) throws Exception {
        var updated = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config), Config.class);
        updated.backgroundTranslation = draft.backgroundTranslation;
        updated.backgroundIdleSeconds = draft.backgroundIdleSeconds;
        updated.backgroundIntervalSeconds = draft.backgroundIntervalSeconds;
        Config.save(folder.resolve("config.json"), updated); config = updated;
        idleTranslations.used(System.currentTimeMillis());
    }
    void saveUpdateConfig(Config draft) throws Exception {
        var updated = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config), Config.class);
        updated.autoUpdate = draft.autoUpdate; updated.updateRepository = draft.updateRepository;
        if (!updated.autoUpdate || !Objects.equals(config.updateRepository, updated.updateRepository)) updater.cancelPending();
        Config.save(folder.resolve("config.json"), updated); config = updated;
    }
    private void backgroundTick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (idleConnection != mc.getConnection()) {
            idleConnection = mc.getConnection(); idleTranslations.clear(now); lastItem = null;
        }
        if (!active() || !config.loreTranslation || mc.player == null) return;
        boolean apiReady = !config.provider.equals("GROQ") || !config.groqApiKey.isBlank();
        boolean editing = mc.gui.screen() instanceof SettingsScreen || mc.gui.screen() instanceof ChatSettingsScreen
            || mc.gui.screen() instanceof HudPositionScreen || mc.gui.screen() instanceof BackgroundSettingsScreen || mc.gui.screen() instanceof ApiHelpScreen;
        var job = idleTranslations.next(now, translator.lastForegroundUse(), config,
            apiReady && !editing && translator.backgroundReady(), text -> translator.cached(text) != null);
        if (job == null) return;
        var service = translator; var connection = idleConnection;
        LOG.info("SkyBlock JP: background paragraph started; interval={}s", config.backgroundIntervalSeconds);
        service.requestBackground(job.text(), job.context()).thenAccept(result -> mc.execute(() -> {
            if (service != translator || connection != mc.getConnection()) return;
            LOG.info("SkyBlock JP: background paragraph {}", result == null ? "deferred or failed (see diagnostics)" : "cached");
        }));
    }
    public boolean scrollHud(double x, double y, double amount) {
        return active() && config.translationHud && hud.scroll(x, y, amount, config);
    }
    private void openSettings() {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new SettingsScreen(mc.gui.screen(), this));
    }
    public void onInitializeClient() {
        INSTANCE = this; chats = new ChatTranslations(this);
        folder = FabricLoader.getInstance().getConfigDir().resolve("skyblock_jp");
        reload();
        updater.check(config, false);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(
            net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,
            net.minecraft.resources.Identifier.fromNamespaceAndPath("skyblock_jp", "translation"),
            (graphics, delta) -> { if (active() && config.translationHud && !(Minecraft.getInstance().gui.screen() instanceof HudPositionScreen)) hud.render(graphics, config, false); });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.getWindow() == null) return;
            backgroundTick(mc);
            if (!config.initialSetupShown && config.enabled && mc.gui.overlay() == null
                && mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen) {
                config.initialSetupShown = true;
                try { Config.save(folder.resolve("config.json"), config); }
                catch (Exception ex) { LOG.warn("SkyBlock JP: initial setup state could not save ({})", ex.getClass().getSimpleName()); }
                mc.gui.setScreen(new ApiHelpScreen(new SettingsScreen(mc.gui.screen(), this)));
                return;
            }
            if (!InputConstants.isKeyDown(mc.getWindow(), config.translateKey)) translateHeld = false;
            boolean settingsDown = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_F8);
            if (settingsDown && !settingsHeld && !(mc.gui.screen() instanceof SettingsScreen)) openSettings();
            settingsHeld = settingsDown;
        });
        ItemTooltipCallback.EVENT.register((stack, context, flags, lines) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!active() || !config.loreTranslation || mc.getWindow() == null) return;
            if (InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT) || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT)) return;
            List<Component> original = new ArrayList<>(lines);
            if (mc.gui.screen() instanceof SettingsScreen || (mc.gui.screen() != null && mc.gui.screen().getFocused() instanceof EditBox)) return;
            String itemKey = String.join("\n", original.stream().map(Component::getString).toList());
            List<LoreParser.Block> blocks = LoreParser.parse(original.stream().map(Component::getString).toList());
            List<StyledParagraph> paragraphs = blocks.stream().map(b -> new StyledParagraph(original.subList(b.start(), b.end()))).toList();
            if (!blocks.isEmpty()) {
                lastItem = new ItemSource(itemKey, original.getFirst().getString(), blocks, paragraphs);
                var jobs = new ArrayList<IdleTranslations.Job>();
                for (int n = 0; n < paragraphs.size(); n++) {
                    var block = blocks.get(n);
                    jobs.add(new IdleTranslations.Job(itemKey, paragraphs.get(n).encoded(), "item=" + lastItem.name() + "; lines=" + (block.start() + 1) + "-" + block.end() + "; original=" + block.text()));
                }
                idleTranslations.observe(itemKey, jobs, System.currentTimeMillis());
                if (config.backgroundTranslation && paragraphs.stream().allMatch(p -> translator.cached(p.encoded()) != null)) {
                    if (requestedItems.size() > 1000) requestedItems.clear();
                    requestedItems.add(itemKey);
                }
            }
            boolean pressed = InputConstants.isKeyDown(mc.getWindow(), config.translateKey) && !translateHeld;
            if (pressed) {
                translateHeld = true;
                if (!blocks.isEmpty()) {
                    boolean force = InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL) || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
                    if (force) retranslateLastItem();
                    else translateItem(lastItem, false);
                }
            }
            String keyName = InputConstants.Type.KEYSYM.getOrCreate(config.translateKey).getDisplayName().getString();
            if (!requestedItems.contains(itemKey)) {
                if (!blocks.isEmpty()) lines.add(Component.literal("JP: " + keyName + " で説明を翻訳 · F8 設定").withStyle(ChatFormatting.DARK_GRAY));
                return;
            }
            int wrapWidth = Math.min(config.wrapWidth, Math.max(120, mc.getWindow().getGuiScaledWidth() - 50));
            var batch = ItemTranslation.prepare(original, blocks, paragraphs, translator::cached,
                component -> wrap(component, wrapWidth));
            if (batch.ready()) { lines.clear(); lines.addAll(batch.lines());
                audit.record(itemKey, original, batch.lines(), paragraphs.stream().map(p -> p.decodeForDisplay(translator.cached(p.encoded())).getString()).toList(), wrapWidth, config.provider, config.groqModel); }
            if (blocks.isEmpty()) lines.add(Component.literal("JP: 翻訳対象の説明文が見つかりません").withStyle(ChatFormatting.YELLOW));
            else lines.add(Component.literal(!batch.ready() ? "JP: 翻訳中 " + batch.complete() + "/" + batch.total() + " · 原文を表示中" : "JP: 表示更新 ✓ · Ctrl+" + keyName + " 再翻訳 · Shift 原文").withStyle(ChatFormatting.DARK_GRAY));
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !active() || !config.npcTranslation) return;
            String text = LoreParser.clean(message.getString());
            if (!NpcTranslation.automaticMessage(text)) return;
            chats.npc(message);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(ClientCommands.literal("sbjp")
            .executes(c -> { Minecraft.getInstance().schedule(this::openSettings); return 1; })
            .then(ClientCommands.literal("translate").then(ClientCommands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes(c -> { chats.request(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id")); return 1; })))
            .then(ClientCommands.literal("reload").executes(c -> { reload(); c.getSource().sendFeedback(Component.literal("SkyBlock JP: 設定を再読み込みしました。")); return 1; }))
            .then(ClientCommands.literal("toggle").executes(c -> { config.enabled = !config.enabled; c.getSource().sendFeedback(Component.literal("SkyBlock JP: " + (config.enabled ? "ON" : "OFF"))); return 1; }))));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { hud.close(); translator.close(); if (audit != null) audit.close(); });
        LOG.info("SkyBlock JP 0.6.4 initialized; manual item translation; F8 settings.");
    }
    private void reload() {
        try {
            Config loaded = Config.load(folder.resolve("config.json"));
            if (translator != null) translator.close();
            if (audit != null) audit.close();
            audit = new TooltipAudit(folder.resolve("translation-display.jsonl"), LOG, loaded.groqApiKey);
            config = loaded;
            hud.configureAudit(folder.resolve("hud-display.jsonl"), loaded.groqApiKey);
            requestedItems.clear();
            translator = new Translator(config, folder.resolve("cache.json"), LOG);
            idleTranslations.clear(System.currentTimeMillis());
        } catch (Exception e) {
            LOG.error("SkyBlock JP: configuration could not load ({})", e.getClass().getSimpleName());
            if (config == null) { config = new Config(); config.enabled = false; translator = new Translator(config, folder.resolve("cache.json"), LOG); }
        }
    }
    boolean active() {
        if (!config.enabled) return false;
        var server = Minecraft.getInstance().getCurrentServer();
        if (!config.hypixelOnly) return true;
        if (server == null) return false;
        String host = server.ip.toLowerCase(Locale.ROOT).split(":")[0];
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }
    private List<Component> wrap(Component text, int width) {
        return TooltipWrap.wrap(text, width, Minecraft.getInstance().font::width);
    }
}

