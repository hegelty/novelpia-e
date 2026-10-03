package me.crema.novelia.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpCookie;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Strict pure-Java parser for the {@code novelia-session-v1} export produced
 * by the bundled Chrome helper.
 *
 * <p>The helper only ever reads Novelpia cookies from the browser's own
 * cookie store, and this parser is a pure validator: it never contacts the
 * network, never queries browser or HTTP cookie jars, and never handles
 * passwords. The whole document is validated before anything is returned, so
 * one foreign cookie, one control character or one oversized field rejects the
 * entire import instead of silently producing a partial, misleading session.
 * Error messages never contain cookie names, values or other secrets.</p>
 *
 * <p>Kept free of Android-only API surface so it compiles and tests on a
 * plain JVM with the platform {@code org.json} implementation (API 19).</p>
 */
public final class SessionImport {

    /** Schema identifier required verbatim by {@link #parse}. */
    public static final String FORMAT = "novelia-session-v1";

    /** Maximum number of cookies accepted per export. */
    public static final int MAX_COOKIES = 128;

    /** Maximum UTF-8 bytes accepted by {@link #read}. */
    public static final int MAX_READ_BYTES = 128 * 1024;

    /** Export older than this many seconds is a requested fresh export. */
    public static final long STALE_SECONDS = 24L * 60L * 60L;

    /** An export dated more than five minutes in the future is rejected. */
    public static final long CLOCK_SKEW_FUTURE_SECONDS = 5L * 60L;

    /** RFC 6265 token characters allowed in cookie names. */
    private static final String TOKEN_CHARS =
            "!#$%&'*+-.^_`|~" + "0123456789" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            + "abcdefghijklmnopqrstuvwxyz";

    /** Upper bound for a cookie value, far beyond the site's real sizes. */
    private static final int MAX_VALUE_CHARS = 8192;

    /* Match the helper's bounds: no cookie name longer than 128 characters
     * and the whole exported document stays under 128 KiB. */
    private static final int MAX_NAME_CHARS = 128;

    private SessionImport() {
        // Static utility.
    }

