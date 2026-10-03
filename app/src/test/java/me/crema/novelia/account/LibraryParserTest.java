package me.crema.novelia.account;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

public class LibraryParserTest {
    private static final String HOST = "https://novelpia.com";

    @Test public void unsolicited_search_cannot_change_active_selection() throws Exception {
        String html = "<div class='mybook-data-list-items'>" + row("1", "합성", false)
                + "</div><ul class='pagination'>"
                + anchor(1, "like", "0", "date", "other") + "</ul>";
        expectParseFailure(html, HOST + "/mybook");
    }

    // ------------------------------------------------------------------
    // Fixture helpers
    // ------------------------------------------------------------------

    private static String row(String id, String title, boolean continuation) {
        return row(id, title, continuation, "", "", "");
    }

    private static String row(String id, String title, boolean continuation,
                              String numerical, String continueText, String writer) {
        StringBuilder html = new StringBuilder("<div class='novel-list-real-container'>");
        html.append("<div class='novel-name' onclick=\"location.href='/novel/")
                .append(id).append("'\">").append(title).append("</div>");
        if (continuation) {
            html.append("<button class='novel-btn-continue' onclick=\"location.href='/viewer/")
                    .append(id).append("'\">");
            html.append(continueText.length() == 0 ? "계속" : continueText).append("</button>");
        }
        if (numerical.length() != 0) {
            html.append("<div class='novel-numerical'>").append(numerical).append("</div>");
        }
        if (writer.length() != 0) {
            html.append("<div class='writer-name'>").append(writer).append("</div>");
        }
        html.append("</div>");
        return html.toString();
    }

    /** Row with an optional site get_next_episode button; attributes may be empty. */
    private static String nextRow(String id, String title, boolean continuation,
                                  String numerical, String continueText, String writer,
                                  String nextAttrs, String nextOnclick) {
        StringBuilder html = new StringBuilder("<div class='novel-list-real-container'>");
        html.append("<div class='novel-name' onclick=\"location.href='/novel/")
                .append(id).append("'\">").append(title).append("</div>");
        if (continuation) {
            html.append("<button class='novel-btn-continue' onclick=\"location.href='/viewer/")
                    .append(id).append("'\">");
            html.append(continueText.length() == 0 ? "계속" : continueText).append("</button>");
        }
        if (numerical.length() != 0) {
            html.append("<div class='novel-numerical'>").append(numerical).append("</div>");
        }
        if (writer.length() != 0) {
            html.append("<div class='writer-name'>").append(writer).append("</div>");
        }
        if (nextAttrs.length() != 0 || nextOnclick.length() != 0) {
            html.append("<button class='novel-btn-next' ").append(nextAttrs);
            if (nextOnclick.length() != 0) {
                html.append(" onclick=\"").append(nextOnclick).append("\"");
            }
            html.append("></button>");
        }
        html.append("</div>");
        return html.toString();
    }

    private static String chapterCell(String totalHtml) {
        return "<div><span class='novel-numerical-title'>회차</span><span>"
                + totalHtml + "</span></div>";
    }

    private static String anchor(int n, String shelf, String group, String sort) {
        String path = "/mybook/" + shelf + "/" + group + "/" + sort + "/" + n;
        return "<li" + (n == 1 ? " class='active'" : "") + "><a href='" + path + "'>"
                + n + "</a></li>";
    }

    private static String anchor(int n, String shelf, String group, String sort, String search) {
        String path = "/mybook/" + shelf + "/" + group + "/" + sort + "/" + n;
        if (search != null && search.length() != 0) path += "?search=" + encode(search);
        return "<li" + (n == 1 ? " class='active'" : "") + "><a href='" + path + "'>"
                + n + "</a></li>";
    }

    private static String pager(String shelf, String group, String sort) {
        return "<ul class='pagination'>" + anchor(1, shelf, group, sort)
                + anchor(2, shelf, group, sort) + "</ul>";
    }

    private static String pager(String shelf, String group, String sort, String search) {
        String links = anchor(1, shelf, group, sort, search);
        if (search != null && search.length() != 0) {
            // The site often drops ?search= from the page-2 link; both shapes
            // are exercised by default so parsing stays robust.
            links += anchor(2, shelf, group, sort);
        } else {
            links += anchor(2, shelf, group, sort);
        }
        return "<ul class='pagination'>" + links + "</ul>";
    }

    private static String nonEmptyPage(String rows) {
        return nonEmptyPage("like", "0", "date", rows);
    }

    private static String nonEmptyPage(String shelf, String group, String sort, String rows) {
        return "<div class='mybook-data-list-items'>" + rows + "</div>"
                + pager(shelf, group, sort);
    }

    private static String withValidPager(String html) {
        return html + pager("like", "0", "date");
    }

    /** Filters and group navigation next to the list, as observed. */
    private static String filterBar(String shelf, String sort) {
        StringBuilder html = new StringBuilder("<div class='mybook-filter-align-box'>")
                .append("<div data-item='date'>공개일자순</div>")
                .append("<div data-item='view'>조회순</div>")
                .append("<div data-item='list'>등록순</div>")
                .append("<div data-item='vote'>추천순</div>")
                .append("</div>");
        html.append("<div onclick=\"move_cate('/mybook/").append(shelf).append("/0/")
                .append(sort).append("/1')\">전체</div>");
        html.append("<div onclick=\"move_cate('/mybook/").append(shelf).append("/-1/")
                .append(sort).append("/1')\">미분류</div>");
        html.append("<div onclick=\"move_cate('/mybook/").append(shelf).append("/-2/")
                .append(sort).append("/1')\">연중</div>");
        html.append("<div onclick=\"move_cate('/mybook/").append(shelf).append("/77/")
                .append(sort).append("/1')\">내 그룹</div>");
        return html.toString();
    }

