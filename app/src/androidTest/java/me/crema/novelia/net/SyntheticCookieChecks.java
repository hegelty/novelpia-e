package me.crema.novelia.net;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** On-device CookieManager verification with synthetic data only; never connects. */
public final class SyntheticCookieChecks {
    public static void run() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String json = "{\"format\":\"novelia-session-v1\",\"exportedAt\":" + now
                + ",\"cookies\":[{\"name\":\"synthetic\",\"value\":\"not-a-real-session\","
                + "\"domain\":\".novelpia.com\",\"path\":\"/\",\"secure\":true}]}";
        SessionCookies jar = new SessionCookies();
        jar.replace(SessionImport.parse(json, now));
        Probe apex = new Probe("https://novelpia.com/mybook/last_view");
        jar.emitFor(apex, apex.getURL());
        require(apex.sawCookie, "apex cookie missing");
        Probe child = new Probe("https://book.novelpia.com/");
        jar.emitFor(child, child.getURL());
        require(child.sawCookie, "subdomain cookie missing");
        Probe foreign = new Probe("https://example.com/");
        jar.emitFor(foreign, foreign.getURL());
        require(!foreign.sawCookie, "foreign cookie leak");
        Probe plain = new Probe("http://novelpia.com/");
        jar.emitFor(plain, plain.getURL());
        require(!plain.sawCookie, "plaintext cookie leak");
        jar.clear();
        Probe cleared = new Probe("https://novelpia.com/");
        jar.emitFor(cleared, cleared.getURL());
        require(!cleared.sawCookie, "clear failed");

        // Reproduce a realistic multi-cookie jar without any real credentials.
        String multi = "{\"format\":\"novelia-session-v1\",\"exportedAt\":" + now
                + ",\"cookies\":["
                + "{\"name\":\"syntheticSession\",\"value\":\"fake-session\","
                + "\"domain\":\".novelpia.com\",\"path\":\"/\",\"secure\":true},"
                + "{\"name\":\"syntheticMember\",\"value\":\"fake-member\","
                + "\"domain\":\".novelpia.com\",\"path\":\"/\",\"secure\":true},"
                + "{\"name\":\"syntheticPath\",\"value\":\"fake-path\","
                + "\"domain\":\".novelpia.com\",\"path\":\"/mybook\",\"secure\":true},"
                + "{\"name\":\"syntheticBook\",\"value\":\"fake-book\","
                + "\"domain\":\"book.novelpia.com\",\"path\":\"/\",\"secure\":true},"
                + "{\"name\":\"syntheticApex\",\"value\":\"fake-apex\","
                + "\"domain\":\"novelpia.com\",\"path\":\"/\",\"secure\":true}"
                + "]}";
        jar.replace(SessionImport.parse(multi, now));
        Probe combined = new Probe("https://novelpia.com/mybook/last_view");
        jar.emitFor(combined, combined.getURL());
        require(combined.contains("syntheticSession=fake-session"), "session missing in mixed jar");
        require(combined.contains("syntheticMember=fake-member"), "member missing in mixed jar");
        require(combined.contains("syntheticPath=fake-path"), "path cookie missing");
        require(combined.contains("syntheticApex=fake-apex"), "apex host-only cookie missing");
        require(!combined.contains("syntheticBook="), "child cookie leaked to apex");
        require(!combined.contains("$Version") && !combined.contains("$Domain")
                && !combined.contains("$Path") && !combined.contains("\""),
                "unexpected version 1 cookie attributes");
        Probe otherPath = new Probe("https://novelpia.com/");
        jar.emitFor(otherPath, otherPath.getURL());
        require(otherPath.contains("syntheticSession=fake-session"), "root session missing");
        require(!otherPath.contains("syntheticPath="), "path restriction lost");
        Probe book = new Probe("https://book.novelpia.com/");
        jar.emitFor(book, book.getURL());
        require(book.contains("syntheticBook=fake-book")
                && book.contains("syntheticSession=fake-session")
                && book.contains("syntheticMember=fake-member"), "mixed subdomain jar mismatch");
        Probe response = new Probe("https://novelpia.com/mybook/last_view");
        response.responseHeaders.put("Set-Cookie", Arrays.asList(
                "syntheticSession=changed-fake; Domain=.novelpia.com; Path=/; Secure; HttpOnly"));
        jar.storeFrom(response);
        Probe updated = new Probe("https://novelpia.com/mybook/last_view");
        jar.emitFor(updated, updated.getURL());
        require(updated.contains("syntheticSession=changed-fake"), "server cookie update missing");
        require(!updated.contains("syntheticSession=fake-session"), "old session survived update");
        require(updated.contains("syntheticMember=fake-member"), "server update lost other cookies");
        // A rejected import must not replace the existing jar.
        try {
            jar.replace(SessionImport.parse(multi.replace(".novelpia.com", ".example.com"), now));
            throw new AssertionError("foreign import accepted");
        } catch (java.io.IOException expected) {}
        Probe unchanged = new Probe("https://novelpia.com/");
        jar.emitFor(unchanged, unchanged.getURL());
        require(unchanged.contains("syntheticSession=changed-fake"), "invalid import changed jar");

        // Inspect the real API19 URLConnection's assembled request properties,
        // not only the interception probe. Never connect/send these fake values.
        URL target = new URL("https://novelpia.com/mybook/last_view");
        HttpURLConnection real = (HttpURLConnection) target.openConnection();
        try {
            jar.emitFor(real, target);
            List<String> headers = real.getRequestProperties().get("Cookie");
            require(headers != null && headers.size() == 1, "not one combined Cookie header");
            String field = headers.get(0);
            require(field.contains("syntheticSession=changed-fake")
                    && field.contains("syntheticMember=fake-member")
                    && field.contains("syntheticApex=fake-apex")
                    && field.contains("syntheticPath=fake-path"), "assembled request lost cookies");
            require(!field.contains(",") && !field.contains("$Version"), "non-browser cookie encoding");
        } finally { real.disconnect(); }
    }

    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static final class Probe extends HttpURLConnection {
        boolean sawCookie;
        final List<String> sent = new ArrayList<String>();
        final Map<String, List<String>> responseHeaders = new LinkedHashMap<String, List<String>>();
        Probe(String address) throws Exception { super(new URL(address)); }
        @Override public void addRequestProperty(String key, String value) {
            if ("Cookie".equalsIgnoreCase(key)) {
                sent.add(value);
                if (value.contains("synthetic=not-a-real-session")) sawCookie = true;
            }
        }
        boolean contains(String syntheticPart) {
            for (String value : sent) if (value.contains(syntheticPart)) return true;
            return false;
        }
        @Override public Map<String, List<String>> getHeaderFields() {
            return Collections.unmodifiableMap(responseHeaders);
        }
        @Override public void connect() { throw new AssertionError("must not connect"); }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
    }
}
