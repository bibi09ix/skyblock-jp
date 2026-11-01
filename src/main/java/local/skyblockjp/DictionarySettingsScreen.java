package local.skyblockjp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Separate opt-in until the offline corpus has been verified in actual game displays. */
public final class DictionarySettingsScreen extends Screen {
    private final Screen parent;
    private final Config draft;
    private final SkyBlockJp mod;
    private boolean enabled, autoDisplay, diagnostics, plainFallback;
    private Button autoDisplayButton;
    private int left, top, panelWidth;
    private String status = "";

    public DictionarySettingsScreen(Screen parent, Config draft, SkyBlockJp mod) {
        super(Component.literal("同梱辞書（試験版）"));
        this.parent = parent; this.draft = draft; this.mod = mod;
        enabled = draft.candidateDictionary;
        autoDisplay = draft.dictionaryAutoDisplay;
        diagnostics = draft.dictionaryDiagnostics;
        plainFallback = draft.dictionaryPlainFallback;
    }
    private String label() { return "試験版の同梱辞書: " + (enabled ? "ON" : "OFF"); }
    private String autoDisplayLabel() { return "登録済みの説明を即表示: " + (autoDisplay ? "ON" : "OFF"); }
    @Override protected void init() {
        panelWidth = Math.min(420, width - 24); left = (width - panelWidth) / 2;
        top = Math.max(28, (height - 228) / 2);
        addRenderableWidget(Button.builder(Component.literal(label()), b -> {
            enabled = !enabled; b.setMessage(Component.literal(label()));
            autoDisplayButton.active = enabled;
        }).bounds(left, top + 80, panelWidth, 20).build());
        autoDisplayButton = addRenderableWidget(Button.builder(Component.literal(autoDisplayLabel()), b -> {
            autoDisplay = !autoDisplay; b.setMessage(Component.literal(autoDisplayLabel()));
        }).bounds(left, top + 104, panelWidth, 20).build());
        autoDisplayButton.active = enabled;
        autoDisplayButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
            "辞書に一致した段落だけ、最初のツールチップから日本語で表示します。未登録の文は原文のままです。APIへは自動送信しません。")));
        int half = (panelWidth - 6) / 2;
        var diagnosticsButton = addRenderableWidget(Button.builder(Component.literal("照合理由ログ: " + (diagnostics ? "ON" : "OFF")), b -> {
            diagnostics = !diagnostics; b.setMessage(Component.literal("照合理由ログ: " + (diagnostics ? "ON" : "OFF")));
        }).bounds(left, top + 128, half, 20).build());
        diagnosticsButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
            "対象の分類・行の除外理由・段落・辞書照合・色復元・G操作を記録します。元の色や一致0件も含め、重複とログサイズを制限します。")));
        var plainButton=addRenderableWidget(Button.builder(Component.literal("確認済み本文の標準色: " + (plainFallback?"ON":"OFF")),b->{
            plainFallback=!plainFallback;b.setMessage(Component.literal("確認済み本文の標準色: "+(plainFallback?"ON":"OFF")));
        }).bounds(left+half+6,top+128,half,20).build());
        plainButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("辞書で普通の説明文と確認された箇所だけ、強調色の対応が不明な場合に本文の色で表示します。数値・名前・記号の内容と色は保持します。OFFなら色が完全に対応する訳だけを表示します。")));
        addRenderableWidget(Button.builder(Component.literal("保存して戻る"), b -> {
            try {
                mod.saveDictionaryConfig(enabled, autoDisplay, diagnostics,plainFallback);
                draft.candidateDictionary = enabled; draft.dictionaryAutoDisplay = autoDisplay;
                draft.dictionaryDiagnostics = diagnostics;
                draft.dictionaryPlainFallback = plainFallback;
                onClose();
            }
            catch (Exception e) { status = "設定を保存できませんでした"; }
        }).bounds(left, top + 158, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("キャンセル"), b -> onClose())
            .bounds(left + half + 6, top + 158, half, 20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, top - 20, 0xFFFFFFFF);
        String[] help = {
            "未検証の訳を含むため、同梱辞書の初期値はOFFです。",
            "即表示ONなら、最初から登録済み段落を訳します。",
            "未登録の文は原文のまま。APIへ自動送信しません。",
            "翻訳キーで全体を翻訳。Ctrl併用でAPIへ再送します。",
            "NPC会話・登録済みの固定通知は自動翻訳します。"
        };
        for (int i = 0; i < help.length; i++) graphics.text(font,
            font.plainSubstrByWidth(help[i], panelWidth), left, top + i * 14, 0xFFCCCCCC);
        var stats = mod.service().dictionaryStats();
        String detail = !status.isEmpty() ? status : stats.loaded()
            ? "読込済み: " + stats.availableKeys() + "件 / " + stats.buildId()
            : mod.configuration().candidateDictionary ? "辞書を読込できません。ログを確認してください。" : "辞書は保存後に読み込みます。APIキーなしでも一致文を利用できます。";
        graphics.text(font, font.plainSubstrByWidth(detail, panelWidth), left, top + 193, 0xFFFFFF88);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
