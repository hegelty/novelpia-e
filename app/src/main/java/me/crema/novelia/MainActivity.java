package me.crema.novelia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.*;
import me.crema.novelia.net.NativeHttp;
import me.crema.novelia.account.AccountClient;
import me.crema.novelia.account.LibraryPage;
import me.crema.novelia.account.LibraryParser;
import me.crema.novelia.account.CredentialStore;
import me.crema.novelia.ui.InkUi;
import me.crema.novelia.ui.PagedListView;
import me.crema.novelia.display.DisplayProfile;
import me.crema.novelia.display.DisplayTuningLayout;
import me.crema.novelia.display.DisplaySettingsDialog;
import me.crema.novelia.input.KeyBindings;
import me.crema.novelia.input.KeyMappingDialog;
import me.crema.novelia.reader.ReaderView;
import me.crema.novelia.site.SiteClient;
import me.crema.novelia.site.SiteClient.Entry;
import me.crema.novelia.site.SiteClient.Chapter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** No WebView, Javascript bridge, service, analytics, or background downloading. */
public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://novelpia.com/";
    private static final String HOME = "https://book.novelpia.com/webnovel/serial";
    private static final String FAVORITES = "local:favorites";
    private static final int OPEN_TEXT = 19;
    private static final int OPEN_SESSION = 20;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private CredentialStore credentials;
    private boolean signingIn, loginOpening;
    private AlertDialog loginDialog;
    private volatile SiteClient site;
    private volatile NativeHttp http;
    private DisplayTuningLayout root;
    private LinearLayout content, readerBar, navigation, readerTools;
    private TextView status, heading;
    private TextView readerProgress, readerSize;
    private Button readerNext;
    private PagedListView pagedList;
    private KeyBindings keyBindings;
    private final java.util.HashSet<Integer> consumedKeys = new java.util.HashSet<Integer>();
    private boolean loading;
    private Runnable refreshAction;
    private ReaderView reader;
    private FrameLayout readerFrame;
    private Chapter chapter;
    private String location = HOME;
    private String currentTitle = "노벨피아 e-ink";
    private int generation, turnCount;
    private boolean destroyed, reading;
    private int fontSize;
    private final java.util.LinkedHashMap<String, Integer> listScreens = new java.util.LinkedHashMap<String, Integer>();
    private final ArrayList<String> history = new ArrayList<String>();
    private String listNovelId, readerReturnUrl, readerReturnNovelId;
    private int listEpisodePage, readerReturnPage, readerReturnScreen;
    private boolean returningToList;

    /** Memory-only across rotation. Never parcel chapter text or session secrets. */
    private static final class Retained {
        SiteClient site;
        NativeHttp http;
        boolean loginPending;
        Chapter chapter;
        String location;
        float progress;
        ArrayList<String> history;
        java.util.LinkedHashMap<String, Integer> listScreens;
        String readerReturnUrl, readerReturnNovelId, listNovelId;
        int readerReturnPage, readerReturnScreen, listEpisodePage, listScreen;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Avoid screenshots of sign-in fields and personal library/content in recents.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        credentials = new CredentialStore(this);
        fontSize = prefs.getInt("font", 22);
        keyBindings = KeyBindings.load(prefs);
        root = new DisplayTuningLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setProfile(DisplayProfile.load(prefs));
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        AppFont.install(root);
        navigation = horizontal();
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(16), dp(6), dp(16), dp(6));
        TextView brand = label("노벨피아 e-ink", 20);
        brand.setContentDescription("노벨피아 e-ink 시작 화면");
        brand.setPadding(dp(8), dp(8), dp(8), dp(8));
        brand.setOnClickListener(v -> { ++generation; loading = false; welcome(); });
        brand.setFocusable(true);
        navigation.addView(brand, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button searchButton = button("검색", v -> search());
        InkUi.quiet(searchButton);
        navigation.addView(searchButton, new LinearLayout.LayoutParams(dp(72), dp(48)));
        Button shelfButton = button("내서재", v -> open(ORIGIN + "mybook", true));
        InkUi.quiet(shelfButton);
        navigation.addView(shelfButton, new LinearLayout.LayoutParams(dp(84), dp(48)));
        navigation.addView(menuButton(v -> menu()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(navigation);
        heading = label("", 24);
        heading.setPadding(dp(24), dp(12), dp(24), dp(14));
        heading.setMaxLines(2);
        root.addView(heading);
        content = vertical();
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        readerBar = horizontal();
        readerBar.setVisibility(View.GONE);
        root.addView(readerBar);
        status = label("", 12);
        status.setPadding(dp(24), dp(8), dp(24), dp(8));
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // Reserve this space on list/home screens so request messages do not
        // change pagination or move the controls. Reading has its own footer.
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(40)));
        Retained retained = (Retained) getLastNonConfigurationInstance();
        if (retained != null) {
            site = retained.site;
            http = retained.http;
            location = retained.location;
            history.addAll(retained.history);
            if (retained.listScreens != null) listScreens.putAll(retained.listScreens);
            readerReturnUrl = retained.readerReturnUrl;
            readerReturnNovelId = retained.readerReturnNovelId;
            readerReturnPage = retained.readerReturnPage;
            readerReturnScreen = retained.readerReturnScreen;
            if (retained.loginPending) {
                welcome();
                login();
            } else if (retained.chapter != null) {
                showChapter(retained.chapter);
                reader.restoreProgress(retained.progress);
            } else if (retained.listNovelId != null)
                showEpisodes(retained.listNovelId, retained.listEpisodePage, retained.listScreen);
            else open(location, false);
        } else {
            welcome();
            if (credentials.isAutoLoginEnabled()) autoLogin();
            else login();
        }
    }

    private void welcome() {
        resetContent("");
        location = HOME;
        readerReturnUrl = null;
        heading.setVisibility(View.GONE);
        LinearLayout body = vertical();
        body.setPadding(dp(24), dp(20), dp(24), dp(16));
        Button resume = button(prefs.contains("lastUrl") ? "이어서 읽기" : "최근 본 작품",
                v -> open(prefs.contains("lastUrl") ? prefs.getString("lastUrl", HOME)
                        : ORIGIN + "mybook/last_view", true));
        InkUi.primary(resume);
        body.addView(resume, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout shelf = horizontal();
        shelf.setPadding(0, dp(10), 0, 0);
        shelf.addView(button("선호작", v -> open(ORIGIN + "mybook", true)), weighted());
        shelf.addView(button("최근 본 작품", v -> open(ORIGIN + "mybook/last_view", true)), weighted());
        body.addView(shelf);
        LinearLayout quick = horizontal();
        quick.setPadding(0, dp(10), 0, dp(10));
        quick.addView(button("작품 찾아보기", v -> home()), weighted());
        quick.addView(button("내 파일 읽기", v -> importText()), weighted());
        body.addView(quick);
        body.addView(button("책갈피", v -> bookmarks()), new LinearLayout.LayoutParams(-1, dp(52)));
        content.addView(body, new LinearLayout.LayoutParams(-1, -1));
        status.setText("");
    }

    private void home() { open(HOME, true); }

    private void resetContent(String title) {
        saveProgress();
        reading = false;
        reader = null;
        pagedList = null;
        listNovelId = null;
        listEpisodePage = 0;
        refreshAction = null;
        readerTools = null;
        readerProgress = null;
        readerNext = null;
        readerFrame = null;
        navigation.setVisibility(View.VISIBLE);
        heading.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        readerBar.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        content.removeAllViews();
        currentTitle = title;
        heading.setText(title);
    }

    private SiteClient client() throws Exception {
        if (site == null) {
            http = new NativeHttp(getApplicationContext());
            site = new SiteClient(http);
        }
        return site;
    }

    private AccountClient accountClient() throws Exception {
        client();
        return new AccountClient(http);
    }

    private void accountMenu() {
        choices("내서재", new String[]{"최근 본 작품", "전체 선호작", "알림 작품", "소장 작품", "최애 작품", "로그인 관리", "계정 연동 검사"},
                new Runnable[]{() -> open(ORIGIN + "mybook/last_view", true),
                        () -> open(ORIGIN + "mybook", true), () -> open(ORIGIN + "mybook/alarm", true),
                        () -> open(ORIGIN + "mybook/collect", true), () -> open(FAVORITES, true),
                        this::accountSettings, this::checkAccountLink});
    }

    private void accountSettings() {
        choices("로그인 관리", new String[]{"이메일 로그인", "저장된 로그인 설정", "PC 로그인 가져오기 · 구글 계정", "이 기기에서 로그아웃"},
                new Runnable[]{this::login, this::savedLoginSettings, this::importSession, () ->
                        new FontDialogBuilder(this).setTitle("로그아웃할까요?")
                                .setMessage("현재 로그인과 저장된 이메일·비밀번호를 지우고 자동 로그인을 끕니다.")
                                .setNegativeButton("취소", null).setPositiveButton("로그아웃", (d,w) ->
                                    request("로그아웃 중…", () -> { client(); http.clearCookies(); credentials.clear(); return true; },
                                            ok -> { chapter = null; welcome(); toast("로그아웃했습니다."); })).show()});
    }

    private void checkAccountLink() {
        request("로그인 상태와 내서재 연동 확인 중…", () -> {
            AccountClient account = accountClient();
            AccountClient.SessionStatus session = account.sessionStatus();
            if (!session.isAuthenticated()) return session.message;
            String favorite;
            try { favorite = account.favorites().size() + "개 수신 (최대 5개)"; }
            catch (java.io.IOException failure) {
                favorite = AccountClient.describeFavoritesFailure(failure);
            }
            String recent = librarySummary(account, ORIGIN + "mybook/last_view");
            String preferred = librarySummary(account, ORIGIN + "mybook");
            return "로그인 상태: 서버에서 확인됨\n최애 작품: " + favorite
                    + "\n최근 본 작품: " + recent + "\n전체 선호작: " + preferred + "\n\n"
                    + "목록은 한 쪽씩 조회합니다. 다음 쪽은 목록 화면에서 열 수 있습니다.\n"
                    + "최애 작품은 전체 선호작 목록과 다릅니다. 계정 식별자·쿠키·작품 제목은 "
                    + "진단 로그나 파일에 기록하지 않았습니다.";
        }, result -> new FontDialogBuilder(this).setTitle("계정 연동 검사")
                .setMessage(result).setPositiveButton("확인", null).show());
    }

    private static String librarySummary(AccountClient account, String url) {
        try { return account.library(url).items.size() + "개 수신 (1쪽)"; }
        catch (java.io.IOException ignored) { return "library:unavailable"; }
    }

    private interface Work<T> { T run() throws Exception; }
    private interface Result<T> { void accept(T value); }
    private <T> void request(String message, Work<T> work, Result<T> result) {
        final int token = ++generation;
        final int priorStatusVisibility = status.getVisibility();
        loading = true;
        status.setText(message);
        if (!reading) status.setVisibility(View.VISIBLE);
        if (reading && readerProgress != null) readerProgress.setText(message);
        io.execute(() -> {
            try {
                final T value = work.run();
                runOnUiThread(() -> {
                    if (!destroyed && token == generation) {
                        loading = false;
                        status.setText("완료");
                        status.setVisibility(priorStatusVisibility);
                        if (reading) updateProgress();
                        result.accept(value);
                    }
                });
            } catch (Exception e) {
                // Do not log exception details: server messages could contain session data.
                final String error = safeMessage(e);
                runOnUiThread(() -> {
                    if (!destroyed && token == generation) {
                        loading = false;
                        updateProgress();
                        status.setText("요청 실패 · 다시 시도할 수 있습니다");
                        FontDialogBuilder dialog = new FontDialogBuilder(this);
                        dialog.setTitle("불러오지 못했습니다").setMessage(error)
                                .setNegativeButton("취소", null);
                        if ("로그인이 필요합니다.".equals(error))
                            dialog.setPositiveButton("로그인", (d,w) -> login());
                        else dialog.setPositiveButton("다시 시도", (d,w) -> request(message, work, result));
                        dialog.show();
                    }
                });
            }
        });
    }

    private String safeMessage(Exception error) {
        if (error instanceof javax.net.ssl.SSLException) {
            return "TLS 인증서 또는 암호화 연결에 실패했습니다. 기기 날짜·시간과 Wi-Fi를 확인해주세요. "
                    + "이 앱은 인증서 검증을 생략하지 않습니다.";
        }
        String message = error.getMessage();
        if ("library:invalid_page".equals(message))
            return "서재 주소 또는 목록 형식을 확인하지 못했습니다. 기본 최근 기록·선호작 메뉴에서 다시 열어주세요. "
                    + "사이트 구조가 바뀌었을 수도 있습니다. (library:invalid_page)";
        if (message == null || message.contains("<") || message.length() > 240)
            return "네트워크 또는 사이트 응답을 처리하지 못했습니다. 로그인 상태와 연결을 확인해주세요.";
        return message;
    }

    private static boolean allowed(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && ("novelpia.com".equalsIgnoreCase(uri.getHost())
                    || "book.novelpia.com".equalsIgnoreCase(uri.getHost()))
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (Exception e) { return false; }
    }

    private void open(final String url, boolean push) {
        open(url, push, false);
    }

    private void open(final String url, boolean push, boolean back) {
        open(url, push, back, listScreens.containsKey(url) ? listScreens.get(url) : 0);
    }

    private void open(final String url, boolean push, boolean back, final int initialScreen) {
        if (FAVORITES.equals(url)) {
            request("최애 작품 불러오는 중…", () -> accountClient().favorites(), rows -> {
                commitNavigation(FAVORITES, push, back);
                showRows("내서재 · 최애 작품", rows, null, 0, initialScreen);
            });
            return;
        }
        if (!allowed(url)) {
            toast("노벨피아 공식 작품 사이트의 HTTPS 주소만 열 수 있습니다.");
            return;
        }
        saveProgress();
        final String path = Uri.parse(url).getPath();
        if ("/mybook".equals(path) || (path != null && path.startsWith("/mybook/"))) {
            request("내서재 한 쪽 불러오는 중…", () -> accountClient().library(url), page -> {
                commitNavigation(page.url, push, back);
                showLibrary(page, initialScreen);
            });
        } else if (path != null && path.matches("/viewer/\\d+/?")) {
            request("회차 본문 확인 중…", () -> client().readChapter(url), value -> {
                if (!reading) {
                    readerReturnUrl = pagedList == null ? null : location;
                    readerReturnNovelId = listNovelId;
                    readerReturnPage = listEpisodePage;
                    readerReturnScreen = pagedList == null ? 0 : pagedList.getScreen();
                }
                commitNavigation(url, push, back);
                showChapter(value);
            });
        } else if (path != null && path.matches("/novel/\\d+/?")) {
            final String id = path.replaceAll("[^0-9]", "");
            request("회차 목록 불러오는 중…", () -> client().episodes(id, 0), rows -> {
                commitNavigation(url, push, back);
                showRows("회차 목록 · 1쪽", rows, id, 0);
            });
        } else {
            request("작품 목록 불러오는 중…", () -> client().browse(url), rows -> {
                commitNavigation(url, push, back);
                showRows("작품 둘러보기", rows, null, 0, initialScreen);
            });
        }
    }

    private void commitNavigation(String url, boolean push, boolean back) {
        if (returningToList && url.equals(readerReturnUrl)) {
            int origin = history.lastIndexOf(url);
            if (origin >= 0) while (history.size() > origin) history.remove(history.size() - 1);
            else while (!history.isEmpty() && history.get(history.size() - 1).contains("/viewer/"))
                history.remove(history.size() - 1);
            returningToList = false;
        }
        // Failed/stale async loads must not consume the Back destination.
        if (back && !history.isEmpty() && url.equals(history.get(history.size() - 1)))
            history.remove(history.size() - 1);
        if (push && !location.equals(url)) {
            if (history.size() >= 30) history.remove(0);
            history.add(location);
        }
        location = url;
    }

    private void showEpisodes(final String novelId, final int page) {
        showEpisodes(novelId, page, 0);
    }

    private void showEpisodes(final String novelId, final int page, final int screen) {
        request("회차 목록을 가져오는 중…", () -> client().episodes(novelId, page),
                rows -> showRows("회차 목록", rows, novelId, page, screen));
    }

    private LinearLayout libraryTabs() {
        LinearLayout tabs = horizontal();
        tabs.setPadding(dp(24), 0, dp(24), dp(8));
        String[] names = {"선호작", "알림", "소장", "최근 기록"};
        String[] shelves = {"like", "alarm", "collect", "last_view"};
        for (int i = 0; i < names.length; i++) {
            final String shelf = shelves[i];
            Button tab = button(names[i], v -> open(ORIGIN + "mybook/" + shelf, true));
            boolean selected = location.contains("/mybook/" + shelf)
                    || (i == 0 && (location.equals(ORIGIN + "mybook") || location.startsWith(ORIGIN + "mybook?")));
            if (selected) InkUi.primary(tab);
            tab.setSelected(selected);
            tab.setContentDescription(names[i] + (selected ? " · 선택됨" : ""));
            tabs.addView(tab, weighted());
        }
        return tabs;
    }

    private void showLibrary(final LibraryPage page) { showLibrary(page, 0); }

    private void showLibrary(final LibraryPage page, final int initialScreen) {
        resetContent("내서재");
        heading.setVisibility(View.GONE);
        content.addView(libraryTabs());
        LinearLayout filters = horizontal();
        filters.setPadding(dp(24), 0, dp(24), dp(8));
        Button sort = button(optionLabel(page.sorts, page.sort, "last_view".equals(page.shelf) ? "최근 본 순" : "정렬"),
                v -> chooseShelfOption(page, true));
        sort.setEnabled(!page.sorts.isEmpty());
        Button group = button(optionLabel(page.groups, page.group, "전체"), v -> chooseShelfOption(page, false));
        group.setEnabled(!page.groups.isEmpty());
        Button search = button(page.search.isEmpty() ? "서재 검색" : "검색 변경", v -> searchShelf(page));
        filters.addView(sort, weighted()); filters.addView(group, weighted()); filters.addView(search, weighted());
        content.addView(filters);
        if (!page.search.isEmpty()) {
            Button clear = button("검색: " + page.search + "  ×", v -> filterShelf(page, page.group, page.sort, ""));
            clear.setSingleLine(true); clear.setEllipsize(android.text.TextUtils.TruncateAt.END);
            InkUi.quiet(clear); content.addView(clear, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        ArrayList<PagedListView.Row> rows = new ArrayList<PagedListView.Row>();
        for (LibraryPage.Item item : page.items) {
            String detail = item.progressLabel();
            if (item.hasNextEpisode()) detail = detail.replaceFirst("^다음 회차 있음(?: · )?", "");
            if (!item.author.isEmpty()) detail += "\n" + item.author;
            rows.add(new PagedListView.Row(item.entry.title, detail, () -> {
                ArrayList<String> labels = new ArrayList<String>();
                ArrayList<Runnable> actions = new ArrayList<Runnable>();
                if (!item.continueUrl.isEmpty()) {
                    labels.add(item.lastReadEpisode >= 0 ? "EP." + item.lastReadEpisode + " 이어보기" : "이어서 읽기");
                    actions.add(() -> open(item.continueUrl, true));
                }
                if (item.hasNextEpisode()) {
                    labels.add("다음 회차 읽기");
                    actions.add(() -> openNextEpisode(item));
                }
                labels.add("회차 목록");
                actions.add(() -> open(item.entry.url, true));
                if (actions.size() == 1) actions.get(0).run();
                else choices(item.entry.title, labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
            }, 2, item.hasNextEpisode() ? "다음화" : "이어보기",
                    item.hasNextEpisode() ? () -> openNextEpisode(item)
                            : item.continueUrl.isEmpty() ? null : () -> open(item.continueUrl, true)));
        }
        pagedList = new PagedListView(this);
        pagedList.setNote("목록 " + page.page);
        pagedList.setEmptyMessage(page.search.isEmpty() ? "등록된 작품이 없습니다." : "검색 결과가 없습니다. 위에서 검색을 해제할 수 있습니다.");
        pagedList.setBoundaryActions(page.previousUrl.isEmpty() ? null
                        : () -> { if (!loading) open(page.previousUrl, true, false, -1); },
                page.nextUrl.isEmpty() ? null : () -> { if (!loading) open(page.nextUrl, true, false, 0); });
        pagedList.setRows(rows, initialScreen);
        content.addView(pagedList, new LinearLayout.LayoutParams(-1, 0, 1));
        rememberScreens(page.url, pagedList);
        refreshAction = () -> open(page.url, false, false, pagedList.getScreen());
        status.setText("");
        status.setVisibility(View.INVISIBLE);
    }

    private void openNextEpisode(LibraryPage.Item item) {
        if (loading) return;
        request("다음 회차 확인 중…", () -> accountClient().nextEpisode(item), next -> {
            if (next.state == AccountClient.NextEpisode.State.AVAILABLE) open(next.url, true);
            else new FontDialogBuilder(this).setTitle("다음 회차")
                    .setMessage(next.state == AccountClient.NextEpisode.State.WAITING
                            ? "아직 공개되지 않은 회차입니다." : "현재 확인되는 마지막 회차입니다. 목록을 새로고침해주세요.")
                    .setPositiveButton("확인", null).show();
        });
    }

    private static String optionLabel(List<LibraryPage.Option> options, String value, String fallback) {
        for (LibraryPage.Option option : options) if (option.value.equals(value)) return option.label;
        return fallback;
    }

    private void chooseShelfOption(final LibraryPage page, final boolean sorting) {
        final List<LibraryPage.Option> options = sorting ? page.sorts : page.groups;
        if (options.isEmpty()) return;
        PagedListView list = new PagedListView(this);
        final AlertDialog dialog = new FontDialogBuilder(this).setTitle(sorting ? "정렬" : "그룹")
                .setView(list).setNegativeButton("취소", null).create();
        ArrayList<PagedListView.Row> rows = new ArrayList<PagedListView.Row>();
        for (LibraryPage.Option option : options) rows.add(new PagedListView.Row(option.label,
                option.value.equals(sorting ? page.sort : page.group) ? "선택됨" : "", () -> {
                    dialog.dismiss();
                    filterShelf(page, sorting ? page.group : option.value, sorting ? option.value : page.sort, page.search);
                }));
        list.setRows(rows, 0);
        dialog.setOnShowListener(d -> {
            FontDialogBuilder.decorate(dialog);
            list.getLayoutParams().height = Math.min(dp(Math.min(4, options.size()) * 81 + 56),
                    getResources().getDisplayMetrics().heightPixels * 2 / 3);
            list.requestLayout();
        });
        dialog.show();
    }

    private void filterShelf(LibraryPage page, String group, String sort, String search) {
        try { open(LibraryParser.buildUrl(page.shelf, group, sort, 1, search), true, false, 0); }
        catch (java.io.IOException invalid) { toast("정렬·그룹·검색 조건을 확인해주세요."); }
    }

    private void searchShelf(final LibraryPage page) {
        EditText query = input("서재의 작품명", false);
        query.setText(page.search);
        query.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)});
        new FontDialogBuilder(this).setTitle("서재 검색").setView(query).setNegativeButton("취소", null)
                .setNeutralButton("검색 해제", (d,w) -> filterShelf(page, page.group, page.sort, ""))
                .setPositiveButton("검색", (d,w) -> filterShelf(page, page.group, page.sort, query.getText().toString().trim())).show();
    }

    private void showRows(String title, List<Entry> entries, String novelId, int page) {
        showRows(title, entries, novelId, page, 0);
    }

    private void showRows(String title, List<Entry> entries, String novelId, int page, int initialScreen) {
        boolean favorite = FAVORITES.equals(location) && title.startsWith("내서재");
        resetContent(favorite ? "나의 최애 작품" : novelId != null ? "회차 목록" : title);
        listNovelId = novelId;
        listEpisodePage = page;
        if (favorite) content.addView(libraryTabs());
        ArrayList<PagedListView.Row> rows = new ArrayList<PagedListView.Row>();
        for (Entry entry : entries) rows.add(new PagedListView.Row(entry.title,
                entry.detail == null || entry.detail.length() == 0
                        ? (novelId == null ? "선택하여 열기" : "회차 읽기") : entry.detail,
                () -> open(entry.url, true)));
        pagedList = new PagedListView(this);
        pagedList.setEmptyMessage(favorite ? "아직 담긴 최애 작품이 없습니다."
                : novelId != null ? "더 표시할 회차가 없습니다. 이전 목록으로 돌아갈 수 있습니다."
                : "표시할 작품이 없습니다.");
        pagedList.setNote(novelId == null ? "" : "회차 목록 " + (page + 1));
        if (novelId != null) pagedList.setBoundaryActions(page == 0 ? null
                        : () -> { if (!loading) showEpisodes(novelId, page - 1, -1); },
                entries.isEmpty() ? null : () -> { if (!loading) showEpisodes(novelId, page + 1, 0); });
        pagedList.setRows(rows, initialScreen);
        content.addView(pagedList, new LinearLayout.LayoutParams(-1, 0, 1));
        rememberScreens(location + (novelId == null ? "" : "#episodes=" + page), pagedList);
        refreshAction = novelId == null ? () -> open(location, false)
                : () -> showEpisodes(novelId, page, pagedList.getScreen());
        status.setText("");
        status.setVisibility(View.INVISIBLE);
    }

    private void rememberScreens(final String key, final PagedListView view) {
        view.setOnScreenChanged(() -> {
            if (view != pagedList) return;
            if (listScreens.size() >= 40 && !listScreens.containsKey(key))
                listScreens.remove(listScreens.keySet().iterator().next());
            listScreens.put(key, view.getScreen());
        });
    }

    private void search() {
        EditText input = input("작품명 또는 https://novelpia.com/novel/번호", false);
        new FontDialogBuilder(this).setTitle("작품 검색 / 주소 열기").setView(input)
                .setNegativeButton("취소", null).setPositiveButton("열기", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.length() == 0) return;
                    if (value.startsWith("https://")) open(value, true);
                    else {
                        try { open(ORIGIN + "search?search_string=" + URLEncoder.encode(value, "UTF-8"), true); }
                        catch (Exception ignored) { toast("검색어를 확인해주세요."); }
                    }
                }).show();
    }

    private void login() {
        if (loginOpening || (loginDialog != null && loginDialog.isShowing())) return;
        if (signingIn) { toast("로그인 중입니다."); return; }
        loginOpening = true;
        io.execute(() -> {
            CredentialStore.Credentials saved = null;
            boolean failed = false;
            try { if (credentials.hasSaved()) saved = credentials.load(); }
            catch (Exception ignored) { failed = true; }
            final CredentialStore.Credentials value = saved;
            final boolean unavailable = failed;
            runOnUiThread(() -> {
                loginOpening = false;
                if (destroyed) return;
                showLoginFields(value == null ? "" : value.email, value == null ? "" : value.password);
                if (unavailable) toast("저장된 로그인 정보를 열 수 없습니다. 직접 입력해주세요.");
            });
        });
    }

    private void showLoginFields(String savedEmail, String savedPassword) {
        if (loginDialog != null && loginDialog.isShowing()) return;
        LinearLayout fields = vertical();
        fields.setPadding(dp(16), dp(8), dp(16), dp(8));
        EditText email = input("노벨피아 이메일", false);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText password = input("비밀번호", true);
        email.setText(savedEmail); password.setText(savedPassword);
        fields.addView(email); fields.addView(password);
        final boolean[] options = {credentials.hasSaved(), credentials.isAutoLoginEnabled()};
        Button remember = button("", null), automatic = button("", null);
        Runnable update = () -> {
            remember.setText("비밀번호 저장  ·  " + (options[0] ? "켬" : "끔"));
            automatic.setText("자동 로그인  ·  " + (options[1] ? "켬" : "끔"));
        };
        remember.setOnClickListener(v -> { options[0] = !options[0]; if (!options[0]) options[1] = false; update.run(); });
        automatic.setOnClickListener(v -> { options[1] = !options[1]; if (options[1]) options[0] = true; update.run(); });
        update.run();
        fields.addView(remember, new LinearLayout.LayoutParams(-1, dp(48)));
        fields.addView(automatic, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView note = label("저장을 켜면 이 기기의 키로 암호화합니다. 공용 기기에서는 끄세요.", 12);
        note.setPadding(0, dp(8), 0, 0); fields.addView(note);
        AlertDialog dialog = new FontDialogBuilder(this).setTitle("이메일 로그인").setView(fields)
                .setNegativeButton("취소", null).setPositiveButton("로그인", null).create();
        loginDialog = dialog;
        dialog.setOnDismissListener(d -> {
            password.setText(""); email.setText("");
            if (loginDialog == dialog) loginDialog = null;
        });
        dialog.setOnShowListener(d -> {
            FontDialogBuilder.decorate(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final String e = email.getText().toString().trim();
                final String p = password.getText().toString();
                if (e.length() == 0) { email.setError("이메일을 입력하세요."); email.requestFocus(); return; }
                if (p.length() == 0) { password.setError("비밀번호를 입력하세요."); password.requestFocus(); return; }
                if (e.length() > 512 || p.length() > 4096) { toast("입력 길이를 확인해주세요."); return; }
                dialog.dismiss();
                signIn(e, p, options[0], options[1], false);
            });
        });
        dialog.show();
    }

    private void autoLogin() {
        if (signingIn) return;
        signIn(null, null, true, true, true);
    }

    /** One attempt per cold launch; no generic retry closure retaining passwords. */
    private void signIn(final String email, final String password, final boolean remember,
                        final boolean automatic, final boolean fromStorage) {
        if (signingIn) return;
        signingIn = true;
        final int token = ++generation;
        loading = true;
        status.setText(fromStorage ? "자동 로그인 중…" : "로그인 중…");
        io.execute(() -> {
            AccountClient.SessionStatus result = null;
            String problem = "";
            try {
                CredentialStore.Credentials saved = fromStorage ? credentials.load() : null;
                if (!fromStorage && !remember) credentials.clear();
                client(); http.clearCookies();
                result = accountClient().login(fromStorage ? saved.email : email,
                        fromStorage ? saved.password : password);
                if (result.isAuthenticated() && !fromStorage && remember) {
                    try { credentials.save(email, password, automatic); }
                    catch (Exception ignored) {
                        // Never silently retain an older account for auto-login after switching.
                        try { credentials.clear(); } catch (Exception ignoredAgain) { }
                        problem = "로그인은 완료했지만 암호화 저장에 실패했습니다. 저장 및 자동 로그인은 설정되지 않았습니다.";
                    }
                } else if (fromStorage && result.state == AccountClient.SessionStatus.State.UNAUTHENTICATED) {
                    credentials.setAutoLoginEnabled(false);
                }
            } catch (Exception ignored) {
                problem = "저장된 정보를 사용할 수 없거나 로그인 연결에 실패했습니다. 이메일 로그인을 다시 시도해주세요.";
            }
            final AccountClient.SessionStatus outcome = result;
            final String notice = problem;
            runOnUiThread(() -> {
                signingIn = false;
                if (destroyed || token != generation) return;
                loading = false;
                if (outcome != null && outcome.isAuthenticated()) {
                    status.setText("로그인됨");
                    open(ORIGIN + "mybook/last_view", true);
                    if (!notice.isEmpty()) new FontDialogBuilder(this).setTitle("저장하지 못했습니다")
                            .setMessage(notice).setPositiveButton("확인", null).show();
                } else {
                    status.setText("로그인 필요");
                    if (fromStorage) {
                        login();
                        toast(!notice.isEmpty() ? notice : "자동 로그인에 실패했습니다. 다시 로그인해주세요.");
                        return;
                    }
                    new FontDialogBuilder(this).setTitle("로그인 실패")
                            .setMessage(!notice.isEmpty() ? notice : outcome == null ? "로그인을 다시 시도해주세요." : outcome.message)
                            .setNegativeButton("닫기", null).setPositiveButton("이메일 로그인", (d,w) -> login()).show();
                }
            });
        });
    }

    private void savedLoginSettings() {
        choices("저장된 로그인", new String[]{credentials.isAutoLoginEnabled() ? "자동 로그인 끄기" : "자동 로그인 켜기", "저장된 이메일·비밀번호 삭제"},
                new Runnable[]{() -> {
                    if (!credentials.hasSaved()) { toast("이메일 로그인에서 비밀번호 저장을 먼저 켜세요."); return; }
                    request("설정 저장 중…", () -> {
                        credentials.setAutoLoginEnabled(!credentials.isAutoLoginEnabled()); return true;
                    }, ok -> toast("자동 로그인 설정을 변경했습니다."));
                }, () -> new FontDialogBuilder(this).setTitle("저장된 로그인을 삭제할까요?")
                        .setMessage("저장된 이메일·비밀번호를 삭제하고 자동 로그인을 끕니다. 현재 로그인은 유지합니다.")
                        .setNegativeButton("취소", null).setPositiveButton("삭제", (d,w) ->
                            request("삭제 중…", () -> { credentials.clear(); return true; }, ok -> toast("저장된 로그인 정보를 삭제했습니다."))).show()});
    }

    private void menu() {
        choices("메뉴", new String[]{"새로고침", "읽기 설정", "화면 조정", "물리 버튼", "파일과 책갈피", "계정과 도움말"},
                new Runnable[]{this::refresh, this::settings, this::displaySettings, this::keySettings,
                    () -> choices("파일과 책갈피", new String[]{"내 파일 읽기", "책갈피 목록", "현재 위치 책갈피", "리더 미리보기"},
                            new Runnable[]{this::importText, this::bookmarks, this::addBookmark, this::demo}),
                    () -> choices("계정과 도움말", new String[]{"로그인 관리", "계정 연동 검사", "사용 방법", "앱 정보"},
                            new Runnable[]{this::accountSettings, this::checkAccountLink, this::help, this::about})});
    }

    private void choices(String title, String[] labels, Runnable[] actions) {
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        final AlertDialog dialog = new FontDialogBuilder(this).setTitle(title).setView(body)
                .setNegativeButton("닫기", null).create();
        for (int i = 0; i < labels.length; i++) {
            final Runnable action = actions[i];
            body.addView(button(labels[i], v -> { dialog.dismiss(); action.run(); }),
                    new LinearLayout.LayoutParams(-1, dp(52)));
        }
        dialog.setOnShowListener(d -> FontDialogBuilder.decorate(dialog));
        dialog.show();
    }

    private void help() {
        new FontDialogBuilder(this).setTitle("사용 방법")
                .setMessage("목록은 아래의 이전·다음 버튼으로 한 화면씩 넘깁니다. 마지막 화면에서는 다음 목록을 가져옵니다.\n\n"
                        + "본문의 왼쪽·오른쪽을 누르면 페이지가 바뀝니다. 가운데 또는 메뉴 아이콘을 누르면 글자 크기·회차 이동 도구가 열립니다.\n\n"
                        + "물리 키가 다르게 동작하면 메뉴 → 물리 버튼에서 직접 등록하세요. 화면 조정은 앱의 명도·대비·채도를 바꾸며 기기의 조명은 바꾸지 않습니다.")
                .setPositiveButton("확인", null).show();
    }

    private void about() {
        new FontDialogBuilder(this).setTitle("노벨피아 e-ink")
                .setMessage("노벨피아와 무관한 비공식 리더입니다.\n\n"
                        + "비밀번호 저장을 켜면 이메일·비밀번호를 기기 키로 암호화해 저장합니다. 자동 로그인은 선택 사항이며 로그인 관리에서 삭제·해제할 수 있습니다. 쿠키와 본문은 저장하지 않습니다.\n\n"
                        + "앱 내부 TLS는 인증서와 호스트명을 검증하며 시스템 인증서를 변경하지 않습니다. 구독·결제 확인을 우회하지 않습니다.\n\n"
                        + "화면 다시 그리기는 일반 흑백 화면 갱신이며 제조사 EPD 파형 제어가 아닙니다. 구글 세션 가져오기와 크레마 실기기는 추가 검증이 필요합니다.")
                .setPositiveButton("닫기", null).show();
    }

    private void displaySettings() {
        DisplaySettingsDialog.show(this, DisplayProfile.load(prefs), profile -> {
            profile.save(prefs);
            root.setProfile(profile);
            toast("화면 설정을 적용했습니다.");
        });
    }

    private void keySettings() {
        KeyMappingDialog.show(this, keyBindings, bindings -> {
            keyBindings = bindings;
            bindings.save(prefs);
            consumedKeys.clear();
            toast("물리 버튼 설정을 저장했습니다.");
        });
    }

    private void refresh() {
        if (loading) return;
        if (reading) flash();
        else if (refreshAction != null) refreshAction.run();
        else root.invalidate();
    }

    private void showChapter(Chapter value) {
        if (!allowed(value.url)) readerReturnUrl = null;
        resetContent(value.title);
        chapter = value;
        reading = true;
        navigation.setVisibility(View.GONE);
        heading.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        reader = new ReaderView(this);
        String episode = value.episodeTitle;
        if (!value.episodeLabel.isEmpty() && !episode.startsWith(value.episodeLabel))
            episode = value.episodeLabel + (episode.isEmpty() ? "" : " · " + episode);
        final boolean hasEpisode = !episode.isEmpty();
        reader.setPadding(dp(24), dp(hasEpisode ? 80 : 56), dp(24), dp(allowed(value.nextUrl) ? 56 : 40));
        reader.setTextSizeSp(fontSize);
        reader.setLineSpacing(prefs.getBoolean("wideLines", true) ? 1.65f : 1.35f);
        reader.setContent(value.title, value.text);
        reader.restoreProgress(allowed(value.url) ? prefs.getFloat(progressKey(value.url), 0f) : 0f);
        reader.setOnProgressChangedListener((view, progress, pageIndex, pageCount) -> updateProgress());
        final float[] down = new float[2];
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        reader.setContentDescription("본문. 왼쪽은 이전 페이지, 오른쪽은 다음 페이지, 가운데는 읽기 메뉴");
        reader.setOnClickListener(v -> toggleReaderTools());
        reader.setOnTouchListener((v,event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                down[0] = event.getX(); down[1] = event.getY(); return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (Math.abs(event.getX()-down[0]) <= slop && Math.abs(event.getY()-down[1]) <= slop) {
                    if (readerTools != null && readerTools.getVisibility() == View.VISIBLE) toggleReaderTools();
                    else if (event.getX() < v.getWidth() * .28f) turn(false);
                    else if (event.getX() > v.getWidth() * .72f) turn(true);
                    else v.performClick();
                }
                return true;
            }
            return true;
        });
        readerFrame = new FrameLayout(this);
        readerFrame.setBackgroundColor(Color.WHITE);
        readerFrame.addView(reader, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout titles = vertical();
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView bookTitle = label(value.title, 13);
        bookTitle.setSingleLine(true);
        bookTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bookTitle.setGravity(Gravity.CENTER_VERTICAL);
        titles.addView(bookTitle);
        if (hasEpisode) {
            TextView episodeTitle = label(episode, 12);
            episodeTitle.setSingleLine(true);
            episodeTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titles.addView(episodeTitle);
        }
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, dp(hasEpisode ? 72 : 48), Gravity.TOP);
        titleParams.leftMargin = dp(80); titleParams.rightMargin = dp(68);
        readerFrame.addView(titles, titleParams);
        Button back = button("목록", v -> returnToList());
        InkUi.quiet(back);
        back.setContentDescription("읽기 전 목록으로 돌아가기");
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(64), dp(48), Gravity.TOP | Gravity.LEFT);
        backParams.leftMargin = dp(8);
        readerFrame.addView(back, backParams);
        Button menu = menuButton(v -> toggleReaderTools());
        menu.setContentDescription("읽기 메뉴 열기");
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.RIGHT);
        menuParams.rightMargin = dp(12);
        readerFrame.addView(menu, menuParams);
        readerProgress = label("", 12);
        readerProgress.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        readerProgress.setPadding(dp(16), 0, dp(24), 0);
        readerFrame.addView(readerProgress, new FrameLayout.LayoutParams(-1, dp(32), Gravity.BOTTOM));
        readerNext = button("다음 회차", v -> { if (!loading) open(value.nextUrl, true); });
        InkUi.quiet(readerNext);
        readerNext.setVisibility(View.GONE);
        FrameLayout.LayoutParams endParams = new FrameLayout.LayoutParams(dp(112), dp(48), Gravity.BOTTOM | Gravity.LEFT);
        endParams.leftMargin = dp(12);
        readerFrame.addView(readerNext, endParams);
        readerTools = vertical();
        readerTools.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
        panel.setColor(Color.WHITE); panel.setStroke(dp(1), Color.BLACK);
        readerTools.setBackground(panel);
        LinearLayout toolHeading = horizontal();
        TextView toolTitle = label("읽기 도구", 18);
        toolTitle.setGravity(Gravity.CENTER_VERTICAL);
        toolHeading.addView(toolTitle, weighted());
        toolHeading.addView(button("닫기", v -> toggleReaderTools()), new LinearLayout.LayoutParams(dp(80), dp(48)));
        readerTools.addView(toolHeading);
        LinearLayout type = horizontal();
        type.addView(button("글자 −", v -> resize(-2)), weighted());
        readerSize = label(fontSize + " sp", 16);
        readerSize.setGravity(Gravity.CENTER);
        type.addView(readerSize, weighted());
        type.addView(button("글자 +", v -> resize(2)), weighted());
        readerTools.addView(type);
        LinearLayout chapters = horizontal();
        Button previous = button("이전 회차", v -> open(value.previousUrl, true));
        previous.setEnabled(value.previousUrl != null && allowed(value.previousUrl));
        Button next = button("다음 회차", v -> open(value.nextUrl, true));
        next.setEnabled(value.nextUrl != null && allowed(value.nextUrl));
        chapters.addView(previous, weighted());
        chapters.addView(button("책갈피", v -> addBookmark()), weighted());
        chapters.addView(next, weighted());
        readerTools.addView(chapters);
        LinearLayout options = horizontal();
        options.addView(button("화면 조정", v -> displaySettings()), weighted());
        options.addView(button("물리 버튼", v -> keySettings()), weighted());
        options.addView(button("더보기", v -> menu()), weighted());
        readerTools.addView(options);
        readerTools.setVisibility(View.GONE);
        readerFrame.addView(readerTools, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        content.addView(readerFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        reader.requestFocus();
        if (prefs.getBoolean("keepAwake", false))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (value.url != null && allowed(value.url)) {
            location = value.url;
            prefs.edit().putString("lastUrl", value.url).apply();
        }
        reader.post(this::updateProgress);
    }

    private void returnToList() {
        if (loading) return;
        saveProgress();
        if (chapter == null || !allowed(chapter.url)) { welcome(); return; }
        if (readerReturnUrl == null) {
            readerReturnUrl = chapter.novelUrl;
            readerReturnNovelId = null;
            readerReturnScreen = 0;
        }
        if (readerReturnUrl == null || readerReturnUrl.isEmpty()) { welcome(); return; }
        returningToList = true;
        if (readerReturnNovelId != null) {
            final String target = readerReturnUrl, id = readerReturnNovelId;
            final int page = readerReturnPage, screen = readerReturnScreen;
            request("회차 목록으로 돌아가는 중…", () -> client().episodes(id, page), rows -> {
                commitNavigation(target, false, false);
                showRows("회차 목록", rows, id, page, screen);
            });
        } else open(readerReturnUrl, false, false, readerReturnScreen);
    }

    private void toggleReaderTools() {
        if (!reading || readerTools == null) return;
        readerTools.setVisibility(readerTools.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    private void turn(boolean forward) {
        if (!reading || reader == null) return;
        float before = reader.getProgress();
        if (forward) reader.nextPage(); else reader.previousPage();
        if (before == reader.getProgress()) toast(forward ? "회차의 마지막 페이지입니다. 메뉴에서 다음 회차를 선택하세요." : "첫 페이지입니다.");
        updateProgress();
        saveProgress();
        int interval = prefs.getInt("flashInterval", 0);
        if (interval > 0 && ++turnCount % interval == 0) flash();
    }

    private void resize(int delta) {
        fontSize = Math.max(16, Math.min(36, fontSize + delta));
        prefs.edit().putInt("font", fontSize).apply();
        if (readerSize != null) readerSize.setText(fontSize + " sp");
        if (reader != null) { reader.setTextSizeSp(fontSize); reader.post(this::updateProgress); }
    }

    private void updateProgress() {
        if (reader != null) {
            String value = reader.isLastPage() ? "마지막 페이지"
                    : String.format(Locale.KOREA, "%.0f%%", reader.getProgress() * 100f);
            status.setText(value);
            if (readerProgress != null) readerProgress.setText(value);
            if (readerNext != null) readerNext.setVisibility(reader.isLastPage()
                    && chapter != null && allowed(chapter.nextUrl) ? View.VISIBLE : View.GONE);
        }
    }

    private String progressKey(String url) {
        // Only public chapter URL is persisted; no content, credentials, or cookies.
        return "progress:" + (url == null ? "local" : url);
    }

    private void saveProgress() {
        if (reading && reader != null && chapter != null && allowed(chapter.url)) {
            SharedPreferences.Editor edit = prefs.edit();
            // Bound stored positions. Saved bookmarks are a separate bounded collection.
            int count = 0;
            for (String key : prefs.getAll().keySet()) if (key.startsWith("progress:")) count++;
            if (count > 100) for (String key : prefs.getAll().keySet())
                if (key.startsWith("progress:")) edit.remove(key);
            edit.putFloat(progressKey(chapter.url), reader.getProgress()).apply();
        }
    }

    private void settings() {
        final boolean[] values = {prefs.getBoolean("wideLines", true), prefs.getBoolean("keepAwake", false),
                prefs.getInt("flashInterval", 0) > 0};
        final int[] size = {fontSize};
        LinearLayout body = vertical();
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout type = horizontal();
        TextView current = label(size[0] + " sp", 18); current.setGravity(Gravity.CENTER);
        type.addView(button("글자 −", v -> { size[0] = Math.max(16,size[0]-2); current.setText(size[0]+" sp"); }), weighted());
        type.addView(current, weighted());
        type.addView(button("글자 +", v -> { size[0] = Math.min(36,size[0]+2); current.setText(size[0]+" sp"); }), weighted());
        body.addView(type);
        String[] names = {"넓은 줄 간격", "읽는 동안 화면 켜기", "8쪽마다 흑백 재그리기"};
        for (int i=0; i<names.length; i++) {
            final int n=i;
            Button toggle = button(names[i] + "  ·  " + (values[i] ? "켬" : "끔"), null);
            toggle.setOnClickListener(v -> {
                values[n] = !values[n];
                toggle.setText(names[n]+"  ·  "+(values[n] ? "켬" : "끔"));
            });
            body.addView(toggle, new LinearLayout.LayoutParams(-1, dp(52)));
        }
        new FontDialogBuilder(this).setTitle("읽기 설정").setView(body)
                .setNegativeButton("취소", null).setPositiveButton("적용", (d,w) -> {
                    fontSize=size[0];
                    prefs.edit().putInt("font",fontSize).putBoolean("wideLines",values[0])
                            .putBoolean("keepAwake",values[1]).putInt("flashInterval",values[2]?8:0).apply();
                    if (reader != null) {
                        reader.setTextSizeSp(fontSize);
                        reader.setLineSpacing(values[0]?1.65f:1.35f);
                        reader.post(this::updateProgress);
                    }
                    if (readerSize != null) readerSize.setText(fontSize+" sp");
                    if (reading && values[1]) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    toast("읽기 설정을 적용했습니다.");
                }).show();
    }

    private void flash() {
        if (readerFrame == null) { root.invalidate(); return; }
        final FrameLayout frame = readerFrame;
        final View overlay = new View(this);
        overlay.setBackgroundColor(Color.BLACK);
        frame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.postDelayed(() -> {
            overlay.setBackgroundColor(Color.WHITE);
            overlay.postDelayed(() -> { frame.removeView(overlay); frame.invalidate(); }, 100);
        }, 100);
    }

    private void addBookmark() {
        if (reading && (chapter == null || chapter.url == null || !allowed(chapter.url))) {
            toast("로컬 샘플·TXT는 온라인 책갈피에 추가하지 않습니다."); return;
        }
        try {
            JSONArray old = new JSONArray(prefs.getString("bookmarks", "[]"));
            JSONArray next = new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("url", location);
            entry.put("title", currentTitle);
            next.put(entry);
            for (int i = 0; i < old.length() && next.length() < 40; i++) {
                JSONObject item = old.getJSONObject(i);
                if (!location.equals(item.getString("url"))) next.put(item);
            }
            prefs.edit().putString("bookmarks", next.toString()).apply();
            toast("주소와 읽던 위치를 저장했습니다.");
        } catch (Exception e) { toast("책갈피를 저장하지 못했습니다."); }
    }

    private void bookmarks() {
        ++generation; loading = false;
        try {
            JSONArray items = new JSONArray(prefs.getString("bookmarks", "[]"));
            resetContent("책갈피");
            ArrayList<PagedListView.Row> rows = new ArrayList<PagedListView.Row>();
            for (int i=0; i<items.length() && i<40; i++) {
                JSONObject item = items.getJSONObject(i);
                final String url = item.getString("url");
                rows.add(new PagedListView.Row(item.getString("title"), "저장한 위치 열기", () -> open(url, true)));
            }
            pagedList = new PagedListView(this);
            pagedList.setEmptyMessage("읽기 메뉴에서 책갈피를 남겨보세요.");
            pagedList.setRows(rows, 0);
            content.addView(pagedList, new LinearLayout.LayoutParams(-1,0,1));
            if (!rows.isEmpty()) content.addView(button("책갈피 비우기", v -> new FontDialogBuilder(this)
                    .setTitle("책갈피를 모두 지울까요?").setMessage("저장한 주소만 지우며 작품이나 읽던 위치는 지우지 않습니다.")
                    .setNegativeButton("취소",null).setPositiveButton("지우기",(d,w)->{
                        prefs.edit().remove("bookmarks").apply(); bookmarks();
                    }).show()));
            status.setText(rows.size()+"개 저장됨 · 이 기기에만 보관합니다");
        } catch (Exception e) { toast("책갈피를 읽지 못했습니다."); }
    }

    private void importText() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("text/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, OPEN_TEXT); }
        catch (android.content.ActivityNotFoundException e) { toast("이 기기에 문서 선택 앱이 없습니다."); }
    }

    private void importSession() {
        new FontDialogBuilder(this).setTitle("PC의 노벨피아 로그인 가져오기")
                .setMessage("PC 브라우저에서 노벨피아에 구글로 로그인한 다음, 동봉한 세션 내보내기 "
                        + "확장 프로그램으로 만든 JSON 파일을 선택하세요.\n\n"
                        + "구글 비밀번호·구글 쿠키는 가져오지 않습니다. 파일은 로그인 권한을 가진 "
                        + "민감한 정보입니다. 채팅·클라우드로 공유하지 말고, 가져온 뒤 PC와 기기의 "
                        + "사본을 삭제하세요.\n\n"
                        + "기존 메모리 세션을 교체합니다. 앱 종료 시 다시 가져와야 하며, "
                        + "서버 정책에 따라 PC 세션을 사용할 수 없을 수도 있습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("파일 선택", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    try { startActivityForResult(intent, OPEN_SESSION); }
                    catch (android.content.ActivityNotFoundException e) {
                        toast("이 기기에 문서 선택 앱이 없습니다.");
                    }
                }).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == OPEN_SESSION) {
            request("세션 파일 검증 및 로그인 확인 중…", () -> {
                client();
                boolean replaced = false;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    http.importSession(in);
                    replaced = true;
                    AccountClient.SessionStatus result = accountClient().sessionStatus();
                    if (!result.isAuthenticated()) http.clearCookies();
                    else credentials.setAutoLoginEnabled(false);
                    return result;
                } catch (Exception error) {
                    if (replaced) http.clearCookies();
                    // Neither ContentResolver errors nor JSON parser errors may expose secrets.
                    throw new java.io.IOException("세션을 가져오지 못했습니다. 24시간 이내에 내보낸 파일, "
                            + "기기 날짜·시간, 인터넷 연결을 확인하세요.");
                }
            }, result -> {
                chapter = null;
                welcome();
                new FontDialogBuilder(this)
                        .setTitle(result.isAuthenticated() ? "로그인 확인됨" : "로그인 미확인")
                        .setMessage(result.message + "\n\n가져온 세션 파일의 PC·기기 사본을 삭제하세요.")
                        .setPositiveButton(result.isAuthenticated() ? "연동 검사" : "확인",
                                (d, w) -> { if (result.isAuthenticated()) checkAccountLink(); }).show();
            });
            return;
        }
        if (requestCode != OPEN_TEXT) return;
        request("TXT 여는 중…", () -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("파일을 열 수 없습니다.");
                return makeChapter("내 UTF-8 TXT", LocalText.read(in), "local:import");
            }
        }, this::showChapter);
    }

    private Chapter makeChapter(String title, String text, String url) {
        return new Chapter(title, text, url, null, null);
    }

    private void demo() {
        ++generation; loading = false;
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 24; i++) {
            text.append(i).append(". 조용한 도서관\n\n")
                    .append("창가에 앉아 책장을 펼쳤다. 흰 종이 위의 검은 글자는 천천히 이야기가 되었다.\n")
                    .append("이 글은 리더 동작을 확인하기 위해 작성한 샘플입니다. ")
                    .append("화면 양옆이나 기기의 페이지 키로 넘겨 보세요. 글자 크기와 줄 간격을 바꾸어도 읽던 위치를 유지합니다.\n\n")
                    .append("화면은 한 페이지씩 그리며, 스크롤 애니메이션과 배경 다운로드를 사용하지 않습니다.\n\n");
        }
        showChapter(makeChapter("조용한 도서관 · 리더 샘플", text.toString(), "local:demo"));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int token = event.getKeyCode() * 65536 + event.getScanCode();
        if (event.getAction() == KeyEvent.ACTION_UP && consumedKeys.remove(token)) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN || !getWindow().getDecorView().hasWindowFocus()
                || getCurrentFocus() instanceof EditText || keyBindings == null) return super.dispatchKeyEvent(event);
        if (consumedKeys.contains(token)) return true;
        KeyBindings.Action action = keyBindings.actionFor(event.getKeyCode(), event.getScanCode());
        if (action == KeyBindings.Action.NONE) return super.dispatchKeyEvent(event);
        if ((action == KeyBindings.Action.NEXT || action == KeyBindings.Action.PREVIOUS)
                && !reading && pagedList == null) return super.dispatchKeyEvent(event);
        consumedKeys.add(token);
        if (event.getRepeatCount() != 0) return true;
        if (action == KeyBindings.Action.MENU) { if (reading) toggleReaderTools(); else menu(); }
        else if (action == KeyBindings.Action.REFRESH) refresh();
        else if (!loading) {
            boolean forward = action == KeyBindings.Action.NEXT;
            if (reading) turn(forward);
            else if (pagedList != null) {
                boolean moved = forward ? pagedList.next() : pagedList.previous();
                if (!moved) toast(forward ? "마지막 화면입니다." : "첫 화면입니다.");
            }
        }
        return true;
    }

    @Override public void onBackPressed() {
        if (reading && readerTools != null && readerTools.getVisibility() == View.VISIBLE) {
            toggleReaderTools(); return;
        }
        if (!loading && !reading && pagedList != null && pagedList.getScreen() > 0) {
            pagedList.previous(); return;
        }
        ++generation;
        loading = false;
        if (!history.isEmpty()) open(history.get(history.size() - 1), false, true);
        else if (reading) welcome();
        else super.onBackPressed();
    }
    @Override protected void onPause() { saveProgress(); consumedKeys.clear(); super.onPause(); }
    @Override public Object onRetainNonConfigurationInstance() {
        Retained value = new Retained();
        value.loginPending = loginOpening || signingIn || (loginDialog != null && loginDialog.isShowing());
        value.site = site;
        value.http = http;
        value.chapter = reading ? chapter : null;
        value.location = location;
        value.progress = reader == null ? 0f : reader.getProgress();
        value.history = new ArrayList<String>(history);
        value.listScreens = new java.util.LinkedHashMap<String, Integer>(listScreens);
        value.readerReturnUrl = readerReturnUrl;
        value.readerReturnNovelId = readerReturnNovelId;
        value.readerReturnPage = readerReturnPage;
        value.readerReturnScreen = readerReturnScreen;
        value.listNovelId = listNovelId;
        value.listEpisodePage = listEpisodePage;
        value.listScreen = pagedList == null ? 0 : pagedList.getScreen();
        return value;
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (loginDialog != null) loginDialog.dismiss();
        ++generation;
        io.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout vertical() { LinearLayout v = new LinearLayout(this); v.setOrientation(1); return v; }
    private LinearLayout horizontal() { LinearLayout v = new LinearLayout(this); v.setOrientation(0); return v; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, dp(52), 1); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView v = new TextView(this); v.setText(text); v.setTextColor(Color.BLACK); v.setTextSize(size);
        v.setTypeface(AppFont.get(this)); return v;
    }
    private Button button(String text, View.OnClickListener action) {
        return InkUi.button(this, text, action);
    }
    private Button menuButton(View.OnClickListener action) {
        Button button = button("", action);
        InkUi.quiet(button);
        android.graphics.drawable.Drawable glyph = new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(android.graphics.Canvas canvas) {
                paint.setColor(Color.BLACK); paint.setStrokeWidth(dp(2));
                android.graphics.Rect r = getBounds();
                for (int i=0; i<3; i++) { float y=r.top + dp(6+i*6); canvas.drawLine(r.left+dp(3),y,r.right-dp(3),y,paint); }
            }
            @Override public void setAlpha(int a) { paint.setAlpha(a); }
            @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
        glyph.setBounds(0,0,dp(24),dp(24));
        button.setCompoundDrawables(glyph,null,null,null);
        button.setContentDescription("메뉴");
        return button;
    }
    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this); e.setHint(hint); e.setTextColor(Color.BLACK); e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        e.setSaveEnabled(false);
        e.setTypeface(AppFont.get(this));
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        e.setMinHeight(dp(48));
        android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
        outline.setColor(Color.WHITE); outline.setStroke(dp(1), Color.DKGRAY); e.setBackground(outline);
        return e;
    }
    private void toast(String message) {
        Toast value = Toast.makeText(this, message, Toast.LENGTH_LONG);
        AppFont.apply(value.getView());
        value.show();
    }
}
