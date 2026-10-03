package me.crema.novelia;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalTextTest {
    @Test public void utf8BomAndNewlines() throws Exception {
        assertEquals("가\n나\n다", LocalText.read(new ByteArrayInputStream(
                "\ufeff가\r\n나\r다".getBytes("UTF-8"))));
    }
    @Test(expected = IOException.class) public void rejectMalformedUtf8() throws Exception {
        LocalText.read(new ByteArrayInputStream(new byte[]{(byte) 0xff}));
    }
    @Test(expected = IOException.class) public void rejectOversized() throws Exception {
        LocalText.read(new ByteArrayInputStream(new byte[LocalText.MAX_BYTES + 1]));
    }
    @Test(expected = IOException.class) public void rejectBlank() throws Exception {
        LocalText.read(new ByteArrayInputStream(" \n\t".getBytes("UTF-8")));
    }
    @Test public void preserveUnicode() throws Exception {
        String text = "한글과 😀, 문단\n\n다음 문단";
        assertEquals(text, LocalText.read(new ByteArrayInputStream(text.getBytes("UTF-8"))));
    }
}