    /**
     * Parses and validates one {@code novelia-session-v1} document.
     *
     * @param json            document to validate (never logged or echoed)
     * @param nowEpochSeconds caller's wall-clock Unix seconds; cookies whose
     *                        {@code expirationDate} has passed are skipped
     * @return an immutable, non-empty list of VERSION 0 cookies with the
     *         exported domain/path preserved, HTTPS-only forced on, and a
     *         safe remaining lifetime
     * @throws IOException on malformed, foreign, non-matching-format, stale
     *                     or empty input (without cookie values in messages)
     */
    public static List<HttpCookie> parse(String json, long nowEpochSeconds)
            throws IOException {
        validateBounds(json);
        JSONObject root;
        try {
            root = new JSONObject(json == null ? "{}" : json);
        } catch (JSONException e) {
            // org.json exception messages can quote the input credential.
            throw new IOException("Session file is not valid JSON");
        }
        Object rawFormat = root.opt("format");
        if (!(rawFormat instanceof String) || !FORMAT.equals(rawFormat)) {
            throw new IOException("Unsupported session file format (expected "
                    + FORMAT + ")");
        }
        Object rawExportedAt = root.opt("exportedAt");
        if (!(rawExportedAt instanceof Number)) {
            throw new IOException("Session file is missing a Unix-seconds "
                    + "exportedAt timestamp");
        }
        double exportedAt = ((Number) rawExportedAt).doubleValue();
        if (Double.isNaN(exportedAt) || Double.isInfinite(exportedAt)
                || exportedAt < 0 || exportedAt != Math.floor(exportedAt)) {
            throw new IOException("Invalid export timestamp");
        }
        double age = (double) nowEpochSeconds - exportedAt;
        if (age > STALE_SECONDS) {
            throw new IOException("Session export is older than 24 hours; "
                    + "export a fresh session file and import it again");
        }
        if (age < -CLOCK_SKEW_FUTURE_SECONDS) {
            throw new IOException("Session export timestamp is in the future; "
                    + "check the device clock and export again");
        }

        Object cookies = root.opt("cookies");
        if (!(cookies instanceof JSONArray)) {
            throw new IOException("Session file has no cookies list");
        }
        JSONArray array = (JSONArray) cookies;
        if (array.length() > MAX_COOKIES) {
            throw new IOException("Session file contains more than "
                    + MAX_COOKIES + " cookies");
        }

        List<HttpCookie> result = new ArrayList<HttpCookie>(array.length());
        for (int i = 0; i < array.length(); i++) {
            HttpCookie cookie = parseCookie(array.opt(i), nowEpochSeconds);
            if (cookie != null) {
                result.add(cookie);
            }
        }
        if (result.isEmpty()) {
            throw new IOException("Session file contains no usable cookies; "
                    + "log in and export a fresh session");
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Reads at most {@value #MAX_READ_BYTES} UTF-8 bytes from {@code in} and
     * returns the decoded string. Oversized payloads are rejected rather than
     * truncated so a huge or hostile file can never be half-parsed.
     *
     * @throws IOException on cap overflow or decoding failure
     */
    public static String read(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("Null session file stream");
        }
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(Math.min(8192, MAX_READ_BYTES));
        byte[] buffer = new byte[4096];
        while (true) {
            int count = in.read(buffer);
            if (count < 0) {
                break;
            }
            if (out.size() + count > MAX_READ_BYTES) {
                throw new IOException("Session file exceeds the "
                        + MAX_READ_BYTES + " byte limit");
            }
            out.write(buffer, 0, count);
        }
        byte[] raw = out.toByteArray();
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("Session file is not valid UTF-8", invalid);
        }
    }

    /** Returns a cookie, or null for a valid but already-expired one. */
    private static HttpCookie parseCookie(Object item, long nowEpochSeconds)
            throws IOException {
        if (!(item instanceof JSONObject)) {
            throw new IOException("Session file contains an invalid cookie "
                    + "entry (cookies must be objects)");
        }
        JSONObject object = (JSONObject) item;

        String rawName = requiredString(object, "name");
        if (rawName == null || !isValidName(rawName)) {
            throw new IOException("Session file contains a cookie with an "
                    + "invalid name");
        }
        String value = requiredString(object, "value");
        if (value == null || !isValidValue(value)) {
            throw new IOException("Session file contains a cookie with an "
                    + "unsafe or oversized value");
        }

        // HttpCookie's own domain setter is more lenient, so the strict form
        // is checked first and the cookie only ever receives the verified
        // ("", ".novelpia.com" or "<label>.novelpia.com") value.
        String domain = requiredString(object, "domain");
        if (!isValidNovelpiaDomain(domain)) {
            throw new IOException("Session file contains a cookie for a "
                    + "disallowed domain (only novelpia.com and its "
                    + "subdomains are accepted)");
        }

        String path = requiredString(object, "path");
        if (path == null || path.length() == 0 || path.charAt(0) != '/'
                || path.length() > 2048 || containsControl(path)
                || path.indexOf(';') >= 0 || path.indexOf('"') >= 0 || path.indexOf('\\') >= 0) {
            throw new IOException("Session file contains a cookie with an "
                    + "invalid path");
        }

        boolean secure = false;
        if (object.has("secure")) {
            Object secureValue = object.opt("secure");
            if (!(secureValue instanceof Boolean)) {
                throw new IOException("Session file contains a cookie with a "
                        + "non-boolean secure flag");
            }
            secure = ((Boolean) secureValue).booleanValue();
        }

        Object expiration = object.opt("expirationDate");
        if (expiration != null && !(expiration instanceof Number)) {
            throw new IOException("Session file contains a cookie with a "
                    + "non-numeric expirationDate");
        }
        if (expiration != null) {
            double doubleValue = ((Number) expiration).doubleValue();
            if (Double.isNaN(doubleValue) || doubleValue < 0.0d
                    || isIntegerLockedAtMax(doubleValue)) {
                throw new IOException("Session file contains a cookie with an "
                        + "invalid expirationDate");
            }
            double remaining = doubleValue - (double) nowEpochSeconds;
            if (remaining <= 0.0d) {
                return null; // Expired already; valid output may omit it.
            }
            double floor = Math.floor(remaining);
            long maxAge = (long) Math.min(floor, (double) Integer.MAX_VALUE);
            return build(rawName, value, domain, path, secure, Math.max(1L, maxAge));
        }

        // No expirationDate: a browser session cookie. Keep web semantics and
        // expire it with the process (HttpCookie maxAge -1).
        return build(rawName, value, domain, path, secure, -1L);
    }

    private static HttpCookie build(String name, String value, String domain,
            String path, boolean secure, long maxAge) throws IOException {
        try {
            // Input was validated above; a remaining constructor or setter
            // failure is impossible and must never leak cookie values.
            HttpCookie cookie = new HttpCookie(name, value);
            cookie.setDomain(domain);
            cookie.setPath(path);
            cookie.setVersion(0);
            cookie.setSecure(true); // HTTPS-only client; never plaintext.
            cookie.setMaxAge(maxAge);
            return cookie;
        } catch (IllegalArgumentException impossible) {
            throw new IOException("Rejected a malformed cookie entry", impossible);
        }
    }

    /** RFC 6265 token, per Chrome's practical 128-character name ceiling. */
    private static boolean isValidName(String name) {
        if (name.length() == 0 || name.length() > MAX_NAME_CHARS) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (TOKEN_CHARS.indexOf(name.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Header-safe value: visible ASCII only, no CR/LF/NUL/semicolon/quote. */
    private static boolean isValidValue(String value) {
        if (value.length() > MAX_VALUE_CHARS) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0x7F || c < 0x20 || c == 0x7F || c == ';' || c == '"') {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code novelpia.com} or a strict subdomain of it, with an optional
     * leading dot. Rejects foreign hosts, lookalikes (e.g.
     * {@code novelpia.com.evil.example}), IPs, userinfo and every non-DNS
     * character, mirroring {@link UrlPolicy#isValidHostname} after stripping
     * the optional dot.
     */
    private static boolean isValidNovelpiaDomain(String domain) {
        if (domain == null || domain.length() < "novelpia.com".length()) {
            return false;
        }
        int start = 0;
        if (domain.charAt(0) == '.') {
            start = 1;
        }
        return isValidHostname(domain.substring(start));
    }

    private static boolean isValidHostname(String host) {
        if (host.length() == 0 || host.length() > 253) {
            return false;
        }
        if (host.startsWith(".") || host.endsWith(".")) {
            return false;
        }
        if (host.indexOf('\\') >= 0 || host.indexOf('/') >= 0) {
            return false;
        }
        if (!host.endsWith(".novelpia.com") && !host.equals("novelpia.com")) {
            return false;
        }
        String labels = host.equals("novelpia.com")
                ? "novelpia.com" : host.substring(0, host.length() - 13);
        for (String label : labels.split("\\.", -1)) {
            if (label.length() == 0 || label.length() > 63 || label.startsWith("-")
                    || label.endsWith("-")) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '-')) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean containsControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static String requiredString(JSONObject object, String key) throws IOException {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw new IOException("Missing or invalid cookie field");
        return (String) value;
    }

    /** Bound recursion before Android's recursive JSON parser sees input. */
    private static void validateBounds(String json) throws IOException {
        if (json == null || json.length() > MAX_READ_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_READ_BYTES) {
            throw new IOException("Session file exceeds the size limit");
        }
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '[' || c == '{') {
                if (++depth > 16) throw new IOException("Session file nesting exceeds the limit");
            } else if (c == ']' || c == '}') depth--;
            // Reject nonstandard quoted strings/comments accepted by some org.json versions.
            else if (c == '\'' || c == '/' || c == '#')
                throw new IOException("Session file is not standard JSON");
        }
    }

    /**
     * org.json doubles equal to the largest long lose precision; at those
     * magnitudes the cookie is long past its session anyway, so rejecting is
     * safer than guessing. Nothing below {@link Integer#MAX_VALUE} is hit,
     * which is all the remaining-lifetime clamp needs.
     */
    private static boolean isIntegerLockedAtMax(double value) {
        return value >= 9223372036854775807.0d;
    }
}
