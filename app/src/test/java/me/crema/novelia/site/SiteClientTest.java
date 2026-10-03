package me.crema.novelia.site;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixtures authored from observed Novelpia markup/JSON shapes
 * (docs/SITE.md). No live network.
 */
public class SiteClientTest {

    private static final String HOST = "https://novelpia.com";

    // ------------------------------------------------------------------
    // Fake HTTP agent (net.NativeHttp is final; SiteClient.HttpAgent is the
    // package-private test seam)
    // ------------------------------------------------------------------

    private static final class FakeHttp implements SiteClient.HttpAgent {
        final Map<String, String> gets = new LinkedHashMap<String, String>();
        final Map<String, String> posts = new LinkedHashMap<String, String>();
        final Map<String, String> postBodies = new LinkedHashMap<String, String>();

        @Override public String get(String url) throws IOException {
            // /proc/novel is a query-parameter API: semantic params are
            // asserted by the tests from lastSearchUrl, not exact URI order.
            String key = url;
            if (url.startsWith(HOST + "/proc/novel?")) {
                lastSearchUrl = url;
                key = HOST + "/proc/novel";
            }
            String r = gets.get(key);
            if (r == null) throw new IOException("no fixture for GET " + url);
            return r;
        }

        String lastSearchUrl;

        @Override public String post(String url, Map<String, String> form) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (sb.length() > 0) sb.append('&');
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            postBodies.put(url, sb.toString());
            String r = posts.get(url);
            if (r == null) throw new IOException("no fixture for POST " + url);
            return r;
        }
    }

    // ------------------------------------------------------------------
    // Fixtures (observed shapes)
    // ------------------------------------------------------------------

    private static final String NOVEL_PAGE =
            "<html><body>"
            + "<a href=\"/novel/442723\">헌터들이 집착하는 EX급 핍 제작자가 되었다</a>"
            + "<a href=\"/viewer/5890523\">1화</a>"
            + "<a href=\"https://book.novelpia.com/webnovel/ranking\">랭킹</a>"
            + "<a href=\"https://novelpia.com.evil/x\">evil</a>"
            + "</body></html>";

    private static final String EPISODE_SHEET =
            "<html><body><table id=\"episode_table\">"
            + "<tr class=\"ep_style5\" data-episode-no=\"5890523\">"
            + "<td><span class=\"b_free s_inv\">무료</span><b> 20. 마수 강화 부문 </b></td></tr>"
            + "<tr class=\"ep_style5\" data-episode-no=\"5890533\">"
            + "<td><span class=\"b_plus s_inv\">PLUS</span><b> 21. 새로운 강화체? </b></td></tr>"
            + "</table></body></html>";

    private static final String SEARCH_JSON = "{"
            + "\"status\":200,\"code\":\"0000\",\"errmsg\":\"\","
            + "\"total_cnt\":2,"
            + "\"list\":["
            + "{\"novel_no\":442723,\"novel_name\":\"헌터들이 집착하는 EX급 핍 제작자가 되었다\","
            + "\"writer_nick\":\"백수\",\"novel_type\":1,\"is_complete\":0,\"count_book\":72,"
            + "\"novel_genre_arr\":[\"판타지\",\"헌터\"]},"
            + "{\"novel_no\":888,\"novel_name\":\"둘째 소설\","
            + "\"writer_nick\":\"작가\",\"is_complete\":1,\"count_book\":5,"
            + "\"novel_genre_arr\":[]}"
            + "]}";

    private static final String VIEWER_PAGE =
            "<html><head><title>노벨피아 - 웹소설로 꿈꾸는 세상! - [헌터들이 집착하는 EX급 핍 제작자가 되었다]</title></head>"
            + "<body>"
            + "<input type=\"hidden\" id=\"novel_no\" value=\"442723\">"
            + "<input type=\"hidden\" id=\"content_no\" value=\"5890523\">"
            + "<input type=\"hidden\" name=\"content_no\" value=\"5890523\">"
            + "<input type=\"hidden\" id=\"content_no_next\" value=\"5890533\">"
            + "<input type=\"hidden\" id=\"content_no_pre\" value=\"\">"
            + "<input type=\"hidden\" id=\"back_epi_auto_url\" value=\"/viewer/5890513\">"
            + "<div class=\"menu-title-wrapper\">"
            + "<span class=\"menu-top-tag\">프롤로그</span>"
            + "<span class=\"menu-top-title\">프롤로그</span>"
            + "</div>"
            + "<div id=\"novel_text\"><div id=\"load_bar\">소설 내용을 불러오고 있습니다.</div></div>"
            + "<script>is_member_login=false;</script>"
            + "<p>this is SSR chrome text and MUST NOT become the chapter body</p>"
            + "</body></html>";

    private static final String VIEWER_BODY_JSON =
            "{\"status\":200,\"s\":["
            + "{\"text\":\"첫 문단입니다.\",\"size\":11,\"align\":\"left\"},"
            + "{\"text\":\"둘째 문단입니다.\",\"size\":11,\"align\":\"left\"}"
            + "]}";

    private static final String GATED_BODY_HTML =
            "<html><body>로그인이 필요합니다. 구매하기 button</body></html>";

    private static final String BAD_JSON =
            "<html><body>리다이렉트 또는 오류 페이지</body></html>";

    private static final String LOGIN_FAIL =
            "<html><body><form id=\"login_box\">다시 로그인해주세요</form></body></html>";

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private static final class Harness {
        final FakeHttp http = new FakeHttp();
        final SiteClient client = new SiteClient(http);

        Harness() {
            http.gets.put(HOST + "/novel/442723", NOVEL_PAGE);
            http.gets.put(HOST + "/viewer/5890523", VIEWER_PAGE);
            http.gets.put(HOST + "/proc/novel", SEARCH_JSON);
            http.posts.put(HOST + "/proc/episode_list_viewer", EPISODE_SHEET);
            http.posts.put(HOST + "/proc/viewer_data/5890523", VIEWER_BODY_JSON);
            http.posts.put(HOST + "/proc/login", LOGIN_FAIL);
        }
    }

    // ------------------------------------------------------------------
    // browse
    // ------------------------------------------------------------------

    @Test
    public void browse_parses_novel_and_chapter() throws Exception {
        List<SiteClient.Entry> e = new Harness().client.browse(HOST + "/novel/442723");
        assertNotNull(e);
        assertTrue(e.size() >= 2);
        boolean novel = false, chapter = false, evil = false;
        for (SiteClient.Entry x : e) {
            if ("novel".equals(x.kind) && x.url.endsWith("/novel/442723")) novel = true;
            if ("chapter".equals(x.kind) && x.url.endsWith("/viewer/5890523")) chapter = true;
            if (x.url.startsWith("https://novelpia.com.evil")) evil = true;
        }
        assertTrue(novel);
        assertTrue(chapter);
        assertFalse("look-alike host must be dropped", evil);
        for (SiteClient.Entry x : e) {
            assertTrue("only novel/chapter rows are extracted",
                    "novel".equals(x.kind) || "chapter".equals(x.kind));
        }
    }

    @Test
    public void browse_relative_input() throws Exception {
        List<SiteClient.Entry> e = new Harness().client.browse("/novel/442723");
        assertFalse(e.isEmpty());
    }

    @Test
    public void browse_rejects_foreign_url() throws Exception {
        expectNotAllowed(new Harness().client, "https://evil.example/novel/1");
    }

    @Test
    public void browse_rejects_lookalike_host() throws Exception {
        // prefix matching would accept this; strict host validation must not
        expectNotAllowed(new Harness().client, "https://novelpia.com.evil/novel/1");
        expectNotAllowed(new Harness().client, "https://novelpia.com@evil.example/x");
        expectNotAllowed(new Harness().client, "http://novelpia.com/novel/1");
    }

    // ------------------------------------------------------------------
    // search (the MainActivity URL translated to /proc/novel)
    // ------------------------------------------------------------------

    @Test
    public void browse_search_url_runs_novel_search_and_parses_rows() throws Exception {
        Harness h = new Harness();
        List<SiteClient.Entry> e = h.client.browse(
                HOST + "/search/all//1/" + "헌터" + "?page=1&rows=30");
        assertEquals(2, e.size());
        assertEquals("novel", e.get(0).kind);
        assertEquals("헌터들이 집착하는 EX급 핍 제작자가 되었다", e.get(0).title);
        assertEquals(HOST + "/novel/442723", e.get(0).url);
        assertTrue(e.get(0).detail.contains("판타지"));
        assertTrue(e.get(0).detail.contains("작가: 백수"));
        assertTrue(e.get(1).detail.contains("완결"));
        Map<String, String> p = queryParams(h.http.lastSearchUrl);
        assertEquals("novel_search", p.get("cmd"));
        assertEquals("all", p.get("search_type"));
        assertEquals("헌터", p.get("search_val"));
        assertEquals("1", p.get("page"));
        assertEquals("30", p.get("rows"));
        assertEquals("last_viewdate", p.get("sort_col"));
        assertEquals("list", p.get("list_display"));
        assertEquals("0", p.get("block_out"));
        assertEquals("0", p.get("block_stop"));
        assertEquals("0", p.get("is_contest"));
        h.http.lastSearchUrl = null;
    }

    @Test
    public void browse_legacy_search_query_uses_search_string() throws Exception {
        Harness h = new Harness();
        List<SiteClient.Entry> e = h.client.browse(HOST + "/search?search_string=헌터&sort_col=count_good");
        assertEquals(2, e.size());
        Map<String, String> p = queryParams(h.http.lastSearchUrl);
        assertEquals("헌터", p.get("search_val"));
        assertEquals("count_good", p.get("sort_col"));
        assertFalse("search_string must never leak into /proc/novel", p.containsKey("search_string"));
        // legacy box had no search_val in the URL; the keyword must be moved
        // into search_val, not forwarded under the private key
        assertEquals(1, countKey(p, "search_val"));
    }

    @Test
    public void search_empty_keyword_throws_not_empty_list() throws Exception {
        try {
            new Harness().client.browse(HOST + "/search?search_string=");
            org.junit.Assert.fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("키워드"));
        }
    }

    // ------------------------------------------------------------------
    // episodes
    // ------------------------------------------------------------------

    @Test
    public void episodes_posts_0_based_and_parses_badges() throws Exception {
        Harness h = new Harness();
        List<SiteClient.Entry> e = h.client.episodes("442723", 0);
        assertEquals(2, e.size());
        assertEquals("20. 마수 강화 부문", e.get(0).title.trim());
        assertEquals("무료", e.get(0).detail);
        assertEquals(HOST + "/viewer/5890523", e.get(0).url);
        assertEquals("21. 새로운 강화체?", e.get(1).title.trim());
        assertEquals("PLUS", e.get(1).detail);
        assertEquals("novel_no=442723&sort=up&page=0",
                h.http.postBodies.get(HOST + "/proc/episode_list_viewer"));
    }

    @Test
    public void episodes_rejects_non_numeric_id() throws Exception {
        assertEquals(0, new Harness().client.episodes(null, 0).size());
        assertEquals(0, new Harness().client.episodes("abc;alert", 0).size());
        assertEquals(0, new Harness().client.episodes("442723", -1).size());
    }

    // ------------------------------------------------------------------
    // login
    // ------------------------------------------------------------------

    @Test
    public void login_posts_exact_fields() {
        Harness h = new Harness();
        SiteClient.LoginResult r = h.client.login("a@b.co", "secret");
        assertFalse(r.success);
        assertTrue(r.message.length() > 0);
        String sent = h.http.postBodies.get(HOST + "/proc/login");
        assertNotNull(sent);
        assertTrue(sent.startsWith("email="));
        assertTrue(sent.contains("&wd="));
        assertTrue(sent.contains("redirectrurl="));
    }

    @Test
    public void login_empty_rejected() {
        assertFalse(new Harness().client.login("", "").success);
        assertFalse(new Harness().client.login(null, "x").success);
    }

    @Test
    public void login_body_never_claims_success_without_auth_marker() throws Exception {
        final String unverifiable =
                "요청은 전송했지만 로그인 성공 여부를 확인하지 못했습니다. 권한이 있는 회차를 다시 열어 확인해주세요.";
        Harness h = new Harness();
        // Observed failure page shape: login form + menus containing generic
        // strings ("user", "mybook", "/proc/login") yet still a failure page.
        h.http.posts.put(HOST + "/proc/login",
                "<html><body><div class=\"login_area\"></div>"
                + "<a href=\"/user/mybook\">내 서재</a>"
                + "<script>var p='/proc/login'</script></body></html>");
        h.http.gets.put(HOST + "/viewer/1",
                "<html><body><div class=\"login_box\">로그인 후 이용해주세요</div></body></html>");
        SiteClient.LoginResult r = h.client.login("a@b.co", "secret");
        assertFalse("failure page must never report success", r.success);
        assertEquals(unverifiable, r.message);

        Harness h2 = new Harness();
        h2.http.posts.put(HOST + "/proc/login", "<html>OK user mybook path</html>");
        h2.http.gets.put(HOST + "/viewer/1",
                "<html><body><div class=\"login_box\">로그인 후 이용해주세요</div></body></html>");
        SiteClient.LoginResult r2 = h2.client.login("a@b.co", "secret");
        assertFalse("ambiguous body must not claim success", r2.success);
        assertEquals(unverifiable, r2.message);
    }

    // ------------------------------------------------------------------
    // readChapter (real loader; no doc.text() fallback)
    // ------------------------------------------------------------------

    @Test
    public void readChapter_posts_viewer_data_and_returns_lines() throws Exception {
        Harness h = new Harness();
        h.http.posts.put(HOST + "/proc/viewer_data/5890523",
                "{\"status\":200,\"s\":["
                + "{\"text\":\"첫 문단 <script>alert(1)</script>입니다.\"},"
                + "{\"text\":\"둘째 <b>문단</b> &amp; 마침.\"},"
                + "{\"text\":\"<a href=\\\"/evil\\\">링크 텍스트만 남음</a>\"}"
                + "],\"data\":true}");
        SiteClient.Chapter c = h.client.readChapter(HOST + "/viewer/5890523");
        assertNotNull(c);
        assertEquals("[헌터들이 집착하는 EX급 핍 제작자가 되었다]", c.title);
        assertEquals("프롤로그", c.episodeTitle);
        assertEquals("프롤로그", c.episodeLabel);
        assertEquals(HOST + "/novel/442723", c.novelUrl);
        assertTrue(c.text.contains("첫 문단"));
        assertFalse("script must be removed from body", c.text.contains("<script>"));
        assertTrue(c.text.contains("둘째 문단 & 마침."));
        assertTrue("HTML entities must be unescaped", !c.text.contains("&amp;"));
        assertEquals("링크 텍스트만 남음", c.text.split("\n")[2].trim());
        assertFalse("SSR chrome text must not leak into the body",
                c.text.contains("MUST NOT become the chapter body"));
        assertEquals(HOST + "/viewer/5890533", c.nextUrl);
        assertEquals(HOST + "/viewer/5890513", c.previousUrl);
        assertEquals("size=14&viewer_paging=",
                h.http.postBodies.get(HOST + "/proc/viewer_data/5890523"));
    }

    @Test
    public void readChapter_body_over_300k_throws_not_truncates() throws Exception {
        Harness h = new Harness();
        StringBuilder huge = new StringBuilder();
        huge.append("{\"status\":200,\"s\":[");
        for (int i = 0; i < 5; i++) {
            if (i > 0) huge.append(',');
            huge.append("{\"text\":\"").append(rep("가나다", 20_000)).append("\"}");
        }
        huge.append("]}");
        h.http.posts.put(HOST + "/proc/viewer_data/5890523", huge.toString());
        try {
            h.client.readChapter(HOST + "/viewer/5890523");
            fail("over-limit body must throw, never silently truncate");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("300,000"));
        }
    }

    @Test
    public void readChapter_leaves_only_allowed_search_query_keys_for_forwarding() throws Exception {
        Harness h = new Harness();
        h.client.browse(HOST + "/search/all//1/x"
                + "?search_string=SECRET&search_val=SECRET2&evil=1&rows=40&is_complete=1");
        Map<String, String> p = queryParams(h.http.lastSearchUrl);
        assertFalse("search_string must not be forwarded as a query key", p.containsKey("search_string"));
        assertFalse("foreign search_val=SECRET2 must not leak (keyword wins)",
                p.containsValue("SECRET2"));
        assertFalse("unknown keys must not leak", p.containsKey("evil"));
        assertEquals("40", p.get("rows"));
        assertEquals("1", p.get("is_complete"));
        assertEquals("SECRET", p.get("search_val")); // legacy search_string keyword, not the private query form
    }

    @Test
    public void readChapter_gated_payload_throws_informative_not_body() throws Exception {
        Harness h = new Harness();
        h.http.posts.put(HOST + "/proc/viewer_data/5890523", GATED_BODY_HTML);
        try {
            h.client.readChapter(HOST + "/viewer/5890523");
            org.junit.Assert.fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("로그인 또는 구매 필요")
                    || expected.getMessage().contains("본문"));
        }
    }

    @Test
    public void readChapter_empty_list_throws() throws Exception {
        Harness h = new Harness();
        h.http.posts.put(HOST + "/proc/viewer_data/5890523",
                "{\"status\":200,\"s\":[],\"errmsg\":\"\"}");
        try {
            h.client.readChapter(HOST + "/viewer/5890523");
            org.junit.Assert.fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("권한"));
        }
    }

    @Test
    public void readChapter_plain_text_never_used_as_body() throws Exception {
        // Viewer page itself contains readable prose; loader returns a gate.
        // The adapter must throw, never fall back to doc.text().
        Harness h = new Harness();
        h.http.posts.put(HOST + "/proc/viewer_data/5890523", BAD_JSON);
        try {
            h.client.readChapter(HOST + "/viewer/5890523");
            org.junit.Assert.fail("expected IOException");
        } catch (IOException expected) {
            assertFalse("fake body from SSR page is forbidden",
                    expected.getMessage().contains("SSR chrome text"));
        }
    }

    // ------------------------------------------------------------------
    // parse helpers (static parse path)
    // ------------------------------------------------------------------

    @Test
    public void parse_episode_sheet_static() {
        List<SiteClient.Entry> e = SiteClient.parseEpisodeSheet(EPISODE_SHEET, "442723");
        assertEquals(2, e.size());
        assertEquals("무료", e.get(0).detail);
        assertEquals("PLUS", e.get(1).detail);
    }

    @Test
    public void parse_catalog_static_dedupes_and_caps_after_100() {
        List<SiteClient.Entry> e = SiteClient.parseCatalog(NOVEL_PAGE + NOVEL_PAGE);
        int viewer = 0;
        for (SiteClient.Entry x : e) if (x.url.endsWith("/viewer/5890523")) viewer++;
        assertEquals(1, viewer);
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            many.append("<a href=\"/novel/").append(i).append("\">n").append(i).append("</a>");
        }
        List<SiteClient.Entry> capped = SiteClient.parseCatalog(many.toString());
        assertEquals(100, capped.size());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void expectNotAllowed(SiteClient client, String url) {
        try {
            client.browse(url);
            org.junit.Assert.fail("expected IllegalArgumentException for " + url);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not allowed"));
        } catch (IOException e) {
            org.junit.Assert.fail("should fail with IllegalArgumentException, got IOException");
        }
    }

    private static Map<String, String> queryParams(String url) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (url == null) return m;
        int q = url.indexOf('?');
        if (q < 0) return m;
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            m.put(toString(decodeQuery(pair.substring(0, eq))),
                    toString(decodeQuery(pair.substring(eq + 1))));
        }
        return m;
    }

    private static int countKey(Map<String, String> m, String key) {
        int n = 0;
        for (String k : m.keySet()) if (key.equals(k)) n++;
        return n;
    }

    private static String decodeQuery(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String toString(String s) {
        return s == null ? "" : s;
    }

    private static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Reader title cleanup + episode metadata + navigation (2026-09-27)
    // ------------------------------------------------------------------

    @Test
    public void cleanTitle_strips_exact_slogan_prefix_and_legacy_suffix() {
        assertEquals("[헌터들이 집착하는 EX급 핍 제작자가 되었다]",
                SiteClient.cleanTitle("노벨피아 - 웹소설로 꿈꾸는 세상! - [헌터들이 집착하는 EX급 핍 제작자가 되었다]"));
        assertEquals("헌터들이 집착하는 EX급 핍 제작자가 되었다",
                SiteClient.cleanTitle("노벨피아 - 헌터들이 집착하는 EX급 핍 제작자가 되었다"));
        assertEquals("헌터들이 집착하는 EX급 핍 제작자가 되었다",
                SiteClient.cleanTitle("헌터들이 집착하는 EX급 핍 제작자가 되었다 - 노벨피아"));
    }

    @Test
    public void cleanTitle_exact_slogan_prefix_tolerates_near_spaces() {
        String novel = "헌터들이 집착하는 EX급 핍 제작자가 되었다";
        assertEquals(novel, SiteClient.cleanTitle("노벨피아-웹소설로 꿈꾸는 세상!-" + novel));
        assertEquals(novel, SiteClient.cleanTitle("노벨피아  -  웹소설로 꿈꾸는 세상!  -  " + novel));
        assertEquals(novel, SiteClient.cleanTitle("노벨피아 - 웹소설로 꿈꾸는 세상! - " + novel));
    }

    @Test
    public void cleanTitle_preserves_embedded_slogan_and_leaves_unknown_titles() {
        // Slogan appearing in the middle of a legitimate title must never
        // be globally removed (only an exact anchored prefix is stripped).
        String embedded = "작품 제목 노벨피아 - 웹소설로 꿈꾸는 세상! - 가운데 문구";
        assertEquals(embedded, SiteClient.cleanTitle(embedded));
        String suffix = "작품 제목 - 노벨피아 - 웹소설로 꿈꾸는 세상!";
        assertEquals(suffix, SiteClient.cleanTitle(suffix));
        // Slogan-like suffix must not be treated as a prefix (anchored only).
        assertEquals("꿈꾸는 세상! 노벨피아 - 웹소설로", SiteClient.cleanTitle("꿈꾸는 세상! 노벨피아 - 웹소설로"));
        assertEquals("", SiteClient.cleanTitle(""));
        assertEquals("", SiteClient.cleanTitle("   "));
        assertEquals("어느 작품", SiteClient.cleanTitle("어느 작품"));
        assertEquals("엠프티", SiteClient.cleanTitle("노벨피아 - 웹소설로 꿈꾸는 세상! - 엠프티"));
    }

    @Test
    public void readChapter_slogan_strip_and_episode_metadata() throws Exception {
        Harness h = new Harness();
        SiteClient.Chapter c = h.client.readChapter(HOST + "/viewer/5890523");
        assertEquals("[헌터들이 집착하는 EX급 핍 제작자가 되었다]", c.title);
        assertEquals("프롤로그", c.episodeTitle);
        assertEquals("프롤로그", c.episodeLabel);
        assertEquals(HOST + "/novel/442723", c.novelUrl);
        assertEquals(HOST + "/viewer/5890533", c.nextUrl);
        // previousUrl comes from #back_epi_auto_url in the shared fixture
        assertEquals(HOST + "/viewer/5890513", c.previousUrl);
        assertEquals(HOST + "/viewer/5890523", c.url);
        assertEquals("size=14&viewer_paging=",
                h.http.postBodies.get(HOST + "/proc/viewer_data/5890523"));
    }

    @Test
    public void readChapter_nav_rejects_zero_self_foreign_and_confusions() throws Exception {
        Harness h = new Harness();
        h.http.gets.put(HOST + "/viewer/1",
                "<html><head><title>노벨피아 - 웹소설로 꿈꾸는 세상! - 작품</title></head><body>"
                + "<input type=\"hidden\" id=\"novel_no\" value=\"442723\">"
                + "<input type=\"hidden\" id=\"content_no\" value=\"1\">"
                + "<input type=\"hidden\" name=\"content_no\" value=\"1\">"
                + "<input type=\"hidden\" id=\"content_no_next\" value=\"0\">"
                + "<input type=\"hidden\" id=\"content_no_pre\" value=\"1\">"
                + "<input type=\"hidden\" id=\"next_epi_auto_url\" value=\"/viewer/2\">"
                + "<input type=\"hidden\" id=\"back_epi_auto_url\" value=\"javascript:alert(1)\">"
                + "</body></html>");
        h.http.posts.put(HOST + "/proc/viewer_data/1", VIEWER_BODY_JSON);
        SiteClient.Chapter c = h.client.readChapter(HOST + "/viewer/1");
        assertEquals("", c.nextUrl);
        assertEquals("", c.previousUrl);

        Harness h2 = new Harness();
        h2.http.gets.put(HOST + "/viewer/5",
                "<html><head><title>노벨피아 - 웹소설로 꿈꾸는 세상! - 작품</title></head><body>"
                + "<input type=\"hidden\" id=\"novel_no\" value=\"442723\">"
                + "<input type=\"hidden\" id=\"content_no\" value=\"5\">"
                + "<input type=\"hidden\" name=\"content_no\" value=\"5\">"
                + "<input type=\"hidden\" id=\"content_no_next\" value=\"https://evil.example/viewer/6\">"
                + "<input type=\"hidden\" id=\"content_no_pre\" value=\"/viewer/4?x=1\">"
                + "</body></html>");
        h2.http.posts.put(HOST + "/proc/viewer_data/5", VIEWER_BODY_JSON);
        SiteClient.Chapter c2 = h2.client.readChapter(HOST + "/viewer/5");
        assertEquals("", c2.nextUrl);
        assertEquals("", c2.previousUrl);

        Harness h3 = new Harness();
        h3.http.gets.put(HOST + "/viewer/3",
                "<html><head><title>노벨피아 - 웹소설로 꿈꾸는 세상! - 작품</title></head><body>"
                + "<input type=\"hidden\" id=\"novel_no\" value=\"442723\">"
                + "<input type=\"hidden\" id=\"content_no\" value=\"3\">"
                + "<input type=\"hidden\" name=\"content_no\" value=\"3\">"
                + "<input type=\"hidden\" id=\"content_no_next\" value=\"https://book.novelpia.com/viewer/6\">"
                + "<input type=\"hidden\" id=\"content_no_pre\" value=\"4\">"
                + "</body></html>");
        h3.http.posts.put(HOST + "/proc/viewer_data/3", VIEWER_BODY_JSON);
        SiteClient.Chapter c3 = h3.client.readChapter(HOST + "/viewer/3");
        // book.novelpia.com is one of the two official hosts the app can open
        assertEquals("https://book.novelpia.com/viewer/6", c3.nextUrl);
        assertEquals(HOST + "/viewer/4", c3.previousUrl);
    }

    @Test
    public void readChapter_auto_url_rejects_non_official_subdomains() throws Exception {
        // Policy: only novelpia.com and book.novelpia.com may produce prev/next
        // URLs; every other *.novelpia.com subdomain is not the app's host.
        String[] bad = {
            "https://m.novelpia.com/viewer/7",
            "https://ads.novelpia.com/viewer/7",
            "https://evil.novelpia.com/viewer/7",
            "https://novelpia.com.evil/viewer/7",
            "https://novelpia.com@evil.example/viewer/7",
            "http://book.novelpia.com/viewer/7",
            "https://book.novelpia.com:8443/viewer/7",
            "https://book.novelpia.com/viewer/7?x=1",
            "javascript:alert(1)",
            "/viewer/7/extra"
        };
        for (String value : bad) {
            Harness h = new Harness();
            h.http.gets.put(HOST + "/viewer/3",
                    "<html><head><title>노벨피아 - 웹소설로 꿈꾸는 세상! - 작품</title></head><body>"
                    + "<input type=\"hidden\" id=\"novel_no\" value=\"442723\">"
                    + "<input type=\"hidden\" id=\"content_no\" value=\"3\">"
                    + "<input type=\"hidden\" name=\"content_no\" value=\"3\">"
                    + "<input type=\"hidden\" id=\"next_epi_auto_url\" value=\"" + value + "\">"
                    + "<input type=\"hidden\" id=\"back_epi_auto_url\" value=\"/viewer/2\">"
                    + "</body></html>");
            h.http.posts.put(HOST + "/proc/viewer_data/3", VIEWER_BODY_JSON);
            SiteClient.Chapter c = h.client.readChapter(HOST + "/viewer/3");
            assertEquals("auto next must be empty for " + value, "", c.nextUrl);
            assertEquals("good previous must still work for " + value,
                    HOST + "/viewer/2", c.previousUrl);
        }
    }

    @Test
    public void readChapter_auto_url_same_id_on_alternate_official_host_is_self() throws Exception {
        // self-detection compares the viewer id parsed from the current URL,
        // so a same-id link on the other official host must also be rejected.
        Harness h = new Harness();
        h.http.gets.put(HOST + "/viewer/9",
                "<html><head><title>노벨피아 - 웹소설로 꿈꾸는 세상! - 작품</title></head><body>"
                + "<input type=\"hidden\" id=\"novel_no\" value=\"442723\">"
                + "<input type=\"hidden\" id=\"content_no\" value=\"9\">"
                + "<input type=\"hidden\" name=\"content_no\" value=\"9\">"
                + "<input type=\"hidden\" id=\"next_epi_auto_url\" value=\"https://book.novelpia.com/viewer/9\">"
                + "<input type=\"hidden\" id=\"back_epi_auto_url\" value=\"https://book.novelpia.com/viewer/8\">"
                + "</body></html>");
        h.http.posts.put(HOST + "/proc/viewer_data/9", VIEWER_BODY_JSON);
        SiteClient.Chapter c = h.client.readChapter(HOST + "/viewer/9");
        assertEquals("", c.nextUrl);
        assertEquals("https://book.novelpia.com/viewer/8", c.previousUrl);

        Harness h2 = new Harness();
        h2.http.gets.put(HOST + "/viewer/9",
                "<html><head><title>노벨피아 - 웹소설로 꿈꾸는 세상! - 작품</title></head><body>"
                + "<input type=\"hidden\" id=\"novel_no\" value=\"442723\">"
                + "<input type=\"hidden\" id=\"content_no\" value=\"9\">"
                + "<input type=\"hidden\" name=\"content_no\" value=\"9\">"
                + "<input type=\"hidden\" id=\"next_epi_auto_url\" value=\"https://novelpia.com/viewer/9\">"
                + "</body></html>");
        h2.http.posts.put(HOST + "/proc/viewer_data/9", VIEWER_BODY_JSON);
        SiteClient.Chapter c2 = h2.client.readChapter(HOST + "/viewer/9");
        assertEquals("", c2.nextUrl);
    }

    @Test
    public void readChapter_array_nav_inputs_may_be_name_only() throws Exception {
        Harness h = new Harness();
        h.http.gets.put(HOST + "/viewer/11",
                "<html><head><title>노벨피아 - 웹소설로 꿈꾸는 세상! - 작품</title></head><body>"
                + "<input type=\"hidden\" id=\"novel_no\" value=\"442723\">"
                + "<input type=\"hidden\" id=\"content_no\" value=\"11\">"
                + "<input type=\"hidden\" name=\"content_no\" value=\"11\">"
                + "<input type=\"hidden\" name=\"content_no_next\" value=\"12\">"
                + "<input type=\"hidden\" name=\"content_no_pre\" value=\"10\">"
                + "</body></html>");
        h.http.posts.put(HOST + "/proc/viewer_data/11", VIEWER_BODY_JSON);
        SiteClient.Chapter c = h.client.readChapter(HOST + "/viewer/11");
        assertEquals(HOST + "/viewer/12", c.nextUrl);
        assertEquals(HOST + "/viewer/10", c.previousUrl);
        assertEquals("", c.episodeTitle);
        assertEquals("", c.episodeLabel);
        assertEquals(HOST + "/novel/442723", c.novelUrl);
    }

    @Test
    public void readChapter_empty_metadata_is_graceful_no_invented_ordinal() throws Exception {
        Harness h = new Harness();
        h.http.gets.put(HOST + "/viewer/77",
                "<html><head><title>노벨피아 - 웹소설로 꿈꾸는 세상! - 작품</title></head><body>"
                + "<input type=\"hidden\" id=\"content_no\" value=\"77\">"
                + "<input type=\"hidden\" name=\"content_no\" value=\"77\">"
                + "</body></html>");
        h.http.posts.put(HOST + "/proc/viewer_data/77", VIEWER_BODY_JSON);
        SiteClient.Chapter c = h.client.readChapter(HOST + "/viewer/77");
        assertEquals("작품", c.title);
        assertEquals("", c.nextUrl);
        assertEquals("", c.previousUrl);
        assertEquals("", c.episodeTitle);
        assertEquals("", c.episodeLabel);
        assertEquals("", c.novelUrl);
        assertEquals(HOST + "/viewer/77", c.url);
    }

    @Test
    public void readChapter_bounds_episode_metadata_and_title() throws Exception {
        Harness h = new Harness();
        String longTitle = rep("가", 1200);
        h.http.gets.put(HOST + "/viewer/8",
                "<html><head><title>노벨피아 - 웹소설로 꿈꾸는 세상! - " + longTitle + "</title></head><body>"
                + "<input type=\"hidden\" id=\"novel_no\" value=\"1\">"
                + "<input type=\"hidden\" id=\"content_no\" value=\"8\">"
                + "<input type=\"hidden\" name=\"content_no\" value=\"8\">"
                + "<div class=\"menu-title-wrapper\">"
                + "<span class=\"menu-top-tag\">" + rep("라", 60) + "</span>"
                + "<span class=\"menu-top-title\">" + rep("나", 700) + "</span>"
                + "</div>"
                + "</body></html>");
        h.http.posts.put(HOST + "/proc/viewer_data/8", VIEWER_BODY_JSON);
        SiteClient.Chapter c = h.client.readChapter(HOST + "/viewer/8");
        assertEquals(1000, c.title.length());
        assertEquals(500, c.episodeTitle.length());
        assertEquals(60, c.episodeLabel.length());
    }

    @Test
    public void chapter_legacy_constructor_keeps_new_fields_empty() {
        SiteClient.Chapter c = new SiteClient.Chapter("t", "x", HOST + "/viewer/1", "", "");
        assertEquals("t", c.title);
        assertEquals("", c.episodeTitle);
        assertEquals("", c.episodeLabel);
        assertEquals("", c.novelUrl);
    }
}
