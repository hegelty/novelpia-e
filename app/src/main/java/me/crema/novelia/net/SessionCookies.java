package me.crema.novelia.net;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Memory-only cookie jar for one {@link NativeHttp} instance.
 *
 * <p>Plain {@link CookieManager} with an in-memory store: no disk persistence
 * and never the Android WebView cookie manager. The policy is
 * {@link CookiePolicy#ACCEPT_ORIGINAL_SERVER}, so a domain can only read and
 * write its own cookies — a foreign host can never plant or receive another
 * host's session data. All methods are {@code synchronized} on the jar, and
 * because NativeHttp serializes request chains with its instance lock, the same
 * jar can never be mutated concurrently by two request chains.</p>
 */
final class SessionCookies {

    private static final String COOKIE_HEADER = "Cookie";

    private final CookieManager manager =
            new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);

    /** Removes every cookie from the jar. */
    synchronized void clear() {
        manager.getCookieStore().removeAll();
    }

    /** Only receives an already fully validated import, under NativeHttp's lock. */
    synchronized void replace(List<HttpCookie> validated) {
        manager.getCookieStore().removeAll();
        for (HttpCookie cookie : validated) {
            String host = cookie.getDomain();
            if (host.startsWith(".")) host = host.substring(1);
            manager.getCookieStore().add(URI.create("https://" + host + "/"), cookie);
        }
    }

    /**
     * Emits the cookies the jar has for {@code url}. Must be called before the
     * connection's output stream is used: on {@code HttpURLConnection} the
     * {@code Cookie} request header is silently dropped if it is set after
     * the body starts flowing.
     *
     * <p>Failures are propagated as {@link IOException} rather than swallowed:
     * a broken cookie handoff must surface instead of silently degrading into
     * a session-less request.</p>
     *
     * @throws IOException when cookie retrieval or emission fails
     */
    synchronized void emitFor(HttpURLConnection connection, URL url)
            throws IOException {
        Map<String, List<String>> headers;
        try {
            headers = manager.get(url.toURI(),
                    Collections.<String, List<String>>emptyMap());
        } catch (Exception e) {
            throw new IOException("Failed to read session cookies", e);
        }
        List<String> cookies = headers.get(COOKIE_HEADER);
        if (cookies != null) {
            for (String cookie : cookies) {
                connection.addRequestProperty(COOKIE_HEADER, cookie);
            }
        }
    }

    /**
     * Stores any {@code Set-Cookie} headers from a response. The header map
     * returned by {@link HttpURLConnection#getHeaderFields()} is never
     * mutated; {@link CookieManager#put} ignores the request-only
     * {@code Cookie} header and RFC 6265 storage semantics anyway.
     */
    synchronized void storeFrom(HttpURLConnection connection) {
        try {
            URI uri = connection.getURL().toURI();
            Map<String, List<String>> headers = connection.getHeaderFields();
            manager.put(uri, headers);
        } catch (Exception ignored) {
            // Best-effort cookie persistence; a failed store must not abort a
            // successful response. (RFC 6265 attribute failures are also
            // handled internally by the CookieManager policy.)
        }
    }
}
