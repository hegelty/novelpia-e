package me.crema.novelia.reader;

/**
 * Deterministic {@link TextMeasurer} for tests: non-surrogate characters
 * occupy one unit of width, a UTF-16 surrogate pair occupies one unit (never
 * split), and newlines end lines. A non-empty text always returns at least 1
 * line; the last line of a slice may overflow the page and is cut at a line
 * boundary.
 */
final class FixedLineMeasurer implements TextMeasurer {

    private int charsPerLine = 10;
    private int linesPerPageValue = 5;

    void setCharsPerLine(int charsPerLine) {
        this.charsPerLine = Math.max(1, charsPerLine);
    }

    void setLinesPerPage(int linesPerPageValue) {
        this.linesPerPageValue = Math.max(1, linesPerPageValue);
    }

    @Override
    public int boundaryForHeight(CharSequence text, float heightPx) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        // The whole slice fits.
        if (lineCount(text) <= linesPerPageValue) {
            return text.length();
        }
        int lines = 0;
        int col = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (lines == linesPerPageValue - 1) {
                    return i + 1;
                }
                lines++;
                col = 0;
            } else {
                // A surrogate pair takes a single column, never split.
                boolean pair = Character.isHighSurrogate(c)
                        && i + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(i + 1));
                col++;
                if (col == charsPerLine + 1) {
                    if (lines == linesPerPageValue - 1) {
                        return i;
                    }
                    lines++;
                    col = 1;
                }
                if (pair) {
                    i++;
                }
            }
        }
        return text.length();
    }

    @Override
    public int lineCount(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        int lines = 1;
        int col = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines++;
                col = 0;
            } else {
                boolean pair = Character.isHighSurrogate(c)
                        && i + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(i + 1));
                col++;
                if (col == charsPerLine + 1) {
                    lines++;
                    col = 1;
                }
                if (pair) {
                    i++;
                }
            }
        }
        return lines;
    }
}
