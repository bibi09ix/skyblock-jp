package local.skyblockjp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

final class HudPositionScreen extends Screen {
    private final Screen parent;
    private final Config draft;
    private final TranslationHud hud;
    private final SkyBlockJp mod;
    private final int beforeWidth, beforeLines;
    private boolean resizing;
    private String status = "枠を移動・右下の角でサイズ変更";
    private final double beforeX, beforeY;
    private final double beforeTextScale;
    private boolean dragging;
    private double grabX, grabY;
    private int resizeX, resizeY;
    private TextScaleSlider textScaleSlider;

    HudPositionScreen(Screen parent, Config draft, SkyBlockJp mod) {
        super(Component.literal("翻訳HUDの位置"));
        this.parent = parent;
        this.draft = draft;
        this.hud = mod.hud();
        this.mod = mod;
        beforeWidth = draft.hudWidth;
        beforeLines = draft.hudLines;
        beforeX = draft.hudX;
        beforeY = draft.hudY;
        beforeTextScale = draft.hudTextScale;
    }

    @Override
    protected void init() {
        addRenderableWidget(
                Button.builder(
                                Component.literal("保存して戻る"),
                                b -> {
                                    try {
                                        mod.saveHudConfig(draft);
                                        minecraft.gui.setScreen(parent);
                                    } catch (Exception ex) {
                                        status = "保存できませんでした。";
                                    }
                                })
                        .bounds(width / 2 - 155, 8, 100, 20)
                        .build());
        addRenderableWidget(
                Button.builder(
                                Component.literal("初期値"),
                                b -> {
                                    draft.resetHudLayout();
                                    textScaleSlider.syncFromConfig();
                                })
                        .bounds(width / 2 - 50, 8, 100, 20)
                        .build());
        addRenderableWidget(
                Button.builder(Component.literal("キャンセル"), b -> onClose())
                        .bounds(width / 2 + 55, 8, 100, 20)
                        .build());
        textScaleSlider = addRenderableWidget(new TextScaleSlider(width / 2 - 155, 34, 310, draft));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (super.mouseClicked(e, doubleClick)) return true;
        if (e.y() < 74) return false;
        int w = TranslationHud.panelWidth(draft, width),
                h = TranslationHud.panelHeight(draft, height);
        int x = TranslationHud.x(draft, width, w), y = TranslationHud.y(draft, height, h);
        if (e.button() == 0 && e.x() >= x && e.x() <= x + w && e.y() >= y && e.y() <= y + h) {
            resizing = e.x() >= x + w - 16 && e.y() >= y + h - 16;
            resizeX = x;
            resizeY = y;
            dragging = !resizing;
            setDragging(true);
            grabX = e.x() - x;
            grabY = e.y() - y;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (resizing) {
            int x = resizeX, y = resizeY;
            draft.hudWidth =
                    (int) Math.clamp(e.x() - x, 160, Math.min(600, Math.max(160, width - x)));
            double scale = TranslationHud.textScale(draft);
            draft.hudLines =
                    (int)
                            Math.clamp(
                                    Math.round(((e.y() - y) / scale - 22) / 11),
                                    3,
                                    Math.max(
                                            3,
                                            Math.min(
                                                    20, (int) (((height - y) / scale - 22) / 11))));
            draft.hudX =
                    Math.clamp(
                            (double) x
                                    / Math.max(1, width - TranslationHud.panelWidth(draft, width)),
                            0,
                            1);
            draft.hudY =
                    Math.clamp(
                            (double) y
                                    / Math.max(
                                            1, height - TranslationHud.panelHeight(draft, height)),
                            0,
                            1);
            return true;
        }
        if (dragging) {
            draft.hudX =
                    Math.clamp(
                            (e.x() - grabX)
                                    / Math.max(1, width - TranslationHud.panelWidth(draft, width)),
                            0,
                            1);
            draft.hudY =
                    Math.clamp(
                            (e.y() - grabY)
                                    / Math.max(
                                            1, height - TranslationHud.panelHeight(draft, height)),
                            0,
                            1);
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        dragging = false;
        resizing = false;
        setDragging(false);
        return super.mouseReleased(e);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hud.render(g, draft, true);
        int w = TranslationHud.panelWidth(draft, width),
                h = TranslationHud.panelHeight(draft, height);
        int x = TranslationHud.x(draft, width, w), y = TranslationHud.y(draft, height, h);
        g.fill(x + w - 12, y + h - 12, x + w, y + h, 0xFF77DDFF);
        g.fill(x, y, x + w, y + 1, 0xFF77DDFF);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF77DDFF);
        g.fill(x, y, x + 1, y + h, 0xFF77DDFF);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF77DDFF);
        // Keep editor controls readable even when enlarged HUD text reaches the top.
        g.fill(0, 0, width, 74, 0xCC101820);
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, status + "（" + w + " × " + h + "）", width / 2, 60, 0xFFFFFF88);
    }

    @Override
    public void onClose() {
        draft.hudX = beforeX;
        draft.hudY = beforeY;
        draft.hudWidth = beforeWidth;
        draft.hudLines = beforeLines;
        draft.hudTextScale = beforeTextScale;
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
