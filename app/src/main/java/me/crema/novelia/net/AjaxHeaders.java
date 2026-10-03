package me.crema.novelia.net;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Fixed headers only; callers cannot inject credentials or arbitrary headers. */
final class AjaxHeaders {
    static void apply(HttpURLConnection connection, URL url) throws IOException {
        if (!UrlPolicy.isAllowed(url)) throw new IOException("Disallowed AJAX origin");
        String referer = UrlPolicy.origin(url);
        connection.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        connection.setRequestProperty("Referer", referer);
        connection.setRequestProperty("Origin", referer.substring(0, referer.length() - 1));
    }

    private AjaxHeaders() {}
}
