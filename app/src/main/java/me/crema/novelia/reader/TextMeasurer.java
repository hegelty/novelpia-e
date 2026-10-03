package me.crema.novelia.reader;

/**
 * Measures how text breaks into visual lines at the reader's current font
 * size, width, and line spacing. Abstracted so pagination logic (and its
 * tests) runs on plain JVM code without Android classes.
 */
public interface TextMeasurer {

    /**
     * Returns the character offset (relative to {@code text}) at which the
     * last line that fits inside {@code heightPx} ends. The returned boundary
     * is always on a visual line boundary (never mid-line), so pagination and
     * drawing agree exactly.
     *
     * <p>Must return at least 1 for non-empty text so pages always advance.
     * An empty text returns 0.</p>
     */
    int boundaryForHeight(CharSequence text, float heightPx);

    /**
     * Returns the number of visual lines {@code text} produces at the current
     * layout width. A trailing {@code '\n'} counts as one (possibly empty)
     * line, so newlines are never collapsed. An empty text returns 0.
     */
    int lineCount(CharSequence text);
}
