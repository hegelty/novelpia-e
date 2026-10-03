package me.crema.novelia;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import me.crema.novelia.account.LibraryPage;
import me.crema.novelia.input.KeyBindings;
import me.crema.novelia.input.KeyMappingDialog;
import me.crema.novelia.site.SiteClient;
import me.crema.novelia.ui.PagedListView;

/**
 * Opt-in captures of the real application UI for release materials.
 * The library uses fictional titles, authors, and explicitly assigned latest
 * episode labels. They are fixtures, not data fetched from Novelpia.
 */
final class PublicationScreenshots {
    private PublicationScreenshots() { }

    static void run(final SmokeInstrumentation instrumentation, final Activity activity)
            throws Exception {
        if (!(activity instanceof MainActivity)) {
            throw new AssertionError("publication screenshots require MainActivity");
        }

        capture(instrumentation, activity.getWindow().getDecorView(), "release-home.png");
        showFixtureLibrary(instrumentation, activity);
        instrumentation.waitForIdleSync();
        capture(instrumentation, activity.getWindow().getDecorView(), "release-library.png");

        runOnMain(instrumentation, new Runnable() {
            @Override public void run() {
                invoke(activity, "demo");
            }
        });
        instrumentation.waitForIdleSync();
        capture(instrumentation, activity.getWindow().getDecorView(), "release-reader.png");

        runOnMain(instrumentation, new Runnable() {
            @Override public void run() {
                invoke(activity, "toggleReaderTools");
            }
        });
        instrumentation.waitForIdleSync();
        capture(instrumentation, activity.getWindow().getDecorView(), "release-reader-tools.png");

        final AlertDialog[] keys = new AlertDialog[1];
        runOnMain(instrumentation, new Runnable() {
            @Override public void run() {
                keys[0] = KeyMappingDialog.show(activity, KeyBindings.defaults(), null);
            }
        });
        try {
            instrumentation.waitForIdleSync();
            capture(instrumentation, keys[0].getWindow().getDecorView(), "release-keys.png");
        } finally {
            runOnMain(instrumentation, new Runnable() {
                @Override public void run() {
                    if (keys[0] != null && keys[0].isShowing()) keys[0].dismiss();
                }
            });
        }
    }

    private static void showFixtureLibrary(final SmokeInstrumentation instrumentation,
                                          final Activity activity) {
        final LibraryPage page = fixturePage();
        runOnMain(instrumentation, new Runnable() {
            @Override public void run() {
                try {
                    Field location = MainActivity.class.getDeclaredField("location");
                    location.setAccessible(true);
                    location.set(activity, page.url);
                    Method show = MainActivity.class.getDeclaredMethod(
                            "showLibrary", LibraryPage.class, int.class);
                    show.setAccessible(true);
                    show.invoke(activity, page, 0);

                    // The real library UI installs a callback that fetches latest
                    // episode labels for visible rows. These fixture labels are
                    // already supplied, so disable that callback before layout.
                    Field listField = MainActivity.class.getDeclaredField("pagedList");
                    listField.setAccessible(true);
                    ((PagedListView) listField.get(activity)).setOnVisibleRowsChanged(null);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }
        });
    }

    private static LibraryPage fixturePage() {
        String[] titles = {"작은 서점의 오후", "밤을 걷는 우체부", "오래된 지도", "별이 머무는 집"};
        int[] lastReadEpisodes = {12, 7, 21, 3};
        int[] registeredCounts = {25, 19, 33, 12};
        String[] latestLabels = {"EP.24", "EP.18", "EP.32", "EP.11"};
        List<LibraryPage.Item> rows = new ArrayList<LibraryPage.Item>();
        for (int i = 0; i < titles.length; i++) {
            String id = String.valueOf(910001 + i);
            SiteClient.Entry entry = new SiteClient.Entry(titles[i],
                    "https://novelpia.com/novel/" + id, "novel", "");
            rows.add(new LibraryPage.Item(entry,
                    "https://novelpia.com/viewer/" + (920001 + i),
                    lastReadEpisodes[i], registeredCounts[i], "예시 작가",
                    String.valueOf(930001 + i), latestLabels[i]));
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
                "https://novelpia.com/mybook/like/0/date/1", "", "",
                "like", "0", "date", "", sorts, groups);
    }

    private static void capture(final SmokeInstrumentation instrumentation,
                                final View view, final String filename) {
        runOnMain(instrumentation, new Runnable() {
            @Override public void run() { instrumentation.captureNow(view, filename); }
        });
    }

    private static void runOnMain(SmokeInstrumentation instrumentation, Runnable action) {
        instrumentation.runOnMainSync(action);
    }

    private static void invoke(Activity activity, String methodName) {
        try {
            Method method = MainActivity.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(activity);
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
}
