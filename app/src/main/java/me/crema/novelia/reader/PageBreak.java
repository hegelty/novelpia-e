package me.crema.novelia.reader;

/**
 * A single page slice of the original text.
 */
public final class PageBreak {

    private final int start;
    private final int end;
    private final String text;

    /**
     * @param start inclusive start offset in the original text
     * @param end exclusive end offset in the original text
     * @param text the page content ({@code text.substring(start, end)})
     */
    public PageBreak(int start, int end, String text) {
        this.start = start;
        this.end = end;
        this.text = text;
    }

    /** Inclusive start offset in the original text. */
    public int getStart() {
        return start;
    }

    /** Exclusive end offset in the original text. */
    public int getEnd() {
        return end;
    }

    /** Page content. */
    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return "PageBreak{" + start + ".." + end + "}";
    }
}
