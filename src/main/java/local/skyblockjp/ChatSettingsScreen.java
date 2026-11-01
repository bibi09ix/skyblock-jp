package local.skyblockjp;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

final class ChatSettingsScreen extends Screen {
    private final Screen parent;
    private final Config draft;
    private final SkyBlockJp mod;
    private int left, top, panel;
    private String status = "変更は保存ボタンで反映されます。";
    ChatSettingsScreen(Screen parent, Config draft, SkyBlockJp mod) {
        super(Component.literal("チャット・翻訳HUD")); this.parent = parent; this.draft = draft; this.mod = mod;
    }
    private Button row(String text, int offset, Button.OnPress action) {
        return addRenderableWidget(Button.builder(Component.literal(text), action).bounds(left, top + offset, panel, 20).build());
    }
    @Override protected void init() {
        panel = Math.min(400, width - 24); left = (width - panel) / 2; top = Math.max(24, (height - 224) / 2);
        int half = (panel - 6) / 2;
        row(destination(), 0, b -> { draft.translationHud = !draft.translationHud; b.setMessage(Component.literal(destination())); });
        row("HUDの位置・大きさを調整", 22, b -> minecraft.gui.setScreen(new HudPositionScreen(this, draft, mod)));
        row(buttons(), 44, b -> { draft.chatButtons = !draft.chatButtons; b.setMessage(Component.literal(buttons())); });
        addRenderableWidget(Button.builder(Component.literal("HUDの幅: " + draft.hudWidth), b -> { draft.hudWidth = draft.hudWidth >= 480 ? 200 : draft.hudWidth + 40; b.setMessage(Component.literal("HUDの幅: " + draft.hudWidth)); }).bounds(left, top + 88, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("最大行数: " + draft.hudLines), b -> { draft.hudLines = draft.hudLines >= 16 ? 4 : draft.hudLines + 2; b.setMessage(Component.literal("最大行数: " + draft.hudLines)); }).bounds(left + half + 6, top + 88, half, 20).build());
        addRenderableWidget(new TextScaleSlider(left, top + 110, panel, draft));
        row(duration(), 132, b -> { draft.hudSeconds = draft.hudSeconds == 0 ? 15 : draft.hudSeconds >= 120 ? 0 : draft.hudSeconds + 15; b.setMessage(Component.literal(duration())); });
        addRenderableWidget(new OpacitySlider(left, top + 156, panel, draft));
        row("保存して戻る", 180, b -> { try { mod.saveHudConfig(draft); onClose(); } catch (Exception e) { status = "保存できませんでした。"; } });
    }
    private String duration() { return "HUDの表示時間: " + (draft.hudSeconds == 0 ? "消えない" : draft.hudSeconds + "秒"); }
    private String destination() { return "別HUD表示: " + (draft.translationHud ? "ON" : "OFF"); }
    private String buttons() { return "チャットのホバー翻訳: " + (draft.chatButtons ? "ON" : "OFF"); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, title, width / 2, top - 20, 0xFFFFFFFF);
        String key = com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(draft.translateKey).getDisplayName().getString();
        g.text(font, "テキストにカーソルを合わせて " + key + " で翻訳", left + 4, top + 72, 0xFFBBBBBB);
        g.text(font, status, left, top + 204, 0xFFFFFF88);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
