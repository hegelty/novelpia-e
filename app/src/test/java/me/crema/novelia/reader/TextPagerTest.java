package me.crema.novelia.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class TextPagerTest {

    private FixedLineMeasurer measurer;

    @Before
    public void setUp() {
        measurer = new FixedLineMeasurer();
        measurer.setCharsPerLine(10);
        measurer.setLinesPerPage(7);
    }

    // --- basics -----------------------------------------------------------

    @Test
    public void emptyTextGivesNoPages() {
        assertNull(TextPager.create("").page(0));
        assertNull(TextPager.create(null).page(0));
    }

    @Test
    public void singleShortParagraphFitsOnePage() {
        TextPager pager = TextPager.create("hello");
        pager.configure(measurer, 100f);
        PageBreak page = pager.page(0);
        assertNotNull(page);
        assertEquals(0, page.getStart());
        assertEquals("hello", page.getText());
        assertTrue(pager.isComplete());
    }

    @Test
    public void paginationCoversWholeTextWithoutLoss() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("word").append(i).append(' ');
        }
        String text = sb.toString();
        TextPager pager = TextPager.create(text);
        pager.configure(measurer, 100f);
        StringBuilder rebuilt = new StringBuilder();
        int last = 0;
        int index = 0;
        while (true) {
            PageBreak page = pager.page(index);
            if (page == null) {
                break;
            }
            assertTrue(page.getStart() == last);
            assertTrue(page.getEnd() > page.getStart());
            assertFalse(hasLoneSurrogate(page.getText()));
            rebuilt.append(page.getText());
            last = page.getEnd();
            index++;
        }
        assertEquals(text, rebuilt.toString());
        assertEquals(text.length(), last);
    }

    @Test
    public void textLongerThanPageSplits() {
        TextPager pager = TextPager.create(repeatChar('a', 200));
        pager.configure(measurer, 100f);
        int index = 0;
        while (pager.page(index) != null) {
            index++;
        }
        assertTrue(index > 1);
        assertTrue(pager.isComplete());
    }

    // --- paragraph preservation ------------------------------------------

    @Test
    public void paragraphBoundaryPagesKeepNewline() {
        TextPager pager = TextPager.create("alpha\nbeta");
        pager.configure(measurer, 100f);
        PageBreak page = pager.page(0);
        assertNotNull(page);
        assertEquals("alpha\nbeta", page.getText());
    }

    @Test
    public void blankParagraphsArePreserved() {
        String text = "\n\n\n\n\n\n\n\n\n\n\n\n";
        TextPager pager = TextPager.create(text);
        pager.configure(measurer, 100f);
        StringBuilder rebuilt = new StringBuilder();
        int index = 0;
        PageBreak page;
        while ((page = pager.page(index)) != null) {
            rebuilt.append(page.getText());
            index++;
        }
        assertEquals(text, rebuilt.toString());
    }

    @Test
    public void pageBreakPrefersNewlineAndKeepsLines() {
        measurer.setLinesPerPage(2);
        measurer.setCharsPerLine(10);
        String text = "abcdefghij\nklmnopqrst\nuvwxyzabcd";
        TextPager pager = TextPager.create(text);
        pager.configure(measurer, 100f);
        StringBuilder rebuilt = new StringBuilder();
        int index = 0;
        PageBreak page;
        while ((page = pager.page(index)) != null) {
            rebuilt.append(page.getText());
            index++;
        }
        assertEquals(text, rebuilt.toString());
    }

    // --- lazy navigation --------------------------------------------------

    @Test
    public void lazyPage0ThenNextPageIndex1Works() {
        TextPager pager = TextPager.create(repeatChar('a', 200));
        pager.configure(measurer, 100f);
        PageBreak page0 = pager.page(0);
        assertNotNull(page0);
        PageBreak page1 = pager.page(1);
        assertNotNull(page1);
        assertTrue(page1.getStart() > page0.getStart());
        assertTrue(page1.getEnd() > page1.getStart());
        assertEquals(page0.getEnd(), page1.getStart());
    }

    // --- surrogate pairs --------------------------------------------------

    @Test
    public void surrogatePairsAreNeverSplitAcrossPages() {
        String emoji = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02";
        measurer.setLinesPerPage(1);
        measurer.setCharsPerLine(1);
        TextPager pager = TextPager.create(emoji);
        pager.configure(measurer, 100f);
        StringBuilder rebuilt = new StringBuilder();
        int index = 0;
        PageBreak page;
        while ((page = pager.page(index)) != null) {
            assertTrue(!hasLoneSurrogate(page.getText()));
            rebuilt.append(page.getText());
            index++;
        }
        assertEquals(emoji, rebuilt.toString());
    }

    // --- bounded memory ---------------------------------------------------

    @Test
    public void measurementInputNeverExceedsMaxPageChars() {
        measurer.setLinesPerPage(5);
        measurer.setCharsPerLine(10);
        // 200k chars, far above MAX_PAGE_CHARS: every measurer call must be a
        // bounded slice, never the whole book.
        String text = repeatChar('a', 200_000);
        CountedMeasurer counting = new CountedMeasurer(measurer);
        TextPager pager = TextPager.create(text);
        pager.configure(counting, 100f);
        StringBuilder rebuilt = new StringBuilder();
        int index = 0;
        PageBreak page;
        while ((page = pager.page(index)) != null && index < 20) {
            rebuilt.append(page.getText());
            index++;
            if (pager.isComplete()) {
                break;
            }
        }
        assertTrue(counting.calls > 0);
        assertTrue(rebuilt.length() > 0);
        // Every single measurement was a bounded slice.
        assertEquals(0, counting.oversized);
    }

    // --- helpers ----------------------------------------------------------

    @Test public void measurementWindowCannotSplitEmojiAt8000() {
        String text = repeatChar('a', TextPager.MAX_PAGE_CHARS - 1) + "\uD83D\uDE00tail";
        TextPager pager = TextPager.create(text);
        pager.configure(new TextMeasurer() {
            @Override public int boundaryForHeight(CharSequence value, float height) {
                assertFalse(hasLoneSurrogate(value.toString()));
                return value.length();
            }
            @Override public int lineCount(CharSequence value) { return 1; }
        }, 100);
        StringBuilder rebuilt = new StringBuilder();
        for (int index = 0; pager.page(index) != null; index++) {
            String page = pager.page(index).getText();
            assertFalse(hasLoneSurrogate(page));
            rebuilt.append(page);
        }
        assertEquals(text, rebuilt.toString());
    }

    private static String repeatChar(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return true;
                i++;
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** Records every substring handed to the delegate measurer. */
    private static final class CountedMeasurer implements TextMeasurer {
        final TextMeasurer delegate;
        int calls = 0;
        int oversized = 0;

        CountedMeasurer(TextMeasurer delegate) {
            this.delegate = delegate;
        }

        @Override
        public int boundaryForHeight(CharSequence text, float heightPx) {
            calls++;
            if (text != null && text.length() > TextPager.MAX_PAGE_CHARS) {
                oversized++;
            }
            return delegate.boundaryForHeight(text, heightPx);
        }

        @Override
        public int lineCount(CharSequence text) {
            return delegate.lineCount(text);
        }
    }
}
