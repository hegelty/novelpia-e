package me.crema.novelia.net;

import android.content.Context;

import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.List;

/**
 * Small diagnostic status provider for the networking layer.
 *
 * <p>Exposes a terse, copy-safe description of the TLS/trust/redirect posture
 * together with a health check that verifies the bundled root certificate
 * asset is loadable and currently valid. No credentials, tokens, cookies or
 * request bodies are ever exposed. Intended to be called only when diagnostics
 * are displayed to the user.</p>
 */
public final class NetStatus {

    private static final String OK = "ok";

    private NetStatus() {
        // Static utility.
    }

    /** TLS/trust/redirect posture of {@link NativeHttp}. */
    public static String description(Context context) {
        return new NativeHttp(context).getDiagnosticsDescription();
    }

    /**
     * Loads the bundled Amazon Root CA 1 and returns a short health summary:
     * {@code ok} plus validity/contents info, or a non-sensitive error class
     * name and message.
     */
    public static String check(Context context) {
        try {
            List<X509Certificate> certs = NativeHttp.loadBundledCertificates(context);
            if (certs.isEmpty()) {
                return "error: no bundled root certificate";
            }
            X509Certificate root = certs.get(0);
            root.checkValidity();
            return OK + "(AmazonRootCA1 valid, SHA-256 "
                    + fingerprintOf(root) + ")";
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.length() > 160) {
                message = message.substring(0, 160);
            }
            return "error: " + e.getClass().getSimpleName()
                    + (message == null ? "" : ": " + message);
        }
    }

    private static String fingerprintOf(X509Certificate certificate) {
        try {
            byte[] der = certificate.getEncoded();
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(der);
            StringBuilder sb = new StringBuilder(hash.length * 3);
            for (int i = 0; i < hash.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format(Locale.US, "%02X", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
