package local.skyblockjp;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.input.KeyEvent;
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
    private final SharedLogExportService sharedLogs = new SharedLogExportService();
    AutoUpdater updater() { return updater; }
    private ChatTranslations chats;
    public static SkyBlockJp instance() { return INSTANCE; }
    public ChatTranslations chats() { return chats; }
    TranslationHud hud() { return hud; }
    private Config config;
    private Translator translator;
    private TooltipAudit audit;
    private Path folder;
    private final Set<String> requestedItems = new LinkedHashSet<>();
    private final TooltipKeyInput tooltipKeyInput = new TooltipKeyInput();
    private boolean settingsPending;
    private Screen settingsOrigin;
    private ItemSource lastItem;
    private PendingTooltip lastTooltip;
    private final DisplayedTooltips<PendingTooltip> displayedTooltips = new DisplayedTooltips<>();
    private record ParseKey(TooltipContext.Kind kind,String classificationReason,List<String> lines) {}
    private final Map<ParseKey,LoreParser.Analysis> tooltipParses = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ParseKey,LoreParser.Analysis> entry) { return size() > 256; }
    };
    private record PendingTooltip(ItemSource source, List<Component> original, ItemTooltipTranslation.Result batch,
                                  List<Translator.DictionaryDecision> decisions, int width, String screenTitle,
                                  TooltipContext.Decision classification, LoreParser.Analysis analysis, String gateReason) {}
    private Object itemConnection;
    private record ItemSource(String key, String name, List<LoreParser.Block> blocks, List<StyledParagraph> paragraphs,
                              String channel, List<String> channels, List<Map<String,Object>> paragraphContexts,Map<String,Object> dictionaryContext) {
        String channel(int index) { return channels.get(index); }
        Map<String,Object> context(int index) {return paragraphContexts.get(index);}
    }
    String lastItemName() { return lastItem == null ? null : lastItem.name(); }
    boolean retranslateLastItem() {
        if (!config.apiTranslation) { logRetranslation("API_DISABLED", -1); return false; }
        if (lastItem == null) { logRetranslation("NO_LAST_ITEM", -1); return false; }
        for (int i = 0; i < lastItem.paragraphs().size(); i++)
            if (translator.pending(lastItem.paragraphs().get(i).encoded(), lastItem.channel(i),lastItem.context(i))) {
                logRetranslation("REQUEST_ALREADY_PENDING", i); return false;
            }
        logRetranslation("FORCE_API_REQUESTED", -1);
        translateItem(lastItem, true);
        return true;
    }
    private void logRetranslation(String reason, int paragraphIndex) {
        if (audit == null || !config.dictionaryDiagnostics) return;
        var details = new LinkedHashMap<String,Object>(screenDetails(Minecraft.getInstance()));
        details.put("paragraphIndex", paragraphIndex);
        audit.recordPipeline(lastItem == null ? "no-last-item" : lastItem.key(),
            lastTooltip != null ? lastTooltip.original() : lastItem == null ? List.of() : List.of(Component.literal(lastItem.name())),
            "RETRANSLATION", reason, details);
    }
    private void translateItem(ItemSource item, boolean force) {
        requestedItems.remove(item.key());
        if (requestedItems.size() >= 1000) requestedItems.remove(requestedItems.iterator().next());
        requestedItems.add(item.key());
        if (audit != null) audit.forget(item.key());
        Set<String> submitted = new HashSet<>();
        for (int n = 0; n < item.paragraphs().size(); n++) {
            var paragraph = item.paragraphs().get(n); var block = item.blocks().get(n);
            String channel = item.channel(n);
            var dictionaryContext=item.context(n);
            var service = translator;
            if (!submitted.add(service.requestIdentity(paragraph,channel,dictionaryContext))) continue;
            String context = "scope=" + channel + "; tooltipId=" + TooltipAudit.tooltipId(item.key()) + "; paragraph=" + n
                + "; item=" + item.name() + "; lines=" + (block.start() + 1) + "-" + block.end() + "; original=" + block.text();
            var requestAudit = audit;
            if (!force && config.apiTranslation) service.retry(paragraph.encoded(), channel,dictionaryContext);
            var before = service.cacheInspection(paragraph.encoded(), channel,dictionaryContext);
            var local = service.inspectDictionary(paragraph, channel, "",true,dictionaryContext);
            int paragraphIndex = n;
            List<Component> diagnosticSource = lastTooltip != null && lastTooltip.source().key().equals(item.key())
                ? lastTooltip.original() : List.of(Component.literal(item.name()));
            String requestKey = item.key() + "|paragraph=" + n;
            if (requestAudit != null && config.dictionaryDiagnostics) requestAudit.recordPipeline(requestKey, diagnosticSource,
                "REQUEST_DISPATCH", force ? "FORCE_API_REQUEST" : local.accepted() ? "LOCAL_DICTIONARY" : before.status(),
                Map.of("tooltipId", TooltipAudit.tooltipId(item.key()), "paragraphId", TooltipAudit.tooltipId(item.key()) + ":" + n,
                    "channel", channel, "source", block.text(), "cacheState", before.status(), "dictionaryReason", local.reason(),
                    "apiTranslationEnabled", config.apiTranslation));
            var future = force ? service.retranslate(paragraph, context,dictionaryContext) : service.request(paragraph, context,dictionaryContext);
            future.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
                if (service != translator || requestAudit == null || requestAudit != audit || !config.dictionaryDiagnostics) return;
                var after = service.cacheInspection(paragraph.encoded(), channel,dictionaryContext);
                String resultReason = failure != null ? "REQUEST_EXCEPTION" : !force && local.accepted() ? "LOCAL_DICTIONARY_COMPLETE" : after.status();
                requestAudit.recordPipeline(requestKey, diagnosticSource, "REQUEST_COMPLETE", resultReason,
                    Map.of("tooltipId", TooltipAudit.tooltipId(item.key()), "paragraphId", TooltipAudit.tooltipId(item.key()) + ":" + paragraphIndex,
                        "channel", channel, "cacheState", after.status(), "error", after.error(), "hasResult", result != null,
                        "exceptionClass", failure == null ? "" : failure.getClass().getSimpleName()));
            }));
        }
    }
    public Config configuration() { return config; }
    Translator service() { return translator; }
    SharedLogExportService.State sharedLogExportState() { return sharedLogs.state(); }
    Path sharedLogFolder() { return FabricLoader.getInstance().getGameDir().resolve("skyblock-jp-reports"); }
    boolean exportSharedLogs(String draftSecret) {
        var runtime = configuration();
        var metadata = new SharedLogExporter.Metadata(installedVersion("skyblock_jp"), installedVersion("minecraft"),
            installedVersion("fabricloader"), System.getProperty("java.version", "unknown"),
            translator == null ? "" : translator.dictionaryStats().buildId(), runtime.apiTranslation,
            runtime.candidateDictionary, runtime.dictionaryAutoDisplay, runtime.dictionaryDiagnostics, runtime.dictionaryPlainFallback);
        var secrets = new ArrayList<String>();
        if (runtime.groqApiKey != null && !runtime.groqApiKey.isBlank()) secrets.add(runtime.groqApiKey);
        if (draftSecret != null && !draftSecret.isBlank() && !secrets.contains(draftSecret)) secrets.add(draftSecret);
        Path sourceFolder = folder, reportFolder = sharedLogFolder();
        return sharedLogs.start(() -> SharedLogExporter.export(sourceFolder, reportFolder, metadata, secrets));
    }
    private static String installedVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }
    void saveConfig(Config updated) throws Exception {
        Config.save(folder.resolve("config.json"), updated);
        reload();
        chats.refreshButtons();
    }
    void saveDictionaryConfig(boolean enabled, boolean autoDisplay) throws Exception {
        saveDictionaryConfig(enabled, autoDisplay, config.dictionaryDiagnostics);
    }
    void saveDictionaryConfig(boolean enabled, boolean autoDisplay, boolean diagnostics) throws Exception {
        saveDictionaryConfig(enabled,autoDisplay,diagnostics,config.dictionaryPlainFallback);
    }
    void saveDictionaryConfig(boolean enabled,boolean autoDisplay,boolean diagnostics,boolean plainFallback) throws Exception {
        var updated = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config), Config.class);
        updated.candidateDictionary = enabled;
        updated.dictionaryAutoDisplay = autoDisplay;
        updated.dictionaryDiagnostics = diagnostics;
        updated.dictionaryPlainFallback = plainFallback;
        saveConfig(updated);
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
        hud.invalidateLayout();
        chats.refreshButtons();
        LOG.info("SkyBlock JP HUD settings saved: x={}, y={}, width={}, lines={}, seconds={}, opacity={}",
            config.hudX, config.hudY, config.hudWidth, config.hudLines, config.hudSeconds, config.hudOpacity);
    }
    void saveUpdateConfig(Config draft) throws Exception {
        var updated = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config), Config.class);
        updated.autoUpdate = draft.autoUpdate; updated.updateRepository = draft.updateRepository;
        if (!updated.autoUpdate || !Objects.equals(config.updateRepository, updated.updateRepository)) updater.cancelPending();
        Config.save(folder.resolve("config.json"), updated); config = updated;
    }
    public boolean scrollHud(double x, double y, double amount) {
        return active() && config.translationHud && hud.scroll(x, y, amount, config);
    }
    private void openSettings() {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new SettingsScreen(mc.gui.screen(), this));
    }
    private static boolean itemDisplayScreen(Screen screen) {
        return screen != null && !(screen instanceof ChatScreen)
            && !screen.getClass().getPackageName().equals(SkyBlockJp.class.getPackageName());
    }
    private static boolean itemInputScreen(Screen screen) {
        return itemDisplayScreen(screen) && !(screen.getFocused() instanceof EditBox);
    }
    private static TooltipContext.Decision tooltipClassification(Minecraft mc, ItemStack stack, List<String> lines) {
        Screen screen = mc.gui.screen();
        var location = TooltipContext.Location.UNKNOWN;
        boolean slotMatches = false;
        int slotIndex = -1, containerSize = -1;
        if (screen instanceof AbstractContainerScreen<?> container) {
            containerSize = container.getMenu().slots.size();
            if (screen instanceof ContainerScreenAccess access) {
                var slot = access.skyblockjp$hoveredSlot();
                if (slot != null) {
                    slotIndex = slot.index;
                    slotMatches = ItemStack.isSameItemSameComponents(slot.getItem(), stack);
                    if (slotMatches) location = slot.container instanceof net.minecraft.world.entity.player.Inventory
                        ? TooltipContext.Location.PLAYER : TooltipContext.Location.CONTAINER;
                }
            }
        }
        String id = "";
        var custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom != null) {
            var tag = custom.copyTag();
            id = tag.getStringOr("id", tag.getCompoundOrEmpty("ExtraAttributes").getStringOr("id", ""));
        }
        var evidence = new TooltipContext.Evidence(screen == null ? "" : screen.getClass().getName(),
            screen == null ? "" : screen.getTitle().getString(), location, slotMatches, id,
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), slotIndex, containerSize);
        return TooltipContext.analyze(evidence, lines);
    }
    private String tooltipGate(Minecraft mc) {
        if (!config.enabled) return "MOD_DISABLED";
        if (!active()) return "SERVER_SCOPE_REJECTED";
        if (!config.loreTranslation) return "ITEM_TRANSLATION_DISABLED";
        if (!itemDisplayScreen(mc.gui.screen())) return "SCREEN_NOT_SUPPORTED";
        if (InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT)) return "SHIFT_ORIGINAL";
        return "";
    }
    private static Map<String,Object> screenDetails(Minecraft mc) {
        Screen screen = mc.gui.screen();
        return Map.of("screenClass", screen == null ? "" : screen.getClass().getName(),
            "screenTitle", screen == null ? "" : screen.getTitle().getString(),
            "textInputFocused", screen != null && screen.getFocused() instanceof EditBox);
    }
    /** Handle real press/release events, including taps shorter than a render frame. */
    public boolean handleKey(long window, int action, KeyEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (config == null || mc.getWindow() == null || window != mc.getWindow().handle()) return false;
        Screen screen = mc.gui.screen();
        if (event.key() == GLFW.GLFW_KEY_F8 && !(screen instanceof SettingsScreen)) {
            if (action == GLFW.GLFW_PRESS) { settingsPending = true; settingsOrigin = screen; }
            return action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT || action == GLFW.GLFW_RELEASE;
        }
        if (event.key() != config.translateKey) return false;
        if (action == GLFW.GLFW_RELEASE) { tooltipKeyInput.release(); return false; }
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT || screen instanceof ChatScreen) return false;
        if (action == GLFW.GLFW_REPEAT) return tooltipKeyInput.repeat() != TooltipKeyInput.Press.NONE;
        boolean eligible = active() && config.loreTranslation && itemInputScreen(screen) && !event.hasShiftDown();
        var press = tooltipKeyInput.press(screen, lastItem == null ? null : lastItem.key(),
            mc.mouseHandler.getScaledXPos(mc.getWindow()), mc.mouseHandler.getScaledYPos(mc.getWindow()), System.nanoTime(), eligible);
        if (action == GLFW.GLFW_PRESS && audit != null && config.dictionaryDiagnostics) {
            var details = new LinkedHashMap<String,Object>(screenDetails(mc));
            details.put("forceApi", event.hasControlDown()); details.put("pressResult", press.name());
            String reason = press == TooltipKeyInput.Press.FIRST ? "TRANSLATION_REQUESTED"
                : !active() ? "MOD_OR_SERVER_DISABLED" : !config.loreTranslation ? "ITEM_TRANSLATION_DISABLED"
                : event.hasShiftDown() ? "SHIFT_ORIGINAL" : screen != null && screen.getFocused() instanceof EditBox ? "TEXT_INPUT_FOCUSED"
                : lastTooltip != null && lastTooltip.source().blocks().isEmpty() ? "NO_TRANSLATABLE_PARAGRAPHS" : tooltipKeyInput.reason();
            audit.recordPipeline(lastTooltip == null ? "no-hover|" + details.get("screenClass") : lastTooltip.source().key(),
                lastTooltip == null ? List.of() : lastTooltip.original(), "MANUAL_INPUT", reason, details);
        }
        if (press == TooltipKeyInput.Press.FIRST) {
            if (event.hasControlDown()) retranslateLastItem();
            else translateItem(lastItem, false);
        }
        return press != TooltipKeyInput.Press.NONE;
    }
    public void onInitializeClient() {
        INSTANCE = this; chats = new ChatTranslations(this);
        folder = FabricLoader.getInstance().getConfigDir().resolve("skyblock_jp");
        reload();
        updater.check(config, false);
        net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
            .registerReloadListener(net.minecraft.resources.Identifier.fromNamespaceAndPath("skyblock_jp", "hud_layout"),
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> {
                    // Resource application can replace glyph metrics without replacing the Font object.
                    Minecraft.getInstance().execute(hud::invalidateLayout);
                });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // A previous frame's tooltip must not survive into an empty/new screen frame.
            ScreenEvents.beforeExtract(screen).register((current, graphics, x, y, delta) -> { tooltipKeyInput.clearHover(); lastTooltip = null; });
            ScreenEvents.remove(screen).register(current -> { tooltipKeyInput.reset(); displayedTooltips.clear(); lastTooltip = null; });
        });
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(
            net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CHAT,
            net.minecraft.resources.Identifier.fromNamespaceAndPath("skyblock_jp", "translation"),
            (graphics, delta) -> { if (active() && config.translationHud && !(Minecraft.getInstance().gui.screen() instanceof HudPositionScreen)) hud.render(graphics, config, false); });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.getWindow() == null) return;
            chats.syncConnection();
            if (itemConnection != mc.getConnection()) {
                itemConnection = mc.getConnection(); lastItem = null; lastTooltip = null; tooltipKeyInput.reset(); displayedTooltips.clear(); tooltipParses.clear();
            }
            if (!config.initialSetupShown && config.enabled && config.apiTranslation && mc.gui.overlay() == null
                && mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen) {
                config.initialSetupShown = true;
                try { Config.save(folder.resolve("config.json"), config); }
                catch (Exception ex) { LOG.warn("SkyBlock JP: initial setup state could not save ({})", ex.getClass().getSimpleName()); }
                mc.gui.setScreen(new ApiHelpScreen(new SettingsScreen(mc.gui.screen(), this)));
                return;
            }
            if (settingsPending) {
                settingsPending = false;
                if (mc.gui.screen() == settingsOrigin && !(mc.gui.screen() instanceof SettingsScreen)) openSettings();
                settingsOrigin = null;
            }
        });
        ItemTooltipCallback.EVENT.register((stack, context, flags, lines) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!mc.isSameThread() || config == null || mc.getWindow() == null) return;
            List<Component> original = new ArrayList<>(TooltipObservation.snapshot(lines));
            if (original.isEmpty()) return;
            List<String> sourceLines = original.stream().map(Component::getString).toList();
            String gate = tooltipGate(mc);
            if (!gate.isEmpty()) {
                if (config.dictionaryDiagnostics) {
                    var source = new ItemSource("unclassified|" + String.join("\n", sourceLines), sourceLines.getFirst(),
                        List.of(), List.of(), "unknown", List.of(),List.of(),Map.of());
                    var batch = new ItemTooltipTranslation.Result(original, List.of(), ItemTooltipTranslation.Mode.ORIGINAL, 0, 0, 0);
                    displayedTooltips.put(mc.gui.screen(), lines, new PendingTooltip(source, original, batch, List.of(), config.wrapWidth,
                        mc.gui.screen() == null ? "" : mc.gui.screen().getTitle().getString(), null, null, gate));
                }
                return;
            }
            var classification = tooltipClassification(mc, stack, sourceLines);
            var parseKey = new ParseKey(classification.kind(),classification.reason(),sourceLines);
            var analysis = tooltipParses.computeIfAbsent(parseKey, ignored -> LoreParser.analyze(sourceLines, classification, translator::knownItemLabel));
            String channel = classification.channel();
            List<String> channels = analysis.paragraphs().stream().map(LoreParser.Paragraph::channel).toList();
            List<LoreParser.Block> blocks = analysis.paragraphs().stream().map(LoreParser.Paragraph::block).toList();
            List<StyledParagraph> paragraphs = blocks.stream().map(b -> new StyledParagraph(original.subList(b.start(), b.end()))).toList();
            String itemKey = TooltipTranslationKey.of(classification, original.getFirst().getString(), channels, paragraphs);
            boolean manualRequested = requestedItems.contains(itemKey);
            var plan=TooltipPlan.prepare(original,analysis,classification,translator,manualRequested);
            analysis=plan.analysis();channels=plan.channels();blocks=plan.blocks();paragraphs=plan.paragraphs();
            String keyName = InputConstants.Type.KEYSYM.getOrCreate(config.translateKey).getDisplayName().getString();
            int wrapWidth = Math.min(config.wrapWidth, Math.max(120, mc.getWindow().getGuiScaledWidth() - 50));
            Map<StyledParagraph, Translator.DictionaryDecision> decisions = new IdentityHashMap<>();
            Map<StyledParagraph, String> paragraphChannels = new IdentityHashMap<>();
            Map<StyledParagraph, Map<String,Object>> paragraphContexts = new IdentityHashMap<>();
            for (int n = 0; n < paragraphs.size(); n++) {
                var paragraph = paragraphs.get(n); paragraphChannels.put(paragraph, channels.get(n));
                paragraphContexts.put(paragraph,plan.contexts().get(n));
                decisions.put(paragraph,plan.decisions().get(n));
            }
            var batch = ItemTooltipTranslation.prepare(original, blocks, paragraphs, config, manualRequested,
                p -> decisions.get(p).encoded(), p -> translator.cached(p, paragraphChannels.get(p),paragraphContexts.get(p)),
                component -> wrap(component, wrapWidth), p -> translator.pending(p.encoded(), paragraphChannels.get(p),paragraphContexts.get(p)));
            if (batch.mode() != ItemTooltipTranslation.Mode.ORIGINAL) {
                // This callback mutates the component list before Minecraft draws its first tooltip frame.
                lines.clear(); lines.addAll(batch.lines());
            }
            var source = new ItemSource(itemKey,original.getFirst().getString(),blocks,paragraphs,channel,channels,plan.contexts(),classification.evidence());
            var observation = new PendingTooltip(source, original, batch,
                paragraphs.stream().map(decisions::get).toList(),wrapWidth,mc.gui.screen().getTitle().getString(),classification,analysis,"");
            if (blocks.isEmpty()) { displayedTooltips.put(mc.gui.screen(), lines, observation, itemKey); return; }
            String hint = switch (batch.mode()) {
                case ORIGINAL -> "JP: " + keyName + " で説明を翻訳 · F8 設定";
                case DICTIONARY -> batch.dictionaryMatches() == batch.total()
                    ? "JP: 辞書を適用 ✓ · Ctrl+" + keyName + " 再翻訳 · Shift 原文"
                    : "JP: 辞書 " + batch.dictionaryMatches() + "/" + batch.total() + " · " + keyName + " で残りを翻訳 · Shift 原文";
                case PENDING -> "JP: 翻訳中 " + batch.complete() + "/" + batch.total()
                    + (batch.dictionaryMatches() > 0 ? " · 未完了の説明は原文" : " · 原文を表示中");
                case FULL -> "JP: 表示更新 ✓ · Ctrl+" + keyName + " 再翻訳 · Shift 原文";
            };
            if (!config.apiTranslation) hint = !config.candidateDictionary ? "JP: 外部API・同梱辞書ともOFF · F8 設定"
                : !config.dictionaryAutoDisplay && !manualRequested ? "JP: " + keyName + " で辞書照合 · API翻訳OFF · F8 設定"
                : "JP: 辞書 " + batch.dictionaryMatches() + "/" + batch.total() + " · API翻訳OFF · Shift 原文";
            lines.add(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
            displayedTooltips.put(mc.gui.screen(), lines, observation, itemKey);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !active() || !config.npcTranslation) return;
            String text = LoreParser.clean(message.getString());
            if (NpcTranslation.automaticMessage(text)) chats.npc(message);
            else chats.system(message);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(ClientCommands.literal("sbjp")
            .executes(c -> { Minecraft.getInstance().schedule(this::openSettings); return 1; })
            .then(ClientCommands.literal("translate").then(ClientCommands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes(c -> { chats.request(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id")); return 1; })))
            .then(ClientCommands.literal("reload").executes(c -> { reload(); c.getSource().sendFeedback(Component.literal("SkyBlock JP: 設定を再読み込みしました。")); return 1; }))
            .then(ClientCommands.literal("toggle").executes(c -> { config.enabled = !config.enabled; c.getSource().sendFeedback(Component.literal("SkyBlock JP: " + (config.enabled ? "ON" : "OFF"))); return 1; }))));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { sharedLogs.close(); hud.close(); translator.close(); if (audit != null) audit.close(); });
        LOG.info("SkyBlock JP {} initialized; hover chat translation; F8 settings.",ModVersion.CURRENT);
    }
    /** Invoked from the actual component-tooltip display API, after any search/index probes. */
    public void tooltipDisplayed(List<Component> lines) {
        Minecraft mc = Minecraft.getInstance();
        tooltipDisplayed(lines, (int)mc.mouseHandler.getScaledXPos(mc.getWindow()), (int)mc.mouseHandler.getScaledYPos(mc.getWindow()));
    }
    public void tooltipDisplayed(List<Component> lines, int displayX, int displayY) {
        tooltipDisplayed(lines, displayX, displayY, lines);
    }
    public void tooltipDisplayed(List<Component> lines, int displayX, int displayY, List<Component> identitySource) {
        Minecraft mc=Minecraft.getInstance();
        if(config==null||mc.getWindow()==null||!mc.isSameThread())return;
        var pending=displayedTooltips.displayed(mc.gui.screen(),lines,identitySource);
        if(pending==null) {
            if(audit!=null&&config.dictionaryDiagnostics&&active()&&itemDisplayScreen(mc.gui.screen())&&!lines.isEmpty())
                audit.recordPipeline("untracked|"+mc.gui.screen().getClass().getName()+"|"+lines.stream().map(Component::getString).toList(),
                    lines,"OBSERVATION",displayedTooltips.reason(),screenDetails(mc));
            return;
        }
        lastTooltip=pending;
        String gate=pending.gateReason().isEmpty()?tooltipGate(mc):pending.gateReason();
        if(!gate.isEmpty()) {
            if(audit!=null&&config.dictionaryDiagnostics)audit.recordPipeline(pending.source().key(),pending.original(),"ELIGIBILITY",gate,screenDetails(mc));
            return;
        }
        var source=pending.source();var batch=pending.batch();
        lastItem=source.blocks().isEmpty()?null:source;
        if(!source.blocks().isEmpty()) {
            TooltipKeyInput.Bounds bounds = null;
            if (pending.classification() != null && mc.gui.screen() instanceof ContainerScreenAccess access) {
                var slot = access.skyblockjp$hoveredSlot();
                if (slot != null && Boolean.TRUE.equals(pending.classification().evidence().get("slotMatches"))
                    && Objects.equals(slot.index, pending.classification().evidence().get("slotIndex"))) {
                    int x = access.skyblockjp$leftPos() + slot.x, y = access.skyblockjp$topPos() + slot.y;
                    bounds = new TooltipKeyInput.Bounds(x, y, x + 16, y + 16);
                }
            }
            tooltipKeyInput.observe(mc.gui.screen(),source.key(),mc.mouseHandler.getScaledXPos(mc.getWindow()),mc.mouseHandler.getScaledYPos(mc.getWindow()),System.nanoTime(),bounds);
            if (audit != null && config.dictionaryDiagnostics) {
                var observed=new LinkedHashMap<String,Object>(screenDetails(mc));
                observed.put("displayX",displayX);observed.put("displayY",displayY);
                observed.put("slotBoundsAvailable",bounds!=null);observed.put("finalDeferredTooltip",true);
                observed.put("dictionaryContext",source.dictionaryContext());
                if(pending.classification()!=null)observed.put("tooltipClassification",Map.of("kind",pending.classification().kind().name(),
                    "reason",pending.classification().reason(),"evidence",pending.classification().evidence()));
                audit.recordPipeline(source.key(),pending.original(),"OBSERVATION",displayedTooltips.reason(),observed);
            }
        }
        if(audit==null)return;
        if(config.dictionaryDiagnostics) {
            var states=new ArrayList<Translator.CacheInspection>();
            for(int i=0;i<source.paragraphs().size();i++)states.add(translator.cacheInspection(source.paragraphs().get(i).encoded(),source.channel(i),source.context(i)));
            audit.recordDetailed(source.key(),pending.original(),batch.lines(),batch.paragraphs(),pending.width(),config.provider,config.groqModel,source.channel(),pending.screenTitle(),source.blocks(),pending.decisions(),batch.mode().name(),
                config.candidateDictionary&&(config.dictionaryAutoDisplay||requestedItems.contains(source.key())&&!config.apiTranslation)
                    ||requestedItems.contains(source.key())&&batch.mode()==ItemTooltipTranslation.Mode.FULL,
                pending.classification(),pending.analysis(),states,requestedItems.contains(source.key()),config.candidateDictionary&&config.dictionaryAutoDisplay);
        }
        else if(batch.mode()!=ItemTooltipTranslation.Mode.ORIGINAL)audit.record(source.key(),pending.original(),batch.lines(),batch.paragraphs(),pending.width(),batch.mode()==ItemTooltipTranslation.Mode.FULL?"MIXED":"DICTIONARY",config.groqModel);
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
            lastItem = null; lastTooltip = null;
            tooltipKeyInput.reset();
            displayedTooltips.clear();
            tooltipParses.clear();
            translator = new Translator(config, folder.resolve("cache.json"), LOG);
            LOG.info("SkyBlock JP translation policy: apiTranslation={}, apiCacheDisplay={}, dictionary={}, dictionaryAutoDisplay={}",
                config.apiTranslation, config.apiTranslation, config.candidateDictionary, config.dictionaryAutoDisplay);
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

