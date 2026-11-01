package local.skyblockjp;

/** Client-thread latch for a key event and the tooltip that was actually observed. */
final class TooltipKeyInput {
    static final long MAX_HOVER_AGE_NANOS = 250_000_000L;
    enum Press { NONE, FIRST, REPEAT }
    private Object screen;
    private String item;
    private double x, y;
    record Bounds(double left, double top, double right, double bottom) {
        boolean contains(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }
    }
    private Bounds bounds;
    private long observedAt;
    private boolean held, consumed;
    private String reason = "NO_TOOLTIP_OBSERVED";
    String reason() { return reason; }

    void observe(Object screen, String item, double x, double y, long now) {
        observe(screen, item, x, y, now, null);
    }
    void observe(Object screen, String item, double x, double y, long now, Bounds bounds) {
        this.screen = screen; this.item = item; this.x = x; this.y = y; observedAt = now;
        this.bounds = bounds != null && bounds.contains(x, y) ? bounds : null;
    }
    void clearHover() { screen = null; item = null; reason = "NO_TOOLTIP_THIS_FRAME"; }
    void release() { held = false; consumed = false; }
    void reset() { clearHover(); release(); }
    // Repeats may suppress an already handled press, but must never start a new one.
    Press repeat() { return held && consumed ? Press.REPEAT : Press.NONE; }

    Press press(Object currentScreen, String currentItem, double mouseX, double mouseY, long now, boolean eligible) {
        if (held) { reason = "KEY_ALREADY_HELD"; return consumed ? Press.REPEAT : Press.NONE; }
        held = true;
        reason = !eligible ? "INPUT_NOT_ELIGIBLE" : screen == null || item == null ? "NO_TOOLTIP_THIS_FRAME"
            : screen != currentScreen ? "SCREEN_CHANGED" : !item.equals(currentItem) ? "TOOLTIP_CHANGED"
            : bounds != null ? bounds.contains(mouseX, mouseY) ? ageReason(now) : "CURSOR_LEFT_SOURCE_BOUNDS"
            : Double.compare(x, mouseX) != 0 || Double.compare(y, mouseY) != 0 ? "CURSOR_MOVED"
            : now < observedAt || now - observedAt > MAX_HOVER_AGE_NANOS ? "STALE_TOOLTIP_OBSERVATION" : "TRANSLATION_REQUESTED";
        consumed = reason.equals("TRANSLATION_REQUESTED");
        return consumed ? Press.FIRST : Press.NONE;
    }
    private String ageReason(long now) { return now < observedAt || now - observedAt > MAX_HOVER_AGE_NANOS ? "STALE_TOOLTIP_OBSERVATION" : "TRANSLATION_REQUESTED"; }
}
