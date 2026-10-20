package local.skyblockjp;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

final class TextScaleSlider extends AbstractSliderButton {
    private final Config draft;
    TextScaleSlider(int x, int y, int width, Config draft) {
        super(x, y, width, 20, Component.empty(), (draft.hudTextScale - .75) / 1.75);
        this.draft = draft; updateMessage();
    }
    @Override protected void updateMessage() {
        setMessage(Component.literal("HUDの文字サイズ: " + Math.round((.75 + value * 1.75) * 100) + "%"));
    }
    @Override protected void applyValue() { draft.hudTextScale = Math.round((.75 + value * 1.75) * 100) / 100.0; }
}
