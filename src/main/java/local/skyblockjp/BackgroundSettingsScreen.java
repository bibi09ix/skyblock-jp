package local.skyblockjp;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

final class BackgroundSettingsScreen extends Screen {
    private final Screen parent;
    private final Config draft;
    private final SkyBlockJp mod;
    private int left, top, panel;
    private String status = "";
    BackgroundSettingsScreen(Screen parent, Config draft, SkyBlockJp mod) {
        super(Component.literal("バックグラウンド翻訳")); this.parent = parent; this.draft = draft; this.mod = mod;
    }
    @Override protected void init() {
        panel = Math.min(400, width - 24); left = (width - panel) / 2; top = Math.max(28, (height - 180) / 2);
        addRenderableWidget(Button.builder(Component.literal(enabled()), b -> { draft.backgroundTranslation = !draft.backgroundTranslation; b.setMessage(Component.literal(enabled())); }).bounds(left, top, panel, 20).build());
        addRenderableWidget(Button.builder(Component.literal(idle()), b -> { draft.backgroundIdleSeconds = draft.backgroundIdleSeconds >= 600 ? 60 : draft.backgroundIdleSeconds + 60; b.setMessage(Component.literal(idle())); }).bounds(left, top + 24, panel, 20).build());
        addRenderableWidget(Button.builder(Component.literal(interval()), b -> { draft.backgroundIntervalSeconds = draft.backgroundIntervalSeconds >= 900 ? 120 : draft.backgroundIntervalSeconds + 60; b.setMessage(Component.literal(interval())); }).bounds(left, top + 48, panel, 20).build());
        addRenderableWidget(Button.builder(Component.literal("保存して戻る"), b -> { try { mod.saveBackgroundConfig(draft); onClose(); } catch (Exception e) { status = "保存できませんでした"; } }).bounds(left, top + 140, panel, 20).build());
    }
    private String enabled() { return "アイテムの事前翻訳: " + (draft.backgroundTranslation ? "ON" : "OFF"); }
    private String idle() { return "使われていない時間: " + draft.backgroundIdleSeconds + "秒"; }
    private String interval() { return "1段落ごとの間隔: " + draft.backgroundIntervalSeconds + "秒"; }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        g.centeredText(font, title, width / 2, top - 20, 0xFFFFFFFF);
        g.textWithWordWrap(font, Component.literal("最近見た最大20アイテムを、新しい順に事前翻訳します。手動・NPC翻訳中は待機。API制限時は15分休止します。APIの利用枠を消費します。"), left, top + 76, panel, 0xFFCCCCCC);
        g.text(font, status, left, top + 164, 0xFFFFFF88);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
