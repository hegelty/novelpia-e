package me.crema.novelia.reader;

import java.util.ArrayList;
import java.util.List;

/**
 * Lazy, bounded text paginator.
 *
 * <p>A page is produced on demand: the upcoming window (at most
 * {@link #MAX_PAGE_CHARS} characters) is measured once with
 * {@link TextMeasurer#boundaryForHeight}, which returns the exact character
 * offset of the last visual line that fits the page height. The same bounded
 * text is then rendered by {@link PageLayoutEngine}, so pagination and
 * drawing always agree (wrapped paragraphs are never mis-stepped).</p>
 *
 * <ul>
 *   <li><b>Newlines preserved.</b> The window is measured as-is; a trailing
 *       newline counts as its own line, and consecutive blank lines or empty
 *       paragraphs stay distinct.</li>
 *   <li><b>Surrogate safe.</b> Every page end is trimmed with
 *       {@link PageBreakHelper#trimSurrogate}; a UTF-16 pair is never split.</li>
 *   <li><b>Bounded.</b> Only the requested page's window is laid out; the
 *       whole book is never measured in one call. Produced page starts are
 *       cached as plain ints for instant back navigation.</li>
 * </ul>
 */
public final class TextPager {

    /** Absolute cap on characters measured in one shot. */
    public static final int MAX_PAGE_CHARS = 8000;

    private String text = "";
    private TextMeasurer measurer;
    private float heightPx;

    private int cursor = 0;
    private int known = 0;
    private boolean complete = false;

    private final List<PageBreak> pages = new ArrayList<PageBreak>();
    private int[] starts = new int[64];
    private int[] ends = new int[64];

    private TextPager(String text) {
        this.text = text;
    }

    /** Creates a paginator over {@code text}; {@code null} is treated as "". */
    public static TextPager create(String text) {
        return new TextPager(text == null ? "" : text);
    }

    public String getText() {
        return text;
    }

    /** Prepares the paginator for a new layout (font, width, spacing, height). */
    public void configure(TextMeasurer measurer, float heightPx) {
        this.measurer = measurer;
        this.heightPx = heightPx;
        reset();
    }

    /** Drops every produced page and restarts from the beginning. */
    public void reset() {
        cursor = 0;
        known = 0;
        complete = false;
        pages.clear();
        java.util.Arrays.fill(starts, 0);
        java.util.Arrays.fill(ends, 0);
    }

    /**
     * Returns the page at {@code index}, producing it lazily when needed.
     * Returns {@code null} for negative/after-end indices, empty text, or
     * before {@link #configure}.
     */
    public PageBreak page(int index) {
        if (index < 0 || text.length() == 0 || measurer == null) {
            return null;
        }
        while (known <= index && !complete) {
            PageBreak next = nextPage();
            if (next == null) {
                complete = true;
                break;
            }
            pages.add(next);
            record(next);
            known++;
            if (next.getEnd() >= text.length()) {
                complete = true;
            }
        }
        return index < pages.size() ? pages.get(index) : null;
    }

    public int producedCount() {
        return known;
    }

    public boolean isComplete() {
        return complete;
    }

    /**
     * Total pages. While the book is not fully paginated this returns the
     * produced count plus one for the un-computed remainder; it becomes exact
     * once the final page is produced.
     */
    public int totalPages() {
        if (text.length() == 0) {
            return 0;
        }
        if (complete) {
            return known;
        }
        return known + 1;
    }

    /**
     * Index of the first produced page containing {@code offset}. Pages are
     * produced lazily until the containing page is found, so restoring a saved
     * progress only lays out the prefix up to that position.
     */
    public int findPageIndex(int offset) {
        if (text.length() == 0) {
            return 0;
        }
        if (offset <= 0) {
            return 0;
        }
        int target = Math.min(offset, text.length());
        int index = 0;
        while (true) {
            PageBreak p = page(index);
            if (p == null) {
                return Math.max(0, known - 1);
            }
            if (target < p.getEnd()) {
                return index;
            }
            if (p.getEnd() >= text.length()) {
                return index;
            }
            index++;
        }
    }

    private PageBreak nextPage() {
        if (cursor >= text.length() || !(heightPx > 0f)) {
            return null;
        }
        int start = cursor;
        int max = Math.min(text.length(), start + MAX_PAGE_CHARS);
        max = PageBreakHelper.trimSurrogate(text, start, max);
        CharSequence window = text.subSequence(start, max);
        int rel = measurer.boundaryForHeight(window, heightPx);
        int end = start + Math.min(Math.max(1, rel), max - start);
        end = PageBreakHelper.trimSurrogate(text, start, end);
        if (end <= start) {
            end = PageBreakHelper.nextCodePointIndex(text, start, max);
        }
        cursor = end;
        return new PageBreak(start, end, text.substring(start, end));
    }

    private void record(PageBreak page) {
        if (known >= starts.length) {
            int n = starts.length * 2;
            int[] ns = new int[n];
            int[] ne = new int[n];
            System.arraycopy(starts, 0, ns, 0, starts.length);
            System.arraycopy(ends, 0, ne, 0, ends.length);
            starts = ns;
            ends = ne;
        }
        starts[known] = page.getStart();
        ends[known] = page.getEnd();
    }
}
