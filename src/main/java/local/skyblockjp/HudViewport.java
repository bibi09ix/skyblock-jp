package local.skyblockjp;

/** Input geometry is published by a visible render, never inferred from maximum configured size. */
record HudViewport(int x, int y, int width, int height, int lineLimit, int totalLines) {
    static final HudViewport EMPTY = new HudViewport(0, 0, 0, 0, 0, 0);

    boolean scrollableAt(double mx, double my) {
        return width > 0
                && height > 0
                && lineLimit > 0
                && totalLines > lineLimit
                && mx >= x
                && mx < x + width
                && my >= y
                && my < y + height;
    }

    int scroll(int offset, double amount) {
        return Math.clamp(
                offset + (int) Math.signum(amount) * 3, 0, Math.max(0, totalLines - lineLimit));
    }
}
