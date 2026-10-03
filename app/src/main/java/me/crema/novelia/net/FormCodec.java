package me.crema.novelia.net;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;

/**
 * Pure-Java UTF-8 {@code application/x-www-form-urlencoded} encoder.
 *
 * <p>Kept free of Android and Conscrypt dependencies so it can be compiled and
 * tested on a plain JVM.</p>
 */
public final class FormCodec {

    public static final String CONTENT_TYPE = "application/x-www-form-urlencoded";

    private FormCodec() {
        // Static utility.
    }

    /**
     * Encodes {@code form} as UTF-8 form data; {@code null}/empty maps yield
     * an empty body. Null keys or values are rejected because they cannot be
     * represented safely in a form body.
     */
    public static byte[] encode(Map<String, String> form) throws IOException {
        if (form == null || form.isEmpty()) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : form.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || value == null) {
                throw new IOException("Form data contains a null key or value; "
                        + "keys and values must be non-null strings");
            }
            if (!first) {
                sb.append('&');
            }
            sb.append(encodeComponent(name));
            sb.append('=');
            sb.append(encodeComponent(value));
            first = false;
        }
        try {
            return sb.toString().getBytes("UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IOException("UTF-8 not supported by this runtime", impossible);
        }
    }

    private static String encodeComponent(String component) throws IOException {
        try {
            return URLEncoder.encode(component, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IOException("UTF-8 not supported by this runtime", impossible);
        }
    }
}
