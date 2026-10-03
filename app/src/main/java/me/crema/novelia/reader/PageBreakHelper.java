package me.crema.novelia.reader;

/**
 * Small Unicode-aware text helpers (pure Java, no Android types).
 *
 * <p>All offsets are UTF-16 code unit indices (what {@code String} and
 * {@code CharSequence} use on Android). These helpers never split a surrogate
 * pair, so pagination output never contains a dangling surrogate.</p>
 */
public final class PageBreakHelper {

    private PageBreakHelper() {
        // Utility class.
    }

    /**
     * Advances one complete code point without exceeding limit. If a pair
     * cannot fit before limit, returns its start (no progress), never half a pair.
     * Callers that require progress must provide room for a whole code point.
     */
    public static int nextCodePointIndex(CharSequence text, int index, int limit) {
        limit = Math.max(0, Math.min(limit, text.length()));
        index = Math.max(0, Math.min(index, limit));
        if (index >= limit) return limit;
        if (index > 0 && index < text.length()
                && Character.isLowSurrogate(text.charAt(index))
                && Character.isHighSurrogate(text.charAt(index - 1))) {
            index--;
        }
        if (index + 1 < text.length()
                && Character.isHighSurrogate(text.charAt(index))
                && Character.isLowSurrogate(text.charAt(index + 1))) {
            return index + 2 <= limit ? index + 2 : index;
        }
        return Math.min(index + 1, limit);
    }

    /**
     * Returns the largest boundary {@code <= candidate} and {@code >= start}
     * that does not split a UTF-16 surrogate pair.
     *
     * <p>A boundary {@code k} splits a pair exactly when a high surrogate sits
     * immediately before it and a low surrogate immediately after it.</p>
     */
    public static int trimSurrogate(CharSequence text, int start, int candidate) {
        int k = Math.max(start, Math.min(candidate, text.length()));
        while (k > start && k < text.length()
                && Character.isHighSurrogate(text.charAt(k - 1))
                && Character.isLowSurrogate(text.charAt(k))) {
            k--;
        }
        return k;
    }
}
