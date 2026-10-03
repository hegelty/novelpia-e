package me.crema.novelia.site;

import me.crema.novelia.net.NativeHttp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Native Novelpia adapter. Uses only endpoints and data shapes observed in
 * Novelpia's public HTML and JS (see docs/SITE.md for the exact evidence).
 * No paywall/age-gate bypass, no ticket or purchase consumption, no
 * credential probing, no endpoint guessing.
 *
 * <p>Public contract (parent app):
 * <ul>
 *   <li>{@link #browse(String)} - legacy/book catalog HTML pages (also the
 *       real search flow via /proc/novel, which the MainActivity search URL
 *       is translated into inside {@link #browse}).
 *       Legacy {@code /search?search_string=...} contains no server-rendered
 *       results, so it is translated to the page's own AJAX search
 *       ({@code GET /proc/novel?cmd=novel_search&...}) instead of returning
 *       an empty list.</li>
 *   <li>{@link #episodes(String,int)} - {@code POST /proc/episode_list_viewer}
 *       with 0-based {@code page} (the public viewer JS reads
 *       {@code localStorage['novel_page_<novel_no>']} and posts it as
 *       {@code page}: 0 = first sheet).</li>
 *   <li>{@link #login(String,String)} - {@code POST /proc/login} with the
 *       user's own credentials; never throws, never stores the password.</li>
 *   <li>{@link #readChapter(String)} - reproduces the normal entitled
 *       client flow: GET the SSR viewer page, then POST the same
 *       {@code /proc/viewer_data/{content_no}} request the public viewer
 *       makes (data: {@code size=14}, {@code viewer_paging}). The server
 *       enforces login/entitlement exactly as in the browser - this client
 *       sends nothing extra and performs no purchase - and a non-200/error
 *       payload raises an informative {@link IOException}. If a server
 *       payload was ever observed to be encrypted at rest, decryption would
 *       need the site's AES routine; as captured (2026-09-27) the fields are
 *       served as plain text, so no decryption is reproduced or needed.</li>
 * </ul>
 *
 * <p>URL policy: only absolute https URLs on novelpia.com (or *.novelpia.com)
 * and root-relative paths are accepted. {@code novelpia.com.evil} and other
 * look-alike hosts are rejected via strict {@link URI} host comparison.
 */
public final class SiteClient {

    /** Primary host used for all endpoints and href absolutization. */
    static final String HOST = "https://novelpia.com";
    private static final Pattern NUM = Pattern.compile("[0-9]{1,12}");
    private static final int CATALOG_CAP = 100;
    private static final int EPISODE_CAP = 200;
    private static final int MAX_TITLE_CHARS = 1000;
    private static final int MAX_EPISODE_TITLE_CHARS = 500;
    private static final int MAX_EPISODE_LABEL_CHARS = 80;
    private static final String VIEWER_PATH_PREFIX = "/viewer/";

    /**
     * The public viewer's <title> slogan, stripped only when it is an exact
     * prefix (anchored at the start) and tolerant of optional whitespace
     * around the dashes and before '!'. Never a global replace, so a slogan
     * inside a legitimate title is preserved.
     */
    private static final Pattern SLOGAN_PREFIX = Pattern.compile(
            "^노벨피아\\s*-\\s*웹소설로\\s*꿈꾸는\\s*세상\\s*!\\s*-\\s*");

    private final HttpAgent agent;

    /** @param http platform HTTP agent (me.crema.novelia.net.NativeHttp) */
    public SiteClient(NativeHttp http) {
        if (http == null) throw new IllegalArgumentException("NativeHttp required");
        this.agent = new RealAgent(http);
    }

    /** Test seam: package-private so unit tests can fake the final NativeHttp. */
    SiteClient(HttpAgent agent) {
        if (agent == null) throw new IllegalArgumentException("HttpAgent required");
        this.agent = agent;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Browse a catalog page, or translate the MainActivity search URL
     * ({@code .../search?search_string=KEYWORD}) into the search flow the
     * public site itself runs (the legacy search HTML is Vue-rendered and
     * contains no server-side results; the site fetches
     * {@code /proc/novel?cmd=novel_search} via AJAX).
     *
     * <p>HTML catalog pages: extracts {@code /novel/<id>} and
     * {@code /viewer/<id>} links, capped (100 novel / 200 chapter rows),
     * de-duplicated by URL. Search: uses the site's own query parameters.
     *
     * @throws IOException if the server reports an error (e.g. search status
     *         != 200) - never silently returns an empty result for a
     *         known-supported flow.
     */
    public List<Entry> browse(String url) throws IOException {
        String target = allowed(url, "browse");
        if (isMainSearchUrl(target)) {
            return search(target);
        }
        String html = agent.get(target);
        if (html == null || html.length() == 0) {
            throw new IOException("서버 응답이 비어 있습니다.");
        }
        return parseCatalog(html);
    }

    /**
     * Episode sheet. {@code page} is 0-based (the site's viewer JS posts
     * {@code localStorage['novel_page_<id>']} as {@code page}; page 0 is the
     * first sheet).
     */
    public List<Entry> episodes(String novelId, int page) throws IOException {
        if (novelId == null || !NUM.matcher(novelId).matches() || page < 0) {
            return new ArrayList<Entry>();
        }
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("novel_no", novelId);
        form.put("sort", "up");
        form.put("page", String.valueOf(page));
        String html = agent.post(HOST + "/proc/episode_list_viewer", form);
        return parseEpisodeSheet(html, novelId);
    }

    /**
     * Login with the user's own credentials via the observed form endpoint
     * {@code POST /proc/login} (fields {@code email}, {@code wd},
     * {@code redirectrurl}). The password is used once and never stored.
     * Never throws; returns {@code success=false} with a message on failure.
     */
    public LoginResult login(String email, String password) {
        if (email == null || password == null
                || email.trim().length() == 0 || password.length() == 0) {
            return new LoginResult(false, "이메일과 비밀번호를 입력해주세요.");
        }
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("email", email.trim());
        form.put("wd", password);
        form.put("redirectrurl", "");
        try {
            String body = agent.post(HOST + "/proc/login", form);
            if (body == null || body.length() == 0) {
                return new LoginResult(false, "서버 응답이 없습니다.");
            }
            return LoginResult.from(body);
        } catch (IOException e) {
            return new LoginResult(false, "네트워크 오류");
        }
    }

    /**
     * Read an entitled chapter by reproducing the viewer's normal load flow:
     * GET the SSR {@code /viewer/{content_no}} page for metadata and the
     * viewer's own POST to {@code /proc/viewer_data/{content_no}} for the
     * body. No bypass: server-side login/entitlement checks are untouched
     * and nothing is purchased. A gated/unauthed payload raises an
     * informative {@link IOException} instead of fake text.
     *
     * <p>Title: the Novelpia slogan is stripped only as an exact prefix (with
     * the legacy prefix/suffix fallback) so the title is the novel/episode
     * title alone; it never does a global replace of middle text. Episode
     * title/tag come only from the observed viewer wrapper and the novel URL
     * from {@code #novel_no}; prev/next come from the observed id/name fields
     * and auto URLs, validated strictly (see {@link #navUrl}).
     */
    public Chapter readChapter(String viewerUrl) throws IOException {
        String target = allowed(viewerUrl, "readChapter");
        String html = agent.get(target);
        if (html == null || html.length() == 0) {
            throw new IOException("뷰어 응답이 비어 있습니다.");
        }
        Document doc = Jsoup.parse(html);
        String title = bounded(cleanTitle(doc.title()), MAX_TITLE_CHARS);
        String contentNo = value(doc, "input[name=content_no]");
        if (contentNo == null || contentNo.length() == 0) {
            contentNo = value(doc, "#content_no");
        }
        if (!num(contentNo)) {
            throw new IOException("뷰어 페이지에서 회차 번호를 확인할 수 없습니다.");
        }

        // Never invent navigation or ordinals from the content id: an absent
        // or unsafe value is simply an empty URL.
        String nextUrl = navUrl(doc, contentNo, target, new String[] {
                "#content_no_next", "input[name=content_no_next]",
                "#next_epi_auto_url", "input[name=next_epi_auto_url]"});
        String prevUrl = navUrl(doc, contentNo, target, new String[] {
                "#content_no_pre", "input[name=content_no_pre]",
                "#back_epi_auto_url", "input[name=back_epi_auto_url]"});
        String episodeTitle = bounded(
                text(doc, ".menu-title-wrapper > .menu-top-title"),
                MAX_EPISODE_TITLE_CHARS);
        String episodeLabel = bounded(
                text(doc, ".menu-title-wrapper > .menu-top-tag"),
                MAX_EPISODE_LABEL_CHARS);
        String novelUrl = novelUrl(doc);

        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("size", "14");
        form.put("viewer_paging", ""); // first sheet = false in the site's viewer
        String body = agent.post(HOST + "/proc/viewer_data/" + contentNo, form);

        String text = parseViewerBody(body, contentNo);
        return new Chapter(title, text, target, nextUrl, prevUrl,
                episodeTitle, episodeLabel, novelUrl);
    }

    // ------------------------------------------------------------------
    // Search (observed in the public /search page JS: Vue component)
    // ------------------------------------------------------------------

    /**
     * Translates the MainActivity URL
     * {@code https://novelpia.com/search/all//1/KEYWORD?page=N&rows=M&...}
     * or the legacy {@code /search?search_string=KEYWORD} into the site's own
     * AJAX search {@code GET /proc/novel?cmd=novel_search&...} and parses the
     * returned JSON list.
     */
    private List<Entry> search(String target) throws IOException {
        String keyword = searchKeyword(target);
        if (keyword.length() == 0) {
            throw new IOException("검색 키워드가 없습니다.");
        }
        Map<String, String> data = searchParams(target);
        data.put("cmd", "novel_search");
        data.put("search_type", "all");
        data.put("search_val", keyword);
        // Allow-listed URL params pass through so the caller can control
        // pagination/sorting; private keys (search_string/search_val/unknown)
        // are never forwarded. Defaults only fill what the caller omitted.
        putDefault(data, "page", "1");
        putDefault(data, "rows", "30");
        putDefault(data, "sort_col", "last_viewdate");
        putDefault(data, "list_display", "list");
        putDefault(data, "block_out", "0");
        putDefault(data, "block_stop", "0");
        putDefault(data, "is_contest", "0");

        String body = agent.get(HOST + "/proc/novel?" + formEncode(data));
        if (body == null || body.length() == 0) {
            throw new IOException("검색 서버 응답이 비어 있습니다.");
        }
        try {
            Object list = new JsonReader(body).at("list");
            if (list == null || !(list instanceof List)) {
                throw new IOException("검색 결과 형식을 확인할 수 없습니다.");
            }
            List<Entry> out = new ArrayList<Entry>();
            Set<String> seen = new HashSet<String>();
            for (Object item : (List<?>) list) {
                Map<String, Object> row = JsonReader.object(item);
                if (row == null) continue;
                String no = JsonReader.string(row.get("novel_no"));
                if (!num(no) || !seen.add(HOST + "/novel/" + no)) continue;
                if (out.size() >= 200) break;
                out.add(new Entry(
                        textOr(JsonReader.string(row.get("novel_name")), "소설 " + no),
                        HOST + "/novel/" + no,
                        "novel",
                        searchDetail(row)));
            }
            return out;
        } catch (RuntimeException e) {
            throw new IOException("검색 응답을 해석할 수 없습니다: " + e.getMessage());
        }
    }

    private static String searchDetail(Map<String, Object> row) {
        List<String> parts = new ArrayList<String>();
        try {
            Object genre = row.get("novel_genre_arr");
            if (genre instanceof List) {
                for (Object g : (List<?>) genre) {
                    String s = JsonReader.string(g);
                    if (s != null && s.length() > 0) parts.add(s);
                }
            }
            String writer = JsonReader.string(row.get("writer_nick"));
            if (writer != null && writer.length() > 0) parts.add("작가: " + writer);
            Integer complete = JsonReader.integer(row.get("is_complete"));
            if (complete != null && complete.intValue() == 1) parts.add("완결");
            Integer count = JsonReader.integer(row.get("count_book"));
            if (count != null) parts.add(count + "회차");
        } catch (RuntimeException ignore) {
            // details are best-effort; a parse hiccup must not kill the row
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    /** Is this the MainActivity search URL (/search or /search/all/...)? */
    private static boolean isMainSearchUrl(String s) {
        String p = s.startsWith(HOST) ? s.substring(HOST.length()) : s;
        String path = p;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path.equals("/search")
                || path.startsWith("/search/");
    }

    /**
     * Keyword from the search URL: last path segment of
     * /search/all//1/{keyword} (site's own encoding) or &search_string=
     * (legacy search box).
     */
    private static String searchKeyword(String target) {
        String p = target.startsWith(HOST) ? target.substring(HOST.length()) : target;
        int q1 = p.indexOf('?');
        String path = q1 >= 0 ? p.substring(0, q1) : p;
        String query = q1 >= 0 ? p.substring(q1 + 1) : "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = pair.substring(0, eq);
            if (k.equals("search_string") || k.equals("search_val")) {
                String v = decode(pair.substring(eq + 1));
                return v == null ? "" : v.trim();
            }
        }
        int last = path.lastIndexOf('/');
        if (last >= 0 && last + 1 < path.length()) {
            String seg = decode(path.substring(last + 1));
            if (seg != null) return seg.trim();
        }
        return "";
    }

    private static void putDefault(Map<String, String> m, String key, String value) {
        if (!m.containsKey(key)) m.put(key, value);
    }

    private static final Set<String> SEARCH_ALLOWED = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "page", "rows", "novel_type", "start_count_book", "end_count_book",
                    "novel_age", "start_days", "sort_col", "novel_genre",
                    "block_out", "block_stop", "is_contest", "is_complete",
                    "is_challenge", "list_display")));

    private static Map<String, String> searchParams(String target) {
        Map<String, String> m = new HashMap<String, String>();
        String p = target.startsWith(HOST) ? target.substring(HOST.length()) : target;
        int q = p.indexOf('?');
        if (q < 0) return m;
        for (String pair : p.substring(q + 1).split("&")) {
            if (pair.length() == 0) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = pair.substring(0, eq).trim();
            if (!SEARCH_ALLOWED.contains(k)) continue; // never forward unknown keys
            String v = eq + 1 <= pair.length() ? pair.substring(eq + 1) : "";
            String d = decode(v);
            m.put(k, d == null ? "" : d.trim());
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Viewer body parsing
    // ------------------------------------------------------------------

    /**
     * Parses the authenticated /proc/viewer_data JSON. The public viewer
     * iterates {@code data.s} and renders each {@code value.text} as one
     * line. A gate/error payload instead yields an informative IOException;
     * never doc.text() of any page and never fabricated prose.
     */
    private static final int MAX_BODY_CHARS = 300_000;

    private static String parseViewerBody(String body, String contentNo) throws IOException {
        if (body == null) {
            throw new IOException("본문 응답이 없습니다.");
        }
        String t = body.trim();
        if (t.length() == 0) {
            throw new IOException("본문 응답이 없습니다.");
        }
        String small = t.length() < 2048 ? t : t.substring(0, 2048);
        if (!small.startsWith("{")) {
            throw new IOException(formatGate(t));
        }
        Object root;
        try {
            root = new JsonReader(t).root();
        } catch (RuntimeException e) {
            throw new IOException("본문 응답을 해석할 수 없습니다: " + e.getMessage());
        }
        if (!(root instanceof Map)) {
            throw new IOException(formatGate(t));
        }
        Map<String, Object> map = (Map<String, Object>) root;
        Integer status = JsonReader.integer(map.get("status"));
        if (status != null && status.intValue() != 200) {
            String msg = JsonReader.string(map.get("errmsg"));
            throw new IOException("본문을 불러오지 못했습니다: "
                    + textOr(msg, "status " + status + " (로그인 또는 구매 필요)"));
        }
        Object s = map.get("s");
        if (!(s instanceof List) || ((List<?>) s).isEmpty()) {
            String err = JsonReader.string(map.get("errmsg"));
            if (err != null && err.length() > 0) {
                throw new IOException("본문을 불러오지 못했습니다: " + err);
            }
            throw new IOException("본문이 비어 있거나 열람 권한이 없습니다 (이용권/구매 로그인 필요). 회차 " + contentNo);
        }
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (Object item : (List<?>) s) {
            Map<String, Object> row = JsonReader.object(item);
            if (row == null) continue;
            String text = JsonReader.string(row.get("text"));
            if (text == null) text = "";
            text = jqHtml(text);
            if (sb.length() > 0) sb.append('\n');
            sb.append(text);
            lines++;
        }
        if (lines == 0) {
            throw new IOException("본문이 비어 있거나 열람 권한이 없습니다 (이용권/구매 로그인 필요). 회차 " + contentNo);
        }
        if (sb.length() > MAX_BODY_CHARS) {
            // Never truncate: a split UTF-16 surrogate would corrupt the page
            // and silent truncation hides real content loss. Fail loudly instead.
            throw new IOException("본문이 너무 커서 표시할 수 없습니다 (300,000자 초과): 회차 " + contentNo);
        }
        return sb.toString();
    }

    private static String formatGate(String t) {
        String s = smallText(t);
        if (s.length() == 0) {
            return "본문을 불러오지 못했습니다: 로그인 또는 구매 필요";
        }
        return "본문을 불러오지 못했습니다: " + s;
    }

    /** A short plain-text representation of a non-JSON (gate) payload. */
    private static String smallText(String t) {
        String s = t == null ? "" : t.trim();
        if (s.length() > 300) s = s.substring(0, 300);
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 200) s = s.substring(0, 200);
        return s;
    }

    /**
     * Normalizes one viewer line the way the site JS renders it (one s[] entry
     * per line). Embedded markup is dropped (scripts/styles/images); html text
     * is unescaped by jsoup. This is a line-level sanitizer only - the chapter
     * body is built from the server's own viewer_data JSON, never from
     * doc.text() of a page, and never fabricated.
     */
    private static String jqHtml(String s) {
        if (s == null) return "";
        String t = s.replace("\r", "");
        if (t.indexOf('<') >= 0) {
            Document frag = Jsoup.parseBodyFragment(t);
            frag.select("script, style, img, iframe, video, audio, object, embed, form").remove();
            t = frag.text();
        }
        return t.replace("&nbsp;", " ").trim();
    }

    // ------------------------------------------------------------------
    // Catalog / episode parsing (package-private static for fixture tests)
    // ------------------------------------------------------------------

    static List<Entry> parseCatalog(String html) {
        Document doc = Jsoup.parse(html);
        List<Entry> out = new ArrayList<Entry>();
        Set<String> seen = new HashSet<String>();
        for (Element a : doc.select("a[href*='/novel/']")) {
            String id = id(a.attr("href"));
            if (id == null) continue;
            String abs = abs(a.attr("href"));
            if (abs == null || !seen.add(abs)) continue;
            if (out.size() >= CATALOG_CAP) break;
            out.add(new Entry(textOr(a.text(), "소설 " + id), abs, "novel", ""));
        }
        for (Element a : doc.select("a[href*='/viewer/']")) {
            String id = id(a.attr("href"));
            if (id == null) continue;
            String abs = abs(a.attr("href"));
            if (abs == null || !seen.add(abs)) continue;
            if (out.size() >= EPISODE_CAP) break;
            out.add(new Entry(textOr(a.text(), "회차 " + id), abs, "chapter", ""));
        }
        return out;
    }

    static List<Entry> parseEpisodeSheet(String html, String novelId) {
        List<Entry> out = new ArrayList<Entry>();
        if (html == null || html.length() == 0) return out;
        Document doc = Jsoup.parse(html);
        Set<String> seen = new HashSet<String>();
        for (Element tr : doc.select("tr[data-episode-no]")) {
            String no = tr.attr("data-episode-no");
            if (!num(no)) continue;
            String url = HOST + "/viewer/" + no;
            if (!seen.add(url)) continue;
            if (out.size() >= EPISODE_CAP) break;
            Element b = tr.select("b").first();
            String title = textOr(b == null ? "" : b.text(), "회차 " + no);
            out.add(new Entry(title, url, "chapter", badge(tr)));
        }
        return out;
    }

    private static String badge(Element tr) {
        for (Element b : tr.select(".b_free, .b_plus, .b_comp, .b_mono, .b_cont")) {
            String cls = b.className();
            if (cls == null) continue;
            if (cls.contains("b_free")) return "무료";
            if (cls.contains("b_plus")) return "PLUS";
            if (cls.contains("b_comp")) return "완결";
            if (cls.contains("b_mono")) return "단편";
            if (cls.contains("b_cont")) return "연재중";
        }
        return "";
    }

    // ------------------------------------------------------------------
    // Strict URL handling
    // ------------------------------------------------------------------

    /**
     * Accepts only https URLs on novelpia.com / *.novelpia.com and
     * root-relative paths; rejects look-alikes such as novelpia.com.evil.
     */
    private static String allowed(String raw, String what) {
        if (raw == null) throw new IllegalArgumentException("URL required for " + what);
        String s = raw.trim();
        if (s.length() == 0) throw new IllegalArgumentException("URL required for " + what);
        if (s.startsWith("/")) return HOST + s;
        URI uri;
        try {
            uri = new URI(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("URL not allowed for " + what + ": " + s);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String path = uri.getPath();
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("URL not allowed for " + what + " (https only): " + s);
        }
        if (host == null || !isNovelpiaHost(host)
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || path == null) {
            throw new IllegalArgumentException("URL not allowed for " + what + ": " + s);
        }
        return s;
    }

    private static boolean isNovelpiaHost(String host) {
        String h = host.toLowerCase();
        return h.equals("novelpia.com") || h.endsWith(".novelpia.com");
    }

    private static String abs(String href) {
        String s = href == null ? "" : href.trim();
        if (s.length() == 0) return null;
        if (s.startsWith("/")) return HOST + s;
        if (!s.startsWith("https://") && !s.startsWith("http://")) return null;
        try {
            URI uri = new URI(s);
            if ("https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && isNovelpiaHost(uri.getHost())
                    && uri.getUserInfo() == null) {
                return s;
            }
        } catch (Exception ignore) {
            // fall through
        }
        return null;
    }

    private static String id(String href) {
        String p = href == null ? "" : href.trim();
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        int last = p.lastIndexOf('/');
        if (last < 0) return null;
        String id = p.substring(last + 1);
        return num(id) ? id : null;
    }

    private static boolean num(String s) {
        return s != null && NUM.matcher(s).matches();
    }

    private static String textOr(String s, String fallback) {
        String t = s == null ? "" : s.trim();
        return t.length() == 0 ? fallback : t;
    }

    private static String value(Document doc, String selector) {
        Elements els = doc.select(selector);
        if (els.isEmpty()) return null;
        String v = els.first().attr("value");
        return v == null ? null : v.trim();
    }

    /**
     * Chapter title without the Novelpia slogan. The observed slogan is
     * stripped only as an exact anchored prefix (optionally tolerant whitespace
     * around dashes and before '!'): {@code 노벨피아 - 웹소설로 꿈꾸는 세상! - }.
     * Only if that pattern does not match are the historical anchored prefix
     * {@code 노벨피아 - } or suffix {@code - 노벨피아} removed. This is never a
     * global replace, so a slogan appearing inside a legitimate title is kept.
     */
    static String cleanTitle(String raw) {
        String t = raw == null ? "" : raw.trim();
        String stripped = SLOGAN_PREFIX.matcher(t).replaceFirst("");
        if (stripped.equals(t)) {
            String legacy = t.replaceFirst("^노벨피아\\s*-\\s*", "");
            if (!legacy.equals(t)) {
                t = legacy;
            } else {
                t = t.replaceFirst("\\s*-\\s*노벨피아$", "");
            }
        } else {
            t = stripped;
        }
        return t.trim();
    }

    /** Trims and bounds a plain metadata string to {@code max} code units. */
    private static String bounded(String s, int max) {
        String t = s == null ? "" : s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max).trim();
    }

    /** Plain text of the first element matching {@code selector}, if any. */
    private static String text(Document doc, String selector) {
        Elements els = doc.select(selector);
        if (els.isEmpty()) return null;
        return textOr(els.first().text(), "");
    }

    /**
     * Novel URL ({@code HOST + "/novel/<id>"}) from the viewer's
     * {@code #novel_no} input (falling back to {@code input[name=novel_no]}).
     * Only a strictly positive numeric id is accepted; 0, negatives, text and
     * absent values yield "" (the content id is never used as a stand-in).
     */
    private static String novelUrl(Document doc) {
        String no = value(doc, "#novel_no");
        if (no == null || no.length() == 0) no = value(doc, "input[name=novel_no]");
        return positiveNum(no) ? HOST + "/novel/" + no : "";
    }

    /**
     * Next/previous URL. Accepted forms (first matching selector wins):
     * a strictly positive bare id, or a safe root-relative/absolute https URL
     * whose path is exactly {@code /viewer/<positiveid>} on one of the two
     * official hosts the app can open: novelpia.com or book.novelpia.com.
     * Rejects 0, the current chapter id (on either official host),
     * foreign hosts, http, userinfo/credentials, non-default ports, query
     * strings, fragments, javascript: and any other confusion. Never computes
     * id+1/id-1; unsafe or absent values simply yield "".
     */
    private static String navUrl(Document doc, String contentNo, String current,
                                 String[] selectors) {
        for (String selector : selectors) {
            String v = value(doc, selector);
            if (v == null || v.length() == 0) continue;
            if (positiveNum(v)) {
                if (v.equals(contentNo)) return "";
                return HOST + VIEWER_PATH_PREFIX + v;
            }
            String url = safeViewerUrl(v, current);
            if (url != null) return url;
            return "";
        }
        return "";
    }

    private static boolean positiveNum(String s) {
        if (!num(s)) return false;
        long n = Long.parseLong(s);
        return n > 0;
    }

    private static String safeViewerUrl(String raw, String current) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) return null;
        if (s.startsWith("/")) s = HOST + s;
        URI uri;
        try {
            uri = new URI(s);
        } catch (Exception e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
        String host = uri.getHost();
        if (host == null
                || !("novelpia.com".equalsIgnoreCase(host)
                || "book.novelpia.com".equalsIgnoreCase(host))) {
            return null;
        }
        if (uri.getUserInfo() != null || uri.getPort() != -1) return null;
        if (uri.getQuery() != null || uri.getFragment() != null) return null;
        String path = uri.getPath();
        if (path == null || !path.startsWith(VIEWER_PATH_PREFIX)) return null;
        String id = path.substring(VIEWER_PATH_PREFIX.length());
        if (id.length() == 0 || path.indexOf('/', VIEWER_PATH_PREFIX.length()) >= 0
                || !positiveNum(id)) {
            return null;
        }
        String currentId = viewerId(current);
        if (currentId != null && id.equals(currentId)) return "";
        return s;
    }

    /**
     * The bare viewer id of the current chapter URL ({@code /viewer/<id>}),
     * or null when the URL is not a valid current viewer URL. Used only to
     * detect self-navigation: the id must be compared to the id parsed out of
     * the current URL, never to the URL string itself or to a guessed
     * id+1/id-1.
     */
    private static String viewerId(String url) {
        String s = url == null ? "" : url.trim();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int f = s.indexOf('#');
        if (f >= 0) s = s.substring(0, f);
        if (!s.startsWith(HOST + VIEWER_PATH_PREFIX)
                && !s.startsWith("https://book.novelpia.com" + VIEWER_PATH_PREFIX)) {
            return null;
        }
        int last = s.lastIndexOf('/');
        if (last < 0) return null;
        String id = s.substring(last + 1);
        return positiveNum(id) ? id : null;
    }

    private static String formEncode(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return sb.toString();
    }

    static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    static String emptyIfNull(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    // HTTP agent seam (NativeHttp is final; tests fake this instead)
    // ------------------------------------------------------------------

    interface HttpAgent {
        String get(String url) throws IOException;

        String post(String url, Map<String, String> form) throws IOException;
    }

    private static final class RealAgent implements HttpAgent {
        private final NativeHttp http;

        RealAgent(NativeHttp http) {
            this.http = http;
        }

        @Override public String get(String url) throws IOException {
            return http.get(url);
        }

        @Override public String post(String url, Map<String, String> form) throws IOException {
            return http.post(url, form);
        }
    }

    // ------------------------------------------------------------------
    // Minimal JSON reader (API 19: no org.json on classpath assumptions)
    // ------------------------------------------------------------------

    static final class JsonReader {
        private final String s;
        private final int n;
        private int i;
        private int depth;
        private int values;

        JsonReader(String s) {
            this.s = s == null ? "" : s;
            this.n = this.s.length();
        }

        Object root() {
            i = 0;
            depth = 0;
            values = 0;
            Object result = parseValue();
            skipWs();
            if (i != n) throw new IllegalStateException("trailing json data");
            return result;
        }

        Object at(String path) {
            Object v = root();
            for (String key : path.split("\\.")) {
                if (!(v instanceof Map)) return null;
                v = ((Map<?, ?>) v).get(key);
                if (v == null) return null;
            }
            return v;
        }

        static Map<String, Object> object(Object v) {
            return v instanceof Map ? (Map<String, Object>) v : null;
        }

        static String string(Object v) {
            if (v == null) return null;
            if (v instanceof String) return (String) v;
            return String.valueOf(v);
        }

        static Integer integer(Object v) {
            if (v == null) return null;
            if (v instanceof Integer) return (Integer) v;
            if (v instanceof Number) return Integer.valueOf(((Number) v).intValue());
            try {
                return Integer.valueOf(String.valueOf(v));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private Object parseValue() {
            if (++values > 50000 || ++depth > 64) {
                throw new IllegalStateException("json complexity limit");
            }
            try {
            skipWs();
            if (i >= n) throw new IllegalStateException("empty json");
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') return expect("true");
            if (c == 'f') return expect("false");
            if (c == 'n') return expect("null");
            return parseNumber();
            } finally {
                depth--;
            }
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                String k = parseString();
                skipWs();
                expect(':');
                m.put(k, parseValue());
                skipWs();
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw new IllegalStateException("object separators");
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> a = new ArrayList<Object>();
            skipWs();
            if (peek() == ']') { i++; return a; }
            while (true) {
                a.add(parseValue());
                skipWs();
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return a; }
                throw new IllegalStateException("array separators");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < n) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= n) break;
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (i + 4 <= n) {
                                try {
                                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                    i += 4;
                                } catch (NumberFormatException ex) {
                                    throw new IllegalStateException("bad \\u escape");
                                }
                            } else throw new IllegalStateException("incomplete unicode escape");
                            break;
                        default: throw new IllegalStateException("invalid escape");
                    }
                } else {
                    if (c < 0x20) throw new IllegalStateException("unescaped control character");
                    sb.append(c);
                }
            }
            throw new IllegalStateException("unterminated string");
        }

        private Object parseNumber() {
            int start = i;
            if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < n && (Character.isDigit(s.charAt(i))
                    || s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E'
                    || s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            String tok = s.substring(start, i);
            if (tok.length() == 0) throw new IllegalStateException("bad number");
            try {
                if (tok.indexOf('.') < 0 && tok.indexOf('e') < 0 && tok.indexOf('E') < 0) {
                    return Long.valueOf(tok);
                }
                return Double.valueOf(tok);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("bad number: " + tok);
            }
        }

        private Object expect(String lit) {
            if (s.startsWith(lit, i)) { i += lit.length(); return null; }
            throw new IllegalStateException("bad literal");
        }

        private void expect(char c) {
            if (i >= n || s.charAt(i) != c) throw new IllegalStateException("expected " + c);
            i++;
        }

        private char peek() {
            return i < n ? s.charAt(i) : '\0';
        }

        private void skipWs() {
            while (i < n) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Value types (nested so MainActivity imports SiteClient.Entry/Chapter)
    // ------------------------------------------------------------------

    /** One catalog or episode-list row. Plain text only, never HTML. */
    public static final class Entry {
        public final String title;
        public final String url;
        public final String kind;   // novel | chapter | page | search
        public final String detail;

        public Entry(String title, String url, String kind, String detail) {
            this.title = emptyIfNull(title);
            this.url = emptyIfNull(url);
            this.kind = emptyIfNull(kind);
            this.detail = emptyIfNull(detail);
        }

        @Override public String toString() {
            return "Entry{" + kind + " \"" + title + "\" -> " + url + "}";
        }
    }

    /** Chapter metadata; text is only set from the site's viewer_data body. */
    public static final class Chapter {
        public final String title;
        public final String text;
        public final String url;
        public final String nextUrl;
        public final String previousUrl;
        public final String episodeTitle;
        public final String episodeLabel;
        public final String novelUrl;

        /** Legacy constructor: episodeTitle/episodeLabel/novelUrl stay empty. */
        public Chapter(String title, String text, String url,
                       String nextUrl, String previousUrl) {
            this(title, text, url, nextUrl, previousUrl, "", "", "");
        }

        /** Preferred constructor with episode metadata and the novel URL. */
        public Chapter(String title, String text, String url,
                       String nextUrl, String previousUrl,
                       String episodeTitle, String episodeLabel, String novelUrl) {
            this.title = emptyIfNull(title);
            this.text = emptyIfNull(text);
            this.url = emptyIfNull(url);
            this.nextUrl = emptyIfNull(nextUrl);
            this.previousUrl = emptyIfNull(previousUrl);
            this.episodeTitle = emptyIfNull(episodeTitle);
            this.episodeLabel = emptyIfNull(episodeLabel);
            this.novelUrl = emptyIfNull(novelUrl);
        }
    }

    /** Login result from POST /proc/login. */
    public static final class LoginResult {
        public final boolean success;
        public final String message;

        public LoginResult(boolean success, String message) {
            this.success = success;
            this.message = emptyIfNull(message);
        }

        static LoginResult from(String body) {
            String b = trim(body);
            if (b.length() == 0) return new LoginResult(false, "서버 응답이 없습니다.");
            if (b.contains("login_box")) return new LoginResult(false, "이메일/비밀번호를 확인해주세요.");
            if (b.contains("로그인 실패") || b.contains("아이디 또는 비밀번호")
                    || b.contains("잘못된") || b.contains("실패")) {
                return new LoginResult(false, "로그인에 실패했습니다.");
            }
            // Body-text heuristics are NOT a reliable authenticated marker: the
            // observed failure page (np_login.body) contains generic strings
            // such as "user", "mybook" and "/proc/login" in menus/scripts while
            // still being a login-failure page. Until NativeHttp can expose an
            // actual auth-cookie probe (e.g. boolean hasCookie), never claim
            // success from the response body alone.
            return new LoginResult(false,
                    "요청은 전송했지만 로그인 성공 여부를 확인하지 못했습니다. 권한이 있는 회차를 다시 열어 확인해주세요.");
        }

        private static String trim(String s) {
            return emptyIfNull(s).trim();
        }
    }
}
