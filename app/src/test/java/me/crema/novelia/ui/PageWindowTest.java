package me.crema.novelia.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public class PageWindowTest {
    @Test public void firstLastAndEmpty() {
        assertEquals(5, PageWindow.count(30,6));
        assertEquals(24, PageWindow.start(-1,30,6));
        assertEquals(0, PageWindow.start(-1,0,6));
        assertEquals(0, PageWindow.count(0,6));
        assertEquals(24, PageWindow.start(Integer.MAX_VALUE,30,6));
    }
    @Test public void resizeKeepsAnchorVisibleOnUniformPages() {
        for (int rows=1; rows<=100; rows++) for (int capacity=1; capacity<=12; capacity++)
            for (int anchor=0; anchor<rows; anchor++) {
                int first=PageWindow.containing(anchor,rows,capacity);
                assertTrue(first<=anchor && anchor<first+capacity);
                assertEquals(0,first%capacity);
                assertEquals(first,PageWindow.start(first/capacity,rows,capacity));
            }
    }
    @Test public void noGapsAcrossEveryPage() {
        for (int rows=1;rows<=100;rows++) for (int capacity=1;capacity<=12;capacity++) {
            int shown=0;
            for(int page=0;page<PageWindow.count(rows,capacity);page++) {
                int first=PageWindow.start(page,rows,capacity);
                assertEquals(shown,first);
                shown+=Math.min(capacity,rows-first);
            }
            assertEquals(rows,shown);
        }
    }
}
