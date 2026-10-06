package me.crema.novelia.account;

import me.crema.novelia.site.SiteClient;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parser for the observed server-rendered Novelpia library pages.
 *
 * <p>Route: {@code /mybook/{like|last_view|alarm|collect}/{group}/{sort}/{page}}
 * with an optional single {@code ?search=} parameter. {@code /mybook} and
 * {@code /mybook/last_view} stay as first-page aliases (query-free). Groups are
 * 0 (all), -1 (미분류), -2 (연중), or a positive user-created group id bounded
 * to 12 digits. Sorts are date 공개일자순, view 조회순, list 등록순,
 * vote 추천순. Pages are 1-based up to {@link #MAX_PAGE}.
 *
 * <p>Safety: URLs are matched by strict regular expressions, only the
 * {@code search} parameter is accepted (percent-encoded, decoded, re-encoded
 * canonically; at most 100 encoded characters), fragments and foreign hosts
 * are rejected, JavaScript in rows is never executed, and only adjacent
 * same-shelf/group/sort pager links are followed.
 */
public final class LibraryParser {
    private static final String HOST = "https://novelpia.com";
    private static final Pattern PAGE_PATH = Pattern.compile(
            "^/mybook/(like|last_view|alarm|collect)/([-]?[0-9]{1,12})/(date|view|list|vote)/([1-9][0-9]{0,5})$");
    private static final Pattern NOVEL_CLICK = Pattern.compile(
            "^location\\.href\\s*=\\s*(['\"])\\/novel\\/([0-9]{1,12})\\1\\s*;?$");
    private static final Pattern CONTINUE_CLICK = Pattern.compile(
            "^location\\.href\\s*=\\s*(['\"])\\/viewer\\/([0-9]{1,12})\\1\\s*;?$");
    private static final Pattern NEXT_CLICK = Pattern.compile(
            "^get_next_episode\\s*\\(\\s*([1-9][0-9]{0,11})\\s*,\\s*([0-9]{1,12})\\s*\\)\\s*;?$");
    private static final Pattern EPISODE_TEXT = Pattern.compile(
            "^EP\\.([0-9]{1,9})(?:\\s+이어보기)?$");
    private static final Pattern GROUP_CLICK = Pattern.compile(
            "^move_cate\\s*\\(\\s*(['\"])([^'\"]{1,300})\\1\\s*\\)\\s*;?$");
    private static final int MAX_PAGE = 100000;
    private static final int MAX_ROWS = 100;
    private static final int MAX_TITLE = 500;
    private static final int MAX_LABEL = 120;
    private static final int MAX_SEARCH = 100;

    private LibraryParser() {}

    /**
     * Accept only canonical Novelpia shelf URLs. The unnumbered shelf URLs are
     * aliases for page one; numbered URLs use the observed full route with an
     * optional single {@code ?search=} parameter. Groups 0/-1/-2 and positive
     * user group ids (up to 12 digits) are allowed; leading-zero spellings are
     * rejected so each selection has exactly one canonical form.
     */
    public static String normalizeUrl(String value) throws IOException {
        if (value == null || value.length() == 0 || value.length() > 1024
                || value.indexOf('#') >= 0 || value.indexOf('\\') >= 0) {
            throw invalid();
        }
        String path = value;
        String search = "";
        int query = value.indexOf('?');
        if (query >= 0) {
            path = value.substring(0, query);
            search = querySearch(value.substring(query + 1));
        }
        if (path.startsWith("/")) {
            // Root-relative path, keep.
        } else if (path.startsWith(HOST + "/")) {
            path = path.substring(HOST.length());
        } else {
            throw invalid();
        }
        if (!"/mybook".equals(path) && !path.startsWith("/mybook/")) throw invalid();
        if ("/mybook".equals(path) || "/mybook/like".equals(path)
                || "/mybook/last_view".equals(path) || "/mybook/alarm".equals(path)
                || "/mybook/collect".equals(path)) {
            if (search.length() != 0) throw invalid();
        } else {
            Matcher matcher = PAGE_PATH.matcher(path);
            if (!matcher.matches()) throw invalid();
            if (!isGroup(matcher.group(2))) throw invalid();
            int page = parseIntGuarded(matcher.group(4));
            if (page < 1 || page > MAX_PAGE) throw invalid();
        }
        StringBuilder canonical = new StringBuilder(HOST + path);
        if (search.length() != 0) {
            canonical.append("?search=").append(encodeComponent(search));
        }
        return canonical.toString();
    }

    /**
     * Build a canonical shelf URL for the given route values. Unknown shelves,
     * groups, sorts or pages are rejected. {@code search} may be null or empty;
     * when provided it is strictly bounded and percent-encoded (canonical form
     * never exceeds 100 characters) so it cannot inject a host, fragment, or
     * extra query parameter.
     */
    public static String buildUrl(String shelf, String group, String sort,
                                  int page, String search) throws IOException {
        if (!isShelf(shelf) || !isGroup(group) || !isSort(sort)
                || page < 1 || page > MAX_PAGE) throw invalid();
        if (search == null) search = "";
        String encoded = "";
        if (search.length() != 0) {
            if (!allPrintable(search)) throw invalid();
            encoded = encodeComponent(search);
        }
        StringBuilder url = new StringBuilder(HOST + "/mybook/" + shelf + "/" + group
                + "/" + sort + "/" + page);
        if (encoded.length() != 0) url.append("?search=").append(encoded);
        return url.toString();
    }

    /** Parse already-authenticated HTML; authentication is verified by caller. */
    public static LibraryPage parse(String html, String url) throws IOException {
        String canonical = normalizeUrl(url);
        if (html == null || html.length() == 0 || html.length() > 2 * 1024 * 1024) {
            throw invalid();
        }
        Route route = routeOf(canonical);
        int requestedPage = route.page;
        Document doc = Jsoup.parse(html);
        List<Element> containers = doc.select(".mybook-data-list-items");
        if (containers.size() != 1) throw invalid();
        Element container = containers.get(0);

        // Selection controls may wrap each novel in one mybook-selection-row.
        // Keep the original direct rows supported, without accepting arbitrary
        // descendant rows (e.g. recommendations or nested/ambiguous markup).
        List<Element> rows = new ArrayList<Element>();
        for (Element child : container.children()) {
            if (child.hasClass("novel-list-real-container")) {
                rows.add(child);
            } else if (child.hasClass("mybook-selection-row")) {
                List<Element> wrapped = child.select("> .novel-list-real-container");
                if (wrapped.size() != 1) throw invalid();
                rows.add(wrapped.get(0));
            }
        }
        if (container.select(".novel-list-real-container").size() != rows.size()) throw invalid();
        if (rows.size() > MAX_ROWS) throw invalid();
        List<LibraryPage.Item> items = new ArrayList<LibraryPage.Item>();
        for (Element row : rows) items.add(parseRow(row));

        Element emptyMarker = null;
        for (Element child : container.children()) {
            if ("등록된 작품이 없습니다.".equals(child.text())) {
                if (emptyMarker != null || !"div".equals(child.normalName())
                        || child.classNames().size() != 0) throw invalid();
                emptyMarker = child;
            }
        }
        List<LibraryPage.Option> sorts = parseSorts(doc);
        List<LibraryPage.Option> groups = parseGroups(doc, route);
        if (rows.isEmpty()) {
            if (emptyMarker == null) throw invalid();
            boolean hasRecommendation = false;
            Element emptyPager = null;
            for (Element child : container.children()) {
                if (child == emptyMarker) continue;
                if ("script".equals(child.normalName())) continue;
                if (isEmptyRecommendation(child) && !hasRecommendation) {
                    hasRecommendation = true;
                    continue;
                }
                Element candidatePager = emptyArrowPager(child);
                if (candidatePager != null && emptyPager == null) {
                    emptyPager = candidatePager;
                    continue;
                }
                throw invalid();
            }
            if (!hasRecommendation || emptyPager == null) throw invalid();
            List<Element> paginations = doc.select(".pagination");
            if (paginations.size() != 1 || paginations.get(0) != emptyPager) throw invalid();
            return new LibraryPage(items, requestedPage, canonical, "", "",
                    route.shelf, route.group, route.sort, route.search, sorts, groups);
        }
        if (emptyMarker != null) throw invalid();

        List<Element> paginations = doc.select(".pagination");
        if (paginations.size() != 1) throw invalid();
        Element pagination = paginations.get(0);
        List<Element> active = pagination.select("li.active");
        if (active.size() != 1) throw invalid();
        List<Element> activeAnchors = active.get(0).select("a[href]");
        if (activeAnchors.size() != 1) throw invalid();
        String activeUrl = safeCanonicalUrl(activeAnchors.get(0).attr("href"));
        Route activeRoute = activeUrl.length() == 0 ? null : routeOrNull(activeUrl);
        if (activeRoute == null || activeRoute.page != requestedPage
                || !samePagerSelection(activeRoute, route)) throw invalid();

        boolean hasPrevious = false;
        boolean hasNext = false;
        for (Element anchor : pagination.select("a[href]")) {
            String href = safeCanonicalUrl(anchor.attr("href"));
            Route linked = href.length() == 0 ? null : routeOrNull(href);
            if (linked == null) continue;
            if (linked.shelf.equals(route.shelf) && linked.group.equals(route.group)
                    && linked.sort.equals(route.sort)
                    && linked.search.length() != 0 && route.search.length() != 0
                    && !linked.search.equals(route.search)) throw invalid();
            if (!samePagerSelection(linked, route)) continue;
            int linkedPage = linked.page;
            if (linkedPage == requestedPage - 1) hasPrevious = true;
            if (linkedPage == requestedPage + 1) hasNext = true;
        }
        // Reconstructed URLs always preserve the requested route values; if the
        // site dropped ?search= from its pager links it is re-appended here.
        String previous = hasPrevious ? buildUrl(route.shelf, route.group, route.sort,
                requestedPage - 1, route.search) : "";
        String next = hasNext ? buildUrl(route.shelf, route.group, route.sort,
                requestedPage + 1, route.search) : "";
        return new LibraryPage(items, requestedPage, canonical, previous, next,
                route.shelf, route.group, route.sort, route.search, sorts, groups);
    }

    /**
     * Page number of a library URL (1 for the unnumbered aliases). Path-relative
     * and absolute canonical forms are accepted; anything else throws.
     */
    public static int pageOf(String url) throws IOException {
        return routeOf(normalizeUrl(url)).page;
    }

    /** The unnumbered tab routes are all first-page aliases (no search). */
    private static String aliasShelf(String path) {
        if ("/mybook/last_view".equals(path)) return "last_view";
        if ("/mybook/alarm".equals(path)) return "alarm";
        if ("/mybook/collect".equals(path)) return "collect";
        return "like";
    }

    /**
     * Route selections derived from a canonical library URL, or null when the
     * URL is not a recognized library route. Used by the legacy
     * {@link LibraryPage} constructor so old callers keep route fields.
     */
    static String[] inferSelection(String url) {
        if (url == null) return null;
        try {
            Route route = routeOf(normalizeUrl(url));
            return new String[] {route.shelf, route.group, route.sort, route.search};
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Route helpers
    // ------------------------------------------------------------------

    private static boolean isShelf(String shelf) {
        return "like".equals(shelf) || "last_view".equals(shelf)
                || "alarm".equals(shelf) || "collect".equals(shelf);
    }

    private static boolean isSort(String sort) {
        return "date".equals(sort) || "view".equals(sort)
                || "list".equals(sort) || "vote".equals(sort);
    }

    /** Groups: 0 (all), -1 (미분류), -2 (연중), or positive ids up to 12 digits. */
    private static boolean isGroup(String group) {
        if (group == null || group.length() == 0 || group.length() > 12) return false;
        if ("0".equals(group) || "-1".equals(group) || "-2".equals(group)) return true;
        if (group.charAt(0) == '0') return false;
        for (int i = 0; i < group.length(); i++) {
            char c = group.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * Pager links belong to the same selection when shelf/group/sort match; the
     * site may drop {@code ?search=} from pager hrefs, so a missing search is
     * tolerated while a different search is still rejected.
     */
    private static boolean samePagerSelection(Route a, Route b) {
        if (!a.shelf.equals(b.shelf) || !a.group.equals(b.group) || !a.sort.equals(b.sort)) {
            return false;
        }
        // The site may drop ?search= from pager hrefs (then the requested
        // search is re-appended to the returned URLs), but a link that names a
        // different search than the requested one is never accepted.
        if (a.search.equals(b.search)) return true;
        return a.search.length() == 0;
    }

    /** Group nav options must sit in the same shelf and sort as the page. */
    private static boolean groupNavMatches(Route candidate, Route route) {
        if (!candidate.shelf.equals(route.shelf) || !candidate.sort.equals(route.sort)) {
            return false;
        }
        if (candidate.search.equals(route.search)) return true;
        return candidate.search.length() == 0 && route.search.length() != 0;
    }

    private static int parseIntGuarded(String value) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    /**
     * Decode and validate the query string of a library URL; only one
     * {@code search} parameter is accepted and its canonical percent-encoded
     * form is at most {@link #MAX_SEARCH} characters.
     */
    private static String querySearch(String query) throws IOException {
        Map<String, String> params = splitQuery(query);
        if (params.size() != 1 || !params.containsKey("search")) throw invalid();
        String raw = params.get("search");
        if (raw == null || raw.length() == 0) throw invalid();
        String decoded = decodeComponent(raw);
        if (!allPrintable(decoded)) throw invalid();
        return decoded;
    }

    private static Map<String, String> splitQuery(String query) throws IOException {
        Map<String, String> params = new LinkedHashMap<String, String>();
        String[] pieces = query.split("&");
        for (String piece : pieces) {
            if (piece.length() == 0) throw invalid();
            int eq = piece.indexOf('=');
            if (eq <= 0) throw invalid();
            String name;
            try {
                name = URLDecoder.decode(piece.substring(0, eq), "UTF-8");
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
            if (!"search".equals(name)) throw invalid();
            if (params.containsKey(name)) throw invalid();
            params.put(name, piece.substring(eq + 1));
        }
        return params;
    }

    private static String decodeComponent(String value) throws IOException {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static String encodeComponent(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Bounded printable search text; control characters and lone surrogates out. */
    private static boolean allPrintable(String value) {
        if (value == null || value.length() == 0 || value.length() > MAX_SEARCH) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)
                    || (c >= 0xD800 && c <= 0xDFFF)
                    || c == 0xFFFD || c == 0xFFFE || c == 0xFFFF) return false;
        }
        return true;
    }

    /** Canonical route of an already-normalized URL, or null. */
    private static Route routeOrNull(String normalized) {
        try {
            return routeOf(normalized);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Validated full route of a canonical URL (aliases expand here). */
    private static Route routeOf(String normalized) throws IOException {
        String withoutQuery = normalized;
        String search = "";
        int query = normalized.indexOf('?');
        if (query >= 0) {
            withoutQuery = normalized.substring(0, query);
            search = querySearch(normalized.substring(query + 1));
        }
        String path = withoutQuery.startsWith(HOST) ? withoutQuery.substring(HOST.length()) : withoutQuery;
        if ("/mybook".equals(path) || "/mybook/like".equals(path)
                || "/mybook/last_view".equals(path) || "/mybook/alarm".equals(path)
                || "/mybook/collect".equals(path)) {
            return new Route(aliasShelf(path), "0", "date", 1, search);
        }
        Matcher matcher = PAGE_PATH.matcher(path);
        if (!matcher.matches()) throw invalid();
        if (!isGroup(matcher.group(2))) throw invalid();
        return new Route(matcher.group(1), matcher.group(2), matcher.group(3),
                parseIntGuarded(matcher.group(4)), search);
    }

    // ------------------------------------------------------------------
    // HTML parsing
    // ------------------------------------------------------------------

    /**
     * Filter tabs rendered by the site next to the shelf list:
     * {@code div.mybook-filter-align-box > div[data-item]} where data-item is
     * one of date/view/list/vote and the tab text is a static label.
     */
    private static List<LibraryPage.Option> parseSorts(Document doc) {
        List<LibraryPage.Option> options = new ArrayList<LibraryPage.Option>();
        Element box = doc.selectFirst("div.mybook-filter-align-box");
        if (box == null) return options;
        for (Element child : box.children()) {
            if (!"div".equals(child.normalName())) continue;
            String dataItem = child.attr("data-item");
            if (!isSort(dataItem)) continue;
            String label = okText(child.text());
            if (label.length() == 0 || label.length() > MAX_LABEL) continue;
            options.add(new LibraryPage.Option(dataItem, label));
        }
        return dedupe(options);
    }

    /**
     * Group navigation observed next to the shelf list:
     * {@code onclick="move_cate('/mybook/{shelf}/{group}/{sort}/{page}')"} with a
     * bounded label. Only labels are parsed; handler URLs are validated against
     * the current shelf/sort and never executed.
     */
    private static List<LibraryPage.Option> parseGroups(Document doc, Route route) {
        List<LibraryPage.Option> options = new ArrayList<LibraryPage.Option>();
        for (Element element : doc.select("[onclick]")) {
            String onclick = element.attr("onclick");
            if (onclick == null || onclick.trim().length() == 0) continue;
            Matcher matcher = GROUP_CLICK.matcher(onclick.trim());
            if (!matcher.matches()) continue;
            String label = okText(element.text());
            if (label.length() == 0 || label.length() > MAX_LABEL) continue;
            String target;
            try {
                target = normalizeUrl(matcher.group(2));
            } catch (IOException ignored) {
                continue;
            }
            Route candidate = routeOrNull(target);
            if (candidate == null || !groupNavMatches(candidate, route)) continue;
            options.add(new LibraryPage.Option(candidate.group, label));
        }
        return dedupe(options);
    }

    private static List<LibraryPage.Option> dedupe(List<LibraryPage.Option> options) {
        List<LibraryPage.Option> unique = new ArrayList<LibraryPage.Option>();
        Set<String> seen = new LinkedHashSet<String>();
        for (LibraryPage.Option option : options) {
            if (seen.add(option.value)) unique.add(option);
        }
        return Collections.unmodifiableList(unique);
    }

    private static boolean isEmptyRecommendation(Element element) {
        if (!"div".equals(element.normalName()) || element.text().length() != 0
                || element.classNames().size() != 1
                || !element.classNames().contains("recommend-botton-section")
                || element.children().size() != 1) return false;
        Element link = element.child(0);
        if (!"a".equals(link.normalName()) || link.children().size() != 2) return false;
        Element first = link.child(0);
        Element second = link.child(1);
        return isImageWithClass(first, "mobile_hidden")
                && isImageWithClass(second, "mobile_show");
    }

    /** Return the observed arrow-only empty-list pager UL, without using its hrefs. */
    private static Element emptyArrowPager(Element element) {
        if (!"div".equals(element.normalName()) || element.text().length() != 0
                || element.classNames().size() != 3
                || !element.classNames().contains("d-flex")
                || !element.classNames().contains("align-items-center")
                || !element.classNames().contains("justify-content-center")
                || element.children().size() != 1) return null;
        Element nav = element.child(0);
        if (!"nav".equals(nav.normalName()) || nav.children().size() != 1) return null;
        Element list = nav.child(0);
        if (!"ul".equals(list.normalName()) || list.classNames().size() != 4
                || !list.classNames().contains("pagination")
                || !list.classNames().contains("pagination-basic")
                || !list.classNames().contains("pagination-primary")
                || !list.classNames().contains("mg-b-0")
                || list.children().size() != 4) return null;
        String[] arrows = {"ion-arrow-left-a", "ion-arrow-left-b",
                "ion-arrow-right-b", "ion-arrow-right-a"};
        for (int i = 0; i < arrows.length; i++) {
            Element item = list.child(i);
            if (!"li".equals(item.normalName()) || item.classNames().size() != 1
                    || !item.classNames().contains("page-item") || item.children().size() != 1) {
                return null;
            }
            Element link = item.child(0);
            if (!"a".equals(link.normalName()) || link.classNames().size() != 1
                    || !link.classNames().contains("page-link") || link.children().size() != 1) {
                return null;
            }
            Element icon = link.child(0);
            if (!"i".equals(icon.normalName()) || icon.classNames().size() != 2
                    || !icon.classNames().contains("icon")
                    || !icon.classNames().contains(arrows[i])) return null;
        }
        return list;
    }

    private static boolean isImageWithClass(Element element, String className) {
        return "img".equals(element.normalName()) && element.classNames().size() == 1
                && element.classNames().contains(className);
    }

    private static LibraryPage.Item parseRow(Element row) throws IOException {
        List<Element> names = row.select(".novel-name");
        if (names.size() != 1) throw invalid();
        Element name = names.get(0);
        String title = okText(name.text());
        if (title.length() == 0 || title.length() > MAX_TITLE) throw invalid();
        String novelNo = clickNumber(name.attr("onclick"), NOVEL_CLICK);
        if (novelNo.length() == 0) throw invalid();
        SiteClient.Entry entry = new SiteClient.Entry(title, HOST + "/novel/" + novelNo,
                "novel", "");

        String continueUrl = "";
        int lastReadEpisode = -1;
        List<Element> continuations = row.select(".novel-btn-continue");
        if (continuations.size() > 1) throw invalid();
        if (continuations.size() == 1) {
            Element button = continuations.get(0);
            String continueNo = clickNumber(button.attr("onclick"), CONTINUE_CLICK);
            if (continueNo.length() == 0) throw invalid();
            continueUrl = HOST + "/viewer/" + continueNo;
            Matcher episode = EPISODE_TEXT.matcher(okText(button.text()));
            if (episode.matches()) {
                int parsed = parseIntQuiet(episode.group(1));
                if (parsed >= 0) lastReadEpisode = parsed;
            }
        }

        // Row metadata: the first cell with title "회차" supplies the total;
        // ambiguous duplicates keep the unknown count (-1) instead of guessing.
        int totalEpisodes = -1;
        boolean sawChapterCell = false;
        for (Element cell : row.select(".novel-numerical > div")) {
            List<Element> titles = cell.select(".novel-numerical-title");
            if (titles.size() != 1) continue;
            String label = okText(titles.get(0).text());
            if (!"회차".equals(label)) continue;
            if (sawChapterCell) {
                totalEpisodes = -1;
                break;
            }
            sawChapterCell = true;
            String content = okText(cell.text());
            String withoutLabel = content.replaceFirst(
                    "^\\s*" + Pattern.quote(label) + "\\s*", "");
            String digits = tailNumber(withoutLabel);
            if (digits.length() > 0 && digits.length() <= 9) {
                int parsed = parseIntQuiet(digits);
                if (parsed >= 0) totalEpisodes = parsed;
            }
        }

        // Author: only a single unambiguous .writer-name is trusted.
        String author = "";
        List<Element> writers = row.select(".writer-name");
        if (writers.size() == 1) {
            String text = okText(writers.get(0).text());
            if (text.length() != 0 && text.length() <= MAX_LABEL) author = text;
        }

        String nextKey = "";
        List<Element> nextButtons = row.select(".novel-btn-next");
        if (nextButtons.size() == 1) {
            Element button = nextButtons.get(0);
            String style = button.attr("style").replaceAll("\\s+", "").toLowerCase(java.util.Locale.US);
            Matcher handler = NEXT_CLICK.matcher(button.attr("onclick").trim());
            if (!button.hasAttr("disabled") && !button.hasAttr("hidden")
                    && !"true".equals(button.attr("aria-disabled"))
                    && !style.contains("display:none") && !style.contains("visibility:hidden")
                    && handler.matches() && novelNo.equals(handler.group(1))) {
                nextKey = handler.group(2);
            }
        }
        return new LibraryPage.Item(entry, continueUrl, lastReadEpisode,
                totalEpisodes, author, nextKey);
    }

    /** The trailing numeric run of a metadata cell's text (commas and 회 tolerated). */
    private static String tailNumber(String text) {
        if (text == null || text.length() == 0) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.insert(0, c);
            } else if (c == ',' || c == ' ' || c == '회') {
                continue;
            } else if (digits.length() != 0) {
                break;
            }
        }
        return digits.toString();
    }

    private static int parseIntQuiet(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 && parsed <= Integer.MAX_VALUE ? (int) parsed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String okText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String clickNumber(String onclick, Pattern pattern) {
        Matcher matcher = pattern.matcher(onclick == null ? "" : onclick.trim());
        return matcher.matches() ? matcher.group(2) : "";
    }

    private static String safeCanonicalUrl(String href) {
        try {
            return normalizeUrl(href);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static IOException invalid() {
        return new IOException("library:invalid_page");
    }

    /** Validated route selection of one canonical library page. */
    private static final class Route {
        final String shelf;
        final String group;
        final String sort;
        final int page;
        final String search;

        Route(String shelf, String group, String sort, int page, String search) {
            this.shelf = shelf;
            this.group = group;
            this.sort = sort;
            this.page = page;
            this.search = search;
        }
    }
}
