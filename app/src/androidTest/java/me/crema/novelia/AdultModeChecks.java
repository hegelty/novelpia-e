package me.crema.novelia;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import me.crema.novelia.account.AdultModeStatus;
import me.crema.novelia.site.ContentAccessException;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Deterministic checks for adult-content access dialogs and adult-mode settings. */
public final class AdultModeChecks {
    private AdultModeChecks() { }

    /**
     * Runs synthetic access failures and known adult-mode settings states. The supplied
     * Activity must be a disposable MainActivity in an isolated validation package.
     * No action button is clicked, so the checks do not log in or mutate server state.
     */
    public static void run(final Instrumentation instrumentation, final Activity activity)
            throws Exception {
        require(activity instanceof MainActivity, "adult-mode check requires MainActivity");

        checkAccessFailure(instrumentation, activity, ContentAccessException.Reason.LOGIN_REQUIRED,
                "로그인 필요", "로그인");
        checkAccessFailure(instrumentation, activity,
                ContentAccessException.Reason.AGE_VERIFICATION_REQUIRED,
                "성인 인증 필요", "인증 페이지 열기");
        checkAccessFailure(instrumentation, activity,
                ContentAccessException.Reason.ADULT_MODE_REQUIRED,
                "성인 모드 꺼짐", "성인 모드 켜기");
        checkAccessFailure(instrumentation, activity, ContentAccessException.Reason.AGE_RESTRICTED,
                "열람 제한", "확인");

        checkModeSettings(instrumentation, activity, AdultModeStatus.State.ON,
                "성인 모드 켜짐", "끄기");
        checkModeSettings(instrumentation, activity, AdultModeStatus.State.OFF,
                "성인 모드 꺼짐", "켜기");
    }

