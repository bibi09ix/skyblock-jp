package local.skyblockjp;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import java.net.URI;

final class ApiHelpScreen extends Screen {
    private final Screen parent;
    private int step;
    private int left, top, panel;
    private static final String[] TITLES = {"1. 翻訳APIを選ぶ", "2. Groqにログイン", "3. APIキーを作成", "4. modへ貼り付け", "5. 接続を確認"};
    private static final String[] BODY = {
        "初期設定はGroqです。翻訳を始めるにはAPIキーを設定してください。この案内で取得から接続確認まで説明します。Googleは設定画面で選べ、APIキーなしで利用できます。",
        "「Groqのキー管理を開く」を押し、公式サイトで登録・ログインしてください。ブラウザーからゲームに戻って、この案内を続けられます。",
        "API Keys画面のCreate API Keyから、分かりやすい名前でキーを作ります。表示されたキーをコピーしてください。キーは他人に渡さず、自分の設定欄へ貼り付けてください。",
        "設定画面へ戻り、翻訳APIをGROQにします。APIキー欄をクリックし、Ctrl+Vで貼り付けます。モデルIDはopenai/gpt-oss-120bを使えます。キーは伏せ字で表示されます。",
        "「保存して接続テスト」を押します。「接続成功」と出たら準備完了です。401/403はキー・権限、429は利用上限です。キーは端末内の設定ファイルに保存されます。"
    };
    ApiHelpScreen(Screen parent) { super(Component.literal("翻訳API セットアップ")); this.parent = parent; }
    @Override protected void init() {
        panel = Math.min(420, width - 24); left = (width - panel) / 2; top = Math.max(24, (height - 220) / 2);
        addRenderableWidget(Button.builder(Component.literal("Groqのキー管理を開く"), b -> Util.getPlatform().openUri(URI.create("https://console.groq.com/keys"))).bounds(left, top + 130, panel, 20).build());
        int third = (panel - 12) / 3;
        var back = addRenderableWidget(Button.builder(Component.literal("前へ"), b -> { step--; rebuildWidgets(); }).bounds(left, top + 156, third, 20).build()); back.active = step > 0;
        var next = addRenderableWidget(Button.builder(Component.literal("次へ"), b -> { step++; rebuildWidgets(); }).bounds(left + third + 6, top + 156, third, 20).build()); next.active = step < BODY.length - 1;
        addRenderableWidget(Button.builder(Component.literal("設定へ戻る"), b -> onClose()).bounds(left + (third + 6) * 2, top + 156, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal("公式の手順を開く"), b -> Util.getPlatform().openUri(URI.create("https://console.groq.com/docs/quickstart"))).bounds(left, top + 182, panel, 20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, title, width / 2, top - 18, 0xFFFFFFFF);
        g.text(font, TITLES[step], left, top + 8, 0xFF77DDFF);
        g.textWithWordWrap(font, Component.literal(BODY[step]), left, top + 28, panel, 0xFFFFFFFF);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
