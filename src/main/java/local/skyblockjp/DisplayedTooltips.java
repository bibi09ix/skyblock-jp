package local.skyblockjp;

import java.util.*;
import net.minecraft.network.chat.Component;

/** Only the actual display call may promote a generated tooltip to an input/audit source. */
final class DisplayedTooltips<T> {
    private record Entry<T>(
            Object screen,
            List<Component> list,
            Component first,
            List<Component> snapshot,
            Object provenance,
            T value) {}

    private final Deque<Entry<T>> pending = new ArrayDeque<>();
    private String reason = "NO_GENERATED_TOOLTIP";

    String reason() {
        return reason;
    }

    void put(Object screen, List<Component> lines, T value) {
        put(screen, lines, value, value);
    }

    void put(Object screen, List<Component> lines, T value, Object provenance) {
        if (lines.isEmpty()) return;
        var snapshot = TooltipObservation.snapshot(lines);
        pending.removeIf(
                e ->
                        e.screen() == screen
                                && Objects.equals(e.provenance(), provenance)
                                && e.snapshot().equals(snapshot));
        if (pending.size() >= 512) pending.removeFirst();
        pending.addLast(new Entry<>(screen, lines, lines.getFirst(), snapshot, provenance, value));
    }

    T displayed(Object screen, List<Component> lines) {
        return displayed(screen, lines, lines);
    }

    T displayed(Object screen, List<Component> lines, List<Component> identitySource) {
        reason = "NO_ITEM_CALLBACK_MATCH";
        if (lines.isEmpty()) return null;
        var snapshot = TooltipObservation.snapshot(lines);
        Entry<T> found = null;
        // A list identity is evidence only while its generated prefix is still intact.
        for (var entry : pending)
            if (entry.screen() == screen
                    && entry.list() == identitySource
                    && prefix(entry.snapshot(), snapshot)) found = entry;
        if (found != null) {
            reason = "SOURCE_LIST_IDENTITY";
            return found.value();
        }
        // Other mods may copy Components or append footers. Require a full styled prefix,
        // multiple meaningful lines, and one source provenance; never match just a title.
        for (var entry : pending) {
            if (entry.screen() != screen || !prefix(entry.snapshot(), snapshot)) continue;
            boolean sharedTitle =
                    !identitySource.isEmpty() && entry.first() == identitySource.getFirst();
            if (!sharedTitle
                    && entry.snapshot().stream().filter(c -> !c.getString().isBlank()).count() < 2)
                continue;
            if (found != null && !Objects.equals(found.provenance(), entry.provenance())) {
                reason = "AMBIGUOUS_TOOLTIP_SOURCE";
                return null;
            }
            found = entry;
        }
        if (found == null) return null;
        reason = "UNIQUE_STYLED_CONTENT";
        return found.value();
    }

    private static boolean prefix(List<Component> expected, List<Component> actual) {
        return actual.size() >= expected.size()
                && actual.subList(0, expected.size()).equals(expected);
    }

    void clear() {
        pending.clear();
        reason = "NO_GENERATED_TOOLTIP";
    }
}
