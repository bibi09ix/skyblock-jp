package local.skyblockjp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.util.*;

final class TranslationHud {
    private record Entry(HudRows.Message message, long time) {}
    private final Deque<Entry> entries = new ArrayDeque<>();
    private String status = "";
    private long statusAt;
    private int historyOffset, totalLines;
    private boolean wasChat;
    private final HudLayoutCache layoutCache = new HudLayoutCache();
    private HudViewport viewport = HudViewport.EMPTY;
    private java.nio.file.Path auditPath;
    private String auditSecret = "", lastSnapshot = "";
    private final java.util.concurrent.ThreadPoolExecutor auditWorker = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
        java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64), r -> {
            var thread = new Thread(r, "SkyBlock-JP-HUD-Audit"); thread.setDaemon(true); return thread;
        }, new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    void configureAudit(java.nio.file.Path path, String secret) { auditPath = path; auditSecret = secret; lastSnapshot = ""; }
    void close() { auditWorker.shutdown(); }
    void clear() { entries.clear(); status = ""; historyOffset = 0; totalLines = 0; wasChat = false; invalidateLayout(); }
    void invalidateLayout() { layoutCache.invalidate(); viewport = HudViewport.EMPTY; }
    void status(String text) { status = text; statusAt = System.currentTimeMillis(); }
    void add(String speaker, Component text) {
        entries.addLast(new Entry(HudRows.message(speaker, text), System.currentTimeMillis()));
        while (entries.size() > 200) entries.removeFirst();
        status = "";
    }
    static int x(Config c, int screen, int panel) { return (int)Math.round(c.hudX * Math.max(0, screen - panel)); }
    static int y(Config c, int screen, int panel) { return (int)Math.round(c.hudY * Math.max(0, screen - panel)); }
    static int panelWidth(Config c, int screen) { return Math.min(c.hudWidth, Math.max(40, screen - 8)); }
    static double textScale(Config c) { return Double.isFinite(c.hudTextScale) ? Math.clamp(c.hudTextScale, .75, 2.5) : 1; }
    static int logicalWidth(Config c, int width) { return Math.max(1, (int)Math.floor(width / textScale(c))); }
    static int lineLimit(Config c, int height) { return Math.min(c.hudLines, Math.max(0, ((int)Math.floor(height / textScale(c)) - 22) / 11)); }
    static int panelHeight(Config c) { return (int)Math.ceil((22 + c.hudLines * 11) * textScale(c)); }
    static int panelHeight(Config c, int screen) { return Math.min(panelHeight(c), Math.max(55, screen - 8)); }
    static boolean visible(long now, long time, Config c, boolean chatOpen) { return chatOpen || c.hudSeconds == 0 || now - time <= c.hudSeconds * 1000L; }
    boolean scroll(double mx, double my, double amount, Config c) {
        if (amount == 0 || !viewport.scrollableAt(mx, my)) return false;
        historyOffset = viewport.scroll(historyOffset, amount);
        return true;
    }
    void render(GuiGraphicsExtractor g, Config c, boolean preview) {
        var mc = Minecraft.getInstance();
        int w = panelWidth(c, mc.getWindow().getGuiScaledWidth()), h = panelHeight(c, mc.getWindow().getGuiScaledHeight());
        int x = x(c, mc.getWindow().getGuiScaledWidth(), w), y = y(c, mc.getWindow().getGuiScaledHeight(), h);
        boolean chatOpen = mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen;
        viewport = HudViewport.EMPTY;
        if (!chatOpen || !wasChat) historyOffset = 0;
        wasChat = chatOpen;
        var messages = new ArrayList<HudRows.Message>();
        long now = System.currentTimeMillis();
        if (preview) {
            messages.add(HudRows.message("Torrhus Canyon", Component.literal("自分で確かめたいなら、Moonglade MarshにあるLaunch Padから行けるよ。")));
            messages.add(HudRows.message("Lumber Jack", Component.literal("斧にEfficiency Vをエンチャントした？")));
        } else for (var entry : entries) {
            if (visible(now, entry.time(), c, chatOpen)) messages.add(entry.message());
        }
        if (!preview && !status.isBlank() && now - statusAt < 15000)
            messages.add(HudRows.message("状態", Component.literal(status).withStyle(ChatFormatting.YELLOW)));
        if (!preview && messages.isEmpty()) { totalLines = 0; historyOffset = 0; return; }
        double scale = textScale(c);
        int logicalW = logicalWidth(c, w);
        var layout = layoutCache.layout(messages, logicalW, mc.font, mc.font::width);
        var lines = layout.rows();
        totalLines = lines.size();
        int limit = lineLimit(c, h);
        historyOffset = Math.clamp(historyOffset, 0, Math.max(0, totalLines - limit));
        int end = lines.size() - (chatOpen && !preview ? historyOffset : 0);
        int start = Math.max(0, end - limit);
        int drawn = end - start;
        int drawnHeight = (int)Math.ceil((22 + drawn * 11) * scale);
        if (!preview && chatOpen && drawn > 0) viewport = new HudViewport(x, y, w, Math.min(h, drawnHeight), limit, totalLines);
        g.fill(x, y, x + w, y + (preview ? h : Math.min(h, drawnHeight)), ((int)Math.round(c.hudOpacity * 255) << 24) | 0x101820);
        var displayed = HudRows.window(layout, start, end, mc.font::width);
        g.enableScissor(x, y, x + w, y + h);
        g.pose().pushMatrix();
        try {
        g.pose().translate((float)x, (float)y).scale((float)scale);
        g.text(mc.font, mc.font.plainSubstrByWidth(chatOpen && !preview ? "翻訳履歴（枠内でスクロール）" : "翻訳", Math.max(1, logicalW - 12)), 6, 5, 0xFF77DDFF);
        int textX = 16 + layout.nameWidth();
        g.fill(textX - 5, 19, textX - 4, 19 + drawn * 11, 0x6077DDFF);
        for (int i = 0; i < displayed.size(); i++) {
            var row = displayed.get(i);
            g.text(mc.font, row.name(), 6, 19 + i * 11, 0xFF77DDFF);
            g.text(mc.font, row.text(), textX, 19 + i * 11, 0xFFFFFFFF);
        }
        } finally { g.pose().popMatrix(); g.disableScissor(); }
        if (!preview && auditPath != null) {
            var displayLines = displayed.stream().map(row -> row.name().getString() + " │ " + row.text().getString()).toList();
            var record = new LinkedHashMap<String, Object>(Map.<String, Object>of("version", ModVersion.CURRENT, "x", x, "y", y, "width", w,
                "height", drawnHeight, "opacity", c.hudOpacity, "chatOpen", chatOpen, "historyOffset", historyOffset,
                "displayLines", displayLines, "displayText", String.join("\n", displayLines)));
            record.put("nameWidth", layout.nameWidth()); record.put("textWidth", layout.textWidth());
            record.put("textScale", scale);
            record.put("displayRows", displayed.stream().map(row -> Map.of("name", row.name().getString(), "speaker", row.speaker().getString(), "text", row.text().getString())).toList());
            String snapshot = record.toString();
            if (!snapshot.equals(lastSnapshot)) {
                lastSnapshot = snapshot; var path = auditPath; var secret = auditSecret;
                auditWorker.execute(() -> { try { TranslationDiagnostics.append(path, record, secret); }
                    catch (Exception e) { org.slf4j.LoggerFactory.getLogger("skyblock_jp").warn("SkyBlock JP: HUD audit failed ({})", e.getClass().getSimpleName()); } });
            }
        }
    }
}
