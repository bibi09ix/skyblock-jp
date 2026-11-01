package local.skyblockjp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** Local export only. Opening from the F8 action starts its bounded background task. */
final class SharedLogScreen extends Screen {
    private final Screen parent;
    private final SkyBlockJp mod;
    private final String draftSecret;
    private boolean started;
    private Button export, folder;
    private int left, top, panel;
    private String folderError = "";

    SharedLogScreen(Screen parent, SkyBlockJp mod, String draftSecret) {
        super(Component.literal("共有用ログZIPを書き出す"));
        this.parent = parent; this.mod = mod; this.draftSecret = draftSecret;
    }
    @Override protected void init() {
        panel = Math.min(440, width - 24); left = (width - panel) / 2;
        top = Math.max(24, (height - 200) / 2);
        int half = (panel - 6) / 2;
        export = addRenderableWidget(Button.builder(Component.literal("もう一度書き出す"), b -> start())
            .bounds(left, top + 150, half, 20).build());
        folder = addRenderableWidget(Button.builder(Component.literal("保存先を開く"), b -> {
            try { Util.getPlatform().openPath(mod.sharedLogFolder()); folderError = ""; }
            catch (RuntimeException failure) { folderError = "保存先フォルダーを開けませんでした。"; }
        }).bounds(left + half + 6, top + 150, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("戻る"), b -> onClose())
            .bounds(left, top + 174, panel, 20).build());
        if (!started) { started = true; start(); }
        refreshButtons();
    }
    private void start() { folderError = ""; mod.exportSharedLogs(draftSecret); refreshButtons(); }
    private void refreshButtons() {
        var state = mod.sharedLogExportState();
        export.active = state.status() != SharedLogExportService.Status.RUNNING && state.status() != SharedLogExportService.Status.CLOSED;
        folder.active = state.status() == SharedLogExportService.Status.SUCCEEDED;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        refreshButtons();
        super.extractRenderState(g, mx, my, delta);
        g.centeredText(font, title, width / 2, top - 20, 0xFFFFFFFF);
        String[] help = {
            "保存済みの専用ログ4種類と前世代をZIPにします。",
            "APIキー・個人チャット・キャッシュを除外します。",
            "NPC台詞やアイテム情報に名前が含まれる場合があります。",
            "共有前に内容を確認してください。自動送信はしません。",
            "保存先: ゲームフォルダー / skyblock-jp-reports"
        };
        for (int i = 0; i < help.length; i++)
            g.text(font, font.plainSubstrByWidth(help[i], panel), left, top + i * 14, 0xFFCCCCCC);
        if (!mod.configuration().dictionaryDiagnostics)
            g.text(font, font.plainSubstrByWidth("照合理由ログはOFFです。保存済みの記録を使います。", panel), left, top + 77, 0xFFFFFF88);
        var state = mod.sharedLogExportState();
        String text = switch (state.status()) {
            case IDLE -> "書き出す準備ができています。";
            case RUNNING -> "ZIPを書き出しています…（画面を閉じても続行）";
            case SUCCEEDED -> "ZIPを保存しました: " + state.result().exportedRecords() + "件";
            case FAILED -> "書き出せませんでした。空き容量・保存先を確認してください。";
            case CLOSED -> "書き出し処理は終了しています。";
        };
        g.text(font, font.plainSubstrByWidth(folderError.isEmpty() ? text : folderError, panel), left, top + 98, 0xFFFFFF88);
        if (state.status() == SharedLogExportService.Status.SUCCEEDED) {
            var result = state.result();
            g.text(font, font.plainSubstrByWidth(result.zipPath().getFileName().toString(), panel), left, top + 112, 0xFFFFFFFF);
            String counts = "除外 " + result.excludedRecords() + " / 不正 " + result.malformedRecords()
                + " / 上限・途中 " + result.truncatedFiles();
            if (result.exportedRecords() == 0) counts = "記録0件。プレイ後、照合理由ログONで再度お試しください。";
            g.text(font, font.plainSubstrByWidth(counts, panel), left, top + 126, 0xFFCCCCCC);
        }
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
