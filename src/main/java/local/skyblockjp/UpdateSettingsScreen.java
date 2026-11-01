package local.skyblockjp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class UpdateSettingsScreen extends Screen {
    private final Screen parent;
    private final Config draft;
    private final SkyBlockJp mod;
    private EditBox repository;
    private int left, top, panel;
    private String error = "";

    UpdateSettingsScreen(Screen parent, Config draft, SkyBlockJp mod) {
        super(Component.literal("SkyBlock JP 自動更新"));
        this.parent = parent;
        this.draft = draft;
        this.mod = mod;
    }

    @Override
    protected void init() {
        panel = Math.min(420, width - 24);
        left = (width - panel) / 2;
        top = Math.max(28, (height - 190) / 2);
        addRenderableWidget(
                Button.builder(
                                Component.literal(label()),
                                b -> {
                                    draft.autoUpdate = !draft.autoUpdate;
                                    b.setMessage(Component.literal(label()));
                                })
                        .bounds(left, top, panel, 20)
                        .build());
        repository =
                addRenderableWidget(
                        new EditBox(
                                font, left, top + 42, panel, 20, Component.literal("GitHubの配布先")));
        repository.setMaxLength(200);
        repository.setValue(draft.updateRepository);
        repository.setResponder(value -> draft.updateRepository = value);
        addRenderableWidget(
                Button.builder(
                                Component.literal("保存して今すぐ確認"),
                                b -> {
                                    if (save()) mod.updater().check(mod.configuration(), true);
                                })
                        .bounds(left, top + 72, panel, 20)
                        .build());
        addRenderableWidget(
                Button.builder(
                                Component.literal("保存して戻る"),
                                b -> {
                                    if (save()) onClose();
                                })
                        .bounds(left, top + 148, panel, 20)
                        .build());
    }

    private String label() {
        return "起動時に自動更新: " + (draft.autoUpdate ? "ON" : "OFF");
    }

    private boolean save() {
        try {
            draft.updateRepository = repository.getValue().strip();
            AutoUpdater.repository(draft.updateRepository);
            mod.saveUpdateConfig(draft);
            error = "";
            return true;
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
            return false;
        } catch (Exception e) {
            error = "設定を保存できませんでした";
            return false;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        g.centeredText(font, title, width / 2, top - 20, 0xFFFFFFFF);
        g.text(font, "配布先 owner/repository（空欄なら同梱の配布先）", left, top + 29, 0xFFCCCCCC);
        g.textWithWordWrap(
                font,
                Component.literal(error.isEmpty() ? mod.updater().status() : error),
                left,
                top + 99,
                panel,
                0xFFFFFF88);
        g.textWithWordWrap(
                font,
                Component.literal("検証済みの新版を終了後に適用します。旧版はバックアップし、設定・APIキーは保持します。"),
                left,
                top + 121,
                panel,
                0xFFCCCCCC);
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
