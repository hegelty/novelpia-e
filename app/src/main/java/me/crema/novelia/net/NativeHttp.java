package me.crema.novelia.net;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.conscrypt.Conscrypt;

/**
 * Small, defensive HTTPS client used to talk to {@code novelpia.com} from
 * Android 4.4 (API 19) upwards.
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li><b>TLS engine.</b> The platform {@code HttpURLConnection} on API 19 is
 *       TLSv1.2-capable through Conscrypt, which the parent build pins
 *       ({@code org.conscrypt:conscrypt-android:2.5.2}, min API 19). This class
 *       builds its own {@link SSLContext} via {@code Conscrypt.newProvider()};
 *       no global TLS defaults are changed anywhere.</li>
 *   <li><b>Trust.</b> The system trust store is authoritative. When it rejects
 *       a chain, a standard PKIX {@link TrustManagerFactory} whose trust store
 *       contains <em>only</em> the bundled self-signed Amazon Root CA 1 is
 *       consulted — expiry, signature and path constraints are validated by the
 *       PKIX provider, exactly as the system store would. There is no
 *       hand-rolled path verification and no intermediate trust anchor; the
 *       bundled root can only authorize the Amazon chain it actually signs.
 *       Nothing is ever trusted blindly.</li>
 *   <li><b>Hostnames.</b> Verified on every hop by the default
 *       {@link HttpsURLConnection} hostname verifier.</li>
 *   <li><b>Origins.</b> Only {@code https://novelpia.com} and its subdomains
 *       are allowed (see {@link UrlPolicy}). Redirects are followed manually
 *       (maximum {@link UrlPolicy#MAX_REDIRECTS}); a hop outside the allow-list
 *       aborts instead of leaking credentials or cookies to a foreign host.</li>
 *   <li><b>Redirects.</b> RFC 7231 semantics: {@code 301}/{@code 302}/
 *       {@code 303} become a body-stripped {@code GET}; {@code 307}/{@code 308}
 *       keep the {@code POST} body and content type only on a same-host hop
 *       (see {@link UrlPolicy#decideRedirect}).</li>
 *   <li><b>State.</b> Cookies live in a per-instance memory-only
 *       {@link java.net.CookieManager} (never the Android WebView store);
 *       {@link #clearCookies()} fully resets the session. Credentials, tokens
 *       and request/response bodies are never logged.</li>
 *   <li><b>Bounding.</b> Responses are capped at
 *       {@link BoundedBody#MAX_RESPONSE_BYTES} after gzip inflation and the
 *       compressed wire stream is capped at
 *       {@link BoundedBody#MAX_COMPRESSED_BYTES}. Connections have connect/read
 *       timeouts. Synchronized requests keep redirect/cookie state ordered;
 *       the caller supplies the background worker.</li>
 * </ul>
 */
public final class NativeHttp {

    /** Byte cap for the <em>inflated</em> response body. */
    public static final int MAX_RESPONSE_BYTES = BoundedBody.MAX_RESPONSE_BYTES;

    /** Byte cap for compressed bytes on the wire. */
    public static final int MAX_COMPRESSED_BYTES = BoundedBody.MAX_COMPRESSED_BYTES;

    /** Maximum number of redirects followed per request. */
    public static final int MAX_REDIRECTS = UrlPolicy.MAX_REDIRECTS;

    /** Milliseconds to wait while establishing the connection. */
    public static final int CONNECT_TIMEOUT_MS = 15_000;

    /** Milliseconds to wait when reading a response chunk. */
    public static final int READ_TIMEOUT_MS = 30_000;

    /** Mobile browser User-Agent sent with every request. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; Pixel 3) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36";

    private static final String BUNDLED_ROOT_ASSET = "certs/AmazonRootCA1.pem";

    private final SessionCookies cookies = new SessionCookies();
    private final SSLSocketFactory socketFactory;
    private final HostnameVerifier hostnameVerifier;

    /**
     * @param context application context used only to read the bundled root
     *                certificate; its application context is retained
     */
    public NativeHttp(Context context) {
        try {
            SSLContext ssl = createIsolatedSslContext(context);
            socketFactory = ssl.getSocketFactory();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Failed to initialise TLS for NativeHttp", e);
        }
        hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
    }

