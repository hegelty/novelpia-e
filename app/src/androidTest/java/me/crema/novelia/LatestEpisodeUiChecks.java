package me.crema.novelia;

import android.app.Activity;
import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import me.crema.novelia.ui.PagedListView;

/** Verifies that asynchronous metadata does not rebuild rows or change local pagination. */
public final class LatestEpisodeUiChecks {
    private LatestEpisodeUiChecks() { }

    public static void run(final Instrumentation instrumentation, final Activity activity) throws Exception {
        final PagedListView[] lists = new PagedListView[1];
        final int[] callbacks = new int[1];
        instrumentation.runOnMainSync(() -> {
            try {
                Field contentField = MainActivity.class.getDeclaredField("content");
                contentField.setAccessible(true);
                LinearLayout content = (LinearLayout) contentField.get(activity);
                content.removeAllViews();
                PagedListView list = new PagedListView(activity);
                ArrayList<PagedListView.Row> rows = new ArrayList<PagedListView.Row>();
                for (int i = 0; i < 30; i++) rows.add(new PagedListView.Row(
                        "작품 " + i, "마지막 읽은 EP.70 · 등록 83편\n작가", () -> { },
                        2, "이어보기", () -> { }));
                list.setRows(rows, 0);
                list.setOnVisibleRowsChanged(() -> callbacks[0]++);
                content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
                lists[0] = list;
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        instrumentation.waitForIdleSync();
        require(callbacks[0] > 0, "initial visible rows were not reported");
        instrumentation.runOnMainSync(() -> require(lists[0].next(), "fixture has no second local page"));
        instrumentation.waitForIdleSync();
        final int[] before = new int[4];
        final View[] focused = new View[1];
        final String updated = "마지막 읽은 EP.70 · 최신 EP.079\n작가";
        instrumentation.runOnMainSync(() -> {
            PagedListView list = lists[0];
            before[0] = list.getFirstVisibleIndex();
            before[1] = list.getScreen();
            before[2] = list.getVisibleCount();
            before[3] = callbacks[0];
            Button quick = findButton(list, "이어보기");
            require(quick != null, "quick action not found");
            quick.setFocusableInTouchMode(true);
            require(quick.requestFocus(), "quick action cannot receive focus");
            focused[0] = quick;
            list.updateRowDetail(before[0], updated);
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            PagedListView list = lists[0];
            require(before[0] == list.getFirstVisibleIndex() && before[1] == list.getScreen()
                    && before[2] == list.getVisibleCount(), "metadata changed pagination");
            require(focused[0] == list.findFocus(), "metadata replaced the focused quick action");
            require(before[3] == callbacks[0], "metadata recursively requested more metadata");
            require(containsText(list, updated), "latest label was not rendered");
            list.previous();
            list.next();
            require(containsText(list, updated), "latest label disappeared after local pagination");
        });
    }

    private static Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button result = findButton(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static boolean containsText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
