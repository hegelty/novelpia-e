package me.crema.novelia.account;

import static org.junit.Assert.*;

import me.crema.novelia.site.SiteClient;
import me.crema.novelia.net.HttpException;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class AccountClientTest {
    private static final String HOST = "https://novelpia.com";
    private static final String AUTH =
            "<script>const _top_obj = { data : { mem_adt : \"0\", "
            + "mem_no : \"42\", mem_birthday : \"2000-01-01\" }, methods : {} };</script>"
            + "<form id='login_box'></form><script>var login_req = 1;</script>"
            + "<a href='/viewer/5890523'>최근 회차</a>";
    private static final class Fake implements AccountClient.HttpAgent {
        final Map<String, String> gets = new LinkedHashMap<String, String>();
        final Map<String, String> posts = new LinkedHashMap<String, String>();
        String postUrl;
        int getCount;
        Map<String, String> form;
        @Override public String get(String url) throws IOException {
            getCount++;
            if (!gets.containsKey(url)) throw new IOException("no fixture");
            return gets.get(url);
        }
        @Override public String post(String url, Map<String, String> form) throws IOException {
            postUrl = url; this.form = new LinkedHashMap<String, String>(form);
            if (!posts.containsKey(url)) throw new IOException("no fixture");
            return posts.get(url);
        }
    }

    /** nextEpisode() only needs the http.post seam; GETs and auth stay unused. */
    private static final class Next implements AccountClient.HttpAgent {
        String postUrl;
        Map<String, String> form;
        final String response;
        Next(String response) { this.response = response; }
        @Override public String get(String url) throws IOException {
            throw new IOException("no get fixture");
        }
        @Override public String post(String url, Map<String, String> form) throws IOException {
            postUrl = url;
            this.form = new LinkedHashMap<String, String>(form);
            return response;
        }
    }

    @Test public void session_requires_explicit_member_number() {
        Fake f = new Fake();
        f.gets.put(HOST + "/mybook/last_view",
                "<script>const _top_obj = { data : { mem_adt : \"0\", "
                + "mem_no : \"0\" }, methods : {} };</script><form id='login_box'></form>");
        assertEquals(AccountClient.SessionStatus.State.UNAUTHENTICATED,
                new AccountClient(f).sessionStatus().state);
        f.gets.put(HOST + "/mybook/last_view",
                "<form id='login_box'></form><script>var login_req = 1;</script>"
                        + "<script>const other = { mem_no : \"88\" };</script>");
        assertEquals(AccountClient.SessionStatus.State.UNKNOWN,
                new AccountClient(f).sessionStatus().state);
    }

    @Test public void login_accepts_each_top_object_declaration() {
        for (String declaration : new String[]{"const", "let", "var"}) {
            Fake f = new Fake();
            f.posts.put(HOST + "/proc/login", "not proof of authentication");
            f.gets.put(HOST + "/mybook/last_view", AUTH.replace("const _top_obj", declaration + " _top_obj"));
            assertEquals(declaration, AccountClient.SessionStatus.State.AUTHENTICATED,
                    new AccountClient(f).login("synthetic@example.invalid", "fake-password").state);
            f.gets.put(HOST + "/mybook/last_view", AUTH.replace("const _top_obj", declaration + " _top_obj")
                    .replace("mem_no : \"42\"", "mem_no : \"0\""));
            assertEquals(declaration, AccountClient.SessionStatus.State.UNAUTHENTICATED,
                    new AccountClient(f).sessionStatus().state);
        }
    }

    @Test public void declaration_variants_do_not_accept_unrelated_member_fields() {
        for (String declaration : new String[]{"const", "let", "var"}) {
            Fake f = new Fake();
            f.gets.put(HOST + "/mybook/last_view", "<script>" + declaration
                    + " _top_obj = { data: {}, methods: { mem_no: \"42\" } };</script>");
            assertEquals(AccountClient.SessionStatus.State.UNKNOWN, new AccountClient(f).sessionStatus().state);
            f.gets.put(HOST + "/mybook/last_view", AUTH.replace("const _top_obj", declaration + " _unrelated"));
            assertEquals(AccountClient.SessionStatus.State.UNKNOWN, new AccountClient(f).sessionStatus().state);
        }
    }

    @Test public void recent_fails_closed_for_unknown_authenticated_markup() throws Exception {
        Fake f = new Fake();
        f.gets.put(HOST + "/mybook/last_view", AUTH);
        try {
            new AccountClient(f).recent();
            fail("unknown authenticated list shape must not be treated as empty/success");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
            assertFalse(expected.getMessage().contains("5890523"));
        }
    }

    private static String libraryHtml() {
        return AUTH + "<div class='mybook-data-list-items'>"
                + "<div class='novel-list-real-container'><div class='novel-name' "
                + "onclick=\"location.href='/novel/123';\">테스트 작품</div>"
                + "<button class='novel-btn-continue' onclick=\"location.href='/viewer/456';\">"
                + "이어보기</button></div></div>"
                + "<ul class='pagination'><li class='active'><a href='/mybook/like/0/date/1'>1</a></li></ul>";
    }

    @Test public void authenticated_library_reads_one_page_without_posting() throws Exception {
        Fake f = new Fake();
        f.gets.put(HOST + "/mybook", libraryHtml());
        LibraryPage result = new AccountClient(f).library(HOST + "/mybook");
        assertEquals(1, f.getCount);
        assertNull(f.postUrl);
        assertEquals(1, result.items.size());
        assertEquals(HOST + "/novel/123", result.items.get(0).entry.url);
        assertEquals(HOST + "/viewer/456", result.items.get(0).continueUrl);
    }

    @Test public void recent_convenience_returns_only_first_page() throws Exception {
        Fake f = new Fake();
        f.gets.put(HOST + "/mybook/last_view", libraryHtml().replace("/mybook/like/", "/mybook/last_view/"));
        assertEquals(1, new AccountClient(f).recent().size());
        assertEquals(1, f.getCount);
        assertNull(f.postUrl);
    }

    @Test public void library_rejects_signed_out_or_unknown_even_with_rows() throws Exception {
        for (String html : new String[] {libraryHtml().replace("mem_no : \"42\"", "mem_no : \"0\""),
                libraryHtml().replace("_top_obj", "_unrelated")}) {
            Fake f = new Fake();
            f.gets.put(HOST + "/mybook", html);
            try {
                new AccountClient(f).library(HOST + "/mybook");
                fail("must validate authentication on the actual page");
            } catch (IOException expected) {
                assertFalse(expected.getMessage().contains("테스트 작품"));
                assertNull(f.postUrl);
            }
        }
    }

    @Test public void library_validates_route_before_network() throws Exception {
        for (String path : new String[] {"/proc/mybook", "/mybook/like/01/date/1",
                "/mybook/last_view/0/date/0", "/mybook?mode=delete"}) {
            Fake f = new Fake();
            try {
                new AccountClient(f).library(HOST + path);
                fail("must reject unsupported routes");
            } catch (IOException expected) {
                assertEquals(0, f.getCount);
                assertNull(f.postUrl);
            }
        }
    }

    @Test public void favorites_uses_exact_read_only_mode() throws Exception {
        Fake f = new Fake();
        f.gets.put(HOST + "/mybook/last_view", AUTH);
        f.posts.put(HOST + "/proc/mybook",
                "{\"status\":200,\"items\":["
                        + "{\"novel_no\":1,\"novel_name\":\"작품1\"},"
                        + "{\"novel_no\":2,\"novel_name\":\"작품2\"},"
                        + "{\"novel_no\":3,\"novel_name\":\"작품3\"},"
                        + "{\"novel_no\":4,\"novel_name\":\"작품4\"},"
                        + "{\"novel_no\":5,\"novel_name\":\"작품5\"},"
                        + "{\"novel_no\":6,\"novel_name\":\"작품6\"}]}");
        java.util.List<SiteClient.Entry> entries = new AccountClient(f).favorites();
        assertEquals("favorite_list", f.form.get("mode"));
        assertEquals(5, entries.size());
        assertEquals(HOST + "/novel/1", entries.get(0).url);
        assertEquals(HOST + "/proc/mybook", f.postUrl);
    }

    private static AccountClient favoritesFixture(String body) {
        Fake f = new Fake();
        f.gets.put(HOST + "/mybook/last_view", AUTH);
        f.posts.put(HOST + "/proc/mybook", body);
        return new AccountClient(f);
    }

    private static void expectFailure(String body, AccountClient.FavoritesException.Category category,
                                      String status) throws Exception {
        try {
            favoritesFixture(body).favorites();
            fail("expected " + category);
        } catch (AccountClient.FavoritesException e) {
            assertEquals(category, e.category);
            assertEquals(status, e.status);
            assertEquals(null, e.getCause());
            assertEquals(e.getMessage(), AccountClient.describeFavoritesFailure(e));
        }
    }

    @Test public void explicit_200_allows_missing_or_null_items_only() throws Exception {
        for (String status : new String[] {"200", "\"200\""}) {
            assertTrue(favoritesFixture("{\"status\":" + status + "}").favorites().isEmpty());
            assertTrue(favoritesFixture("{\"status\":" + status + ",\"items\":null}").favorites().isEmpty());
            assertTrue(favoritesFixture("{\"status\":" + status + ",\"items\":[]}").favorites().isEmpty());
        }
    }

    @Test public void status_errors_are_typed_and_sanitized() throws Exception {
        expectFailure("{\"status\":403,\"errmsg\":\"secret-cookie\"}",
                AccountClient.FavoritesException.Category.STATUS_NON200, "403");
        expectFailure("{\"status\":\"401\",\"errmsg\":\"secret-cookie\"}",
                AccountClient.FavoritesException.Category.STATUS_NON200, "401");
        expectFailure("{\"items\":[]}", AccountClient.FavoritesException.Category.STATUS_MISSING, "");
        expectFailure("{\"status\":null}", AccountClient.FavoritesException.Category.STATUS_MISSING, "");
        expectFailure("{\"status\":\"12345-secret-cookie\"}",
                AccountClient.FavoritesException.Category.STATUS_NONSTANDARD, "");
        expectFailure("{\"status\":true}", AccountClient.FavoritesException.Category.STATUS_NONSTANDARD, "");
        expectFailure("{\"status\":201,\"items\":[]}",
                AccountClient.FavoritesException.Category.STATUS_NON200, "201");
    }

    @Test public void malformed_payloads_do_not_become_empty_lists() throws Exception {
        expectFailure("{bad secret-cookie", AccountClient.FavoritesException.Category.INVALID_JSON, "");
        expectFailure("[]", AccountClient.FavoritesException.Category.INVALID_ENVELOPE, "");
        expectFailure("{\"status\":200,\"items\":{}}",
                AccountClient.FavoritesException.Category.ITEMS_TYPE, "");
        for (String row : new String[] {"null", "{}", "{\"novel_no\":1,\"novel_name\":\"\"}",
                "{\"novel_no\":\"1/secret-cookie\",\"novel_name\":\"private title\"}"}) {
            expectFailure("{\"status\":200,\"items\":[" + row + "]}",
                    AccountClient.FavoritesException.Category.INVALID_ROWS, "");
        }
    }

    @Test public void diagnostics_never_echo_server_text_or_cause() throws Exception {
        IOException cause = new IOException("private title cookie member-id");
        for (IOException error : new IOException[] {
                cause, new HttpException("secret-cookie", 403, cause),
                new HttpException("secret-cookie", -1, cause),
                new HttpException("secret-cookie", 12345, cause)}) {
            String diagnostic = AccountClient.describeFavoritesFailure(error);
            assertFalse(diagnostic.contains("secret"));
            assertFalse(diagnostic.contains("private"));
            assertFalse(diagnostic.contains("12345"));
        }
        assertEquals("favorites:network:403", AccountClient.describeFavoritesFailure(
                new HttpException("private title", 403, cause)));
    }

    @Test public void login_checks_followup_read_only_page_not_post_body() {
        Fake f = new Fake();
        f.posts.put(HOST + "/proc/login", "generic OK");
        f.gets.put(HOST + "/mybook/last_view", AUTH);
        AccountClient.SessionStatus status = new AccountClient(f).login("reader@example.test", "secret");
        assertEquals(AccountClient.SessionStatus.State.AUTHENTICATED, status.state);
        assertTrue(status.isAuthenticated());
        assertEquals("secret", f.form.get("wd"));
        assertFalse(status.message.contains("reader"));
    }

    // ------------------------------------------------------------------
    // nextEpisode(item) metadata resolver
    // ------------------------------------------------------------------

    private static LibraryPage.Item nextItem(String key, String viewer) {
        SiteClient.Entry entry = new SiteClient.Entry("합성", HOST + "/novel/7", "novel", "");
        return new LibraryPage.Item(entry, viewer, -1, -1, "", key);
    }

    private static void assertNextFailure(String json, String itemKey,
                                          String viewer) throws Exception {
        assertNextFailure(json, itemKey, viewer, null);
    }

    private static void assertNextFailure(String json, String itemKey, String viewer,
                                          String expectedMessage) throws Exception {
        Next n = new Next(json);
        try {
            new AccountClient(n).nextEpisode(nextItem(itemKey, viewer));
            fail("malformed next response accepted: " + json);
        } catch (IOException expected) {
            assertEquals(HOST + "/proc/mybook", n.postUrl);
            assertNull(expected.getCause());
            assertFalse(expected.getMessage().contains("secret-cookie"));
            if (expectedMessage != null) assertEquals(expectedMessage, expected.getMessage());
        }
    }

    @Test public void nextEpisode_uses_exact_read_only_mode_and_form() throws Exception {
        Next n = new Next("{\"status\":200,\"result\":{\"next_episode_no\":\"8\"}}");
        AccountClient.NextEpisode resolved = new AccountClient(n)
                .nextEpisode(nextItem("77", HOST + "/viewer/99"));
        assertEquals(AccountClient.NextEpisode.State.AVAILABLE, resolved.state);
        assertEquals(HOST + "/viewer/8", resolved.url);
        assertEquals(HOST + "/proc/mybook", n.postUrl);
        assertEquals("get_next_episode", n.form.get("mode"));
        assertEquals("7", n.form.get("novel_no"));
        assertEquals("77", n.form.get("novel_epi_no"));
    }

    @Test public void nextEpisode_malicious_result_ids_fail_closed() throws Exception {
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":\"08\"}}",
                "77", HOST + "/viewer/99");
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":\"+8\"}}",
                "77", HOST + "/viewer/99");
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":\"1e3\"}}",
                "77", HOST + "/viewer/99");
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":\"9999999999999\"}}",
                "77", HOST + "/viewer/99");
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":\"9 9\"}}",
                "77", HOST + "/viewer/99");
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":\"0x8\"}}",
                "77", HOST + "/viewer/99");
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":\"8e0\"}}",
                "77", HOST + "/viewer/99");
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":\"8a\"}}",
                "77", HOST + "/viewer/99");
        // Number/bool tokens are parsed as strings by Json, but the range guard still rejects them.
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":true}}",
                "77", HOST + "/viewer/99");
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":false}}",
                "77", HOST + "/viewer/99");
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":null}}",
                "77", HOST + "/viewer/99");
    }

    @Test public void nextEpisode_zero_key_is_sent_as_the_ordering_key() throws Exception {
        Next n = new Next("{\"status\":200,\"result\":{\"next_episode_no\":\"8\"}}");
        AccountClient.NextEpisode resolved = new AccountClient(n)
                .nextEpisode(nextItem("0", HOST + "/viewer/99"));
        assertEquals(AccountClient.NextEpisode.State.AVAILABLE, resolved.state);
        assertEquals(HOST + "/viewer/8", resolved.url);
        assertEquals("get_next_episode", n.form.get("mode"));
        assertEquals("7", n.form.get("novel_no"));
        assertEquals("0", n.form.get("novel_epi_no"));
    }

    @Test public void nextEpisode_numeric_and_string_401_are_exact_login_errors() throws Exception {
        assertNextFailure("{\"status\":401,\"result\":{\"next_episode_no\":\"8\"}}",
                "77", HOST + "/viewer/8", "로그인이 필요합니다.");
        assertNextFailure("{\"status\":\"401\",\"errmsg\":\"secret-cookie\","
                + "\"result\":{\"next_episode_no\":\"8\"}}",
                "77", HOST + "/viewer/8", "로그인이 필요합니다.");
    }

    @Test public void nextEpisode_wait_and_end_states_never_open_content() throws Exception {
        Next wait = new Next("{\"status\":200,\"result\":{\"next_episode_no\":\"8\","
                + "\"wait_episode\":\"1\"}}");
        AccountClient.NextEpisode waiting = new AccountClient(wait)
                .nextEpisode(nextItem("77", HOST + "/viewer/99"));
        assertEquals(AccountClient.NextEpisode.State.WAITING, waiting.state);
        assertEquals("", waiting.url);
        assertEquals("get_next_episode", wait.form.get("mode"));
        assertEquals("77", wait.form.get("novel_epi_no"));

        Next end = new Next("{\"status\":200,\"result\":{\"next_episode_no\":\"0\","
                + "\"end_episode\":\"1\"}}");
        AccountClient.NextEpisode ended = new AccountClient(end)
                .nextEpisode(nextItem("77", HOST + "/viewer/99"));
        assertEquals(AccountClient.NextEpisode.State.END, ended.state);
        assertEquals("", ended.url);
        assertEquals("get_next_episode", end.form.get("mode"));
        assertEquals("77", end.form.get("novel_epi_no"));
    }

    @Test public void nextEpisode_rejects_missing_next_or_self_url_and_keeps_items() throws Exception {
        // Missing key means no resolver call and an unknown failure.
        try {
            new AccountClient(new Next("ignored")).nextEpisode(nextItem("", HOST + "/viewer/8"));
            fail("missing next key must fail closed");
        } catch (IOException expected) {
            assertEquals("다음 회차 정보를 확인할 수 없습니다.", expected.getMessage());
        }
        // A server response pointing back at the same viewer id is rejected:
        // the item's continueUrl IS the response target, so no self-loop.
        Next n = new Next("{\"status\":200,\"result\":{\"next_episode_no\":\"8\"}}");
        try {
            new AccountClient(n).nextEpisode(nextItem("77", HOST + "/viewer/8"));
            fail("self next URL must fail closed");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("viewer"));
        }
    }

    @Test public void nextEpisode_401_and_malformed_envelopes_fail_closed() throws Exception {
        assertNextFailure("{\"status\":401,\"result\":{\"next_episode_no\":\"8\"}}",
                "77", HOST + "/viewer/8");
        assertNextFailure("{\"status\":200,\"result\":{\"next_episode_no\":\"abc\"}}",
                "77", HOST + "/viewer/8");
        assertNextFailure("[]", "77", HOST + "/viewer/8");
    }
}
