package me.crema.novelia.site;

import org.junit.Test;
import static org.junit.Assert.*;

public class JsonLimitsTest {
    @Test(expected = IllegalStateException.class) public void rejectsExcessiveNesting() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1000; i++) text.append('[');
        text.append('0');
        for (int i = 0; i < 1000; i++) text.append(']');
        new SiteClient.JsonReader(text.toString()).root();
    }
    @Test(expected = IllegalStateException.class) public void rejectsTrailingPayload() {
        new SiteClient.JsonReader("{\"status\":200} garbage").root();
    }
    @Test(expected = IllegalStateException.class) public void rejectsInvalidEscape() {
        new SiteClient.JsonReader("\"\\x\"").root();
    }
    @Test public void acceptsKoreanAndEscapedUnicode() {
        assertEquals("한글 😀", new SiteClient.JsonReader("\"한글 \\ud83d\\ude00\"").root());
    }
}
