package me.crema.novelia.account;

import me.crema.novelia.net.NativeHttp;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches only the normal episode-list metadata, never chapter bodies. */
public final class LatestEpisodeClient {
    private static final String ENDPOINT = "https://novelpia.com/proc/episode_list_viewer";
    private static final Pattern NOVEL = Pattern.compile("https://novelpia\\.com/novel/([0-9]{1,12})");
    private static final Pattern LABEL = Pattern.compile("EP\\.([0-9]{1,9})");
    private static final int MAX_CACHE = 128;
    private static final long SUCCESS_TTL_MS = 120000L;
    private static final long UNKNOWN_TTL_MS = 30000L;
    private final HttpAgent http;
    private final Clock clock;
    private int generation;
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<String, Cached>();

    public LatestEpisodeClient(final NativeHttp http) {
        this(new HttpAgent() {
            @Override public String post(String url, Map<String, String> form) throws IOException {
                return http.post(url, form);
            }
        }, new Clock() {
            @Override public long now() { return System.nanoTime() / 1000000L; }
        });
        if (http == null) throw new IllegalArgumentException("NativeHttp required");
    }

    LatestEpisodeClient(HttpAgent http, Clock clock) {
        if (http == null || clock == null) throw new IllegalArgumentException("transport and clock required");
        this.http = http;
        this.clock = clock;
    }

    /**
     * Call off the UI thread for visible rows only. Returns the actual newest
     * site's EP label, or empty if unknown. Never derives it from a count or ID.
     * Each cache miss makes at most one metadata request; failed lookups have a
     * short retry delay so rendering cannot repeatedly hit a failing endpoint.
     */
    public String latestLabel(String novelUrl) {
        Matcher novel = NOVEL.matcher(novelUrl == null ? "" : novelUrl);
        if (!novel.matches()) return "";
        long now = clock.now();
        final int version;
        synchronized (this) {
            Cached prior = cache.get(novelUrl);
            if (prior != null && now < prior.expires) return prior.label;
            version = generation;
        }
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("novel_no", novel.group(1));
        // Observed 2026-10-03: UP/page 0 renders EP.79, EP.78, ...;
        // DOWN/page 0 renders EP.0, EP.1, ... for the same novel.
        form.put("sort", "UP");
        form.put("page", "0");
        String label;
        try { label = parseLatestLabel(http.post(ENDPOINT, form)); }
        catch (IOException error) { label = ""; }
        synchronized (this) {
            if (version == generation) {
                cache.remove(novelUrl);
                cache.put(novelUrl, new Cached(label, clock.now()
                        + (label.isEmpty() ? UNKNOWN_TTL_MS : SUCCESS_TTL_MS)));
                while (cache.size() > MAX_CACHE) cache.remove(cache.keySet().iterator().next());
            }
        }
        return label;
    }

    /** Explicit refresh may invalidate metadata without clearing user state. */
    public synchronized void clear() { generation++; cache.clear(); }

    static String parseLatestLabel(String html) {
        if (html == null || html.isEmpty() || html.length() > 2 * 1024 * 1024) return "";
        Document doc = Jsoup.parse(html);
        if (doc.select("table#episode_table").size() != 1) return "";
        String latest = "";
        int previous = Integer.MAX_VALUE;
        int rows = 0;
        for (Element row : doc.select("table#episode_table tr[data-episode-no]")) {
            if (++rows > 200 || !row.attr("data-episode-no").matches("[0-9]{1,12}")) return "";
            String found = "";
            int number = -1;
            for (Element span : row.select(".ep_style2 span")) {
                Matcher match = LABEL.matcher(span.ownText().trim());
                if (!match.matches()) continue;
                if (!found.isEmpty()) return "";
                found = span.ownText().trim();
                number = Integer.parseInt(match.group(1));
            }
            // A row without an EP label could be a notice, gated metadata, or
            // an unfamiliar shape. Do not label an older row as the newest.
            if (found.isEmpty() || number > previous) return "";
            if (latest.isEmpty()) latest = found;
            previous = number;
        }
        return latest;
    }

    interface HttpAgent { String post(String url, Map<String, String> form) throws IOException; }
    interface Clock { long now(); }
    private static final class Cached {
        final String label;
        final long expires;
        Cached(String label, long expires) { this.label = label; this.expires = expires; }
    }
}
