package me.crema.novelia;

import android.app.Instrumentation;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.crema.novelia.account.LibraryPage;
import me.crema.novelia.account.CredentialStore;
import me.crema.novelia.display.DisplayProfile;
import me.crema.novelia.display.DisplaySettingsDialog;
import me.crema.novelia.display.DisplayTuningLayout;
import me.crema.novelia.input.KeyBindings;
import me.crema.novelia.input.KeyMappingDialog;
import me.crema.novelia.net.NativeHttp;
import me.crema.novelia.reader.ReaderView;
import me.crema.novelia.site.SiteClient;
import me.crema.novelia.ui.PagedListView;
import me.crema.novelia.ui.InkUi;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Standalone API 19 instrumentation smoke test, including synthetic Activity UI checks.
 * Pass -e skipNetwork true to run the deterministic checks without internet access.
 * Result keys: reader, rejectHttp, rejectForeign, tlsGet, siteBrowse, passed,
 * failed, skipped and overall. Each check is PASS, FAIL: <exception>, or SKIP.
 */
public final class SmokeInstrumentation extends Instrumentation {
    private Bundle arguments;
    private final Bundle results = new Bundle();
    private int passed;
    private int failed;
    private int skipped;
    /** Activity launched by freshActivity(); shared by synthetic UI scenarios. */
    private final Activity[] activity = new Activity[1];

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override
    public void onStart() {
        super.onStart();
        Object publicationScreenshots = arguments == null
                ? null : arguments.get("publicationScreenshots");
        if (Boolean.TRUE.equals(publicationScreenshots)
                || "true".equalsIgnoreCase(String.valueOf(publicationScreenshots))) {
            runPublicationScreenshotsOnly();
            return;
        }
        try {
            // All Activities auto-login on create when production prefs are enabled.
            // Refuse before launch and skip the synthetic UI rather than ever
            // reading real saved credentials.
            refuseProductionCredentials();
            runCheck("reader", new Check() {
                @Override public void run() throws Exception { checkReader(); }
            });
            runCheck("sessionImport", new Check() {
                @Override public void run() throws Exception {
                    me.crema.novelia.net.SyntheticCookieChecks.run();
                }
            });
            runCheck("credentialStore", new Check() {
                // Synthetic isolated-namespace keystore roundtrip (see
                // checkCredentialStoreCrypto): production credentials are never
                // read or written.
                @Override public void run() throws Exception { checkCredentialStoreCrypto(); }
            });
            runCheck("uiHomeLibrary", new Check() {
                @Override public void run() throws Exception { checkHomeAndLibraryUi(); }
            });
            runCheck("uiLoginNoCredentials", new Check() {
                @Override public void run() throws Exception { checkLoginFields(); }
            });
            runCheck("uiReaderKeys", new Check() {
                @Override public void run() throws Exception { checkReaderKeysAndTools(); }
            });
            runCheck("uiReaderPhysicalInput", new Check() {
                @Override public void run() throws Exception {
                    freshActivity();
                    try {
                        runOnMainSync(() -> {
                            try {
                                Method demo = MainActivity.class.getDeclaredMethod("demo");
                                demo.setAccessible(true);
                                demo.invoke(activity[0]);
                            } catch (Exception e) { throw new RuntimeException(e); }
                        });
                        waitForIdleSync();
                        ReaderInputChecks.run(SmokeInstrumentation.this, activity[0]);
                    } finally { finishMainActivity(); }
                }
            });
            runCheck("uiStartupLogin", new Check() {
                @Override public void run() throws Exception {
                    android.content.Intent intent = new android.content.Intent(getTargetContext(), MainActivity.class);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    Activity result = startActivitySync(intent);
                    try {
                        awaitStartupLogin(result, false);
                        UiStateChecks.runStartup(SmokeInstrumentation.this, result);
                    } finally { runOnMainSync(() -> result.finish()); }
                }
            });
            runCheck("uiLoadingLayout", new Check() {
                @Override public void run() throws Exception {
                    freshActivity();
                    try { UiStateChecks.runLoading(SmokeInstrumentation.this, activity[0]); }
                    finally { finishMainActivity(); }
                }
            });
            runCheck("uiAdultMode", new Check() {
                @Override public void run() throws Exception {
                    freshActivity();
                    try { AdultModeChecks.run(SmokeInstrumentation.this, activity[0]); }
                    finally { finishMainActivity(); }
                }
            });
            runCheck("uiLatestEpisode", new Check() {
                @Override public void run() throws Exception {
                    freshActivity();
                    try { LatestEpisodeUiChecks.run(SmokeInstrumentation.this, activity[0]); }
                    finally { finishMainActivity(); }
                }
            });
            runCheck("uiReaderReturn", new Check() {
                @Override public void run() throws Exception { checkReaderReturn(); }
            });
            runCheck("uiSettingsDialogs", new Check() {
                @Override public void run() throws Exception { checkSettingsDialogs(); }
            });
            runCheck("displayTransform", new Check() {
                @Override public void run() throws Exception { checkDisplayTransform(); }
            });
            runCheck("uiButtonContrast", new Check() {
                @Override public void run() throws Exception { checkButtonContrast(); }
            });
            final NativeHttp http = new NativeHttp(getTargetContext());
            runCheck("rejectHttp", new Check() {
                @Override public void run() throws Exception {
                    expectIOException(http, "http://novelpia.com/");
                }
            });
            runCheck("rejectForeign", new Check() {
                @Override public void run() throws Exception {
                    expectIOException(http, "https://example.com/");
                }
            });
            if (arguments != null
                    && Boolean.parseBoolean(arguments.getString("skipNetwork", "false"))) {
                results.putString("tlsGet", "SKIP");
                results.putString("siteBrowse", "SKIP");
                results.putString("ajaxFavoritesSignedOut", "SKIP");
                skipped += 3;
            } else {
                runCheck("tlsGet", new Check() {
                    @Override public void run() throws Exception {
                        String body = http.get("https://novelpia.com/");
                        require(body != null && body.length() > 0, "empty HTTPS response");
                    }
                });
                runCheck("siteBrowse", new Check() {
                    @Override public void run() throws Exception {
                        List<SiteClient.Entry> entries = new SiteClient(http)
                                .browse("https://book.novelpia.com/webnovel/serial");
                        require(entries != null && entries.size() > 0, "empty catalog");
                        results.putInt("siteBrowse.count", entries.size());
                    }
                });
                runCheck("ajaxFavoritesSignedOut", new Check() {
                    @Override public void run() throws Exception {
                        String body = http.postAjax("https://novelpia.com/proc/mybook",
                                java.util.Collections.singletonMap("mode", "favorite_list"));
                        int code;
                        try { code = new org.json.JSONObject(body).optInt("status", -1); }
                        catch (org.json.JSONException invalid) {
                            throw new IOException("favorites response is not JSON");
                        }
                        require(code == 401, "anonymous favorite_list was not rejected with status 401");
                    }
                });
                if (arguments != null && arguments.containsKey("novelId")) {
                    runCheck("latestEpisode", new Check() {
                        @Override public void run() throws Exception {
                            String label = new me.crema.novelia.account.LatestEpisodeClient(http)
                                    .latestLabel("https://novelpia.com/novel/" + arguments.getString("novelId"));
                            require(label.matches("EP\\.[0-9]+"), "latest episode label unavailable");
                            results.putString("latestEpisode.label", label);
                        }
                    });
                    runCheck("episodes", new Check() {
                        @Override public void run() throws Exception {
                            List<SiteClient.Entry> rows = new SiteClient(http)
                                    .episodes(arguments.getString("novelId"), 0);
                            require(!rows.isEmpty(), "empty episode list");
                            results.putInt("episodes.count", rows.size());
                        }
                    });
                }
                if (arguments != null && arguments.containsKey("searchTerm")) {
                    runCheck("search", new Check() {
                        @Override public void run() throws Exception {
                            String term = java.net.URLEncoder.encode(arguments.getString("searchTerm"), "UTF-8");
                            List<SiteClient.Entry> rows = new SiteClient(http)
                                    .browse("https://novelpia.com/search?search_string=" + term);
                            require(!rows.isEmpty(), "empty search results");
                            results.putInt("search.count", rows.size());
                        }
                    });
                }
                if (arguments != null && arguments.containsKey("viewerUrl")) {
                    runCheck("chapter", new Check() {
                        @Override public void run() throws Exception {
                            SiteClient.Chapter chapter = new SiteClient(http)
                                    .readChapter(arguments.getString("viewerUrl"));
                            require(chapter.text != null && chapter.text.trim().length() > 100,
                                    "no readable chapter body");
                            if (arguments.getString("viewerUrl").endsWith("/5890523")) {
                                require(!chapter.title.startsWith("노벨피아 -"), "public title prefix retained");
                                require(!chapter.episodeTitle.isEmpty(), "observed public episode title missing");
                                require(!chapter.nextUrl.isEmpty() && !chapter.novelUrl.isEmpty(),
                                        "public next/list metadata missing");
                            }
                            results.putInt("chapter.characters", chapter.text.length());
                            // Do not persist, print, screenshot, or share the chapter text.
                        }
                    });
                }
            }
        } catch (Throwable error) {
            // Includes failures constructing Conscrypt: still publish every result.
            results.putString("setup", "FAIL: " + error.getClass().getName());
            failed++;
        } finally {
            String[] names = {"reader", "rejectHttp", "rejectForeign", "tlsGet", "siteBrowse"};
            for (String name : names) {
                if (!results.containsKey(name)) {
                    results.putString(name, "SKIP: setup failed");
                    skipped++;
                }
            }
            results.putInt("passed", passed);
            results.putInt("failed", failed);
            results.putInt("skipped", skipped);
            results.putString("overall", failed == 0 ? "PASS" : "FAIL");
            finish(Activity.RESULT_OK, results);
        }
    }

