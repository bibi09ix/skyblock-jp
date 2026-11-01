package local.skyblockjp;

import java.net.URI;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/** First-run help starts with the local dictionary; provider setup is optional. */
final class ApiHelpScreen extends Screen {
    private static final String[] TITLES = {
        "1. 辞書だけでも使えます", "2. 外部APIへ送る内容", "3. Groqにログイン", "4. APIキーを作成", "5. modへ貼り付けて確認"
    };
    private static final String[] BODY = {
        "同梱辞書は初期値ONです。登録済みの説明・NPC会話は通信もAPIキーも不要です。"
                + "未登録の文も訳したい場合だけ、外部APIを設定してください。"
                + "「辞書だけで使う」はAPI通信と保存済みAPI訳の表示をOFFにして保存します。",
        "APIをONにすると、NPCの未登録台詞は自動送信されます。話者・直前の会話も使い、名前が含まれる場合があります。"
                + "アイテム説明とプレイヤーチャットはGを押した本文を送ります。送信先は選択したGroqまたはGoogleです。"
                + "OFFでは送信せず、辞書だけ使えます。",
        "外部APIの初期選択はGroqです。「Groqのキー管理を開く」から登録・ログインしてください。"
                + "Googleは設定で選べ、APIキーなしで利用できます。どちらも外部サービスの利用条件・データ取扱いが適用されます。",
        "GroqのAPI Keys画面でCreate API Keyを選び、キーをコピーしてください。"
                + "キーは端末内の設定JSONに保存されます。画面では伏せ字ですが、設定ファイルを他人へ渡さないでください。"
                + "共有用ログZIPにはキーを含めません。",
        "設定へ戻り、外部APIをON、翻訳APIをGROQにしてモデルIDとAPIキーを入力します。"
                + "「保存して接続テスト」は確認用の短文を送信します。401/403はキー・権限、429は利用上限です。"
                + "Google利用時はモデルIDとキーの入力は不要です。"
    };
    private final Screen parent;
    private int step;
    private int left, top, panel;
    private String status = "";

    ApiHelpScreen(Screen parent) {
        super(Component.literal("SkyBlock JP はじめての設定"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panel = Math.min(420, width - 24);
        left = (width - panel) / 2;
        top = Math.max(24, (height - 234) / 2);
        int half = (panel - 6) / 2;
        var local =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("辞書だけで使う（保存）"),
                                        b -> {
                                            if (parent instanceof SettingsScreen settings
                                                    && settings.useDictionaryOnly()) onClose();
                                            else status = "保存できませんでした。設定画面でAPIをOFFにしてください";
                                        })
                                .bounds(left, top + 122, half, 20)
                                .build());
        local.active = parent instanceof SettingsScreen;
        addRenderableWidget(
                Button.builder(
                                Component.literal("Groqのキー管理を開く"),
                                b ->
                                        Util.getPlatform()
                                                .openUri(
                                                        URI.create(
                                                                "https://console.groq.com/keys")))
                        .bounds(left + half + 6, top + 122, half, 20)
                        .build());
        int third = (panel - 12) / 3;
        var back =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("前へ"),
                                        b -> {
                                            step--;
                                            rebuildWidgets();
                                        })
                                .bounds(left, top + 148, third, 20)
                                .build());
        back.active = step > 0;
        var next =
                addRenderableWidget(
                        Button.builder(
                                        Component.literal("次へ"),
                                        b -> {
                                            step++;
                                            rebuildWidgets();
                                        })
                                .bounds(left + third + 6, top + 148, third, 20)
                                .build());
        next.active = step < BODY.length - 1;
        addRenderableWidget(
                Button.builder(Component.literal("設定へ戻る"), b -> onClose())
                        .bounds(left + (third + 6) * 2, top + 148, third, 20)
                        .build());
        addRenderableWidget(
                Button.builder(
                                Component.literal("Groqの公式手順を確認"),
                                b ->
                                        Util.getPlatform()
                                                .openUri(
                                                        URI.create(
                                                                "https://console.groq.com/docs/quickstart")))
                        .bounds(left, top + 174, panel, 20)
                        .build());
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, top - 18, 0xFFFFFFFF);
        graphics.text(font, TITLES[step], left, top + 8, 0xFF77DDFF);
        graphics.textWithWordWrap(
                font, Component.literal(BODY[step]), left, top + 28, panel, 0xFFFFFFFF);
        if (!status.isEmpty())
            graphics.text(
                    font, font.plainSubstrByWidth(status, panel), left, top + 204, 0xFFFFFF88);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