    /**
     * Issues a GET request and returns the (UTF-8 decoded) response body.
     *
     * @param url absolute {@code https://} URL whose host is inside the
     *            allow-list
     * @return the response body
     * @throws IOException if the request fails, times out, exceeds the
     *                     response cap, is redirected outside the allow-list,
     *                     or the server answers with a non-2xx status
     */
    public synchronized String get(final String url) throws IOException {
        return requestString("GET", url, null);
    }

    /**
     * Issues a POST with {@code application/x-www-form-urlencoded} (UTF-8)
     * form fields and returns the (UTF-8 decoded) response body.
     *
     * @param url absolute {@code https://} URL whose host is inside the
     *            allow-list
     * @param form fields to send as the POST body; may be empty or null
     * @return the response body
     * @throws IOException if the request fails, times out, exceeds the
     *                     response cap, is redirected outside the allow-list,
     *                     or the server answers with a non-2xx status
     */
    public synchronized String post(final String url, final Map<String, String> form) throws IOException {
        return requestString("POST", url, form);
    }

    /** Same cookie/TLS policy, with the site's normal same-origin AJAX headers. */
    public synchronized String postAjax(final String url, final Map<String, String> form) throws IOException {
        return requestString("POST", url, form, true);
    }

    /** Clears all cookies remembered for this client's session. */
    public synchronized void clearCookies() {
        cookies.clear();
    }

    /** Validate the entire bounded file before replacing any existing session. */
    public synchronized void importSession(InputStream input) throws IOException {
        cookies.replace(SessionImport.parse(SessionImport.read(input),
                System.currentTimeMillis() / 1000L));
    }

