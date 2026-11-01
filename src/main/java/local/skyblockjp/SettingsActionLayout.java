package local.skyblockjp;

/** Keep all three final-row actions visible even at Minecraft's minimum GUI size. */
final class SettingsActionLayout {
    record Rect(int x, int y, int width, int height) {}
    record Actions(Rect retranslate, Rect share, Rect chat) {}
    static Actions bottomRow(int left, int top, int panelWidth) {
        if (panelWidth < 240) throw new IllegalArgumentException("Settings panel too narrow");
        int share = Math.min(150, Math.max(128, panelWidth / 3));
        int first = (panelWidth - share - 12) / 2;
        int last = panelWidth - first - share - 12;
        return new Actions(new Rect(left, top + 172, first, 20),
            new Rect(left + first + 6, top + 172, share, 20),
            new Rect(left + first + share + 12, top + 172, last, 20));
    }
    private SettingsActionLayout() {}
}