    private static void checkAccessFailure(final Instrumentation instrumentation,
            final Activity activity, final ContentAccessException.Reason reason,
            final String expectedTitle, final String expectedPositive) throws Exception {
        instrumentation.runOnMainSync(() -> {
            try {
                Class<?> workType = nestedInterface("Work");
                Class<?> resultType = nestedInterface("Result");
                Object work = Proxy.newProxyInstance(activity.getClassLoader(),
                        new Class<?>[]{workType}, new InvocationHandler() {
                            @Override public Object invoke(Object proxy, Method method, Object[] args)
                                    throws Throwable {
                                if ("run".equals(method.getName()))
                                    throw new ContentAccessException(reason);
                                return null;
                            }
                        });
                Object result = Proxy.newProxyInstance(activity.getClassLoader(),
                        new Class<?>[]{resultType}, new InvocationHandler() {
                            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                                return null;
                            }
                        });
                Method request = MainActivity.class.getDeclaredMethod(
                        "request", String.class, workType, resultType);
                request.setAccessible(true);
                request.invoke(activity, "Synthetic access check", work, result);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });

        View root = awaitDialog(instrumentation, expectedTitle, expectedPositive);
        final Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try {
                checkDialog(root, expectedTitle, expectedPositive,
                        reason == ContentAccessException.Reason.AGE_RESTRICTED ? null : "취소");
            } catch (Throwable error) { failure[0] = error; }
        });
        rethrow(failure[0]);
        dismissByBackAndAwaitActivity(instrumentation, activity, root);
    }

    private static void checkModeSettings(final Instrumentation instrumentation,
            final Activity activity, final AdultModeStatus.State state,
            final String expectedStateText, final String expectedAction) throws Exception {
        instrumentation.runOnMainSync(() -> {
            try {
                AdultModeStatus status = makeStatus(state);
                Method show = MainActivity.class.getDeclaredMethod(
                        "showAdultModeSettings", AdultModeStatus.class);
                show.setAccessible(true);
                show.invoke(activity, status);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });

        View root = awaitDialog(instrumentation, expectedStateText, expectedAction);
        final Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try {
                require(containsText(root, expectedStateText),
                        "adult-mode settings did not show " + expectedStateText);
                require(findButton(root, expectedAction) != null,
                        "adult-mode settings action is missing: " + expectedAction);
            } catch (Throwable error) { failure[0] = error; }
        });
        rethrow(failure[0]);
        dismissByBackAndAwaitActivity(instrumentation, activity, root);
    }

    private static AdultModeStatus makeStatus(AdultModeStatus.State state) throws Exception {
        java.lang.reflect.Constructor<AdultModeStatus> constructor =
                AdultModeStatus.class.getDeclaredConstructor(AdultModeStatus.State.class);
        constructor.setAccessible(true);
        return constructor.newInstance(state);
    }

    private static Class<?> nestedInterface(String simpleName) throws ClassNotFoundException {
        for (Class<?> nested : MainActivity.class.getDeclaredClasses()) {
            if (nested.isInterface() && simpleName.equals(nested.getSimpleName())) return nested;
        }
        throw new ClassNotFoundException("MainActivity." + simpleName);
    }

    private static View awaitDialog(Instrumentation instrumentation, String expectedTitle,
            String expectedAction)
            throws Exception {
        final View[] root = new View[1];
        final Throwable[] failure = new Throwable[1];
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(6);
        do {
            instrumentation.runOnMainSync(() -> {
                try { root[0] = findDialogRoot(expectedTitle, expectedAction); }
                catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null) throw new AssertionError("could not inspect dialog", failure[0]);
            if (root[0] != null) return root[0];
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("active dialog did not appear with title/state "
                + expectedTitle + " and action " + expectedAction);
    }

    private static View findDialogRoot(String expectedText, String expectedAction) {
        try {
            Class<?> managerClass = Class.forName("android.view.WindowManagerGlobal");
            Object manager = managerClass.getMethod("getInstance").invoke(null);
            Field rootsField = managerClass.getDeclaredField("mRoots");
            rootsField.setAccessible(true);
            Object roots = rootsField.get(manager);
            if (!(roots instanceof List<?>)) return null;
            for (Object item : (List<?>) roots) {
                if (item == null) continue;
                try {
                    View root = (View) item.getClass().getMethod("getView").invoke(item);
                    if (root != null && root.isShown() && root.getWindowToken() != null
                            && root.hasWindowFocus() && containsText(root, expectedText)
                            && findButton(root, expectedAction) != null) return root;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static void checkDialog(View root, String expectedTitle,
            String expectedPositive, String expectedNegative) {
        require(hasDialogTitle(root, expectedTitle), "dialog title is missing: " + expectedTitle);
        Button positive = findButton(root, expectedPositive);
        require(positive != null, "dialog action is missing: " + expectedPositive);
        require(positive.getId() == android.R.id.button1,
                "expected action is not the positive dialog button: " + expectedPositive);
        Button negative = findButton(root, "취소");
        if (expectedNegative == null) {
            require(negative == null, "restricted-content dialog must only offer confirmation");
        } else {
            require(negative != null && expectedNegative.contentEquals(negative.getText()),
                    "dialog cancel action is missing");
            require(negative.getId() == android.R.id.button2,
                    "cancel action is not the negative dialog button");
        }
    }

    private static void dismissByBackAndAwaitActivity(Instrumentation instrumentation,
            Activity activity, View dismissedRoot) throws Exception {
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        awaitActivityWindow(instrumentation, activity, dismissedRoot);
    }

    private static void awaitActivityWindow(Instrumentation instrumentation, Activity activity,
            View dismissedRoot) throws Exception {
        final boolean[] ready = new boolean[1];
        final Throwable[] failure = new Throwable[1];
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5);
        do {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                try {
                    View decor = activity.getWindow().getDecorView();
                    ready[0] = dismissedRoot.getWindowToken() == null
                            && !isRegisteredWindowRoot(dismissedRoot)
                            && decor.isShown() && decor.getWindowToken() != null
                            && decor.hasWindowFocus() && isRegisteredFocusedRoot(decor);
                } catch (Throwable error) { failure[0] = error; }
            });
            if (failure[0] != null)
                throw new AssertionError("could not inspect window focus after dialog dismissal",
                        failure[0]);
            if (ready[0]) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Activity window did not regain focus after dialog dismissal");
    }

    private static boolean isRegisteredWindowRoot(View expected) throws Exception {
        for (View root : windowRoots()) if (root == expected) return true;
        return false;
    }

    private static boolean isRegisteredFocusedRoot(View expected) throws Exception {
        for (View root : windowRoots()) {
            if (root == expected && root.isShown() && root.getWindowToken() != null
                    && root.hasWindowFocus()) return true;
        }
        return false;
    }

    /** API 19 WindowManagerGlobal.mRoots contains ViewRootImpl instances. */
    private static List<View> windowRoots() throws Exception {
        Class<?> managerClass = Class.forName("android.view.WindowManagerGlobal");
        Object manager = managerClass.getMethod("getInstance").invoke(null);
        Field rootsField = managerClass.getDeclaredField("mRoots");
        rootsField.setAccessible(true);
        Object roots = rootsField.get(manager);
        if (!(roots instanceof List<?>))
            throw new AssertionError("API 19 WindowManagerGlobal.mRoots is not a List");
        java.util.ArrayList<View> result = new java.util.ArrayList<View>();
        for (Object item : (List<?>) roots) {
            if (item == null) continue;
            try { result.add((View) item.getClass().getMethod("getView").invoke(item)); }
            catch (NoSuchMethodException missingAccessor) {
                Field viewField = item.getClass().getDeclaredField("mView");
                viewField.setAccessible(true);
                result.add((View) viewField.get(item));
            }
        }
        return result;
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView
                && expected.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), expected)) return true;
            }
        }
        return false;
    }

    private static boolean hasDialogTitle(View view, String expected) {
        int titleId = view.getResources().getIdentifier("alertTitle", "id", "android");
        if (titleId != 0 && view.getId() == titleId && view instanceof TextView)
            return expected.contentEquals(((TextView) view).getText());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasDialogTitle(group.getChildAt(i), expected)) return true;
            }
        }
        return false;
    }

    private static Button findButton(View view, String expected) {
        if (view instanceof Button && expected.contentEquals(((Button) view).getText()))
            return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), expected);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rethrow(Throwable error) throws Exception {
        if (error == null) return;
        if (error instanceof Exception) throw (Exception) error;
        if (error instanceof Error) throw (Error) error;
        throw new AssertionError(error);
    }
}
