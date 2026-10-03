package me.crema.novelia.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpCookie;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** JVM-only regression tests for the novelia-session-v1 parser. */
public class SessionImportTest {

    private static final long NOW = 1_800_000_000L; // Stable far-future clock.

    @Test
    public void parsesSessionAndPersistentCookiesWithForcedSecure()
            throws Exception {
        List<HttpCookie> cookies = parse(session());
        assertEquals(2, cookies.size());

        HttpCookie session = cookies.get(0);
        assertEquals("session", session.getName());
        assertEquals("abc", session.getValue());
        assertEquals(".novelpia.com", session.getDomain());
        assertEquals("/", session.getPath());
        assertEquals(0, session.getVersion());
        assertEquals(-1, session.getMaxAge());
        assertTrue("parser must force HTTPS-only", session.getSecure());

        HttpCookie persisted = cookies.get(1);
        assertEquals("reader.novelpia.com", persisted.getDomain());
        assertEquals("/reader", persisted.getPath());
        assertEquals(7200L, persisted.getMaxAge());
        assertTrue(persisted.getSecure());
    }

    @Test
    public void skipsExpiredCookiesButRejectsAnAllExpiredFile()
            throws Exception {
        JSONObject expired = cookie("expired", "gone")
                .put("expirationDate", (double) (NOW - 10L));
        JSONObject mixed = session();
        ((JSONArray) mixed.get("cookies")).put(expired);
        List<HttpCookie> onlyValid = parse(mixed);
        assertEquals(2, onlyValid.size());

        JSONObject doc = session();
        ((JSONArray) doc.get("cookies")).put(expired);
        doc.getJSONArray("cookies").remove(0);
        doc.getJSONArray("cookies").remove(0);
        assertRejects(doc);
    }

    @Test
    public void rejectsForeignAndLookalikeDomains() throws Exception {
        assertRejects(cookie("c", "v").put("domain", "google.com"));
        assertRejects(cookie("c", "v")
                .put("domain", "novelpia.com.evil.example"));
        assertRejects(cookie("c", "v").put("domain", "notnovelpia.com"));
        assertRejects(cookie("c", "v").put("domain", "novelpia.com."));
        assertRejects(cookie("c", "v").put("domain", "-x.novelpia.com"));
        assertRejects(cookie("c", "v").put("domain", "user@novelpia.com"));
        assertRejects(cookie("c", "v").put("domain", "192.168.0.1"));
        assertRejects(cookie("c", "v").put("domain", ".google.com"));
    }

    @Test
    public void acceptsApexAndSubdomainFormsWithOptionalDot()
            throws Exception {
        List<HttpCookie> cookies = parse(withFormat(cookie("c", "v")
                .put("domain", "novelpia.com")));
        assertEquals("novelpia.com", cookies.get(0).getDomain());
        cookies = parse(withFormat(cookie("c", "v")
                .put("domain", "reader.novelpia.com")));
        assertEquals("reader.novelpia.com", cookies.get(0).getDomain());
    }

    @Test
    public void rejectsControlAndInvalidNameCharacters() throws Exception {
        assertRejects(cookie("bad\nname", "v"));
        assertRejects(cookie("bad\rname", "v"));
        assertRejects(cookie("bad name", "v"));
        assertRejects(cookie("bad\u00e9", "v"));
        assertRejects(cookie("", "v"));
        assertRejects(cookie("x", "v").put("name", repeat("x", 129)));
    }

    @Test
    public void rejectsUnsafeOrOversizedValues() throws Exception {
        assertRejects(cookie("c", "a\r\nb"));
        assertRejects(cookie("c", "a\u0000b"));
        assertRejects(cookie("c", "a; b"));
        assertRejects(cookie("c", "\"quoted\""));
        assertRejects(cookie("c", repeat("x", 8193)));
        assertRejects(cookie("c", "\u00e9"));
    }

    @Test
    public void rejectsInvalidPathsAndNonBooleanSecure() throws Exception {
        assertRejects(cookie("c", "v").put("path", "no-slash"));
        assertRejects(cookie("c", "v").put("path", "/a\r\nb"));
        assertRejects(cookie("c", "v").put("secure", "true"));
        assertRejects(cookie("c", "v").put("secure", 1));
    }