    /** Run only the opt-in publication capture path, without network checks. */
    private void runPublicationScreenshotsOnly() {
        try {
            // MainActivity may auto-login on launch. Refuse before opening it
            // if the app namespace contains any saved account credentials.
            refuseProductionCredentials();
            runCheck("publicationScreenshots", new Check() {
                @Override public void run() throws Exception {
                    freshActivity();
                    try {
                        PublicationScreenshots.run(SmokeInstrumentation.this, activity[0]);
                    } finally {
                        finishMainActivity();
                    }
                }
            });
        } catch (Throwable error) {
            results.putString("setup", "FAIL: " + error.getClass().getName());
            failed++;
        }
        results.putInt("passed", passed);
        results.putInt("failed", failed);
        results.putInt("skipped", skipped);
        results.putString("overall", failed == 0 ? "PASS" : "FAIL");
        finish(Activity.RESULT_OK, results);
    }

    private interface Check {
        void run() throws Exception;
    }

    /** Explicit welcome and synthetic library UI drawing; no account data or network. */
    private void checkHomeAndLibraryUi() throws Exception {
        final Throwable[] failure = new Throwable[1];
        try {
            checkRecentTab(failure);
            checkFavoritesSortDialog(failure);
            checkLibraryEmptyState(failure);
        } finally {
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    if (activity[0] != null && !activity[0].isFinishing()) activity[0].finish();
                }
            });
            activity[0] = null;
        }
        if (failure[0] != null) throw new AssertionError("synthetic library UI failed", failure[0]);
    }

    private Activity startScenarioActivity(android.content.Intent intent) {
        Activity result = startActivitySync(intent);
        awaitStartupLogin(result, true);
        return result;
    }

    private void awaitStartupLogin(final Activity result, final boolean dismiss) {
        long deadline = android.os.SystemClock.uptimeMillis() + 10000;
        final boolean[] ready = new boolean[1];
        do {
            runOnMainSync(() -> {
                try {
                    AlertDialog dialog = (AlertDialog) field(result, "loginDialog");
                    ready[0] = dialog != null && dialog.isShowing();
                    if (ready[0] && dismiss) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            if (ready[0]) { waitForIdleSync(); return; }
            android.os.SystemClock.sleep(20);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("startup login did not appear");
    }

    /** A fresh MainActivity for each synthetic scenario, as parent snapshots do. */
    private void freshActivity() {
        if (activity[0] != null) {
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    if (activity[0] != null && !activity[0].isFinishing()) activity[0].finish();
                }
            });
            activity[0] = null;
        }
        android.content.Intent intent = new android.content.Intent(getTargetContext(), MainActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        activity[0] = startScenarioActivity(intent);
        waitForIdleSync();
    }

    private void finishMainActivity() {
        runOnMainSync(new Runnable() {
            @Override public void run() {
                if (activity[0] != null && !activity[0].isFinishing()) activity[0].finish();
            }
        });
        activity[0] = null;
    }

    private void checkRecentTab(final Throwable[] failure) {
        freshActivity();
        capture(activity[0].getWindow().getDecorView(), "ui-home.png");
        // The welcome has no private currentTitle text (it stays '노벨피아e');
        // assert the brand TextView itself.
        View brand = findTextLabel(activity[0].getWindow().getDecorView(), "노벨피아e");
        require(brand instanceof TextView
                        && "노벨피아e".equals(((TextView) brand).getText().toString().trim()),
                "welcome brand '노벨피아e' missing");
        final LibraryPage[] page = new LibraryPage[1];
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    page[0] = syntheticRecentPage();
                    require(page[0].sorts.isEmpty(),
                            "observed recent /last_view shelf has no sort options");
                    require(page[0].url.contains("/last_view/0/date/1"),
                            "synthetic recent url must be a valid /mybook/last_view/0/date/1 path");
                    Field location = MainActivity.class.getDeclaredField("location");
                    location.setAccessible(true);
                    location.set(activity[0], page[0].url);
                    Method show = MainActivity.class.getDeclaredMethod("showLibrary",
                            LibraryPage.class, int.class);
                    show.setAccessible(true);
                    show.invoke(activity[0], page[0], 0);
                } catch (Throwable error) { failure[0] = error; }
            }
        });
        if (failure[0] != null) return;
        waitForIdleSync();
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    LinearLayout content = (LinearLayout) field(activity[0], "content");
                    LinearLayout tabs = (LinearLayout) content.getChildAt(0);
                    require(tabs != null && tabs.getChildCount() == 4,
                            "library must expose 4 tabs");
                    LinearLayout filter = (LinearLayout) content.getChildAt(1);
                    require(filter != null && filter.getChildCount() == 3,
                            "library filter bar (sort/group/search) missing");
                    Button sortButton = buttonAt(filter, 0);
                    require(sortButton != null && !sortButton.isEnabled(),
                            "recent sort button must be disabled when sorts are empty");
                    require("최근 본 순".equals(sortButton.getText().toString().trim()),
                            "disabled sort button must read '최근 본 순'");
                    Button groupButton = buttonAt(filter, 1);
                    require(groupButton != null && !groupButton.isEnabled(),
                            "recent group button must be disabled when groups are empty");
                    require("전체".equals(groupButton.getText().toString().trim()),
                            "disabled group button must read '전체'");
                    Button searchButton = buttonAt(filter, filter.getChildCount() - 1);
                    require(searchButton != null && searchButton.isEnabled()
                                    && searchButton.getText().toString().trim().contains("서재 검색"),
                            "filter search button must read '서재 검색'");
                    require(tabs.getChildAt(3).isSelected(), "recent tab index 3 not selected");
                    require(((Button) tabs.getChildAt(3)).getText().toString().contains("최근 기록"),
                            "tab 3 is not 최근 기록");
                    require(pager(activity[0]).getScreenCount() > 1, "30 rows did not paginate");
                    require(!pager(activity[0]).getPreviousButton().isEnabled(), "first page previous must be disabled");
                    LinearLayout body = (LinearLayout) pager(activity[0]).getChildAt(0);
                    require(body.getChildCount() > 0, "no rendered recent rows");
                    View firstRow = body.getChildAt(0);
                    require(firstRow.getHeight() > 0, "first rendered row has zero height");
                    require(findTextLabel(firstRow, "합성 작품 1") != null,
                            "first rendered title missing");
                    require(findTextLabel(firstRow, "마지막 읽은 EP.30 · 등록 30편") != null
                                    && findTextLabel(firstRow, "합성 작가") != null
                                    && findExactButton(firstRow, "다음화") != null,
                            "equal-count next-episode metadata missing");
                    require(!containsScrollContainer(activity[0].getWindow().getDecorView()),
                            "library introduced a scrolling container");
                    captureNow(activity[0].getWindow().getDecorView(), "ui-library.png");
                } catch (Throwable error) { failure[0] = error; }
            }
        });
        if (failure[0] != null) return;
        exercisePaging(activity[0], failure);
        if (failure[0] != null) return;
        finishMainActivity();
    }

    private void checkFavoritesSortDialog(final Throwable[] failure) {
        freshActivity();
        final LibraryPage[] like = new LibraryPage[1];
        final Throwable[] dialogError = new Throwable[1];
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    like[0] = syntheticLikePage(true);
                    Field location = MainActivity.class.getDeclaredField("location");
                    location.setAccessible(true);
                    location.set(activity[0], like[0].url);
                    Method show = MainActivity.class.getDeclaredMethod("showLibrary",
                            LibraryPage.class, int.class);
                    show.setAccessible(true);
                    show.invoke(activity[0], like[0], 0);
                } catch (Throwable error) { failure[0] = error; }
            }
        });
        if (failure[0] != null) return;
        waitForIdleSync();
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    LinearLayout content = (LinearLayout) field(activity[0], "content");
                    LinearLayout tabs = (LinearLayout) content.getChildAt(0);
                    require(tabs != null && tabs.getChildCount() == 4 && tabs.getChildAt(0).isSelected()
                    && ((Button) tabs.getChildAt(0)).getText().toString().contains("선호작"),
                            "like shelf tab index 0 (선호작) not selected");
                    LinearLayout filter = (LinearLayout) content.getChildAt(1);
                    Button sortButton = buttonAt(filter, 0);
                    require(sortButton != null && sortButton.isEnabled(),
                            "like sort button missing or disabled");
                    require("공개일자순".equals(sortButton.getText().toString().trim()),
                            "like sort button must read '공개일자순'");
                    Button groupButton = buttonAt(filter, 1);
                    require(groupButton != null && groupButton.isEnabled()
                                    && "전체".equals(groupButton.getText().toString().trim()),
                            "like group button must be enabled and read '전체'");
                    Button searchButton = buttonAt(filter, filter.getChildCount() - 1);
                    require(searchButton != null
                                    && searchButton.getText().toString().trim().contains("서재 검색"),
                            "like search button must read '서재 검색'");
                    require(like[0].url.trim().equals("https://novelpia.com/mybook/like/0/date/1"),
                            "synthetic like url must be a valid /mybook/like/0/date/1 path");
                    captureNow(activity[0].getWindow().getDecorView(), "ui-library-sort.png");
                    // Opening the chooser is local only; no real network sorting here.
                    sortButton.performClick();
                } catch (Throwable error) { failure[0] = error; }
            }
        });
        if (failure[0] != null) return;
        waitForIdleSync();
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    View root = dialogRoot(activity[0], "추천순");
                    require(root != null, "sort selector dialog window not found");
                    String[] sorts = {"공개일자순", "조회순", "추천순", "등록순"};
                    for (String label : sorts) {
                        require(findTextLabel(root, label) != null,
                                "sort selector option missing: " + label);
                    }
                    require(findTextLabel(root, "선택됨") != null,
                            "sort selector must mark the current '선택됨' row");
                    captureNow(root, "ui-sort-selector.png");
                    View cancel = findTextLabel(root, "취소");
                    if (cancel == null) cancel = findTextLabel(root, "닫기");
                    require(cancel != null, "sort selector dismiss control missing");
                    cancel.performClick();
                } catch (Throwable error) { dialogError[0] = error; }
            }
        });
        waitForIdleSync();
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    if (dialogError[0] != null) throw dialogError[0];
                    require(dialogRoot(activity[0], "추천순") == null,
                            "sort selector dialog did not dismiss after cancel");
                    Button unchanged = buttonAt((LinearLayout) ((ViewGroup) field(activity[0], "content")).getChildAt(1), 0);
                    require(unchanged != null
                                    && unchanged.getText().toString().trim().equals("공개일자순"),
                            "filter sort button state changed after cancel");
                } catch (Throwable error) { failure[0] = error; }
            }
        });
        if (failure[0] != null) return;
        finishMainActivity();
    }

    private void checkLibraryEmptyState(final Throwable[] failure) {
        freshActivity();
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    LibraryPage empty = syntheticLikePage(false);
                    Field location = MainActivity.class.getDeclaredField("location");
                    location.setAccessible(true);
                    location.set(activity[0], empty.url);
                    Method show = MainActivity.class.getDeclaredMethod("showLibrary",
                            LibraryPage.class, int.class);
                    show.setAccessible(true);
                    show.invoke(activity[0], empty, 0);
                } catch (Throwable error) { failure[0] = error; }
            }
        });
        if (failure[0] != null) return;
        waitForIdleSync();
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    PagedListView list = pager(activity[0]);
                    require(list.getScreenCount() == 0,
                            "empty library must have zero local screens");
                    View emptyLabel = findTextLabel(activity[0].getWindow().getDecorView(), "등록된 작품이 없습니다");
                    require(emptyLabel instanceof TextView
                                    && ((TextView) emptyLabel).getText().toString().contains("등록된 작품이 없습니다"),
                            "library empty state text missing");
                    captureNow(activity[0].getWindow().getDecorView(), "ui-library-empty.png");
                } catch (Throwable error) { failure[0] = error; }
            }
        });
        if (failure[0] != null) return;
        finishMainActivity();
    }

    private void exercisePaging(final Activity act, final Throwable[] failure) {
        final int[] boundaries = new int[2];
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    PagedListView list = pager(act);
                    list.setBoundaryActions(new Runnable() {
                        @Override public void run() { boundaries[0]++; }
                    }, new Runnable() {
                        @Override public void run() { boundaries[1]++; }
                    });
                    require(list.getPreviousButton().isEnabled(),
                            "previous should have boundary action");
                    int initialScreen = list.getScreen();
                    list.getNextButton().performClick();
                    require(list.getScreen() == initialScreen + 1,
                            "local next did not advance");
                    require(boundaries[1] == 0, "local next incorrectly invoked boundary");
                    list.getPreviousButton().performClick();
                    require(list.getScreen() == initialScreen,
                            "local previous did not return");
                    require(boundaries[0] == 0, "local previous incorrectly invoked boundary");
                } catch (Throwable error) { failure[0] = error; }
            }
        });
        if (failure[0] != null) return;
        while (true) {
            final boolean[] moved = new boolean[1];
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        if (pager(act).getScreen() + 1 < pager(act).getScreenCount()) {
                            pager(act).getNextButton().performClick();
                            moved[0] = true;
                        }
                    } catch (Throwable error) { failure[0] = error; return; }
                }
            });
            if (failure[0] != null) return;
            if (!moved[0]) break;
        }
        waitForIdleSync();
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    require(pager(act).getNextButton().isEnabled(),
                            "next boundary button disabled");
                    captureNow(act.getWindow().getDecorView(), "ui-library-last.png");
                    pager(act).getNextButton().performClick();
                    require(boundaries[1] == 1, "next boundary callback not invoked");
                    while (pager(act).getScreen() > 0) pager(act).getPreviousButton().performClick();
                    pager(act).getPreviousButton().performClick();
                    require(boundaries[0] == 1, "previous boundary callback not invoked");
                } catch (Throwable error) { failure[0] = error; }
            }
        });
    }

    /** 30 recent rows with 읽은 12화 / 전체 30화 · 합성 작가; URL /mybook/last_view/0/date/1. */
    private LibraryPage syntheticRecentPage() {
        List<LibraryPage.Item> rows = new ArrayList<LibraryPage.Item>();
        for (int i = 1; i <= 30; i++) {
            String id = String.valueOf(100 + i);
            rows.add(new LibraryPage.Item(new SiteClient.Entry(
                    "합성 작품 " + i, "https://novelpia.com/novel/" + id, "novel", ""),
                    "https://novelpia.com/viewer/" + id, i == 1 ? 30 : 12, 30, "합성 작가", i == 1 ? "321" : ""));
        }
        // The observed official recent /last_view shelf has no sort/group options, so
        // the filter bar shows disabled '최근 본 순' and '전체' buttons. Valid /0/date/1 route.
        return new LibraryPage(rows, 1,
                "https://novelpia.com/mybook/last_view/0/date/1",
                "",
                "https://novelpia.com/mybook/last_view/0/date/2",
                "last_view", "0", "date", "",
                Collections.<LibraryPage.Option>emptyList(),
                Collections.<LibraryPage.Option>emptyList());
    }

    /** Synthetic like shelf with sort options and 30 rows (or empty); URL /mybook/like/0/date/1. */
    private LibraryPage syntheticLikePage(boolean withRows) {
        List<LibraryPage.Item> rows = new ArrayList<LibraryPage.Item>();
        if (withRows) {
            for (int i = 1; i <= 30; i++) {
                String id = String.valueOf(200 + i);
                rows.add(new LibraryPage.Item(new SiteClient.Entry(
                        "합성 선호작 " + i, "https://novelpia.com/novel/" + id, "novel", ""),
                        "https://novelpia.com/viewer/" + id, 12, 30, "합성 작가"));
            }
        }
        List<LibraryPage.Option> sorts = new ArrayList<LibraryPage.Option>();
        sorts.add(new LibraryPage.Option("date", "공개일자순"));
        sorts.add(new LibraryPage.Option("view", "조회순"));
        sorts.add(new LibraryPage.Option("list", "등록순"));
        sorts.add(new LibraryPage.Option("vote", "추천순"));
        List<LibraryPage.Option> groups = new ArrayList<LibraryPage.Option>();
        groups.add(new LibraryPage.Option("0", "전체"));
        groups.add(new LibraryPage.Option("-1", "미분류"));
        return new LibraryPage(rows, 1,
                "https://novelpia.com/mybook/like/0/date/1",
                "",
                withRows ? "https://novelpia.com/mybook/like/0/date/2" : "",
                "like", "0", "date", "",
                sorts, groups);
    }

    private Object field(Activity act, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(act);
    }

    private PagedListView pager(Activity act) throws Exception {
        Object value = field(act, "pagedList");
        require(value instanceof PagedListView, "library pager missing");
        return (PagedListView) value;
    }

    private static Button buttonAt(LinearLayout row, int index) {
        View view = row.getChildAt(index);
        return view instanceof Button ? (Button) view : null;
    }

    /** Deep search for a TextView whose text contains the fragment (buttons included). */
    private static View findTextLabel(View root, String fragment) {
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null && text.toString().contains(fragment)) return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findTextLabel(group.getChildAt(i), fragment);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static Button findExactButton(View root, String label) {
        if (root instanceof Button && label.equals(((Button) root).getText().toString())) return (Button) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findExactButton(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * AlertDialog windows live in their own WindowManager roots, not in the
     * activity decor. Walk WindowManagerGlobal roots (API 19 reflection) and
     * return the first window root whose tree contains the text fragment.
     */
    private static View dialogRoot(Activity act, String textFragment) {
        try {
            final Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            final Method instance = wmgClass.getMethod("getInstance");
            final Object wmg = instance.invoke(null);
            final Field roots = wmgClass.getDeclaredField("mRoots");
            roots.setAccessible(true);
            final java.util.ArrayList<?> list = (java.util.ArrayList<?>) roots.get(wmg);
            final View mainRoot = act.getWindow().getDecorView();
            for (Object item : list) {
                if (item == null) continue;
                try {
                    final Method getView = item.getClass().getMethod("getView");
                    final View view = (View) getView.invoke(item);
                    if (view == null || view == mainRoot) continue;
                    if (findTextLabel(view, textFragment) != null) return view;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Refuses to launch any Activity when production credentials could trigger
     * auto-login or be read: the whole synthetic UI is skipped instead. */
    private void refuseProductionCredentials() {
        CredentialStore production = new CredentialStore(getTargetContext());
        require(!production.isAutoLoginEnabled() && !production.hasSaved(),
                "refusing synthetic UI: production auto-login/saved credentials present");
    }

    private void checkLoginFields() throws Exception {
        refuseProductionCredentials();
        freshActivity();
        final Throwable[] failure = new Throwable[1];
        try {
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        Method show = MainActivity.class.getDeclaredMethod(
                                "showLoginFields", String.class, String.class);
                        show.setAccessible(true);
                        show.invoke(activity[0], "", ""); // EMPTY: no real credentials.
                    } catch (Throwable error) { throw new RuntimeException(error); }
                }
            });
            waitForIdleSync();
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        View root = dialogRoot(activity[0], "비밀번호 저장");
                        require(root != null, "login dialog window not found");
                        Button remember = findButton(root, "비밀번호 저장");
                        Button automatic = findButton(root, "자동 로그인");
                        require(remember != null && automatic != null,
                                "save/autologin toggle buttons missing");
                        require(remember.getText().toString().contains("끔"),
                                "save toggle must start off with no saved credentials");
                        require(automatic.getText().toString().contains("끔"),
                                "autologin toggle must start off");
                        captureNow(root, "ui-login.png");
                        remember.performClick();
                        require(remember.getText().toString().contains("켬"),
                                "save toggle did not enable");
                        automatic.performClick();
                        require(automatic.getText().toString().contains("켬"),
                                "autologin toggle did not enable");
                        automatic.performClick();
                        require(automatic.getText().toString().contains("끔"),
                                "autologin toggle did not disable");
                        remember.performClick();
                        require(remember.getText().toString().contains("끔"),
                                "save toggle did not disable");
                        Button cancel = findButton(root, "취소");
                        require(cancel != null, "login cancel button missing");
                        cancel.performClick();
                    } catch (Throwable error) { failure[0] = error; }
                }
            });
            waitForIdleSync();
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        if (failure[0] != null) throw failure[0];
                        require(dialogRoot(activity[0], "비밀번호 저장") == null,
                                "login dialog did not dismiss after cancel");
                    } catch (Throwable error) { failure[0] = error; }
                }
            });
            if (failure[0] != null) throw new AssertionError("synthetic login form failed", failure[0]);
            refuseProductionCredentials();
        } finally {
            finishMainActivity();
            final CredentialStore synthetic = new CredentialStore(getTargetContext(), "synthetic_test");
            try { synthetic.clear(); } catch (Throwable ignored) { }
        }
    }

    private void checkReaderKeysAndTools() throws Exception {
        android.content.Intent intent = new android.content.Intent(getTargetContext(), MainActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        final Activity activity = startScenarioActivity(intent);
        waitForIdleSync();
        final Throwable[] failure = new Throwable[1];
        try {
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        Method demo = MainActivity.class.getDeclaredMethod("demo");
                        demo.setAccessible(true);
                        demo.invoke(activity);
                        SiteClient.Chapter sample = (SiteClient.Chapter) field(activity, "chapter");
                        Method show = MainActivity.class.getDeclaredMethod("showChapter", SiteClient.Chapter.class);
                        show.setAccessible(true);
                        show.invoke(activity, new SiteClient.Chapter("조용한 도서관", sample.text,
                                "local:demo", "", "", "문이 열리는 아침", "EP.1", ""));
                    } catch (Throwable error) { failure[0] = error; }
                }
            });
            waitForIdleSync();
            final View decor = activity.getWindow().getDecorView();
            final int width = decor.getWidth(), height = decor.getHeight();
            final float[] progress = new float[1];
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        Field readerField = MainActivity.class.getDeclaredField("reader");
                        readerField.setAccessible(true);
                        ReaderView reader = (ReaderView) readerField.get(activity);
                        require(reader != null, "demo reader missing");
                        require(findTextLabel(decor, "EP.1 · 문이 열리는 아침") != null, "episode header missing");
                        require(findTextLabel(decor, "양옆은 넘김 · 가운데는 메뉴") == null, "obsolete hint visible");
                        require(findTextLabel(decor, "목록") != null, "reader list button missing");
                        progress[0] = reader.getProgress();
                        captureNow(decor, "ui-reader.png");
                    } catch (Throwable error) { failure[0] = error; }
                }
            });
            require(failure[0] == null, "reader initial capture: " + failure[0]);
            KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN);
            KeyEvent repeat = new KeyEvent(0, 1, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_PAGE_DOWN, 1);
            KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN);
            final boolean[] focused = new boolean[1];
            runOnMainSync(new Runnable() {
                @Override public void run() { focused[0] = activity.getWindow().getDecorView().hasWindowFocus(); }
            });
            require(focused[0], "reader activity did not acquire window focus for key dispatch");
            require(dispatchKey(activity, down), "PAGE_DOWN was not handled");
            waitForIdleSync();
            final float progressAfterDown = readerProgress(activity);
            require(dispatchKey(activity, repeat), "repeated PAGE_DOWN was not consumed");
            waitForIdleSync();
            final float progressAfterRepeat = readerProgress(activity);
            require(Math.abs(progressAfterRepeat - progressAfterDown) < 0.0001f,
                    "repeat PAGE_DOWN advanced more than once");
            require(dispatchKey(activity, up), "PAGE_DOWN up was not consumed");
            require(progressAfterDown > progress[0], "PAGE_DOWN did not advance reader");
            require(dispatchKey(activity, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP)),
                    "PAGE_UP was not handled");
            require(dispatchKey(activity, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_UP)),
                    "PAGE_UP up was not consumed");
            waitForIdleSync();
            final float[] afterUp = new float[1];
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        Field readerField = MainActivity.class.getDeclaredField("reader");
                        readerField.setAccessible(true);
                        ReaderView reader = (ReaderView) readerField.get(activity);
                        afterUp[0] = reader.getProgress();
                        Field toolsField = MainActivity.class.getDeclaredField("readerTools");
                        toolsField.setAccessible(true);
                        View tools = (View) toolsField.get(activity);
                        require(tools != null && tools.getVisibility() == View.GONE,
                                "reader tools should start hidden");
                        require(reader.getWidth() > 0 && reader.getHeight() > 0, "reader has no bounds");
                        long now = android.os.SystemClock.uptimeMillis();
                        float x = reader.getWidth() / 2f, y = reader.getHeight() / 2f;
                        MotionEvent touchDown = MotionEvent.obtain(now, now,
                                MotionEvent.ACTION_DOWN, x, y, 0);
                        MotionEvent touchUp = MotionEvent.obtain(now, now + 10,
                                MotionEvent.ACTION_UP, x, y, 0);
                        try {
                            require(reader.dispatchTouchEvent(touchDown), "center touch down not handled");
                            require(reader.dispatchTouchEvent(touchUp), "center touch up not handled");
                        } finally {
                            touchDown.recycle();
                            touchUp.recycle();
                        }
                        require(tools.getVisibility() == View.VISIBLE, "center tap did not show reader tools");
                    } catch (Throwable error) { failure[0] = error; }
                }
            });
            waitForIdleSync();
            capture(decor, "ui-reader-tools.png");
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        Field readerField = MainActivity.class.getDeclaredField("reader");
                        readerField.setAccessible(true);
                        ReaderView reader = (ReaderView) readerField.get(activity);
                        Field toolsField = MainActivity.class.getDeclaredField("readerTools");
                        toolsField.setAccessible(true);
                        View tools = (View) toolsField.get(activity);
                        float saved = reader.getProgress();
                        int readerWidth = reader.getWidth(), readerHeight = reader.getHeight();
                        require(decor.getWidth() == width && decor.getHeight() == height,
                                "reader tools changed root bounds");
                        require(readerWidth > 0 && readerHeight > 0, "reader bounds were empty");
                        reader.performClick();
                        require(tools.getVisibility() == View.GONE, "reader tap did not hide tools");
                        reader.performClick();
                        require(tools.getVisibility() == View.VISIBLE, "reader tap did not reopen tools");
                        require(reader.getWidth() == readerWidth && reader.getHeight() == readerHeight,
                                "reader tools changed reader bounds");
                        require(Math.abs(reader.getProgress() - saved) < 0.0001f,
                                "opening/closing reader tools changed progress");
                        reader.performClick();
                        View list = findTextLabel(decor, "목록");
                        require(list != null && list.performClick(), "list button not clickable");
                        require(!(Boolean) field(activity, "reading"), "local reader did not return home");
                    } catch (Throwable error) { failure[0] = error; }
                }
            });
            require(Math.abs(afterUp[0] - progress[0]) < 0.02f,
                    "PAGE_UP did not restore reader progress");
            require(failure[0] == null, "reader tools interaction failed: " + failure[0]);
        } finally {
            runOnMainSync(new Runnable() {
                @Override public void run() { activity.finish(); }
            });
        }
        if (failure[0] != null) throw new AssertionError("reader interaction failed", failure[0]);
    }

    private void checkReaderReturn() throws Exception {
        freshActivity();
        final Throwable[] failure = new Throwable[1];
        final me.crema.novelia.site.NavigationFixture fixture = new me.crema.novelia.site.NavigationFixture();
        try {
            runOnMainSync(() -> {
                try {
                    Field site = MainActivity.class.getDeclaredField("site"); site.setAccessible(true);
                    site.set(activity[0], fixture.client);
                    Field location = MainActivity.class.getDeclaredField("location"); location.setAccessible(true);
                    location.set(activity[0], "https://novelpia.com/novel/999");
                    Method show = MainActivity.class.getDeclaredMethod("showRows", String.class, List.class,
                            String.class, int.class, int.class);
                    show.setAccessible(true);
                    show.invoke(activity[0], "회차 목록", fixture.client.episodes("999", 2), "999", 2, 1);
                } catch (Throwable e) { failure[0] = e; }
            });
            waitForIdleSync();
            require(failure[0] == null, "return fixture setup failed");
            require(pager(activity[0]).getScreen() == 1, "origin local screen missing");
            for (final String url : new String[]{"https://novelpia.com/viewer/101", "https://novelpia.com/viewer/102"}) {
                runOnMainSync(() -> {
                    try {
                        Method open = MainActivity.class.getDeclaredMethod("open", String.class, boolean.class);
                        open.setAccessible(true); open.invoke(activity[0], url, true);
                    } catch (Throwable e) { failure[0] = e; }
                });
                awaitRequest();
                if (failure[0] != null) throw new AssertionError("synthetic open failed", failure[0]);
            }
            runOnMainSync(() -> {
                try {
                    View decor = activity[0].getWindow().getDecorView();
                    require("합성 작품".equals(field(activity[0], "currentTitle")), "brand slogan leaked into title");
                    require(findTextLabel(decor, "EP.30 · 목록 복귀 확인") != null, "chapter title missing");
                    require(((View) field(activity[0], "readerNext")).getVisibility() == View.VISIBLE,
                            "last-page next action missing");
                    require(findTextLabel(decor, "마지막 페이지") != null, "last-page label missing");
                    captureNow(decor, "ui-reader-end.png");
                    require(Integer.valueOf(2).equals(field(activity[0], "readerReturnPage")), "server page lost");
                    require(Integer.valueOf(1).equals(field(activity[0], "readerReturnScreen")), "local screen lost");
                    Object retained = activity[0].onRetainNonConfigurationInstance();
                    Field retainedPage = retained.getClass().getDeclaredField("readerReturnPage");
                    retainedPage.setAccessible(true);
                    require(retainedPage.getInt(retained) == 2, "rotation return page missing");
                    require(findExactButton(decor, "목록").performClick(), "list click failed");
                } catch (Throwable e) { failure[0] = e; }
            });
            if (failure[0] != null) throw new AssertionError("before list return", failure[0]);
            awaitRequest();
            runOnMainSync(() -> {
                try {
                    require(!(Boolean) field(activity[0], "reading"), "still reading after list click");
                    require(fixture.requestedPage == 2, "wrong episode server page");
                    require(pager(activity[0]).getScreen() == 1, "wrong episode local screen");
                    require(((List<?>) field(activity[0], "history")).isEmpty(), "chapter history not consumed");
                    captureNow(activity[0].getWindow().getDecorView(), "ui-reader-return.png");
                } catch (Throwable e) { failure[0] = e; }
            });
            if (failure[0] != null) throw new AssertionError("reader return failed", failure[0]);
        } finally { finishMainActivity(); }
    }

    private void awaitRequest() throws Exception {
        for (int i = 0; i < 100; i++) {
            waitForIdleSync();
            final boolean[] loading = new boolean[1];
            runOnMainSync(() -> {
                try { loading[0] = (Boolean) field(activity[0], "loading"); }
                catch (Exception e) { throw new AssertionError(e); }
            });
            if (!loading[0]) { waitForIdleSync(); return; }
            Thread.sleep(50);
        }
        throw new AssertionError("synthetic request timed out");
    }

    private void checkButtonContrast() {
        runOnMainSync(new Runnable() {
            @Override public void run() {
                int[][] states = {
                    { android.R.attr.state_enabled },
                    { android.R.attr.state_enabled, android.R.attr.state_pressed },
                    { android.R.attr.state_enabled, android.R.attr.state_focused },
                    { -android.R.attr.state_enabled }
                };
                for (int style = 0; style < 3; style++) {
                    Button button = InkUi.button(getTargetContext(), "확인", null);
                    if (style == 1) InkUi.primary(button);
                    if (style == 2) InkUi.quiet(button);
                    Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
                    try {
                        for (int[] state : states) {
                            android.graphics.drawable.Drawable background = button.getBackground();
                            background.setState(state);
                            background.setBounds(0, 0, 48, 48);
                            background.draw(new Canvas(bitmap));
                            int foreground = button.getTextColors().getColorForState(state, Color.BLACK);
                            require(Math.abs(Color.red(foreground) - Color.red(bitmap.getPixel(8, 8))) >= 80,
                                    "button foreground blends into state background: style " + style);
                        }
                    } finally { bitmap.recycle(); }
                }
            }
        });
    }

    private void checkSettingsDialogs() throws Exception {
        android.content.Intent intent = new android.content.Intent(getTargetContext(), MainActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        final Activity activity = startScenarioActivity(intent);
        waitForIdleSync();
        try {
            final AlertDialog[] dialogs = new AlertDialog[1];
            final int[] displayApplyCalls = new int[1];
            final DisplayProfile[] appliedDisplay = new DisplayProfile[1];
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        Method show = DisplaySettingsDialog.class.getMethod("show",
                                Activity.class, DisplayProfile.class, DisplaySettingsDialog.Callback.class);
                        Object result = show.invoke(null, activity, DisplayProfile.defaults(),
                                new DisplaySettingsDialog.Callback() {
                                    @Override public void onApply(DisplayProfile profile) {
                                        displayApplyCalls[0]++;
                                        appliedDisplay[0] = profile;
                                    }
                                });
                        require(result instanceof AlertDialog,
                                "DisplaySettingsDialog.show must return its AlertDialog");
                        dialogs[0] = (AlertDialog) result;
                    } catch (Throwable error) { throw new RuntimeException(error); }
                }
            });
            waitForIdleSync();
            capture(dialogs[0].getWindow().getDecorView(), "ui-display.png");
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    View decor = dialogs[0].getWindow().getDecorView();
                    Button brightnessPlus = findRowButton(decor, "명도");
                    Button saturationPlus = findRowButton(decor, "채도");
                    require(brightnessPlus != null && saturationPlus != null,
                            "display adjustment controls missing");
                    brightnessPlus.performClick();
                    for (int i = 0; i < 4; i++) saturationPlus.performClick();
                    require(displayApplyCalls[0] == 0, "display staged values applied before Apply");
                }
            });
            waitForIdleSync();
            capture(dialogs[0].getWindow().getDecorView(), "ui-display-adjusted.png");
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    Button cancel = findButton(dialogs[0].getWindow().getDecorView(), "취소");
                    require(cancel != null, "display cancel button missing");
                    cancel.performClick();
                    require(!dialogs[0].isShowing(), "display Cancel did not dismiss");
                    require(displayApplyCalls[0] == 0, "display Cancel invoked Apply callback");
                }
            });

            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        Method show = DisplaySettingsDialog.class.getMethod("show",
                                Activity.class, DisplayProfile.class, DisplaySettingsDialog.Callback.class);
                        Object result = show.invoke(null, activity, DisplayProfile.defaults(),
                                new DisplaySettingsDialog.Callback() {
                                    @Override public void onApply(DisplayProfile profile) {
                                        displayApplyCalls[0]++;
                                        appliedDisplay[0] = profile;
                                    }
                                });
                        require(result instanceof AlertDialog, "display dialog was not returned for Apply");
                        dialogs[0] = (AlertDialog) result;
                    } catch (Throwable error) { throw new RuntimeException(error); }
                }
            });
            waitForIdleSync();
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    View decor = dialogs[0].getWindow().getDecorView();
                    Button brightnessPlus = findRowButton(decor, "명도");
                    Button saturationPlus = findRowButton(decor, "채도");
                    require(brightnessPlus != null && saturationPlus != null,
                            "display adjustment controls missing on Apply dialog");
                    brightnessPlus.performClick();
                    for (int i = 0; i < 4; i++) saturationPlus.performClick();
                    require(displayApplyCalls[0] == 0, "display callback ran before Apply");
                    Button apply = findButton(decor, "적용");
                    require(apply != null, "display Apply button missing");
                    apply.performClick();
                }
            });
            waitForIdleSync();
            require(displayApplyCalls[0] == 1 && appliedDisplay[0] != null
                            && appliedDisplay[0].brightness == 10
                            && appliedDisplay[0].contrast == 100
                            && appliedDisplay[0].saturation == 100,
                    "display Apply callback did not receive staged brightness/contrast/saturation");

            final int[] keyApplyCalls = new int[1];
            final KeyBindings[] appliedBindings = new KeyBindings[1];
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        Method show = KeyMappingDialog.class.getMethod("show",
                                Activity.class, KeyBindings.class, KeyMappingDialog.Callback.class);
                        Object result = show.invoke(null, activity, KeyBindings.defaults(),
                                new KeyMappingDialog.Callback() {
                                    @Override public void onApply(KeyBindings bindings) {
                                        keyApplyCalls[0]++;
                                        appliedBindings[0] = bindings;
                                    }
                                });
                        require(result instanceof AlertDialog,
                                "KeyMappingDialog.show must return its AlertDialog");
                        dialogs[0] = (AlertDialog) result;
                    } catch (Throwable error) { throw new RuntimeException(error); }
                }
            });
            waitForIdleSync();
            capture(dialogs[0].getWindow().getDecorView(), "ui-keys.png");
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    Button clear = findButton(dialogs[0].getWindow().getDecorView(), "해제");
                    require(clear != null, "key dialog clear control missing");
                    clear.performClick(); // Changes only the dialog's staged copy.
                    Button cancel = findButton(dialogs[0].getWindow().getDecorView(), "취소");
                    require(cancel != null, "key dialog cancel button missing");
                    cancel.performClick();
                    require(!dialogs[0].isShowing(), "key dialog cancel did not dismiss");
                    require(keyApplyCalls[0] == 0, "cancel applied a key mapping");
                }
            });
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        Method show = KeyMappingDialog.class.getMethod("show",
                                Activity.class, KeyBindings.class, KeyMappingDialog.Callback.class);
                        Object result = show.invoke(null, activity, KeyBindings.defaults(),
                                new KeyMappingDialog.Callback() {
                                    @Override public void onApply(KeyBindings bindings) {
                                        keyApplyCalls[0]++;
                                        appliedBindings[0] = bindings;
                                    }
                        });
                        require(result instanceof AlertDialog, "key mapping dialog was not returned");
                        dialogs[0] = (AlertDialog) result;
                    } catch (Throwable error) { throw new RuntimeException(error); }
                }
            });
            waitForIdleSync();
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    Button capture = findActionButton(dialogs[0].getWindow().getDecorView(), "이전 페이지");
                    require(capture != null, "previous-page key capture button missing");
                    capture.performClick();
                }
            });
            require(dispatchDialogKey(dialogs[0], new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT)), "captured PAGE-LEFT down was not consumed");
            require(dispatchDialogKey(dialogs[0], new KeyEvent(0, 1, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, 1)), "captured repeat down was not consumed");
            require(dispatchDialogKey(dialogs[0], new KeyEvent(KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_DPAD_LEFT)), "captured key up was not consumed");
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    Button apply = findButton(dialogs[0].getWindow().getDecorView(), "적용");
                    require(apply != null, "key dialog Apply button missing");
                    apply.performClick();
                }
            });
            waitForIdleSync();
            require(keyApplyCalls[0] == 1 && appliedBindings[0] != null,
                    "explicit apply did not return synthetic key bindings");
            require(appliedBindings[0].keyCodeOf(KeyBindings.Action.PREVIOUS)
                            == KeyEvent.KEYCODE_DPAD_LEFT,
                    "captured key was not present in the staged mapping");

            final int[] backCancelApplyCalls = new int[1];
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        Method show = KeyMappingDialog.class.getMethod("show",
                                Activity.class, KeyBindings.class, KeyMappingDialog.Callback.class);
                        dialogs[0] = (AlertDialog) show.invoke(null, activity, KeyBindings.defaults(),
                                new KeyMappingDialog.Callback() {
                                    @Override public void onApply(KeyBindings bindings) {
                                        backCancelApplyCalls[0]++;
                                    }
                                });
                    } catch (Throwable error) { throw new RuntimeException(error); }
                }
            });
            waitForIdleSync();
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    Button capture = findActionButton(dialogs[0].getWindow().getDecorView(), "이전 페이지");
                    require(capture != null, "Back-cancel capture button missing");
                    capture.performClick();
                }
            });
            require(dispatchDialogKey(dialogs[0], new KeyEvent(KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_BACK)), "Back did not cancel in-flight capture");
            require(dispatchDialogKey(dialogs[0], new KeyEvent(KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_BACK)), "Back key-up was not consumed");
            runOnMainSync(new Runnable() {
                @Override public void run() {
                    require(dialogs[0].isShowing(), "Back capture cancel dismissed the dialog");
                    Button cancel = findButton(dialogs[0].getWindow().getDecorView(), "취소");
                    require(cancel != null, "key dialog Cancel button missing after Back");
                    cancel.performClick();
                    require(backCancelApplyCalls[0] == 0, "Back/Cancel applied staged key mapping");
                }
            });
        } finally {
            runOnMainSync(new Runnable() {
                @Override public void run() { activity.finish(); }
            });
        }
    }

    private void checkDisplayTransform() throws Exception {
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(new Runnable() {
            @Override public void run() {
                Bitmap bitmap = null;
                try {
                    DisplayTuningLayout layout = new DisplayTuningLayout(getTargetContext());
                    View gray = new View(getTargetContext());
                    gray.setBackgroundColor(Color.rgb(100, 40, 20));
                    layout.addView(gray, new LinearLayout.LayoutParams(100, 100));
                    layout.setProfile(new DisplayProfile(20, 100, 0));
                    layout.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
                    layout.layout(0, 0, 100, 100);
                    bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
                    layout.draw(new Canvas(bitmap));
                    int pixel = bitmap.getPixel(50, 50);
                    int red = Color.red(pixel), green = Color.green(pixel), blue = Color.blue(pixel);
                    require(red >= 69 && red <= 74 && Math.abs(red - green) <= 1
                                    && Math.abs(red - blue) <= 1,
                            "brightness/saturation transform pixel " + Color.rgb(red, green, blue));
                } catch (Throwable error) { failure[0] = error; }
                finally { if (bitmap != null) bitmap.recycle(); }
            }
        });
        if (failure[0] != null) throw new AssertionError("display transform failed", failure[0]);
    }

    private void capture(final View view, final String name) {
        runOnMainSync(new Runnable() {
            @Override public void run() { captureNow(view, name); }
        });
    }

    private boolean dispatchKey(final Activity activity, final KeyEvent event) {
        final boolean[] handled = new boolean[1];
        runOnMainSync(new Runnable() {
            @Override public void run() { handled[0] = activity.dispatchKeyEvent(event); }
        });
        return handled[0];
    }

    private boolean dispatchDialogKey(final AlertDialog dialog, final KeyEvent event) {
        final boolean[] handled = new boolean[1];
        runOnMainSync(new Runnable() {
            @Override public void run() { handled[0] = dialog.dispatchKeyEvent(event); }
        });
        return handled[0];
    }

    private float readerProgress(final Activity activity) {
        final float[] value = new float[1];
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    Field field = MainActivity.class.getDeclaredField("reader");
                    field.setAccessible(true);
                    value[0] = ((ReaderView) field.get(activity)).getProgress();
                } catch (Throwable error) { failure[0] = error; }
            }
        });
        require(failure[0] == null, "could not read reader progress: " + failure[0]);
        return value[0];
    }

    private static Button findButton(View root, String textFragment) {
        if (root instanceof Button) {
            CharSequence text = ((Button) root).getText();
            if (text != null && text.toString().contains(textFragment)) return (Button) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = findButton(group.getChildAt(i), textFragment);
                if (result != null) return result;
            }
        }
        return null;
    }

    /** Returns the trailing button in a row whose leading label matches. */
    private static Button findRowButton(View root, String labelFragment) {
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            if (group.getChildCount() > 0 && group.getChildAt(0) instanceof TextView
                    && ((TextView) group.getChildAt(0)).getText().toString().contains(labelFragment)) {
                Button last = null;
                for (int i = 1; i < group.getChildCount(); i++) {
                    if (group.getChildAt(i) instanceof Button) last = (Button) group.getChildAt(i);
                }
                if (last != null) return last;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = findRowButton(group.getChildAt(i), labelFragment);
                if (result != null) return result;
            }
        }
        return null;
    }

    /** Returns the first button after an action label in the key-mapping dialog. */
    private static Button findActionButton(View root, String actionLabel) {
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            if (group.getChildCount() > 0 && group.getChildAt(0) instanceof TextView
                    && ((TextView) group.getChildAt(0)).getText().toString().contains(actionLabel)) {
                for (int i = 1; i < group.getChildCount(); i++) {
                    if (group.getChildAt(i) instanceof Button) return (Button) group.getChildAt(i);
                }
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = findActionButton(group.getChildAt(i), actionLabel);
                if (result != null) return result;
            }
        }
        return null;
    }

    void captureNow(View view, String name) {
        Bitmap bitmap = null;
        try {
            require(view != null && view.getWidth() > 0 && view.getHeight() > 0,
                    "view has no drawable bounds for " + name);
            bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            File image = new File(getTargetContext().getCacheDir(), name);
            try (FileOutputStream out = new FileOutputStream(image)) {
                require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "could not encode " + name);
            }
        } catch (IOException error) { throw new AssertionError("capture failed: " + name, error); }
        finally { if (bitmap != null) bitmap.recycle(); }
    }

    private static boolean containsScrollContainer(View view) {
        if (view instanceof android.widget.ScrollView || view instanceof android.widget.HorizontalScrollView
                || view instanceof android.widget.ListView) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsScrollContainer(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /**
     * Synthetic AndroidKeyStore roundtrip in an isolated namespace so it never
     * touches real saved credentials. Also verifies clear() and that a tampered
     * blob fails closed. On API 19 keystores this exercises the fail-closed
     * contract; a locked/unavailable keystore surfaces here instead of in the
     * app. The isolated synthetic_test namespace derives its own prefs and key
     * alias, so production credentials are never read or written.
     */
    private void checkCredentialStoreCrypto() throws Exception {
        final Context context = getTargetContext();
        final CredentialStore store = new CredentialStore(context, "synthetic_test");
        try {
        store.clear();
        require(!store.hasSaved(), "synthetic store not empty after clear");
        require(!store.isAutoLoginEnabled(), "synthetic autologin leaked after clear");

        // Save/load with a leading+trailing-spaced password: the length-prefixed
        // UTF-8 payload must preserve spaces exactly (no trimming).
        store.save("smoke@example.invalid", " p ass word ", true);
        require(store.hasSaved(), "synthetic save missing");
        require(store.isAutoLoginEnabled(), "synthetic autologin not persisted");
        CredentialStore.Credentials loaded = store.load();
        require("smoke@example.invalid".equals(loaded.email), "synthetic email mismatch");
        require(" p ass word ".equals(loaded.password),
                "synthetic spaced password was trimmed or mangled");
        store.setAutoLoginEnabled(false);
        require(!store.isAutoLoginEnabled(), "synthetic autologin disable failed");
        require(store.hasSaved(), "synthetic blob lost after autologin disable");
        loaded = store.load();
        require(" p ass word ".equals(loaded.password), "password lost after autologin disable");

        // Raw XML/blob must never contain the plaintext email or password.
        final SharedPreferences prefs = context.getSharedPreferences(
                "novelia_account_v1_synthetic_test", Context.MODE_PRIVATE);
        final java.util.Map<String, ?> all = prefs.getAll();
        require(!all.isEmpty(), "synthetic prefs missing after save");
        for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            final String value = String.valueOf(entry.getValue());
            require(!containsIgnoreCase(value, "smoke@example.invalid"),
                    "synthetic prefs leak the email in raw form");
            require(!containsIgnoreCase(value, "p ass word"),
                    "synthetic prefs leak the password in raw form");
        }

        // Recreate the store from the same namespace: prefs and key alias are
        // shared by the isolated storage, so the roundtrip must still decrypt.
        final CredentialStore recreated = new CredentialStore(context, "synthetic_test");
        final CredentialStore.Credentials again = recreated.load();
        require("smoke@example.invalid".equals(again.email), "recreated store email mismatch");
        require(" p ass word ".equals(again.password), "recreated store password mismatch");
        require(recreated.hasSaved(), "recreated store lost blob");
        require(!recreated.isAutoLoginEnabled(), "recreated store lost explicit autologin disable");
        recreated.setAutoLoginEnabled(true);
        require(new CredentialStore(context, "synthetic_test").isAutoLoginEnabled(),
                "recreated store lost explicit autologin enable");

        // Automated clear path must remove blob and flag.
        recreated.clear();
        require(!recreated.hasSaved(), "synthetic blob survived clear");
        require(!recreated.isAutoLoginEnabled(), "synthetic autologin survived clear");
        try {
            recreated.load();
            throw new AssertionError("load after clear must fail");
        } catch (IOException expected) {
            require(true, "expected IOException after clear");
        }

        // Flipping an envelope byte must fail closed instead of returning data.
        store.save("tamper@example.invalid", "will-be-tampered", false);
        final String blob = prefs.getString(CredentialStore.KEY_BLOB, null);
        require(blob != null && blob.length() > 0, "synthetic blob missing for tamper test");
        final byte[] raw = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP);
        raw[raw.length - 1] ^= (byte) 0x01;
        final boolean committed = prefs.edit()
                .putString(CredentialStore.KEY_BLOB,
                        android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP))
                .commit();
        require(committed, "tamper write failed");
        try {
            store.load();
            throw new AssertionError("tampered blob must fail closed");
        } catch (GeneralSecurityException expected) {
            require(true, "expected GeneralSecurityException after tamper");
        }
        // Restore the undamaged blob so synthetic credentials never leak.
        prefs.edit().putString(CredentialStore.KEY_BLOB, blob).commit();
        final CredentialStore.Credentials restored = store.load();
        require("tamper@example.invalid".equals(restored.email), "restore mismatch");
        store.clear();
        require(!store.hasSaved(), "synthetic store not empty at test end");

        // Keystore key lifecycle: the namespace alias must be gone after clear.
        // Uses the real AndroidKeyStore API directly and only the synthetic alias.
        try {
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            require(!keyStore.containsAlias("novelia_account_key_synthetic_test"),
                    "synthetic keystore alias survived clear");
        } catch (Throwable keystoreFailure) {
            throw new AssertionError("keystore cleanup check failed: "
                    + keystoreFailure.getClass().getName(), keystoreFailure);
        }
        } finally { store.clear(); }
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null || needle.length() == 0) return false;
        final String lower = haystack.toLowerCase(java.util.Locale.ROOT);
        return lower.contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    private void runCheck(String name, Check check) {
        String only = arguments == null ? null : arguments.getString("only");
        if (only != null && !java.util.Arrays.asList(only.split(",")).contains(name)) {
            results.putString(name, "SKIP: not selected");
            skipped++;
            return;
        }
        try {
            check.run();
            results.putString(name, "PASS");
            passed++;
        } catch (Throwable error) {
            StringBuilder detail = new StringBuilder("FAIL");
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 5; depth++) {
                detail.append(depth == 0 ? ": " : " <- ");
                detail.append(cause.getClass().getName());
                if (cause.getMessage() != null) detail.append(": ").append(cause.getMessage());
                cause = cause.getCause();
            }
            results.putString(name, detail.toString());
            failed++;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectIOException(NativeHttp http, String url) throws IOException {
        try {
            http.get(url);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("disallowed URL was accepted");
    }

    private void checkReader() {
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(new Runnable() {
            @Override public void run() {
                Bitmap bitmap = null;
                try {
                    ReaderView view = new ReaderView(getTargetContext());
                    view.setBackgroundColor(Color.WHITE);
                    StringBuilder sample = new StringBuilder();
                    for (int i = 0; i < 180; i++) {
                        sample.append("한글 독서 확인 ").append(i)
                                .append(" — 글자 크기와 페이지 넘김을 확인합니다.\n");
                    }
                    view.setContent("Smoke", sample.toString());
                    view.restoreProgress(0.5f); // Must work before the first layout.
                    int width = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY);
                    int height = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY);
                    view.measure(width, height);
                    view.layout(0, 0, 800, 600);
                    bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    view.draw(canvas);
                    require(view.getProgress() > 0.3f, "pre-layout restore lost");
                    require(hasInkBelowMiddle(bitmap), "no ink in lower half of page");
                    float before = view.getProgress();
                    view.nextPage();
                    view.draw(canvas);
                    require(view.getProgress() > before, "nextPage did not advance");
                    view.previousPage();
                    view.draw(canvas);
                    require(Math.abs(view.getProgress() - before) < 0.02f,
                            "previousPage did not return");
                    float saved = view.getProgress();
                    view.restoreProgress(saved);
                    view.draw(canvas);
                    require(Math.abs(view.getProgress() - saved) < 0.0001f,
                            "saving/restoring progress skipped the current page");
                    view.setTextSizeSp(24);
                    view.measure(width, height);
                    view.layout(0, 0, 800, 600);
                    view.draw(canvas);
                    require(view.getProgress() > 0.3f, "font resize lost position");
                    File image = new File(getTargetContext().getCacheDir(), "smoke-reader.png");
                    try (FileOutputStream out = new FileOutputStream(image)) {
                        require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out),
                                "could not encode reader bitmap");
                    }
                } catch (Throwable error) {
                    failure[0] = error;
                } finally {
                    if (bitmap != null) bitmap.recycle();
                }
            }
        });
        if (failure[0] != null) throw new AssertionError("reader smoke failed", failure[0]);
    }

    private static boolean hasInkBelowMiddle(Bitmap bitmap) {
        for (int y = 310; y < 590; y += 3) {
            for (int x = 20; x < 780; x += 3) {
                if (bitmap.getPixel(x, y) != Color.WHITE) return true;
            }
        }
        return false;
    }
}
