package me.crema.novelia.net;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Locale;

/**
 * Pure-Java origin and redirect policy for {@link NativeHttp}.
 *
 * <p>Kept free of Android and Conscrypt dependencies so it can be compiled and
 * tested on a plain JVM. Enforces the {@code https://novelpia.com} +
 * subdomain allow-list, manual redirect semantics (RFC 7231 plus the strict
 * cross-host rule) and same-host origin calculation. Destinations carrying
 * userinfo, non-default ports or malformed/backslash host forms are rejected:
 * the only valid forms are
 * {@code https://novelpia.com[/…]} and {@code https://<label>.novelpia.com[/…]}
 * on the default HTTPS port.</p>
 */
public final class UrlPolicy {

    /** Only hosts matching this suffix are ever contacted. */
    public static final String ALLOWED_HOST_SUFFIX = "novelpia.com";

    /** Maximum number of redirects followed per request. */
    public static final int MAX_REDIRECTS = 5;

    private static final int HTTPS_PORT = 443;
    private static final int HTTP_PORT = 80;

    private UrlPolicy() {
        // Static utility.
    }

    /** Parses {@code urlString} and rejects anything outside the allow-list. */
    public static URL parseAllowed(String urlString) throws IOException {
        URL url;
        try {
            url = new URL(urlString);
        } catch (Exception e) {
            throw new IOException("Malformed URL: " + urlString, e);
        }
        if (!isAllowed(url)) {
            throw new IOException("Disallowed destination: "
                    + (url.getHost() == null ? "unknown" : url.getHost())
                    + " (only novelpia.com and its subdomains over HTTPS, "
                    + "default port, are allowed)");
        }
        return url;
    }

    /**
     * {@code https://} only, host {@code novelpia.com} or a subdomain, no
     * userinfo, default port only, and a well-formed hostname. Never trusts
     * other hosts, plaintext schemes, credentials or weird ports.
     */
    public static boolean isAllowed(URL url) {
        if (url == null || url.getHost() == null) {
            return false;
        }
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            return false;
        }
        if (url.getUserInfo() != null) {
            return false;
        }
        int port = url.getPort();
        if (port != -1 && port != HTTPS_PORT) {
            return false;
        }
        String host = url.getHost().toLowerCase(Locale.US);
        return (host.equals(ALLOWED_HOST_SUFFIX)
                || host.endsWith("." + ALLOWED_HOST_SUFFIX))
                && isValidHostname(host);
    }

    /** Resolves a redirect {@code Location} against {@code base}. */
    public static URL resolve(URL base, String location) throws IOException {
        try {
            return new URL(base, location);
        } catch (Exception e) {
            throw new IOException("Malformed redirect Location: " + location, e);
        }
    }

    /** True when both URLs share scheme, host and effective port. */
    public static boolean sameHost(URL a, URL b) {
        if (a == null || b == null || a.getHost() == null || b.getHost() == null) {
            return false;
        }
        String pa = scheme(a);
        String pb = scheme(b);
        String ha = a.getHost().toLowerCase(Locale.US);
        String hb = b.getHost().toLowerCase(Locale.US);
        return ha.equals(hb) && pa.equals(pb) && portOf(a, pa) == portOf(b, pb);
    }

    /**
     * Scheme+host(+non-default port) with trailing slash, used as the
     * same-host Referer origin. Query strings are never included.
     */
    public static String origin(URL url) {
        String scheme = scheme(url);
        int port = url.getPort();
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(url.getHost());
        if ((scheme.equals("https") && port >= 0 && port != HTTPS_PORT)
                || (scheme.equals("http") && port >= 0 && port != HTTP_PORT)) {
            sb.append(':').append(port);
        }
        sb.append('/');
        return sb.toString();
    }

    public static String scheme(URL url) {
        String p = url.getProtocol();
        return p == null ? "" : p.toLowerCase(Locale.US);
    }

    public static int portOf(URL url, String scheme) {
        int port = url.getPort();
        return port < 0 ? (scheme.equals("https") ? HTTPS_PORT : -1) : port;
    }

    /**
     * Rejects empty labels beyond the base suffix, underscores, backslashes,
     * whitespace and other characters that are illegal in DNS hostnames. The
     * base domain itself may be a single known {@code novelpia.com}.
     */
    private static boolean isValidHostname(String host) {
        if (host == null || host.length() == 0 || host.length() > 253) {
            return false;
        }
        if (host.startsWith(".") || host.endsWith(".")) {
            return false;
        }
        if (host.indexOf('\\') >= 0 || host.indexOf('/') >= 0) {
            return false;
        }
        String base = ALLOWED_HOST_SUFFIX;
        String labels;
        if (host.equals(base)) {
            labels = base;
        } else {
            if (!host.endsWith("." + base)) {
                return false;
            }
            labels = host.substring(0, host.length() - base.length() - 1);
        }
        for (String label : labels.split("\\.", -1)) {
            if (label.length() == 0) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!((c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9')
                        || c == '-')) {
                    return false;
                }
            }
            if (label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decides what a redirect hop may keep:
     *
     * <ul>
     *   <li>RFC 7231: {@code 301}/{@code 302}/{@code 303} always become a
     *       body-stripped {@code GET} (even same-host).</li>
     *   <li>{@code 307}/{@code 308} preserve {@code POST} method, body and
     *       content type, but only for a same-host hop.</li>
     *   <li>A cross-host hop always becomes a body-stripped {@code GET} so
     *       sensitive form data never leaves the original host.</li>
     * </ul>
     */
    public static Redirect decideRedirect(int status, String method, boolean sameHost) {
        boolean preserve = sameHost && "POST".equals(method)
                && (status == 307 || status == 308);
        if (preserve) {
            return new Redirect("POST", true, true);
        }
        return new Redirect("GET", false, false);
    }

    /** Result of {@link #decideRedirect(int, String, boolean)}. */
    public static final class Redirect {
        /** HTTP method for the next hop. */
        public final String method;
        /** Whether the POST body may be forwarded to the next hop. */
        public final boolean keepBody;
        /** Whether the form content type may be forwarded. */
        public final boolean keepContentType;

        private Redirect(String method, boolean keepBody, boolean keepContentType) {
            this.method = method;
            this.keepBody = keepBody;
            this.keepContentType = keepContentType;
        }
    }
}