    private static void expectParseFailure(String html, String url) throws Exception {
        try {
            LibraryParser.parse(html, url);
            fail("malformed library page parsed");
        } catch (IOException expected) {
            assertEquals("library:invalid_page", expected.getMessage());
        }
    }

    private static void expectBadUrl(String value) throws Exception {
        try {
            LibraryParser.normalizeUrl(value);
            fail("accepted disallowed URL: " + value);
        } catch (IOException expected) {
            assertEquals("library:invalid_page", expected.getMessage());
        }
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String queryOf(String canonical) {
        int q = canonical.indexOf('?');
        return q < 0 ? "" : canonical.substring(q + "?search=".length());
    }

    private static String searchOf(String canonical) {
        int q = canonical.indexOf('?');
        if (q < 0) return "";
        try {
            return java.net.URLDecoder.decode(canonical.substring(q + "?search=".length()), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // URL policy
    // ------------------------------------------------------------------

    @Test public void normalize_allows_only_the_canonical_shelf_routes() throws Exception {
        assertEquals(HOST + "/mybook", LibraryParser.normalizeUrl("/mybook"));
        assertEquals(HOST + "/mybook/like", LibraryParser.normalizeUrl("/mybook/like"));
        assertEquals(HOST + "/mybook/alarm", LibraryParser.normalizeUrl("/mybook/alarm"));
        assertEquals(HOST + "/mybook/collect", LibraryParser.normalizeUrl("/mybook/collect"));
        assertEquals(HOST + "/mybook/last_view", LibraryParser.normalizeUrl("/mybook/last_view"));
        assertEquals(HOST + "/mybook/like/0/date/100000",
                LibraryParser.normalizeUrl(HOST + "/mybook/like/0/date/100000"));
        assertEquals(HOST + "/mybook/last_view/0/date/2",
                LibraryParser.normalizeUrl("/mybook/last_view/0/date/2"));
        for (String value : new String[] {
                "http://novelpia.com/mybook", "https://evil.example/mybook",
                "https://novelpia.com.evil.example/mybook", "//evil.example/mybook",
                "https://novelpia.com:443/mybook", "/mybook?x=1", "/mybook#frag",
                "/mybook/like/0/date/0", "/mybook/like/0/date/100001",
                "/mybook/like/0/date/01", "/mybook/like/0/date/1/extra",
                "/novel/123", "/mybook/like?x=1", "/mybook/alarm?search=a",
                "/mybook/like/0/date/1?x=1", "/mybook/like/0/date/1#frag",
                "/mybook/like/0/date/1/",
                "/mybook/like/00/date/1", "/mybook/like/+1/date/1",
                "/mybook/like/0/viewed/1", "/mybook/favorites/0/date/1",
                "https://novelpia.com.evil.example/mybook/like/0/date/1"
        }) expectBadUrl(value);
    }

    @Test public void positive_user_group_ids_are_permitted_up_to_twelve_digits() throws Exception {
        for (String group : new String[] {"1", "12", "123456789012"}) {
            assertEquals(HOST + "/mybook/like/" + group + "/date/1",
                    LibraryParser.normalizeUrl("/mybook/like/" + group + "/date/1"));
        }
        for (String group : new String[] {"0", "-1", "-2"}) {
            assertEquals(HOST + "/mybook/like/" + group + "/date/1",
                    LibraryParser.normalizeUrl("/mybook/like/" + group + "/date/1"));
        }
        for (String group : new String[] {"1234567890123", "01", "00", "-3", "-01", "1x"}) {
            expectBadUrl("/mybook/like/" + group + "/date/1");
        }
    }

    @Test public void all_shelves_and_sorts_are_route_canonical() throws Exception {
        for (String shelf : new String[] {"like", "last_view", "alarm", "collect"}) {
            for (String sort : new String[] {"date", "view", "list", "vote"}) {
                String path = "/mybook/" + shelf + "/-2/" + sort + "/7";
                assertEquals(HOST + path, LibraryParser.normalizeUrl(path));
            }
        }
    }

    @Test public void buildUrl_accepts_every_observed_selection() throws Exception {
        for (String shelf : new String[] {"like", "last_view", "alarm", "collect"}) {
            for (String group : new String[] {"0", "-1", "-2", "777"}) {
                for (String sort : new String[] {"date", "view", "list", "vote"}) {
                    assertEquals(HOST + "/mybook/" + shelf + "/" + group + "/" + sort + "/3",
                            LibraryParser.buildUrl(shelf, group, sort, 3, null));
                }
            }
        }
    }

    @Test public void buildUrl_rejects_unknown_routes_and_bad_search() throws Exception {
        String[][] invalid = {
                {"like", "0", "vote", "100001", null},
                {"like", "0", "vote", "0", null},
                {"like", "0", "other", "1", null},
                {"like", "0", "date", "1", "has\u0000frag"},
                {"like", "0", "date", "1", "trailing\u0000"},
                {"favorites", "0", "date", "1", null},
                {"like", "00", "date", "1", null},
                {"like", "0", "date", "-1", null},
        };
        for (String[] args : invalid) {
            try {
                LibraryParser.buildUrl(args[0], args[1], args[2],
                        Integer.parseInt(args[3]), args[4]);
                fail("invalid buildUrl accepted: " + args[0] + "/" + args[1] + "/" + args[2]
                        + "/" + args[3]);
            } catch (IOException expected) {
                assertEquals("library:invalid_page", expected.getMessage());
            }
        }
        StringBuilder longSearch = new StringBuilder();
        for (int i = 0; i < 101; i++) longSearch.append('가');
        try {
            LibraryParser.buildUrl("like", "0", "date", 1, longSearch.toString());
            fail("overlong search accepted");
        } catch (IOException expected) {
            assertEquals("library:invalid_page", expected.getMessage());
        }
    }

    @Test public void search_is_single_percent_encoded_parameter() throws Exception {
        assertEquals(HOST + "/mybook/like/0/date/1",
                LibraryParser.normalizeUrl("/mybook/like/0/date/1"));
        assertEquals(HOST + "/mybook/like/0/date/1?search=%EC%86%8C%EC%85%9C",
                LibraryParser.normalizeUrl(HOST + "/mybook/like/0/date/1?search=%EC%86%8C%EC%85%9C"));
        // Canonical re-encoding: spaces become %20 and redundant escapes collapse.
        assertEquals(HOST + "/mybook/collect/-2/vote/5?search=%ED%85%8C%EC%8A%A4%ED%8A%B8+%ED%82%A4",
                LibraryParser.normalizeUrl("/mybook/collect/-2/vote/5?search=%ED%85%8C%EC%8A%A4%ED%8A%B8+%ED%82%A4"));
        assertEquals("테스트 키", searchOf(LibraryParser.normalizeUrl(
                "/mybook/collect/-2/vote/5?search=%ED%85%8C%EC%8A%A4%ED%8A%B8+%ED%82%A4")));

        for (String value : new String[] {
                "/mybook/like/0/date/1?search=",
                "/mybook/like/0/date/1?search=%ED%85%8C%EC%8A%A4%ED%8A%B8&x=1",
                "/mybook/like/0/date/1?search=a&search=b",
                "/mybook/like/0/date/1?x=&search=a",
                "/mybook/like/0/date/1?search=a#frag",
                "/mybook/like/0/date/1?search=%00",
        }) expectBadUrl(value);
    }

    @Test public void search_cannot_inject_host_fragment_or_extra_query() throws Exception {
        String built = LibraryParser.buildUrl("like", "0", "date", 1, "a/b?c#d&e=f");
        assertTrue(built.startsWith(HOST + "/mybook/like/0/date/1?search="));
        assertEquals(built, LibraryParser.normalizeUrl(built));
        assertFalse(built.contains("#"));
        assertFalse(built.endsWith("&e=f"));
        assertEquals("a/b?c#d&e=f", searchOf(built));
        // buildUrl output is also parseable round-trip.
        assertEquals(built, LibraryParser.normalizeUrl(built));
    }

    @Test public void search_bound_is_100_decoded_unicode_chars() throws Exception {
        StringBuilder exactly100 = new StringBuilder();
        for (int i = 0; i < 100; i++) exactly100.append('가');
        String url = LibraryParser.buildUrl("like", "0", "date", 1, exactly100.toString());
        assertTrue(url.startsWith(HOST + "/mybook/like/0/date/1?search="));
        assertEquals(exactly100.toString(), searchOf(url));
        assertEquals(HOST + "/mybook/like/0/date/1?search=" + encode(exactly100.toString()),
                url);

        // 101 code units is over the bound even though the encoded form is
        // still far below the 1024-char URL cap.
        StringBuilder tooLong = new StringBuilder(exactly100).append('가');
        try {
            LibraryParser.buildUrl("like", "0", "date", 1, tooLong.toString());
            fail("overlong decoded search accepted");
        } catch (IOException expected) {
            assertEquals("library:invalid_page", expected.getMessage());
        }
        try {
            LibraryParser.normalizeUrl("/mybook/like/0/date/1?search="
                    + encode(tooLong.toString()));
            fail("overlong decoded search accepted");
        } catch (IOException expected) {
            assertEquals("library:invalid_page", expected.getMessage());
        }
    }

    @Test public void pageOf_handles_aliases_group_sorts_and_pages() throws Exception {
        assertEquals(1, LibraryParser.pageOf(HOST + "/mybook"));
        assertEquals(1, LibraryParser.pageOf("/mybook/last_view"));
        assertEquals(9, LibraryParser.pageOf("/mybook/alarm/77/vote/9"));
        assertEquals(100000, LibraryParser.pageOf("/mybook/collect/0/list/100000"));
        assertEquals(1, LibraryParser.pageOf("/mybook/like/0/date/1"));
        for (String bad : new String[] {"/mybook/like/0/date/0",
                "/mybook/like/0/date/100001", "/novel/1", "/mybook/like/00/date/1",
                "/mybook/like/0/date/1?x=1"}) {
            try {
                LibraryParser.pageOf(bad);
                fail("accepted pageOf " + bad);
            } catch (IOException expected) {
                assertEquals("library:invalid_page", expected.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Rows and metadata
    // ------------------------------------------------------------------

    @Test public void parses_rows_and_optional_continue_link_without_running_scripts() throws Exception {
        String html = "<script>location.href='/viewer/999';</script>"
                + "<div class='mybook-data-list-items'>"
                + row("123", "작품 하나", true).replace("/viewer/123", "/viewer/987")
                + row("456", "읽지 않은 작품", false)
                + "</div>" + pager("like", "0", "date");
        LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook");
        assertEquals(1, parsed.page);
        assertEquals("like", parsed.shelf);
        assertEquals("0", parsed.group);
        assertEquals("date", parsed.sort);
        assertEquals("", parsed.search);
        assertEquals(2, parsed.items.size());
        assertEquals("작품 하나", parsed.items.get(0).entry.title);
        assertEquals("novel", parsed.items.get(0).entry.kind);
        assertEquals(HOST + "/novel/123", parsed.items.get(0).entry.url);
        assertEquals(HOST + "/viewer/987", parsed.items.get(0).continueUrl);
        assertEquals("", parsed.items.get(1).continueUrl);
        assertEquals("", parsed.previousUrl);
        assertEquals(HOST + "/mybook/like/0/date/2", parsed.nextUrl);
    }

    @Test public void parses_row_metadata_counts_and_author() throws Exception {
        String html = nonEmptyPage("like", "0", "date",
                row("794", "속표지", true, chapterCell("794"), "EP.794 이어보기", "글쓴이"));
        LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook/like/0/date/1");
        assertEquals(1, parsed.items.size());
        LibraryPage.Item item = parsed.items.get(0);
        assertEquals(794, item.totalEpisodes);
        assertEquals(794, item.lastReadEpisode);
        assertEquals("글쓴이", item.author);
        assertEquals("마지막 읽은 EP.794 · 등록 794편", item.progressLabel());
        assertEquals(HOST + "/viewer/794", item.continueUrl);
    }

    @Test public void parses_thousand_separated_totals_with_commas_or_회_suffix() throws Exception {
        for (String total : new String[] {"1,042", "1,042회", "1042회"}) {
            String html = nonEmptyPage(row("11", "제목", true,
                    chapterCell(total), "EP.1042 이어보기", "작가"));
            LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook");
            assertEquals(1042, parsed.items.get(0).totalEpisodes);
        }
    }

    @Test public void missing_metadata_keeps_unknown_counts_and_no_author() throws Exception {
        LibraryPage parsed = LibraryParser.parse(
                nonEmptyPage(row("123", "작품 하나", true)), HOST + "/mybook");
        LibraryPage.Item item = parsed.items.get(0);
        assertEquals(-1, item.lastReadEpisode);
        assertEquals(-1, item.totalEpisodes);
        assertEquals("", item.author);
        assertEquals("", item.progressLabel());
        assertEquals(HOST + "/viewer/123", item.continueUrl);

        LibraryPage legacy = LibraryParser.parse(
                nonEmptyPage(row("456", "읽지 않은 작품", false)), HOST + "/mybook");
        assertEquals(-1, legacy.items.get(0).lastReadEpisode);
        assertEquals(-1, legacy.items.get(0).totalEpisodes);
        assertEquals("", legacy.items.get(0).progressLabel());
        assertEquals("", legacy.items.get(0).continueUrl);
    }

    @Test public void continue_label_without_episode_keeps_viewer_with_unknown_count() throws Exception {
        LibraryPage parsed = LibraryParser.parse(
                nonEmptyPage(row("9", "제목", true, "", "이어보기", "")), HOST + "/mybook");
        LibraryPage.Item item = parsed.items.get(0);
        assertEquals(-1, item.lastReadEpisode);
        assertEquals(HOST + "/viewer/9", item.continueUrl);
    }

    @Test public void viewer_id_never_becomes_the_episode_count() throws Exception {
        String html = nonEmptyPage(row("999999", "제목", true,
                chapterCell("12"), "EP.794 이어보기", "작가"));
        LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook");
        assertEquals(12, parsed.items.get(0).totalEpisodes);
        assertEquals(794, parsed.items.get(0).lastReadEpisode);
        assertTrue(parsed.items.get(0).entry.url.endsWith("/novel/999999"));
    }

    @Test public void ambiguous_회차_or_writer_metadata_keeps_unknown_instead_of_failing() throws Exception {
        String duplicateChapter = row("12", "제목", true,
                chapterCell("12") + chapterCell("9"), "EP.4 이어보기", "작가");
        LibraryPage parsed = LibraryParser.parse(nonEmptyPage(duplicateChapter), HOST + "/mybook");
        assertEquals(-1, parsed.items.get(0).totalEpisodes);
        assertEquals(4, parsed.items.get(0).lastReadEpisode);

        String doubleWriter = row("12", "제목", true, chapterCell("12"), "", "하나")
                + row("13", "둘", false, "", "", "둘");
        LibraryPage parsed2 = LibraryParser.parse(nonEmptyPage(doubleWriter), HOST + "/mybook");
        assertEquals(2, parsed2.items.size());
        // Each row has exactly one .writer-name, so each is a real author.
        assertEquals("하나", parsed2.items.get(0).author);
        assertEquals("둘", parsed2.items.get(1).author);

        // A single row with two writer names is ambiguous: author stays "".
        String rowWithTwoWriters = row("14", "셋", true, chapterCell("12"), "", "하나")
                .replace("</div>", "<div class='writer-name'>둘</div></div>");
        LibraryPage parsed3 = LibraryParser.parse(
                nonEmptyPage(rowWithTwoWriters), HOST + "/mybook");
        assertEquals("", parsed3.items.get(0).author);
    }

    @Test public void single_non_회차_numerical_cell_is_ignored() throws Exception {
        String html = nonEmptyPage(row("12", "제목", true,
                "<div><span class='novel-numerical-title'>평점</span><span>9.9</span></div>",
                "EP.3 이어보기", ""));
        LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook");
        assertEquals(-1, parsed.items.get(0).totalEpisodes);
        assertEquals(3, parsed.items.get(0).lastReadEpisode);
    }

    // ------------------------------------------------------------------
    // Next-episode row buttons
    // ------------------------------------------------------------------

    @Test public void an_equal_count_next_button_still_has_next() throws Exception {
        // EP.5 이어보기 and "회차 5" are different quantities; the tail
        // count does not decide whether a next episode exists.
        LibraryPage parsed = LibraryParser.parse(nonEmptyPage(
                nextRow("7", "제목", true, chapterCell("7"), "EP.5 이어보기", "",
                        "", "get_next_episode(7, 5)")), HOST + "/mybook");
        LibraryPage.Item item = parsed.items.get(0);
        assertTrue(item.hasNextEpisode());
        assertEquals("5", item.nextEpisodeKey);
        assertEquals("다음 회차 있음 · 마지막 읽은 EP.5 · 등록 7편", item.progressLabel());
        assertEquals("5", item.nextEpisodeKey);
    }

    @Test public void no_next_button_leaves_next_absent_even_with_metadata() throws Exception {
        LibraryPage parsed = LibraryParser.parse(nonEmptyPage(
                row("7", "제목", true, chapterCell("7"), "EP.5 이어보기", "작가")),
                HOST + "/mybook");
        LibraryPage.Item item = parsed.items.get(0);
        assertFalse(item.hasNextEpisode());
        assertEquals("", item.nextEpisodeKey);
        assertEquals("마지막 읽은 EP.5 · 등록 7편", item.progressLabel());
    }

    @Test public void disabled_or_hidden_next_buttons_never_expose_next() throws Exception {
        String[] spoofed = new String[] {
                "<button class='novel-btn-next' disabled onclick='get_next_episode(7, 5)'></button>",
                "<button class='novel-btn-next' hidden onclick='get_next_episode(7, 5)'></button>",
                "<button class='novel-btn-next' aria-disabled='true' onclick='get_next_episode(7, 5)'></button>",
                "<button class='novel-btn-next' style='display:none' onclick='get_next_episode(7, 5)'></button>",
                "<button class='novel-btn-next' style='visibility:hidden' onclick='get_next_episode(7, 5)'></button>",
                "<button class='novel-btn-next' onclick='get_next_episode(6, 5)'></button>",
                "<button class='novel-btn-next' onclick=\"get_next_episode(7, 5);fetch('/private')\"></button>",
        };
        for (String button : spoofed) {
            String rowHtml = "<div class='novel-list-real-container'>"
                    + "<div class='novel-name' onclick=\"location.href='/novel/7'\">제목</div>"
                    + button + "</div>";
            LibraryPage parsed = LibraryParser.parse(
                    nonEmptyPage(rowHtml), HOST + "/mybook");
            LibraryPage.Item item = parsed.items.get(0);
            assertFalse("accepted spoofed button: " + button, item.hasNextEpisode());
            assertEquals("", item.nextEpisodeKey);
        }
    }

    @Test public void zero_next_key_is_promoted_and_survives_the_resolver_guard() throws Exception {
        LibraryPage parsed = LibraryParser.parse(nonEmptyPage(
                nextRow("7", "제목", false, "", "", "",
                        "", "get_next_episode(7, 0)")), HOST + "/mybook");
        LibraryPage.Item item = parsed.items.get(0);
        assertTrue(item.hasNextEpisode());
        assertEquals("0", item.nextEpisodeKey);
        assertEquals("다음 회차 있음", item.progressLabel());
    }

    @Test public void duplicate_next_buttons_never_expose_next() throws Exception {
        // The strict parser leaves the next key absent when the row carries
        // multiple next buttons rather than guessing which one is real.
        String duplicate = "<div class='novel-list-real-container'>"
                + "<div class='novel-name' onclick=\"location.href='/novel/7'\">제목</div>"
                + "<button class='novel-btn-next' onclick=\"get_next_episode(7, 5)\"></button>"
                + "<button class='novel-btn-next' onclick=\"get_next_episode(7, 5)\"></button>"
                + "</div>";
        LibraryPage parsed = LibraryParser.parse(nonEmptyPage(duplicate), HOST + "/mybook");
        LibraryPage.Item item = parsed.items.get(0);
        assertFalse(item.hasNextEpisode());
        assertEquals("", item.nextEpisodeKey);
    }

    // ------------------------------------------------------------------
    // Filters, group navigation
    // ------------------------------------------------------------------

    @Test public void parses_filter_tabs_sorts_and_group_navigation() throws Exception {
        String html = filterBar("collect", "vote")
                + nonEmptyPage("collect", "-2", "vote", row("1", "제목", false));
        LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook/collect/-2/vote/1");
        assertEquals(4, parsed.sorts.size());
        assertEquals("date", parsed.sorts.get(0).value);
        assertEquals("공개일자순", parsed.sorts.get(0).label);
        assertEquals("view", parsed.sorts.get(1).value);
        assertEquals("list", parsed.sorts.get(2).value);
        assertEquals("vote", parsed.sorts.get(3).value);
        assertEquals("추천순", parsed.sorts.get(3).label);
        assertEquals(4, parsed.groups.size());
        assertEquals("0", parsed.groups.get(0).value);
        assertEquals("전체", parsed.groups.get(0).label);
        assertEquals("-1", parsed.groups.get(1).value);
        assertEquals("미분류", parsed.groups.get(1).label);
        assertEquals("-2", parsed.groups.get(2).value);
        assertEquals("연중", parsed.groups.get(2).label);
        assertEquals("77", parsed.groups.get(3).value);
        assertEquals("내 그룹", parsed.groups.get(3).label);
    }

    @Test public void recent_shelf_never_advertises_synthetic_sort_options() throws Exception {
        String html = "<div class='mybook-data-list-items'>" + row("3", "최근", true)
                + "</div>" + pager("last_view", "0", "date");
        LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook/last_view");
        assertEquals(0, parsed.sorts.size());
        assertEquals(0, parsed.groups.size());
        assertEquals("last_view", parsed.shelf);
        assertEquals("date", parsed.sort);
    }

    @Test public void sort_and_group_navigation_is_strictly_scoped() throws Exception {
        String html = nonEmptyPage("like", "0", "date", row("1", "제목", false))
                + "<div class='mybook-filter-align-box'>"
                + "<div data-item='date'>공개일자순</div>"
                + "<div data-item='vote'>추천순</div>"
                + "<div data-item='nope'>나쁜 탭</div>"
                + "<div class='extra' data-item='list'>등록순</div>"
                + "<span data-item='view'>스팬 탭</span>"
                + "</div>"
                + "<div onclick=\"move_cate('/mybook/alarm/0/date/1')\">다른 선반</div>"
                + "<div onclick=\"move_cate('/mybook/like/0/view/1')\">다른 정렬</div>"
                + "<div onclick=\"move_cate('/mybook/like/99/date/444')\">같은 선반</div>"
                + "<span onclick=\"move_cate('/mybook/like/0/date/1');fetch('/x')\">스크립트</span>";
        LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook");
        // Only div[data-item] tabs count; the extra-class list tab still
        // counts (classes are not a trust marker), while 'nope' and the span
        // are ignored.
        assertEquals(3, parsed.sorts.size());
        assertEquals("date", parsed.sorts.get(0).value);
        assertEquals("공개일자순", parsed.sorts.get(0).label);
        assertEquals("vote", parsed.sorts.get(1).value);
        assertEquals("list", parsed.sorts.get(2).value);
        // Other shelf and other sort are ignored; the same sort page 444 is kept.
        assertEquals(1, parsed.groups.size());
        assertEquals("99", parsed.groups.get(0).value);
        assertEquals("같은 선반", parsed.groups.get(0).label);
    }

    // ------------------------------------------------------------------
    // Pagination
    // ------------------------------------------------------------------

    @Test public void parses_recent_numbered_page_and_only_adjacent_same_shelf_links() throws Exception {
        String html = "<div class='mybook-data-list-items'>" + row("789", "최근 작품", true)
                + "</div><ul class='pagination'>"
                + "<li><a href='/mybook/like/0/date/1'>선호작</a></li>"
                + "<li><a href='/mybook/last_view/0/date/1'>1</a></li>"
                + "<li class='active'><a href='/mybook/last_view/0/date/2'>2</a></li>"
                + "<li><a href='/mybook/last_view/0/date/3'>3</a></li>"
                + "<li><a href='/mybook/last_view/0/date/99'>끝</a></li>"
                + "<li><a href='https://evil.example/mybook/last_view/0/date/3'>3</a></li>"
                + "</ul>";
        LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook/last_view/0/date/2");
        assertEquals(2, parsed.page);
        assertEquals(HOST + "/mybook/last_view/0/date/1", parsed.previousUrl);
        assertEquals(HOST + "/mybook/last_view/0/date/3", parsed.nextUrl);
    }

    @Test public void pagination_preserves_shelf_group_sort_and_search() throws Exception {
        String query = "?search=" + encode("이세계");
        String html = "<div class='mybook-data-list-items'>" + row("12", "제목", false)
                + "</div><ul class='pagination'>"
                + "<li><a href='/mybook/collect/-2/vote/1" + query + "'>다른 그룹/정렬</a></li>"
                + "<li class='active'><a href='/mybook/alarm/77/vote/3" + query + "'>3</a></li>"
                + "<li><a href='/mybook/alarm/77/vote/4'>4</a></li>"
                + "<li><a href='/mybook/alarm/77/vote/2" + query + "'>2</a></li>"
                + "<li><a href='/mybook/alarm/77/date/4'>정렬 변경</a></li>"
                + "<li><a href='https://evil.example/mybook/alarm/77/vote/4'>외부</a></li>"
                + "</ul>";
        LibraryPage parsed = LibraryParser.parse(html,
                HOST + "/mybook/alarm/77/vote/3" + query);
        assertEquals("alarm", parsed.shelf);
        assertEquals("77", parsed.group);
        assertEquals("vote", parsed.sort);
        assertEquals("이세계", parsed.search);
        // The page-2 link keeps ?search= and page 4 dropped it; both are
        // recognized and the requested search is preserved/re-appended.
        assertEquals(HOST + "/mybook/alarm/77/vote/2" + query, parsed.previousUrl);
        assertEquals(HOST + "/mybook/alarm/77/vote/4" + query, parsed.nextUrl);
    }

    @Test public void pagination_rejects_other_shelf_group_sort_and_conflicting_search() throws Exception {
        String query = "?search=" + encode("작품");
        String row = row("12", "제목", false);
        String[][] crosses = {
                {"alarm", "-1", "date"}, {"alarm", "77", "view"},
        };
        for (String[] other : crosses) {
            String links = "<li class='active'><a href='/mybook/like/0/date/1" + query + "'>1</a></li>"
                    + "<li><a href='/mybook/" + other[0] + "/" + other[1] + "/"
                    + other[2] + "/2'>2</a></li>";
            LibraryPage skipped = LibraryParser.parse(
                    "<div class='mybook-data-list-items'>" + row
                            + "</div><ul class='pagination'>" + links + "</ul>",
                    HOST + "/mybook/like/0/date/1" + query);
            // Cross-selection links are never trusted as pagination.
            assertEquals("", skipped.previousUrl);
            assertEquals("", skipped.nextUrl);
        }
        // A different search on a same-selection adjacent pager link is
        // rejected, never followed.
        String links = "<li class='active'><a href='/mybook/like/0/date/1" + query + "'>1</a></li>"
                + "<li><a href='/mybook/like/0/date/2?search=%EB%8B%A4%EB%A5%B8'>2</a></li>";
        expectParseFailure("<div class='mybook-data-list-items'>" + row
                + "</div><ul class='pagination'>" + links + "</ul>",
                HOST + "/mybook/like/0/date/1" + query);
    }

    @Test public void parses_paged_search_with_active_link_carrying_search() throws Exception {
        String search = encode("키워드");
        String html = "<div class='mybook-data-list-items'>" + row("12", "제목", false)
                + "</div>" + pager("like", "0", "date", "키워드");
        LibraryPage parsed = LibraryParser.parse(html,
                HOST + "/mybook/like/0/date/1?search=" + search);
        assertEquals("키워드", parsed.search);
        assertEquals(1, parsed.page);
        assertEquals(HOST + "/mybook/like/0/date/2?search=" + search, parsed.nextUrl);
    }

    // ------------------------------------------------------------------
    // Strictness retained from the previous contract
    // ------------------------------------------------------------------

    @Test public void recognizes_only_exact_direct_child_empty_marker() throws Exception {
        String html = "<div class='mybook-data-list-items'>"
                + "<script>private data ignored</script>"
                + "<div>등록된 작품이 없습니다.</div>"
                + "<div class='recommend-botton-section'><a href='https://outside.invalid/' "
                + "onclick=\"location.href='/viewer/999'\"><img class='mobile_hidden'>"
                + "<img class='mobile_show'></a></div>"
                + "<div class='d-flex align-items-center justify-content-center'><nav>"
                + "<ul class='pagination pagination-basic pagination-primary mg-b-0'>"
                + emptyArrow("left-a", "https://outside.invalid/first")
                + emptyArrow("left-b", "/mybook/like/0/date/0")
                + emptyArrow("right-b", "javascript:alert(1)")
                + emptyArrow("right-a", "/mybook/like/0/date/999999")
                + "</ul></nav></div></div>";
        LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook/like/0/date/1");
        assertTrue(parsed.items.isEmpty());
        assertEquals(1, parsed.page);
        assertEquals("", parsed.previousUrl);
        assertEquals("", parsed.nextUrl);
        assertEquals("like", parsed.shelf);
        assertEquals("0", parsed.group);
        assertEquals("date", parsed.sort);
    }

    @Test public void empty_search_page_keeps_the_library_marker_and_sort_options() throws Exception {
        String html = "<div class='mybook-data-list-items'><div>등록된 작품이 없습니다.</div>"
                + "<div class='recommend-botton-section'><a><img class='mobile_hidden'>"
                + "<img class='mobile_show'></a></div>"
                + "<div class='d-flex align-items-center justify-content-center'><nav>"
                + "<ul class='pagination pagination-basic pagination-primary mg-b-0'>"
                + emptyArrow("left-a", "/x") + emptyArrow("left-b", "/x")
                + emptyArrow("right-b", "/x") + emptyArrow("right-a", "/x")
                + "</ul></nav></div></div>"
                + filterBar("like", "date");
        LibraryPage parsed = LibraryParser.parse(html,
                HOST + "/mybook/like/0/date/1?search=" + encode("없는 작품"));
        assertEquals(0, parsed.items.size());
        assertEquals("없는 작품", parsed.search);
        assertEquals(4, parsed.sorts.size());
        assertEquals(4, parsed.groups.size());
    }

    private static String emptyArrow(String icon, String href) {
        return "<li class='page-item'><a class='page-link' href='" + href
                + "' onclick=\"location.href='/viewer/999'\"><i class='icon ion-arrow-"
                + icon + "'></i></a></li>";
    }

    @Test public void malformed_or_ambiguous_markup_fails_closed() throws Exception {
        String[] invalidWithPager = {
                "<div class='mybook-data-list-items'></div>",
                "<div class='mybook-data-list-items'></div><div class='mybook-data-list-items'></div>",
                "<div class='mybook-data-list-items'><div class='novel-list-real-container'>"
                        + "<div class='novel-name'>제목</div></div></div>"
        };
        for (String html : invalidWithPager) {
            expectParseFailure(withValidPager(html), HOST + "/mybook");
        }

        String[] invalidEmptyShapes = {
                "<div class='mybook-data-list-items'><div>광고 래퍼"
                        + row("91", "중첩 작품", false) + "</div>"
                        + "<div>등록된 작품이 없습니다.</div>"
                        + "<div class='recommend-botton-section'><a><img class='mobile_hidden'>"
                        + "<img class='mobile_show'></a></div></div>",
                "<div class='mybook-data-list-items'><div>등록된 작품이 없습니다.</div>"
                        + "<div class='recommend-botton-section'><a><img class='mobile_hidden'>"
                        + "<img class='mobile_show'></a></div>"
                        + "<div class='d-flex align-items-center justify-content-center'><nav>"
                        + "<ul class='pagination pagination-basic pagination-primary mg-b-0'>"
                        + emptyArrow("left-a", "/x") + emptyArrow("left-b", "/x")
                        + emptyArrow("right-b", "/x") + emptyArrow("wrong", "/x")
                        + "</ul></nav></div></div>",
                "<div class='mybook-data-list-items'><div>다른 빈 메시지</div></div>"
        };
        for (String html : invalidEmptyShapes) expectParseFailure(html, HOST + "/mybook");

        // Row-level invalid shapes carry valid pagination; failure is row-level.
        expectParseFailure(nonEmptyPage(row("12", "", false)), HOST + "/mybook");
        expectParseFailure(nonEmptyPage(row("12", "제목", true)
                .replace("/viewer/12", "/viewer/13?x=1")), HOST + "/mybook");
    }

    @Test public void duplicate_active_and_mismatching_pager_paths_fail() throws Exception {
        String row = row("12", "제목", false);
        String noActive = "<li><a href='/mybook/like/0/date/1'>1</a></li>";
        String wrongActive = "<li class='active'><a href='/mybook/last_view/0/date/1'>1</a></li>";
        String duplicate = anchor(1, "like", "0", "date") + anchor(1, "like", "0", "date");
        for (String links : new String[] { noActive, wrongActive, duplicate }) {
            try {
                LibraryParser.parse("<div class='mybook-data-list-items'>" + row
                                + "</div><ul class='pagination'>" + links + "</ul>",
                        HOST + "/mybook");
                fail("invalid pager parsed");
            } catch (IOException expected) {
                assertEquals("library:invalid_page", expected.getMessage());
            }
        }
    }

    @Test public void duplicate_pagination_containers_fail() throws Exception {
        String html = "<div class='mybook-data-list-items'>" + row("12", "제목", false)
                + "</div><ul class='pagination'>" + anchor(1, "like", "0", "date") + "</ul>"
                + "<ul class='pagination'>" + anchor(1, "like", "0", "date") + "</ul>";
        try {
            LibraryParser.parse(html, HOST + "/mybook");
            fail("duplicate pagination containers parsed");
        } catch (IOException expected) {
            assertEquals("library:invalid_page", expected.getMessage());
        }
    }

    @Test public void accepts_only_exact_novel_and_viewer_onclick_patterns() throws Exception {
        for (String onclick : new String[] {
                "window.location.href='/novel/12'", "location.href='/novel/12?x=1'",
                "location.href='https://evil.example/novel/12'", "alert(1);location.href='/novel/12'"
        }) {
            String row = "<div class='novel-list-real-container'>"
                    + "<div class='novel-name' onclick=\"" + onclick + "\">제목</div></div>";
            expectParseFailure(nonEmptyPage(row), HOST + "/mybook");
        }
    }

    @Test public void row_and_title_bounds_fail_with_valid_pagination() throws Exception {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 101; i++) rows.append(row(String.valueOf(i + 1), "제목", false));
        expectParseFailure(nonEmptyPage(rows.toString()), HOST + "/mybook");

        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 501; i++) longTitle.append('가');
        expectParseFailure(nonEmptyPage(row("13", longTitle.toString(), false)), HOST + "/mybook");
    }

    @Test public void duplicate_names_and_continuations_fail_with_valid_pagination() throws Exception {
        String duplicateName = "<div class='novel-list-real-container'>"
                + "<div class='novel-name' onclick=\"location.href='/novel/12'\">하나</div>"
                + "<div class='novel-name' onclick=\"location.href='/novel/12'\">둘</div></div>";
        expectParseFailure(nonEmptyPage(duplicateName), HOST + "/mybook");

        String duplicateContinue = "<div class='novel-list-real-container'>"
                + "<div class='novel-name' onclick=\"location.href='/novel/12'\">제목</div>"
                + "<button class='novel-btn-continue' onclick=\"location.href='/viewer/12'\">계속</button>"
                + "<button class='novel-btn-continue' onclick=\"location.href='/viewer/13'\">계속</button>"
                + "</div>";
        expectParseFailure(nonEmptyPage(duplicateContinue), HOST + "/mybook");
    }

    @Test public void injected_continuation_fails_with_valid_pagination() throws Exception {
        String row = row("12", "제목", true)
                .replace("location.href='/viewer/12'",
                        "location.href='/viewer/12';fetch('/private')");
        expectParseFailure(nonEmptyPage(row), HOST + "/mybook");
    }

    @Test public void empty_and_overlong_html_fail_safely() throws Exception {
        expectParseFailure("", HOST + "/mybook");
        expectParseFailure(null, HOST + "/mybook");
        char[] oversized = new char[2 * 1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, ' ');
        expectParseFailure(new String(oversized), HOST + "/mybook");
    }

    @Test public void last_page_has_no_next_for_nonadjacent_or_external_links() throws Exception {
        String html = "<div class='mybook-data-list-items'>" + row("23", "마지막", false)
                + "</div><ul class='pagination'>"
                + "<li><a href='/mybook/like/0/date/1'>다른 선반</a></li>"
                + "<li><a href='/mybook/last_view/0/date/2'>이전</a></li>"
                + "<li class='active'><a href='/mybook/last_view/0/date/3'>3</a></li>"
                + "<li><a href='/mybook/last_view/0/date/7'>마지막</a></li>"
                + "<li><a href='/mybook/last_view/0/date/4?x=1'>쿼리</a></li>"
                + "<li><a href='https://evil.example/mybook/last_view/0/date/4'>외부</a></li>"
                + "</ul>";
        LibraryPage parsed = LibraryParser.parse(html, HOST + "/mybook/last_view/0/date/3");
        assertEquals(HOST + "/mybook/last_view/0/date/2", parsed.previousUrl);
        assertEquals("", parsed.nextUrl);
    }

    // ------------------------------------------------------------------
    // Compatibility
    // ------------------------------------------------------------------

    @Test public void legacy_constructor_derives_route_fields_from_url() throws Exception {
        LibraryPage legacy = new LibraryPage(
                java.util.Collections.<LibraryPage.Item>emptyList(), 2,
                HOST + "/mybook/alarm/77/vote/2", "", "");
        assertEquals("alarm", legacy.shelf);
        assertEquals("77", legacy.group);
        assertEquals("vote", legacy.sort);
        assertEquals("", legacy.search);
        assertTrue(legacy.sorts.isEmpty());
        assertTrue(legacy.groups.isEmpty());

        LibraryPage alias = new LibraryPage(
                java.util.Collections.<LibraryPage.Item>emptyList(), 1,
                HOST + "/mybook", "", "");
        assertEquals("like", alias.shelf);
        assertEquals("0", alias.group);
        assertEquals("date", alias.sort);
    }

    @Test public void item_progressLabel_reflects_available_metadata_only() throws Exception {
        LibraryPage.Item both = new LibraryPage.Item(
                null, "", 794, 800, "작가");
        assertEquals("마지막 읽은 EP.794 · 등록 800편", both.progressLabel());
        assertFalse(both.hasNextEpisode());
        LibraryPage.Item onlyCount = new LibraryPage.Item(
                null, "", -1, 800, "");
        assertEquals("등록 800편", onlyCount.progressLabel());
        LibraryPage.Item withNext = new LibraryPage.Item(
                null, "", 794, 800, "작가", "123456789012");
        assertTrue(withNext.hasNextEpisode());
        assertEquals("123456789012", withNext.nextEpisodeKey);
        assertEquals("다음 회차 있음 · 마지막 읽은 EP.794 · 등록 800편", withNext.progressLabel());
        LibraryPage.Item none = new LibraryPage.Item(null, "", -1, -1, "");
        assertFalse(none.hasNextEpisode());
        assertEquals("", none.progressLabel());
    }

}
