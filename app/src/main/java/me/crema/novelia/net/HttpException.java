package me.crema.novelia.net;

/**
 * Network or protocol failure thrown by {@link NativeHttp}.
 *
 * <p>The message is user-presentable but intentionally contains no body,
 * cookie or credential data. The HTTP status (when the server answered) can be
 * read via {@link #getStatusCode()}; {@code -1} means no usable status was
 * received (e.g. connect failure, timeout or redirect abuse).</p>
 */
public class HttpException extends java.io.IOException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    /** @param message safe, non-sensitive description */
    public HttpException(String message) {
        this(message, -1, null);
    }

    /** @param message safe, non-sensitive description */
    public HttpException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    /** @param message safe, non-sensitive description */
    public HttpException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public HttpException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP status code of the failed response, or {@code -1} if unknown. */
    public int getStatusCode() {
        return statusCode;
    }
}