    /**
     * Short, clean description of the security posture for a diagnostics
     * provider. Contains no secrets, cookies or bodies.
     */
    public String getDiagnosticsDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("TLS=Conscrypt-2.5.2;")
                .append("protocols=").append(supportedTlsProtocols()).append(';')
                .append("trust=system+PKIX-anchor:AmazonRootCA1;")
                .append("hostnameVerifier=default-HttpsURLConnection;")
                .append("hosts=novelpia.com+subdomains;")
                .append("redirects=").append(MAX_REDIRECTS).append(";")
                .append("responseCap=").append(MAX_RESPONSE_BYTES).append("-bytes;")
                .append("compressedCap=").append(MAX_COMPRESSED_BYTES).append("-bytes;")
                .append("cookies=memory-only-CookieManager;")
                .append("logging=none");
        return sb.toString();
    }

    private String supportedTlsProtocols() {
        SSLSocket probe = null;
        try {
            probe = (SSLSocket) socketFactory.createSocket();
            String[] protocols = probe.getSupportedProtocols();
            StringBuilder sb = new StringBuilder();
            if (protocols != null) {
                boolean first = true;
                for (String protocol : protocols) {
                    if (protocol != null && protocol.startsWith("TLS")) {
                        if (!first) {
                            sb.append(',');
                        }
                        sb.append(protocol);
                        first = false;
                    }
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return "unavailable";
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (IOException ignored) {
                    // Best-effort close; the probe socket is being discarded.
                }
            }
        }
    }

    private String requestString(String method, String urlString, Map<String, String> form)
            throws IOException {
        return requestString(method, urlString, form, false);
    }

    private String requestString(String method, String urlString, Map<String, String> form,
                                 boolean ajax) throws IOException {
        if (urlString == null) {
            throw new IOException("Null URL");
        }
        URL url = UrlPolicy.parseAllowed(urlString);
        byte[] body = "POST".equals(method) ? FormCodec.encode(form) : null;
        String contentType = "POST".equals(method) ? FormCodec.CONTENT_TYPE : null;
        String referer = UrlPolicy.origin(url);
        return run(url, method, body, contentType, referer, 0, ajax);
    }

    private String run(URL url, String method, byte[] body, String contentType,
                       String referer, int redirects, boolean ajax) throws IOException {
        if (redirects > MAX_REDIRECTS) {
            throw new IOException("Too many redirects (max " + MAX_REDIRECTS + ")");
        }
        HttpURLConnection connection = openConnection(url, method, body, contentType, referer, ajax);
        try {
            int code = connection.getResponseCode();
            cookies.storeFrom(connection);

            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Redirect (HTTP " + code
                            + ") without a Location header");
                }
                URL next = UrlPolicy.resolve(url, location);
                if (!UrlPolicy.isAllowed(next)) {
                    throw new IOException("Redirect to disallowed host: "
                            + (next.getHost() == null ? "unknown" : next.getHost()));
                }
                boolean sameHost = UrlPolicy.sameHost(url, next);
                UrlPolicy.Redirect decision = UrlPolicy.decideRedirect(code, method, sameHost);
                byte[] nextBody = decision.keepBody ? body : null;
                String nextContentType = decision.keepContentType ? contentType : null;
                String nextMethod = decision.method;
                // Cross-host hops and RFC 7231 GET downgrades never forward the
                // original POST body; cookies stay host-scoped in the jar.
                return run(next, nextMethod, nextBody, nextContentType,
                        referer, redirects + 1, ajax && sameHost);
            }

            if (code < 200 || code >= 300) {
                throw new HttpException("Unexpected HTTP status " + code + " from "
                        + url.toString()
                        + (code == 401 ? " (unauthorized)"
                        : code == 403 ? " (forbidden)" : " (not 2xx)"),
                        code);
            }

            InputStream in = connection.getInputStream();
            try {
                return BoundedBody.read(in, connection.getContentEncoding());
            } finally {
                closeQuietly(in);
            }
        } finally {
            dispose(connection);
        }
    }

    /**
     * Opens a connection for one request hop: TLS factory, hostname verifier,
     * timeouts, mobile UA, same-origin Referer, session cookies and the POST
     * body. Cookies are emitted <em>before</em> the body is written so the
     * {@code Cookie} header survives connection establishment.
     */
    private HttpURLConnection openConnection(URL url, String method, byte[] body,
                                             String contentType, String referer, boolean ajax)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection secure = (HttpsURLConnection) connection;
            secure.setSSLSocketFactory(socketFactory);
            secure.setHostnameVerifier(hostnameVerifier);
            secure.setInstanceFollowRedirects(false);
        }
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        if (referer != null) {
            connection.setRequestProperty("Referer", referer);
        }
        connection.setDoOutput(false);
        connection.setRequestMethod(method);
        if (ajax) AjaxHeaders.apply(connection, url);
        cookies.emitFor(connection, url);
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", contentType);
            connection.getOutputStream().write(body);
            connection.getOutputStream().close();
        }
        return connection;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Best-effort close; the resource is being discarded anyway.
            }
        }
    }

    private static void dispose(HttpURLConnection connection) {
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (RuntimeException ignored) {
                // Best-effort disconnect; the connection is being discarded.
            }
        }
    }

    private static SSLContext createIsolatedSslContext(Context context)
            throws GeneralSecurityException, IOException {
        // System trust first.
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        X509TrustManager systemTrust = systemTrustManager(tmf.getTrustManagers());

        // Bundled anchor: ONLY the self-signed Amazon Root CA 1 in its own
        // KeyStore, verified through the standard PKIX provider.
        X509Certificate root = loadBundledRoot(context);
        X509TrustManager anchorTrust = anchorTrustManager(root);

        X509TrustManager combined = new CombinedTrustManager(systemTrust, anchorTrust);
        SSLContext sslContext = SSLContext.getInstance("TLS", Conscrypt.newProvider());
        sslContext.init(null, new TrustManager[] { combined }, null);
        return sslContext;
    }

    private static X509TrustManager systemTrustManager(TrustManager[] managers) {
        if (managers != null) {
            for (TrustManager manager : managers) {
                if (manager instanceof X509TrustManager) {
                    return (X509TrustManager) manager;
                }
            }
        }
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager manager : tmf.getTrustManagers()) {
                if (manager instanceof X509TrustManager) {
                    return (X509TrustManager) manager;
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No system X509TrustManager available", e);
        }
        throw new IllegalStateException("No system X509TrustManager available");
    }

    /**
     * A standard PKIX trust manager whose trust store contains only
     * {@code root}. Expiry, signature and path constraints are validated by
     * the provider — no hand-rolled path code exists anywhere in this layer.
     */
    private static X509TrustManager anchorTrustManager(X509Certificate root)
            throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("amazon-root-ca-1", root);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        for (TrustManager manager : tmf.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new IllegalStateException("No X509TrustManager for bundled root");
    }

    /** Loads the bundled self-signed root; package-private for {@link NetStatus}. */
    static List<X509Certificate> loadBundledCertificates(Context context)
            throws IOException {
        List<X509Certificate> certificates = new ArrayList<X509Certificate>(1);
        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new IOException("X.509 CertificateFactory unavailable", e);
        }
        InputStream in = null;
        try {
            in = context.getAssets().open(BUNDLED_ROOT_ASSET);
            Certificate certificate = factory.generateCertificate(in);
            if (certificate instanceof X509Certificate) {
                certificates.add((X509Certificate) certificate);
            }
        } catch (IOException e) {
            throw new IOException("Failed to read bundled certificate "
                    + BUNDLED_ROOT_ASSET, e);
        } catch (CertificateException e) {
            throw new IOException("Bundled certificate " + BUNDLED_ROOT_ASSET
                    + " is not valid X.509", e);
        } finally {
            closeQuietly(in);
        }
        if (certificates.isEmpty()) {
            throw new IOException("Bundled certificate " + BUNDLED_ROOT_ASSET
                    + " did not parse as X.509");
        }
        return certificates;
    }

    private static X509Certificate loadBundledRoot(Context context)
            throws IOException {
        List<X509Certificate> certificates = loadBundledCertificates(context);
        X509Certificate root = certificates.get(0);
        try {
            root.checkValidity();
        } catch (CertificateException e) {
            throw new IOException("Bundled Amazon Root CA 1 is not currently valid", e);
        }
        return root;
    }

    /**
     * Consults the system trust store first, then the anchor-only PKIX trust
     * manager for the bundled Amazon Root CA 1. Hostname verification is never
     * performed here — it stays with the default
     * {@link HttpsURLConnection} hostname verifier.
     */
    private static final class CombinedTrustManager implements X509TrustManager {
        private final X509TrustManager systemTrust;
        private final X509TrustManager anchorTrust;

        CombinedTrustManager(X509TrustManager systemTrust, X509TrustManager anchorTrust) {
            this.systemTrust = systemTrust;
            this.anchorTrust = anchorTrust;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            systemTrust.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] system = systemTrust.getAcceptedIssuers();
            X509Certificate[] anchor = anchorTrust.getAcceptedIssuers();
            List<X509Certificate> result = new ArrayList<X509Certificate>();
            if (system != null) {
                Collections.addAll(result, system);
            }
            if (anchor != null) {
                Collections.addAll(result, anchor);
            }
            return result.toArray(new X509Certificate[result.size()]);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Empty server certificate chain");
            }
            try {
                systemTrust.checkServerTrusted(chain, authType);
                return;
            } catch (CertificateException systemFailure) {
                // Fall back to the bundled anchor only when the system store
                // rejected the chain. The PKIX provider checks validity,
                // signatures and path constraints; there is no trust-all path.
                try {
                    anchorTrust.checkServerTrusted(chain, authType);
                } catch (CertificateException anchorFailure) {
                    CertificateException failure = new CertificateException(
                            "Certificate chain not trusted by the system store "
                                    + "or the bundled Amazon Root CA 1",
                            systemFailure);
                    failure.addSuppressed(anchorFailure);
                    throw failure;
                }
            }
        }
    }

}
