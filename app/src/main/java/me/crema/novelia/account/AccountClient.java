package me.crema.novelia.account;

import me.crema.novelia.net.NativeHttp;
import me.crema.novelia.net.HttpException;
import me.crema.novelia.site.SiteClient;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only account/library adapter. It uses only observed site flows:
 * {@code /mybook/last_view}, {@code /proc/mybook} favorite_list, and the
 * normal {@code /proc/login} form. Credentials and account data are not saved.
 */
public final class AccountClient {
    private static final String HOST = "https://novelpia.com";
    private static final Pattern TOP_MEMBER_NO = Pattern.compile(
            "\\b(?:const|let|var)\\s+_top_obj\\s*=\\s*\\{\\s*data\\s*:\\s*\\{"
                    + "(?:(?!\\bmethods\\s*:)[\\s\\S])*?\\bmem_no\\s*:\\s*\"([0-9]+)\"");
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,12}");
    private static final Pattern SAFE_STATUS = Pattern.compile("[0-9]{1,4}");

    private final HttpAgent http;

    public AccountClient(NativeHttp http) {
        if (http == null) throw new IllegalArgumentException("NativeHttp required");
        this.http = new RealAgent(http);
    }

    /** Package-private transport seam for deterministic fixture tests. */
    AccountClient(HttpAgent http) {
        if (http == null) throw new IllegalArgumentException("HttpAgent required");
        this.http = http;
    }

    /** Check the session using the observed server-rendered member number. */
    public SessionStatus sessionStatus() {
        try {
            String html = http.get(HOST + "/mybook/last_view");
            return statusFromHtml(html);
        } catch (IOException e) {
            return new SessionStatus(SessionStatus.State.UNKNOWN, "세션 상태를 확인하지 못했습니다.");
        }
    }

    /**
     * Submit the site's observed email login form, then verify solely by
     * fetching the authenticated-only recent-reading page. Never infer success
     * from generic response text.
     */
    public SessionStatus login(String email, String password) {
        if (email == null || email.trim().length() == 0
                || password == null || password.length() == 0) {
            return new SessionStatus(SessionStatus.State.UNAUTHENTICATED,
                    "이메일과 비밀번호를 입력해주세요.");
        }
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("email", email.trim());
        form.put("wd", password);
        form.put("redirectrurl", "");
        try {
            http.post(HOST + "/proc/login", form);
            return sessionStatus();
        } catch (IOException e) {
            return new SessionStatus(SessionStatus.State.UNKNOWN, "로그인 요청을 확인하지 못했습니다.");
        }
    }

    /** Compatibility convenience: the first page only, not all account history. */
    public List<SiteClient.Entry> recent() throws IOException {
        List<SiteClient.Entry> entries = new ArrayList<SiteClient.Entry>();
        for (LibraryPage.Item item : library(HOST + "/mybook/last_view").items)
            entries.add(item.entry);
        return entries;
    }

    /**
     * Read exactly one observed server-rendered library page. No eager crawl,
     * preference mutation, history deletion, or guessed API modes.
     */
    public LibraryPage library(String url) throws IOException {
        String target = LibraryParser.normalizeUrl(url);
        String html = http.get(target);
        SessionStatus status = statusFromHtml(html);
        if (!status.isAuthenticated()) throw new IOException(status.message);
        return LibraryParser.parse(html, target);
    }

    /** Resolve only after an explicit click. Never calls set_init_next_episode or opens/purchases content. */
    public NextEpisode nextEpisode(LibraryPage.Item item) throws IOException {
        if (item == null || item.entry == null || !item.hasNextEpisode()
                || !item.entry.url.matches("https://novelpia\\.com/novel/[1-9][0-9]{0,11}")
                || !item.nextEpisodeKey.matches("[0-9]{1,12}"))
            throw new IOException("다음 회차 정보를 확인할 수 없습니다.");
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("mode", "get_next_episode");
        form.put("novel_no", item.entry.url.substring((HOST + "/novel/").length()));
        form.put("novel_epi_no", item.nextEpisodeKey);
        String response = http.post(HOST + "/proc/mybook", form);
        Object root;
        try { root = new Json(response).parse(); }
        catch (IOException malformed) { throw nextFailure(); }
        if (!(root instanceof Map)) throw nextFailure();
        Map<?, ?> envelope = (Map<?, ?>) root;
        String status = scalar(envelope.get("status"));
        if ("401".equals(status)) throw new IOException("로그인이 필요합니다.");
        if (!"200".equals(status) || !(envelope.get("result") instanceof Map)) throw nextFailure();
        Map<?, ?> result = (Map<?, ?>) envelope.get("result");
        String next = scalar(result.get("next_episode_no"));
        if (next.matches("[1-9][0-9]{0,11}")) {
            if ("1".equals(scalar(result.get("wait_episode"))))
                return new NextEpisode(NextEpisode.State.WAITING, "");
            String target = HOST + "/viewer/" + next;
            if (target.equals(item.continueUrl)) throw nextFailure();
            return new NextEpisode(NextEpisode.State.AVAILABLE, target);
        }
        if ((next.isEmpty() || "0".equals(next)) && "1".equals(scalar(result.get("end_episode"))))
            return new NextEpisode(NextEpisode.State.END, "");
        throw nextFailure();
    }

    private static String scalar(Object value) {
        return value instanceof String || value instanceof Number ? String.valueOf(value).trim() : "";
    }
    private static IOException nextFailure() { return new IOException("다음 회차를 확인하지 못했습니다. 목록을 새로고침해주세요."); }
    public static final class NextEpisode {
        public enum State { AVAILABLE, WAITING, END }
        public final State state;
        public final String url;
        NextEpisode(State state, String url) { this.state = state; this.url = url; }
    }

    /**
     * Read the observed homepage "나의 최애 작품" favorite_list response.
     * This is not asserted to be the complete library or "선호작" shelf.
     */
    public List<SiteClient.Entry> favorites() throws IOException {
        SessionStatus status = sessionStatus();
        if (!status.isAuthenticated()) {
            throw new IOException(status.message);
        }
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("mode", "favorite_list");
        String body;
        try {
            body = http.post(HOST + "/proc/mybook", form);
        } catch (IOException e) {
            throw new FavoritesException(FavoritesException.Category.NETWORK,
                    e instanceof HttpException ? safeStatus(((HttpException) e).getStatusCode()) : "");
        }
        Object root;
        try {
            root = new Json(body).parse();
        } catch (IOException e) {
            throw new FavoritesException(FavoritesException.Category.INVALID_JSON, "");
        }
        if (!(root instanceof Map)) throw new FavoritesException(FavoritesException.Category.INVALID_ENVELOPE, "");
        Map<?, ?> object = (Map<?, ?>) root;
        Object rawStatus = object.get("status");
        if (rawStatus == null) throw new FavoritesException(FavoritesException.Category.STATUS_MISSING, "");
        String code = rawStatus instanceof String || rawStatus instanceof Number
                ? String.valueOf(rawStatus) : "";
        if (!SAFE_STATUS.matcher(code).matches())
            throw new FavoritesException(FavoritesException.Category.STATUS_NONSTANDARD, "");
        if (!"200".equals(code))
            throw new FavoritesException(FavoritesException.Category.STATUS_NON200, code);
        Object values = object.get("items");
        if (values == null) return new ArrayList<SiteClient.Entry>();
        if (!(values instanceof List)) throw new FavoritesException(FavoritesException.Category.ITEMS_TYPE, "");
        List<SiteClient.Entry> rows = new ArrayList<SiteClient.Entry>();
        for (Object value : (List<?>) values) {
            if (!(value instanceof Map)) throw new FavoritesException(FavoritesException.Category.INVALID_ROWS, "");
            Map<?, ?> item = (Map<?, ?>) value;
            String no = string(item.get("novel_no"));
            if (item.get("novel_no") instanceof Number) no = String.valueOf(item.get("novel_no"));
            String title = string(item.get("novel_name"));
            if (!NUMBER.matcher(no).matches() || title.length() == 0)
                throw new FavoritesException(FavoritesException.Category.INVALID_ROWS, "");
            if (rows.size() < 5) rows.add(new SiteClient.Entry(title, HOST + "/novel/" + no, "novel", ""));
        }
        return rows;
    }

    private static SessionStatus statusFromHtml(String html) {
        if (html == null || html.length() == 0) {
            return new SessionStatus(SessionStatus.State.UNKNOWN, "서버 응답이 없습니다.");
        }
        Document doc = Jsoup.parse(html);
        for (Element script : doc.select("script")) {
            String source = script.data();
            Matcher marker = TOP_MEMBER_NO.matcher(source);
            if (marker.find()) {
                return "0".equals(marker.group(1))
                        ? new SessionStatus(SessionStatus.State.UNAUTHENTICATED, "로그인이 필요합니다.")
                        : new SessionStatus(SessionStatus.State.AUTHENTICATED, "로그인되었습니다.");
            }
        }
        // Shared login markup and generic login_req references are not proof
        // of an unauthenticated session. Require the page's exact redirect.
        for (Element script : doc.select("script")) {
            if (script.data().contains("location = \"/?login_req=1\"")) {
                return new SessionStatus(SessionStatus.State.UNAUTHENTICATED, "로그인이 필요합니다.");
            }
        }
        return new SessionStatus(SessionStatus.State.UNKNOWN, "로그인 상태를 확인하지 못했습니다.");
    }

    private static String string(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static String safeStatus(int code) {
        String value = String.valueOf(code);
        return SAFE_STATUS.matcher(value).matches() ? value : "";
    }

    /** Only generated diagnostics; never includes response data or a cause. */
    public static final class FavoritesException extends IOException {
        private static final long serialVersionUID = 1L;
        public enum Category {
            NETWORK, INVALID_JSON, INVALID_ENVELOPE, STATUS_MISSING,
            STATUS_NON200, STATUS_NONSTANDARD, ITEMS_TYPE, INVALID_ROWS
        }
        public final Category category;
        public final String status;
        FavoritesException(Category category, String status) {
            super("favorites:" + category.name().toLowerCase(java.util.Locale.US)
                    + (status.length() == 0 ? "" : ":" + status));
            this.category = category;
            this.status = status;
        }
    }

    /** Safe to display or log: never reads the input exception's message or cause. */
    public static String describeFavoritesFailure(IOException error) {
        if (error instanceof FavoritesException) return error.getMessage();
        if (error instanceof HttpException) {
            String code = safeStatus(((HttpException) error).getStatusCode());
            return "favorites:network" + (code.length() == 0 ? "" : ":" + code);
        }
        return "favorites:network";
    }

    interface HttpAgent {
        String get(String url) throws IOException;
        String post(String url, Map<String, String> form) throws IOException;
    }

    private static final class RealAgent implements HttpAgent {
        private final NativeHttp http;
        RealAgent(NativeHttp http) { this.http = http; }
        @Override public String get(String url) throws IOException { return http.get(url); }
        @Override public String post(String url, Map<String, String> form) throws IOException {
            return (HOST + "/proc/mybook").equals(url)
                    ? http.postAjax(url, form) : http.post(url, form);
        }
    }

    /** Contains no username, member number, cookie, or credential. */
    public static final class SessionStatus {
        public enum State { AUTHENTICATED, UNAUTHENTICATED, UNKNOWN }

        public final State state;
        public final String message;
        SessionStatus(State state, String message) {
            this.state = state;
            this.message = message;
        }

        public boolean isAuthenticated() {
            return state == State.AUTHENTICATED;
        }
    }

    /** Small bounded JSON reader for the observed status/items response. */
    private static final class Json {
        private final String s;
        private int i;
        Json(String s) { this.s = s == null ? "" : s; }
        Object parse() throws IOException {
            if (s.length() > 2_000_000) throw bad();
            Object value = value(0);
            ws();
            if (i != s.length()) throw bad();
            return value;
        }
        private Object value(int depth) throws IOException {
            if (depth > 32) throw bad();
            ws();
            if (i >= s.length()) throw bad();
            char c = s.charAt(i);
            if (c == '{') return object(depth + 1);
            if (c == '[') return array(depth + 1);
            if (c == '"') return quoted();
            int start = i;
            while (i < s.length() && ",]} \r\n\t".indexOf(s.charAt(i)) < 0) i++;
            if (start == i) throw bad();
            String token = s.substring(start, i);
            if ("null".equals(token)) return null;
            if ("true".equals(token) || "false".equals(token)
                    || token.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))
                return token;
            throw bad();
        }
        private Map<String, Object> object(int d) throws IOException {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; ws(); if (take('}')) return m;
            while (true) {
                ws(); if (i >= s.length() || s.charAt(i) != '"') throw bad();
                String key = quoted(); ws(); if (!take(':')) throw bad();
                m.put(key, value(d)); ws();
                if (take('}')) return m;
                if (!take(',')) throw bad();
            }
        }
        private List<Object> array(int d) throws IOException {
            List<Object> a = new ArrayList<Object>();
            i++; ws(); if (take(']')) return a;
            while (true) {
                a.add(value(d)); ws();
                if (take(']')) return a;
                if (!take(',')) throw bad();
            }
        }
        private String quoted() throws IOException {
            i++; StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw bad();
                    char e = s.charAt(i++);
                    if (e == '"' || e == '\\' || e == '/') b.append(e);
                    else if (e == 'b') b.append('\b');
                    else if (e == 'f') b.append('\f');
                    else if (e == 'n') b.append('\n');
                    else if (e == 'r') b.append('\r');
                    else if (e == 't') b.append('\t');
                    else if (e == 'u') {
                        if (i + 4 > s.length()) throw bad();
                        try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                        catch (NumberFormatException ex) { throw bad(); }
                        i += 4;
                    } else throw bad();
                } else {
                    if (c < 0x20) throw bad();
                    b.append(c);
                }
            }
            throw bad();
        }
        private boolean take(char c) {
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }
        private void ws() { while (i < s.length() && " \r\n\t".indexOf(s.charAt(i)) >= 0) i++; }
        private IOException bad() { return new IOException("선호작 응답 형식이 올바르지 않습니다."); }
    }
}
