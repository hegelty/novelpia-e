package me.crema.novelia.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.junit.Test;

/** JVM-only regression tests for origin, redirect, cookie and body boundaries. */
public class NetworkSecurityHelpersTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Test
    public void urlPolicyAcceptsOnlyHttpsAllowedOriginsWithoutUserInfoOrOddPorts()
            throws Exception {
        assertEquals("https://novelpia.com/", UrlPolicy.origin(
                UrlPolicy.parseAllowed("https://novelpia.com/chapter?id=1")));
        assertTrue(UrlPolicy.isAllowed(new URL("https://reader.novelpia.com/viewer/1")));

        assertRejected("http://novelpia.com/");
        assertRejected("https://novelpia.com.evil.example/");
        assertRejected("https://evilnovelpia.com/");
        assertRejected("https://user@novelpia.com/");
        assertRejected("https://user:pass@novelpia.com/");
        assertRejected("https://novelpia.com:444/");
        assertRejected("https://novelpia.com:8443/");
    }

    @Test
    public void post303DropsMethodBodyAndContentType() {
        assertRedirect(303, true, "GET", false, false);
    }

    @Test
    public void post307PreservesBodyOnlyForSameHostHop() {
        assertRedirect(307, true, "POST", true, true);
        assertRedirect(307, false, "GET", false, false);
    }

    @Test
    public void formCodecEncodesUtf8AndRejectsNullFields() throws Exception {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("이름 &", "A+B = 한글");
        assertEquals("%EC%9D%B4%EB%A6%84+%26=A%2BB+%3D+%ED%95%9C%EA%B8%80",
                new String(FormCodec.encode(form), UTF8));
        assertArrayEquals(new byte[0], FormCodec.encode(null));
        assertArrayEquals(new byte[0], FormCodec.encode(Collections.<String, String>emptyMap()));

        form.clear();
        form.put("field", null);
        try {
            FormCodec.encode(form);
            fail("null form values must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("null"));
        }
    }

    @Test
    public void cookiesAreAddedBeforeAConnectionStartsSendingItsBody() throws Exception {
        SessionCookies cookies = new SessionCookies();
        URL url = new URL("https://novelpia.com/login");
        RecordingConnection response = new RecordingConnection(url);
        response.responseHeaders = Collections.singletonMap("Set-Cookie",
                Collections.singletonList("test_session=unit-test; Path=/; Secure"));
        cookies.storeFrom(response);

        RecordingConnection request = new RecordingConnection(url);
        cookies.emitFor(request, url);
        assertNotNull("cookie must be attached before body/output starts",
                request.getRequestProperty("Cookie"));
        request.getOutputStream();
        assertTrue(request.outputStarted);

        cookies.clear();
        RecordingConnection cleared = new RecordingConnection(url);
        cookies.emitFor(cleared, url);
        assertEquals(null, cleared.getRequestProperty("Cookie"));
    }

    @Test
    public void gzipBodyIsDecodedAndInflatedLimitIsEnforced() throws Exception {
        byte[] content = repeat("hello gzip\n", 200);
        assertArrayEquals(content, BoundedBody.read(
                new ByteArrayInputStream(gzip(content)), "gzip").getBytes(UTF8));

        byte[] tooLarge = repeat("x", BoundedBody.MAX_RESPONSE_BYTES + 1);
        try {
            BoundedBody.read(new ByteArrayInputStream(gzip(tooLarge)), "gzip");
            fail("inflated response cap must reject an oversized gzip body");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("limit"));
        }
    }

    @Test
    public void gzipCompressedInputLimitIsEnforcedIndependently() throws Exception {
        // Use a valid gzip member followed by enough trailing wire bytes to
        // exceed the compressed cap without increasing the decoded payload.
        byte[] member = gzip("small".getBytes(UTF8));
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(member);
        byte[] padding = new byte[BoundedBody.MAX_COMPRESSED_BYTES + 1];
        for (int i = 0; i < padding.length; i++) {
            padding[i] = (byte) (i * 31 + 7);
        }
        wire.write(padding);
        try {
            BoundedBody.read(new ByteArrayInputStream(wire.toByteArray()), "gzip");
            fail("compressed wire cap must reject excessive gzip input");
        } catch (IOException expected) {
            assertTrue("expected compressed cap failure, got: " + expected,
                    expected.getMessage().contains("Compressed"));
        }
    }

    private static void assertRejected(String value) throws Exception {
        try {
            UrlPolicy.parseAllowed(value);
            fail("URL should have been rejected: " + value);
        } catch (IOException expected) {
            // Rejection is the security contract.
        }
    }

    private static void assertRedirect(int status, boolean sameHost, String method,
                                       boolean keepBody, boolean keepContentType) {
        UrlPolicy.Redirect redirect = UrlPolicy.decideRedirect(status, "POST", sameHost);
        assertEquals(method, redirect.method);
        assertEquals(keepBody, redirect.keepBody);
        assertEquals(keepContentType, redirect.keepContentType);
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        gzip.write(content);
        gzip.close();
        return out.toByteArray();
    }

    private static byte[] repeat(String value, int count) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = value.getBytes(UTF8);
        for (int i = 0; i < count; i++) {
            out.write(bytes);
        }
        return out.toByteArray();
    }

    /** Minimal fake connection; no socket or network access is possible. */
    private static final class RecordingConnection extends HttpURLConnection {
        Map<String, List<String>> responseHeaders = Collections.emptyMap();
        boolean outputStarted;

        RecordingConnection(URL url) {
            super(url);
        }

        @Override public void connect() { connected = true; }
        @Override public void disconnect() { connected = false; }
        @Override public boolean usingProxy() { return false; }
        @Override public int getResponseCode() { return 200; }
        @Override public Map<String, List<String>> getHeaderFields() {
            return responseHeaders;
        }
        @Override public java.io.OutputStream getOutputStream() {
            outputStarted = true;
            return new ByteArrayOutputStream();
        }
        @Override public void addRequestProperty(String key, String value) {
            if (outputStarted) {
                throw new IllegalStateException("headers changed after output began");
            }
            super.addRequestProperty(key, value);
        }
    }
}
