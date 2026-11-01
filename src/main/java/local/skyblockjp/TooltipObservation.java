package local.skyblockjp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Wrap only a newly selected deferred draw; rejected/replaced candidates never observe input. */
public final class TooltipObservation {
    private TooltipObservation() {}
    public static Runnable selected(Runnable before, Runnable after, Runnable observe) {
        if (after == null || after == before) return after;
        return () -> { observe.run(); after.run(); };
    }
    /** Freeze resolved text and inherited styles, including mutable siblings and translation arguments. */
    public static List<Component> snapshot(List<Component> lines) {
        return lines.stream().map(TooltipObservation::snapshot).toList();
    }
    private record Run(String text, Style style) {}
    private static Component snapshot(Component source) {
        var runs = new ArrayList<Run>();
        source.visit((style, text) -> {
            if (!text.isEmpty()) {
                if (!runs.isEmpty() && runs.getLast().style().equals(style)) {
                    var last = runs.removeLast();
                    runs.add(new Run(last.text() + text, style));
                } else runs.add(new Run(text, style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        var copy = Component.empty();
        for (var run : runs) copy.append(Component.literal(run.text()).setStyle(run.style()));
        return copy;
    }
}
