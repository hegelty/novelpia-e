package me.crema.novelia.net;

import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.Test;
import static org.junit.Assert.*;

public class AjaxHeadersTest {
    @Test public void sameOriginHeadersHaveNoCookieOrAuthorization() throws Exception {
        URL url = new URL("https://novelpia.com/proc/mybook");
        Probe connection = new Probe(url);
        AjaxHeaders.apply(connection, url);
        assertEquals("XMLHttpRequest", connection.getRequestProperty("X-Requested-With"));
        assertEquals("https://novelpia.com", connection.getRequestProperty("Origin"));
        assertEquals("https://novelpia.com/", connection.getRequestProperty("Referer"));
        assertTrue(connection.getRequestProperty("Accept").contains("application/json"));
        assertNull(connection.getRequestProperty("Cookie"));
        assertNull(connection.getRequestProperty("Authorization"));
    }

    @Test public void foreignAndPlaintextOriginsAreRejected() throws Exception {
        for (String value : new String[]{"http://novelpia.com/", "https://example.com/"}) {
            URL url = new URL(value);
            try {
                AjaxHeaders.apply(new Probe(url), url);
                fail("disallowed origin");
            } catch (java.io.IOException expected) {}
        }
    }

    private static final class Probe extends HttpURLConnection {
        Probe(URL url) { super(url); }
        @Override public void connect() { throw new AssertionError("must not connect"); }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
    }
}
