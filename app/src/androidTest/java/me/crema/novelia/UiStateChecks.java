package me.crema.novelia;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import me.crema.novelia.ui.PagedListView;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Deterministic UI regressions for request status layout and cold login startup. */
public final class UiStateChecks {
    private UiStateChecks() { }

    /**
     * Exercises MainActivity.request with held success and failure work. The caller must
     * pass a disposable MainActivity launched in the isolated validation package.
     */
    public static void runLoading(final Instrumentation instrumentation,
                                  final Activity activity) throws Exception {
        require(activity instanceof MainActivity, "loading check requires MainActivity");
        dismissLoginIfPresent(instrumentation, activity);
        final View[] contentRef = new View[1];
        final TextView[] statusRef = new TextView[1];
        final PagedListView[] listRef = new PagedListView[1];

        instrumentation.runOnMainSync(() -> {
            try {
                LinearLayout content = (LinearLayout) readField(activity, "content");
                TextView status = (TextView) readField(activity, "status");
                content.removeAllViews();
                PagedListView list = new PagedListView(activity);
                List<PagedListView.Row> rows = new ArrayList<PagedListView.Row>();
                for (int i = 0; i < 36; i++) {
                    rows.add(new PagedListView.Row("fixture row " + (i + 1),
                            "fixture detail", null));
                }
                list.setRows(rows, 0);
                content.addView(list, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
                writeField(activity, "pagedList", list);
                status.setText("");
                status.setVisibility(View.INVISIBLE);
                contentRef[0] = content;
                statusRef[0] = status;
                listRef[0] = list;
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        instrumentation.waitForIdleSync();

        final LayoutState[] before = new LayoutState[1];
        instrumentation.runOnMainSync(() -> before[0] = snapshot(
                contentRef[0], statusRef[0], listRef[0]));
        require(before[0].statusHeight > 0, "status footer has no reserved height while hidden");
        int expectedFooterHeight = Math.round(activity.getResources().getDisplayMetrics().density * 40f);
        require(before[0].statusHeight == expectedFooterHeight,
                "status footer must reserve a fixed 40dp height");
        require(statusRef[0].getMaxLines() == 1, "status footer must be limited to one line");
        require(before[0].visibleRows > 0, "synthetic list has no visible rows");

        final String longStatus = "Synthetic request is loading with a deliberately long status message";
        HeldRequest success = startHeldRequest(instrumentation, activity, longStatus, false);
        require(success.entered.await(5, TimeUnit.SECONDS), "success work did not start");
        instrumentation.waitForIdleSync();
        final LayoutState[] duringSuccess = new LayoutState[1];
        instrumentation.runOnMainSync(() -> duringSuccess[0] = snapshot(
                contentRef[0], statusRef[0], listRef[0]));
        require(longStatus.equals(statusRef[0].getText().toString()),
                "loading message was not shown");
        require(statusRef[0].getVisibility() == View.VISIBLE, "loading status is not visible");
        assertStable(before[0], duringSuccess[0], "while loading");
        success.release.countDown();
        require(success.finished.await(5, TimeUnit.SECONDS), "success work did not finish");
        awaitRequestCallback(instrumentation, activity);
        final LayoutState[] afterSuccess = new LayoutState[1];
        instrumentation.runOnMainSync(() -> afterSuccess[0] = snapshot(
                contentRef[0], statusRef[0], listRef[0]));
        assertStable(before[0], afterSuccess[0], "after success");
        require(success.callbackCalled[0], "success result callback was not called");
        require(statusRef[0].getVisibility() == View.INVISIBLE,
                "successful list request did not restore the hidden footer");

        HeldRequest failure = startHeldRequest(instrumentation, activity, longStatus, true);
        require(failure.entered.await(5, TimeUnit.SECONDS), "failure work did not start");
        instrumentation.waitForIdleSync();
        final LayoutState[] duringFailure = new LayoutState[1];
        instrumentation.runOnMainSync(() -> duringFailure[0] = snapshot(
                contentRef[0], statusRef[0], listRef[0]));
        require(longStatus.equals(statusRef[0].getText().toString()),
                "failure request loading message was not shown");
        assertStable(before[0], duringFailure[0], "while failing request is pending");
        failure.release.countDown();
        require(failure.finished.await(5, TimeUnit.SECONDS), "failure work did not finish");
        awaitRequestCallback(instrumentation, activity);
        final LayoutState[] afterFailure = new LayoutState[1];
        instrumentation.runOnMainSync(() -> afterFailure[0] = snapshot(
                contentRef[0], statusRef[0], listRef[0]));
        assertStable(before[0], afterFailure[0], "after failure");
        require(statusRef[0].getText().toString().contains("요청 실패"),
                "safe failure status was not shown");
        dismissFailureDialog(instrumentation, activity);
    }

    /**
     * Checks the fresh unauthenticated startup path. The supplied Activity must have
     * been launched from a clean isolated validation package with auto-login disabled.
     */
    public static void runStartup(final Instrumentation instrumentation,
                                  final Activity activity) throws Exception {
        require(activity instanceof MainActivity, "startup check requires MainActivity");
        final AlertDialog[] dialogRef = new AlertDialog[1];
        final Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try {
                dialogRef[0] = (AlertDialog) readField(activity, "loginDialog");
                require(dialogRef[0] != null && dialogRef[0].isShowing(),
                        "cold unauthenticated startup did not show login");
                List<EditText> fields = new ArrayList<EditText>();
                collectEditTexts(dialogRef[0].getWindow().getDecorView(), fields);
                require(fields.size() >= 2, "login dialog is missing email/password fields");
                for (EditText field : fields) {
                    require(field.getText().length() == 0,
                            "cold login field contains unexpected text");
                }
                Button cancel = dialogRef[0].getButton(AlertDialog.BUTTON_NEGATIVE);
                require(cancel != null, "login dialog cancel button is missing");
                cancel.performClick();
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        instrumentation.waitForIdleSync();
        if (failure[0] != null) throw new AssertionError("cold login startup check failed", failure[0]);

        instrumentation.runOnMainSync(() -> {
            try {
                require(!dialogRef[0].isShowing(), "cancel did not dismiss cold login");
                LinearLayout content = (LinearLayout) readField(activity, "content");
                TextView heading = (TextView) readField(activity, "heading");
                TextView status = (TextView) readField(activity, "status");
                require(content.getChildCount() > 0, "cancel did not return to the home screen");
                require(heading.getVisibility() == View.GONE,
                        "cancel did not restore the welcome screen");
                require("".contentEquals(status.getText()), "home status is not clear after cancel");
                require("https://book.novelpia.com/webnovel/serial"
                                .equals(readField(activity, "location")),
                        "cancel did not restore the public home location");
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) throw new AssertionError("cancel did not return to home", failure[0]);
    }

    private static HeldRequest startHeldRequest(Instrumentation instrumentation,
            Activity activity, String message, boolean fail) {
        final HeldRequest held = new HeldRequest();
        instrumentation.runOnMainSync(() -> {
            try {
                Class<?> workType = nestedInterface("Work");
                Class<?> resultType = nestedInterface("Result");
                Object work = Proxy.newProxyInstance(activity.getClassLoader(),
                        new Class<?>[]{workType}, new InvocationHandler() {
                            @Override public Object invoke(Object proxy, Method method, Object[] args)
                                    throws Throwable {
                                if (!"run".equals(method.getName())) return null;
                                held.entered.countDown();
                                if (!held.release.await(5, TimeUnit.SECONDS)) {
                                    throw new IOException("synthetic request timed out");
                                }
                                held.finished.countDown();
                                if (fail) throw new IOException("synthetic network failure");
                                return "synthetic result";
                            }
                        });
                Object result = Proxy.newProxyInstance(activity.getClassLoader(),
                        new Class<?>[]{resultType}, new InvocationHandler() {
                            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                                if ("accept".equals(method.getName())) held.callbackCalled[0] = true;
                                return null;
                            }
                        });
                Method request = MainActivity.class.getDeclaredMethod(
                        "request", String.class, workType, resultType);
                request.setAccessible(true);
                request.invoke(activity, message, work, result);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        return held;
    }

    private static Class<?> nestedInterface(String simpleName) throws ClassNotFoundException {
        for (Class<?> nested : MainActivity.class.getDeclaredClasses()) {
            if (nested.isInterface() && simpleName.equals(nested.getSimpleName())) return nested;
        }
        throw new ClassNotFoundException("MainActivity." + simpleName);
    }

    private static void awaitRequestCallback(Instrumentation instrumentation, Activity activity)
            throws Exception {
        final boolean[] loading = new boolean[1];
        final Throwable[] failure = new Throwable[1];
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5);
        do {
            instrumentation.runOnMainSync(() -> {
                try { loading[0] = (Boolean) readField(activity, "loading"); }
                catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw new AssertionError("could not read request state", failure[0]);
            if (!loading[0]) {
                instrumentation.waitForIdleSync();
                return;
            }
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("MainActivity.request callback did not clear loading");
    }

    private static LayoutState snapshot(View content, TextView status, PagedListView list) {
        try {
            return new LayoutState(content.getTop(), content.getBottom(), content.getHeight(),
                    status.getTop(), status.getBottom(), status.getHeight(), list.getTop(),
                    list.getBottom(), list.getHeight(), (Integer) readField(list, "first"),
                    (Integer) readField(list, "capacity"), list.getVisibleCount(),
                    list.getScreen(), list.getScreenCount());
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static void assertStable(LayoutState before, LayoutState current, String phase) {
        require(before.contentTop == current.contentTop && before.contentBottom == current.contentBottom
                        && before.contentHeight == current.contentHeight,
                "content bounds changed " + phase);
        require(before.statusTop == current.statusTop && before.statusBottom == current.statusBottom
                        && before.statusHeight == current.statusHeight,
                "status footer bounds changed " + phase);
        require(before.listTop == current.listTop && before.listBottom == current.listBottom
                        && before.listHeight == current.listHeight,
                "PagedListView bounds changed " + phase);
        require(before.first == current.first && before.capacity == current.capacity
                        && before.visibleRows == current.visibleRows && before.screen == current.screen
                        && before.screenCount == current.screenCount,
                "first visible list range changed " + phase);
    }

    private static void dismissLoginIfPresent(Instrumentation instrumentation, Activity activity) {
        instrumentation.runOnMainSync(() -> {
            try {
                Object value = readField(activity, "loginDialog");
                if (value instanceof AlertDialog && ((AlertDialog) value).isShowing()) {
                    AlertDialog dialog = (AlertDialog) value;
                    Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                    if (cancel != null) cancel.performClick(); else dialog.dismiss();
                }
            } catch (NoSuchFieldException startupLoginNotAddedYet) {
                // Loading regression can also run against a build without startup login.
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        instrumentation.waitForIdleSync();
    }

    private static void dismissFailureDialog(Instrumentation instrumentation, Activity activity) {
        instrumentation.runOnMainSync(() -> {
            View root = findWindowRootWithText(activity, "불러오지 못했습니다");
            if (root == null) return;
            Button cancel = findButton(root, "취소");
            if (cancel != null) cancel.performClick();
        });
        instrumentation.waitForIdleSync();
    }

    private static View findWindowRootWithText(Activity activity, String fragment) {
        try {
            Class<?> managerClass = Class.forName("android.view.WindowManagerGlobal");
            Object manager = managerClass.getMethod("getInstance").invoke(null);
            Field rootsField = managerClass.getDeclaredField("mRoots");
            rootsField.setAccessible(true);
            Object roots = rootsField.get(manager);
            if (!(roots instanceof List<?>)) return null;
            View decor = activity.getWindow().getDecorView();
            for (Object item : (List<?>) roots) {
                if (item == null) continue;
                try {
                    View root = (View) item.getClass().getMethod("getView").invoke(item);
                    if (root != null && root != decor && containsText(root, fragment)) return root;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean containsText(View view, String fragment) {
        if (view instanceof TextView
                && ((TextView) view).getText().toString().contains(fragment)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), fragment)) return true;
            }
        }
        return false;
    }

    private static Button findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText()))
            return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectEditTexts(View view, List<EditText> target) {
        if (view instanceof EditText) target.add((EditText) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                collectEditTexts(group.getChildAt(i), target);
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void writeField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try { return cursor.getDeclaredField(name); }
            catch (NoSuchFieldException missing) { cursor = cursor.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class HeldRequest {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final boolean[] callbackCalled = new boolean[1];
    }

    private static final class LayoutState {
        final int contentTop, contentBottom, contentHeight;
        final int statusTop, statusBottom, statusHeight;
        final int listTop, listBottom, listHeight;
        final int first, capacity, visibleRows, screen, screenCount;
        LayoutState(int contentTop, int contentBottom, int contentHeight,
                int statusTop, int statusBottom, int statusHeight,
                int listTop, int listBottom, int listHeight,
                int first, int capacity, int visibleRows, int screen, int screenCount) {
            this.contentTop = contentTop;
            this.contentBottom = contentBottom;
            this.contentHeight = contentHeight;
            this.statusTop = statusTop;
            this.statusBottom = statusBottom;
            this.statusHeight = statusHeight;
            this.listTop = listTop;
            this.listBottom = listBottom;
            this.listHeight = listHeight;
            this.first = first;
            this.capacity = capacity;
            this.visibleRows = visibleRows;
            this.screen = screen;
            this.screenCount = screenCount;
        }
    }
}
