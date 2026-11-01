package local.skyblockjp;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Native Minecraft settings, no extra GUI library required. */
public final class SettingsScreen extends Screen {
    private final Screen parent;
    private final SkyBlockJp mod;
    private final Config draft;
    private EditBox model, key;
    private boolean capturing;
    private Button bindButton, connectionTestButton, retranslateButton;
    private String status = "";
    private int left, top, panelWidth;
    public SettingsScreen(Screen parent, SkyBlockJp mod) {
        super(Component.literal("SkyBlock JP 設定"));
        this.parent = parent; this.mod = mod;
        draft = new Gson().fromJson(new Gson().toJson(mod.configuration()), Config.class);
    }
    private String on(boolean value) { return value ? "ON" : "OFF"; }
    private Button button(String text, int x, int y, int w, Button.OnPress press) {
        return addRenderableWidget(Button.builder(Component.literal(text), press).bounds(x, y, w, 20).build());
    }
    @Override protected void init() {
        panelWidth = Math.min(440, width - 24); left = (width - panelWidth) / 2; top = Math.max(28, (height - 220) / 2);
        int half = (panelWidth - 6) / 2;
        button("同梱辞書", left, top - 24, 68, b -> minecraft.gui.setScreen(new DictionarySettingsScreen(this, draft, mod)));
        button("自動更新", left + panelWidth - 68, top - 24, 68, b -> minecraft.gui.setScreen(new UpdateSettingsScreen(this, draft, mod)));
        button("翻訳全体: " + on(draft.enabled), left, top, half, b -> { draft.enabled = !draft.enabled; b.setMessage(Component.literal("翻訳全体: " + on(draft.enabled))); });
        button("NPC・選択肢翻訳: " + on(draft.npcTranslation), left + half + 6, top, half, b -> { draft.npcTranslation = !draft.npcTranslation; b.setMessage(Component.literal("NPC・選択肢翻訳: " + on(draft.npcTranslation))); });
        button("アイテム翻訳: " + on(draft.loreTranslation), left, top + 24, half, b -> { draft.loreTranslation = !draft.loreTranslation; b.setMessage(Component.literal("アイテム翻訳: " + on(draft.loreTranslation))); });
        bindButton = button(keyLabel(), left + half + 6, top + 24, half, b -> { capturing = true; b.setMessage(Component.literal("割り当てるキーを押す（Esc取消）")); });
        var apiButton = button("外部API翻訳: " + on(draft.apiTranslation), left, top + 48, half, b -> {
            draft.apiTranslation = !draft.apiTranslation;
            b.setMessage(Component.literal("外部API翻訳: " + on(draft.apiTranslation)));
            refreshApiControls();
        });
        apiButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
            "OFFでAPI通信と過去のAPI訳の表示を停止し、辞書・端末内の定型訳だけを使います。NPC・チャット・G翻訳・再翻訳・接続テストが対象です。保存して反映。キャッシュやキーは削除しません。")));
        button("翻訳API: " + draft.provider, left + half + 6, top + 48, half, b -> { draft.provider = draft.provider.equals("GOOGLE") ? "GROQ" : "GOOGLE"; b.setMessage(Component.literal("翻訳API: " + draft.provider)); });
        model = addRenderableWidget(new EditBox(font, left, top + 84, panelWidth - 100, 20, Component.literal("GroqモデルID")));
        button("推奨モデル", left + panelWidth - 94, top + 84, 94, b -> model.setValue("openai/gpt-oss-120b"));
        model.setMaxLength(160); model.setValue(draft.groqModel);
        model.setResponder(value -> draft.groqModel = value);
        key = addRenderableWidget(new EditBox(font, left, top + 120, panelWidth - 84, 20, Component.literal("Groq APIキー")));
        button("設定ヘルプ", left + panelWidth - 78, top + 120, 78, b -> minecraft.gui.setScreen(new ApiHelpScreen(this)));
        key.setMaxLength(512); key.setValue(draft.groqApiKey);
        key.addFormatter((value, offset) -> FormattedCharSequence.forward("*".repeat(value.length()), Style.EMPTY));
        key.setResponder(value -> draft.groqApiKey = value);
        connectionTestButton = button("保存して接続テスト", left, top + 148, half, b -> {
            if (!draft.apiTranslation) { status = "API翻訳がOFFです。接続テストは送信しません"; return; }
            if (!save()) return;
            status = "接続テスト中…"; b.active = false;
            mod.service().connectionTest().thenAccept(result -> minecraft.execute(() -> {
                refreshApiControls();
                status = result == null ? "接続できませんでした。詳細はログを確認してください" : "接続成功: " + result;
            }));
        });
        button("保存して閉じる", left + half + 6, top + 148, half, b -> { if (save()) onClose(); });
        var actions = SettingsActionLayout.bottomRow(left, top, panelWidth);
        retranslateButton = button(panelWidth < 380 ? "直前を再翻訳" : "直前のアイテムを再翻訳", actions.retranslate().x(), actions.retranslate().y(), actions.retranslate().width(), b -> {
            status = mod.retranslateLastItem() ? "再翻訳開始: " + mod.lastItemName() : "翻訳中です。完了してから再度押してください";
        });
        button("共有用ログZIPを書き出す", actions.share().x(), actions.share().y(), actions.share().width(),
            b -> minecraft.gui.setScreen(new SharedLogScreen(this, mod, draft.groqApiKey)))
            .setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                "必要な専用ログだけを共有用ZIPにします。キー・個人チャット・キャッシュは除外し、自動送信しません。設定を保存せず実行できます。")));
        button(panelWidth < 380 ? "チャット・HUD" : "チャット・HUD設定", actions.chat().x(), actions.chat().y(), actions.chat().width(),
            b -> minecraft.gui.setScreen(new ChatSettingsScreen(this, draft, mod)));
        refreshApiControls();
    }
    private void refreshApiControls() {
        if (connectionTestButton != null) {
            connectionTestButton.active = draft.apiTranslation;
            connectionTestButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                draft.apiTranslation ? "保存したAPI設定で接続を確認します" : "API翻訳がOFFの間は接続テストを送信しません")));
        }
        if (retranslateButton != null) {
            boolean enabled = draft.apiTranslation && mod.configuration().apiTranslation;
            retranslateButton.active = enabled && mod.lastItemName() != null;
            retranslateButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                !enabled ? "外部API翻訳をONにして保存すると利用できます"
                    : mod.lastItemName() == null ? "先にアイテムへカーソルを合わせてください"
                    : mod.lastItemName() + " の原文を、保存済みのAPI設定で再送信します")));
        }
    }
    private String keyLabel() { return "翻訳キー: " + InputConstants.Type.KEYSYM.getOrCreate(draft.translateKey).getDisplayName().getString(); }
    private boolean save() {
        draft.groqModel = model.getValue().strip(); draft.groqApiKey = key.getValue().strip();
        if (draft.apiTranslation && draft.provider.equals("GROQ") && (draft.groqModel.isEmpty() || draft.groqApiKey.isEmpty() && !draft.candidateDictionary)) { status = "GroqのモデルIDとAPIキーを入力してください"; return false; }
        try { mod.saveConfig(draft); status = "保存しました"; return true; }
        catch (Exception e) { status = "設定を保存できませんでした"; return false; }
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (capturing) {
            capturing = false;
            if (event.key() != 256 && event.key() >= 32 && event.key() != 297) draft.translateKey = event.key();
            bindButton.setMessage(Component.literal(keyLabel())); return true;
        }
        return super.keyPressed(event);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, top - 22, 0xFFFFFFFF);
        graphics.text(font, "GroqモデルID（Google利用時は不要）", left, top + 73, 0xFFCCCCCC);
        graphics.text(font, "Groq APIキー（伏せ字表示・端末内に保存）", left, top + 109, 0xFFCCCCCC);
        String hint = status.isEmpty() ? draft.apiTranslation ? "アイテムにカーソルを合わせ翻訳キー。Shiftで原文。"
            : "API翻訳OFF: 辞書と定型訳のみ（保存して反映）" : status;
        graphics.text(font, font.plainSubstrByWidth(hint, panelWidth), left, top + 201, 0xFFFFFF88);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
