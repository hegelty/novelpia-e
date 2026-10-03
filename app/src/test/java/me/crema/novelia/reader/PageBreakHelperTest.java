package me.crema.novelia.reader;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PageBreakHelperTest {

    @Test
    public void nextCodePointIndexAdvancesOneForBmp() {
        assertEquals(1, PageBreakHelper.nextCodePointIndex("abc", 0, 3));
        assertEquals(3, PageBreakHelper.nextCodePointIndex("abc", 2, 3));
        assertEquals(3, PageBreakHelper.nextCodePointIndex("abc", 3, 3));
    }

    @Test
    public void nextCodePointIndexSkipsWholeSurrogatePair() {
        String s = "a\uD83D\uDE00b";
        // 'a' is one code point.
        assertEquals(1, PageBreakHelper.nextCodePointIndex(s, 0, 4));
        // At the high surrogate, the whole pair is one code point.
        assertEquals(3, PageBreakHelper.nextCodePointIndex(s, 1, 4));
        // "b" is one code point.
        assertEquals(4, PageBreakHelper.nextCodePointIndex(s, 3, 4));
    }

    @Test
    public void nextCodePointIndexNeverExceedsLimit() {
        String s = "a\uD83D\uDE00b";
        // limit 2 only fits 'a' (boundary 1); boundary 2 would split the pair.
        assertEquals(1, PageBreakHelper.nextCodePointIndex(s, 0, 2));
        // limit 3 fits 'a' plus the full pair; the pair is one code point, so
        // from index 1 the pair ends at 3 (never split at 2).
        assertEquals(3, PageBreakHelper.nextCodePointIndex(s, 1, 3));
        assertEquals(3, PageBreakHelper.nextCodePointIndex(s, 1, 4));
        // No complete code point fits: do not split the pair just to advance.
        assertEquals(1, PageBreakHelper.nextCodePointIndex(s, 1, 2));
        // From 0, 'a' is one code point: never jump to 4.
        assertEquals(1, PageBreakHelper.nextCodePointIndex(s, 0, 4));
    }

    @Test
    public void trimSurrogateKeepsCompletePair() {
        String s = "ab\uD83D\uDE00cd";
        // boundary 3 splits the pair (high at 2, low at 3): trim to 2.
        assertEquals(2, PageBreakHelper.trimSurrogate(s, 0, 3));
        // boundary 4 includes the whole pair: kept.
        assertEquals(4, PageBreakHelper.trimSurrogate(s, 0, 4));
        assertEquals(2, PageBreakHelper.trimSurrogate(s, 0, 2));
        assertEquals(0, PageBreakHelper.trimSurrogate("", 0, 0));
        assertEquals(5, PageBreakHelper.trimSurrogate(s, 0, 5));
        // boundary 6 is the end of the string: kept.
        assertEquals(6, PageBreakHelper.trimSurrogate(s, 0, 6));
    }
}
