package me.crema.novelia.ui;

/** Pure local-page arithmetic; all positions refer to one bounded server batch. */
public final class PageWindow {
    private PageWindow() {}
    public static int count(int rows, int capacity) {
        if (rows < 0 || rows > 100 || capacity < 1) throw new IllegalArgumentException();
        return rows == 0 ? 0 : 1 + (rows - 1) / capacity;
    }
    public static int start(int screen, int rows, int capacity) {
        int pages = count(rows, capacity);
        if (pages == 0) return 0;
        int selected = screen == -1 ? pages - 1 : Math.max(0, Math.min(screen, pages - 1));
        return selected * capacity;
    }
    public static int containing(int anchor, int rows, int capacity) {
        count(rows, capacity);
        if (rows == 0) return 0;
        return Math.max(0, Math.min(anchor, rows - 1)) / capacity * capacity;
    }
}