    @Test
    public void enforcesTheCookieCountLimit() throws Exception {
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i < SessionImport.MAX_COOKIES + 1; i++) {
            tooMany.put(cookie("c" + i, "v"));
        }
        JSONObject doc = new JSONObject()
                .put("format", SessionImport.FORMAT)
                .put("exportedAt", NOW)
                .put("cookies", tooMany);
        assertRejects(doc);
    }

    @Test
    public void rejectsWrongFormatAndIncompleteShape() throws Exception {
        assertRejects(session().put("format", "other-v1"));
        assertRejects(new JSONObject().put("format", SessionImport.FORMAT));
        assertRejects(new JSONObject()
                .put("format", SessionImport.FORMAT)
                .put("exportedAt", NOW));
        assertRejects(new JSONObject()
                .put("format", SessionImport.FORMAT)
                .put("exportedAt", NOW)
                .put("cookies", "nope"));
        assertRejectsJson("{\"format\":\"novelia-session-v1\","
                + "\"exportedAt\":1,\"cookies\":[1]}");
        assertRejectsJson("");
    }

    @Test
    public void rejectsStaleAndFutureDatedExports() throws Exception {
        assertRejects(session().put("exportedAt",
                NOW - SessionImport.STALE_SECONDS - 1L));
        assertRejects(session().put("exportedAt",
                NOW + SessionImport.CLOCK_SKEW_FUTURE_SECONDS + 1L));

        long boundary = NOW - SessionImport.STALE_SECONDS;
        List<HttpCookie> cookies = parse(session().put("exportedAt", boundary));
        assertEquals(2, cookies.size());
    }

    @Test
    public void malformedJsonIsRejectedWithNoSecretMaterial() {
        try {
            SessionImport.parse("{not json", NOW);
            fail("malformed JSON must be rejected");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
            assertTrue(!expected.getMessage().contains("secret"));
            assertTrue(!expected.getMessage().contains("abc"));
        }
    }

    @Test
    public void readEnforcesThe128KibibyteBound() throws Exception {
        String small = "{\"format\":\"" + SessionImport.FORMAT + "\"}";
        assertEquals(small, SessionImport.read(
                new ByteArrayInputStream(small.getBytes("UTF-8"))));

        try {
            SessionImport.read(new ByteArrayInputStream(
                    new byte[SessionImport.MAX_READ_BYTES + 1]));
            fail("oversized stream must be rejected");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }

        byte[] invalidUtf8 = new byte[]{(byte) 0xC0, (byte) 0xAF};
        try {
            SessionImport.read(new ByteArrayInputStream(invalidUtf8));
            fail("invalid UTF-8 must be rejected");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void rejectsInvalidExpirationNumbers() throws Exception {
        assertRejects(cookie("c", "v").put("expirationDate", -1d));
        assertRejects(cookie("c", "v").put("expirationDate", "later"));
    }

    @Test public void rejectsMissingOrCoercedValuesAndDangerousPaths() throws Exception {
        JSONObject missing = cookie("c", "v");
        missing.remove("value");
        assertRejects(missing);
        assertRejects(cookie("c", "v").put("value", 123));
        assertRejects(cookie("c", "v").put("path", "/x;y"));
        assertRejects(cookie("c", "v").put("domain", repeat("a", 64) + ".novelpia.com"));
        assertRejects(session().put("exportedAt", NOW + 0.5d));
    }

    @Test public void boundsDirectParseAndDropsParserExceptionPayload() throws Exception {
        assertRejectsJson(repeat("[", 10000));
        assertRejectsJson(repeat(" ", SessionImport.MAX_READ_BYTES + 1));
        try {
            SessionImport.parse("{\"secret\":", NOW);
            fail("malformed");
        } catch (IOException error) {
            assertTrue(error.getCause() == null);
            assertTrue(!error.getMessage().contains("secret"));
        }
    }

    private static JSONObject session() {
        JSONObject doc = new JSONObject();
        doc.put("format", SessionImport.FORMAT);
        doc.put("exportedAt", NOW);
        JSONArray cookies = new JSONArray();
        cookies.put(cookie("session", "abc"));
        cookies.put(cookie("persist", "def")
                .put("domain", "reader.novelpia.com")
                .put("path", "/reader")
                .put("expirationDate", (double) (NOW + 7200L)));
        doc.put("cookies", cookies);
        return doc;
    }

    private static JSONObject withFormat(JSONObject cookie) {
        return new JSONObject()
                .put("format", SessionImport.FORMAT)
                .put("exportedAt", NOW)
                .put("cookies", new JSONArray().put(cookie));
    }

    private static JSONObject cookie(String name, String value) {
        return new JSONObject()
                .put("name", name)
                .put("value", value)
                .put("domain", ".novelpia.com")
                .put("path", "/")
                .put("secure", true);
    }

    private static List<HttpCookie> parse(JSONObject doc) throws Exception {
        return SessionImport.parse(doc.toString(), NOW);
    }

    private static void assertRejects(JSONObject doc) {
        try {
            parse(doc.has("name") ? withFormat(doc) : doc);
            fail("expected rejection");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
    }

    private static void assertRejectsJson(String json) {
        try {
            SessionImport.parse(json, NOW);
            fail("expected rejection");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
