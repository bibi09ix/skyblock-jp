package local.skyblockjp;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

final class OpacitySlider extends AbstractSliderButton {
    private final Config draft;

    OpacitySlider(int x, int y, int width, Config draft) {
        super(x, y, width, 20, Component.empty(), 1 - draft.hudOpacity);
        this.draft = draft;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal("背景の透明度: " + Math.round(value * 100) + "%"));
    }

    @Override
    protected void applyValue() {
        draft.hudOpacity = 1 - value;
    }
}
