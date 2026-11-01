package local.skyblockjp;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

/** Hit testing uses vanilla's own layout pass, including scrolling and chat scale. */
public final class ChatHover implements ChatComponent.ChatGraphicsAccess {
    private final Minecraft mc;
    private final double mouseX, mouseY;
    private final Matrix3x2f pose = new Matrix3x2f();
    private FormattedCharSequence hovered;
    private Style hoveredStyle;

    public record Hit(Component source, Style style) {}

    private ChatHover(Minecraft mc, double mouseX, double mouseY) {
        this.mc = mc;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
    }

    public static Component find(Minecraft mc, ChatComponent.DisplayMode mode, double x, double y) {
        var hit = findHit(mc, mode, x, y);
        return hit == null ? null : hit.source();
    }

    public static Hit findHit(Minecraft mc, ChatComponent.DisplayMode mode, double x, double y) {
        var mod = SkyBlockJp.instance();
        if (mod == null || !mod.active() || !mod.configuration().chatButtons) return null;
        var access = (ChatAccess) mc.gui.hud.getChat();
        var hit = new ChatHover(mc, x, y);
        access.skyblockjp$visit(
                hit, mc.getWindow().getGuiScaledHeight(), mc.gui.hud.getGuiTicks(), mode);
        Component source = source(access.skyblockjp$lines(), hit.hovered);
        return source == null ? null : new Hit(source, hit.hoveredStyle);
    }

    static Component source(
            java.util.List<net.minecraft.client.multiplayer.chat.GuiMessage.Line> lines,
            FormattedCharSequence hovered) {
        if (hovered == null) return null;
        for (var line : lines) {
            if (line.content() == hovered) {
                Component source = MessageHandles.originalReference(line.parent().content());
                return source.getString().isBlank() ? null : source;
            }
        }
        return null;
    }

    @Override
    public void updatePose(Consumer<Matrix3x2f> update) {
        update.accept(pose);
    }

    @Override
    public boolean handleMessage(int y, float opacity, FormattedCharSequence text) {
        var a = pose.transformPosition(0, y - 1, new Vector2f());
        var b = pose.transformPosition(mc.font.width(text), y + 8, new Vector2f());
        if (contains(mouseX, mouseY, a.x, a.y, b.x, b.y)) {
            hovered = text;
            var local =
                    new Matrix3x2f(pose)
                            .invert()
                            .transformPosition((float) mouseX, (float) mouseY, new Vector2f());
            hoveredStyle = styleAt(text, local.x, mc.font.getSplitter());
            return true;
        }
        return false;
    }

    static Style styleAt(FormattedCharSequence text, double localX, StringSplitter splitter) {
        if (localX < 0) return null;
        double[] left = {0};
        Style[] result = {null};
        text.accept(
                (index, style, codepoint) -> {
                    double right =
                            left[0]
                                    + splitter.stringWidth(
                                            FormattedCharSequence.codepoint(codepoint, style));
                    if (localX >= left[0] && localX < right) {
                        result[0] = style;
                        return false;
                    }
                    left[0] = right;
                    return true;
                });
        return result[0];
    }

    static boolean contains(
            double x, double y, double left, double top, double right, double bottom) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    @Override
    public void fill(int left, int top, int right, int bottom, int color) {}

    @Override
    public void handleTag(
            int left, int top, int right, int bottom, float opacity, GuiMessageTag tag) {}

    @Override
    public void handleTagIcon(
            int x, int y, boolean hovered, GuiMessageTag tag, GuiMessageTag.Icon icon) {}
}
