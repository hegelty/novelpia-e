package me.crema.novelia;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.crema.novelia.input.KeyBindings;
import me.crema.novelia.input.KeyMappingDialog;
import me.crema.novelia.reader.ReaderView;

/** Actual ViewRoot input checks for reader key routing (API 19 touch mode). */
public final class ReaderInputChecks {
    private ReaderInputChecks() { }

    /**
     * Runs after Activity is resumed. Sets up the synthetic reader page and
     * injects real pointer/key events through Instrumentation, not
     * Activity.dispatchKeyEvent().
     */
    public static void run(final Instrumentation instrumentation, final Activity activity)
            throws Exception {
        final ReaderView reader = readerOf(activity);
        final int[] location = new int[2];
        awaitActivityWindowFocus(instrumentation, activity, null);
        final int[] dimensions = new int[2];
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                reader.getLocationOnScreen(location);
                dimensions[0] = reader.getWidth();
                dimensions[1] = reader.getHeight();
            }
        });
        if (dimensions[0] <= 0 || dimensions[1] <= 0) {
            throw new AssertionError("reader has no bounds for physical input");
        }

        // A real tap enters ViewRoot touch mode. Tap the middle of the body;
        // this opens reader tools without changing page progress.
        tap(instrumentation, location[0] + dimensions[0] / 2f,
                location[1] + dimensions[1] / 2f);
        instrumentation.waitForIdleSync();
        final boolean[] touchState = new boolean[3];
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                touchState[0] = reader.isInTouchMode();
                touchState[1] = reader.isFocusableInTouchMode();
                touchState[2] = reader.isFocused();
            }
        });

        float before = progress(instrumentation, reader);
        press(instrumentation, KeyEvent.KEYCODE_PAGE_DOWN);
        instrumentation.waitForIdleSync();
        float after = progress(instrumentation, reader);
        require(after > before, "first injected PAGE_DOWN after a real touch did not advance");
        require(touchState[0], "first pointer tap did not enter touch mode");
        require(touchState[1], "reader is not focusable in touch mode");
        require(touchState[2], "reader did not hold focus when tapped");

        // Re-enter touch mode from the actual input queue, then make sure the
        // next PAGE_DOWN also reaches the reader after a preceding touch.
        tap(instrumentation, location[0] + dimensions[0] / 2f,
                location[1] + dimensions[1] / 2f);
        instrumentation.waitForIdleSync();
        requireTouchFocusableFocus(instrumentation, reader);
        before = progress(instrumentation, reader);
        press(instrumentation, KeyEvent.KEYCODE_PAGE_DOWN);
        instrumentation.waitForIdleSync();
        after = progress(instrumentation, reader);
        require(after > before, "PAGE_DOWN after repeated touch did not advance");

        // An open dialog owns key input. Its PAGE_DOWN must not page the reader.
        final AlertDialog[] dialog = new AlertDialog[1];
        final Throwable[] error = new Throwable[1];
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    dialog[0] = KeyMappingDialog.show(activity, bindingsOf(activity),
                            new KeyMappingDialog.Callback() {
                                @Override public void onApply(KeyBindings ignored) { }
                            });
                } catch (Throwable t) { error[0] = t; }
            }
        });
        instrumentation.waitForIdleSync();
        rethrow(error[0]);
        final View[] keyDialogRoot = new View[1];
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                keyDialogRoot[0] = dialog[0].getWindow().getDecorView();
            }
        });
        awaitDialogWindowFocus(instrumentation, keyDialogRoot[0]);
        before = progress(instrumentation, reader);
        press(instrumentation, KeyEvent.KEYCODE_PAGE_DOWN);
        instrumentation.waitForIdleSync();
        require(Math.abs(progress(instrumentation, reader) - before) < 0.0001f,
                "PAGE_DOWN paged reader while key-mapping dialog was open");
        final View[] dismissedDialogRoot = new View[1];
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                dismissedDialogRoot[0] = dialog[0].getWindow().getDecorView();
                dialog[0].dismiss();
            }
        });
        awaitActivityWindowFocus(instrumentation, activity, dismissedDialogRoot[0]);

        // A focused ReaderView must still let an unhandled configured key
        // reach MainActivity.dispatchKeyEvent(). Use F1 to avoid default maps.
        final Field bindingsField = MainActivity.class.getDeclaredField("keyBindings");
        bindingsField.setAccessible(true);
        final KeyBindings[] bindingValues = new KeyBindings[2];
        final Field toolsField = MainActivity.class.getDeclaredField("readerTools");
        toolsField.setAccessible(true);
        final View[] toolsHolder = new View[1];
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    toolsHolder[0] = (View) toolsField.get(activity);
                    bindingValues[0] = (KeyBindings) bindingsField.get(activity);
                    bindingValues[1] = bindingValues[0]
                            .withBinding(KeyBindings.Action.MENU, KeyEvent.KEYCODE_F1, 0);
                    bindingsField.set(activity, bindingValues[1]);
                    toolsHolder[0].setVisibility(View.GONE);
                    reader.requestFocus();
                } catch (Throwable t) { error[0] = t; }
            }
        });
        rethrow(error[0]);
        awaitReaderFocus(instrumentation, activity, reader);
        press(instrumentation, KeyEvent.KEYCODE_F1);
        instrumentation.waitForIdleSync();
        require(isVisible(instrumentation, toolsHolder[0]),
                "configured MENU key was swallowed by focused ReaderView");
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                try { bindingsField.set(activity, bindingValues[0]); }
                catch (Throwable t) { error[0] = t; }
            }
        });
        rethrow(error[0]);
    }

    private static ReaderView readerOf(Activity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("reader");
        field.setAccessible(true);
        ReaderView reader = (ReaderView) field.get(activity);
        if (reader == null) throw new AssertionError("synthetic reader missing");
        return reader;
    }

    private static KeyBindings bindingsOf(Activity activity) throws Exception {
        Field field = MainActivity.class.getDeclaredField("keyBindings");
        field.setAccessible(true);
        return (KeyBindings) field.get(activity);
    }

    private static void requireTouchFocusableFocus(final Instrumentation instrumentation,
            final ReaderView reader) {
        final boolean[] values = new boolean[3];
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                values[0] = reader.isInTouchMode();
                values[1] = reader.isFocusableInTouchMode();
                values[2] = reader.isFocused();
            }
        });
        if (!values[0]) throw new AssertionError("tap did not enter touch mode");
        if (!values[1]) throw new AssertionError("reader is not focusable in touch mode");
        if (!values[2]) throw new AssertionError("reader did not retain focus in touch mode");
    }

    private static boolean isVisible(final Instrumentation instrumentation, final View view) {
        final boolean[] visible = new boolean[1];
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() { visible[0] = view.getVisibility() == View.VISIBLE; }
        });
        return visible[0];
    }

    private static void awaitReaderFocus(Instrumentation instrumentation, Activity activity,
            ReaderView reader) throws Exception {
        final boolean[] focused = new boolean[1];
        final Throwable[] failure = new Throwable[1];
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5);
        do {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        View decor = activity.getWindow().getDecorView();
                        focused[0] = decor.hasWindowFocus() && isRegisteredFocusedRoot(decor)
                                && reader.isShown() && reader.getWindowToken() != null
                                && reader.isFocused();
                    } catch (Throwable error) { failure[0] = error; }
                }
            });
            rethrow(failure[0]);
            if (focused[0]) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("reader did not regain focus in the active Activity window");
    }

    private static void awaitDialogWindowFocus(Instrumentation instrumentation, View dialogRoot)
            throws Exception {
        final boolean[] focused = new boolean[1];
        final Throwable[] failure = new Throwable[1];
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5);
        do {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        focused[0] = dialogRoot.isShown() && dialogRoot.getWindowToken() != null
                                && dialogRoot.hasWindowFocus()
                                && isRegisteredFocusedRoot(dialogRoot);
                    } catch (Throwable error) { failure[0] = error; }
                }
            });
            rethrow(failure[0]);
            if (focused[0]) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("key-mapping dialog did not become the active focused window");
    }

    private static void awaitActivityWindowFocus(Instrumentation instrumentation,
            Activity activity, View dismissedDialogRoot) throws Exception {
        final boolean[] focused = new boolean[1];
        final Throwable[] failure = new Throwable[1];
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5);
        do {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(new Runnable() {
                @Override public void run() {
                    try {
                        View decor = activity.getWindow().getDecorView();
                        boolean dialogGone = dismissedDialogRoot == null
                                || (dismissedDialogRoot.getWindowToken() == null
                                    && !isRegisteredWindowRoot(dismissedDialogRoot));
                        focused[0] = dialogGone && decor.isShown()
                                && decor.getWindowToken() != null && decor.hasWindowFocus()
                                && isRegisteredFocusedRoot(decor);
                    } catch (Throwable error) { failure[0] = error; }
                }
            });
            rethrow(failure[0]);
            if (focused[0]) return;
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

    /** API 19 WindowManagerGlobal.mRoots is a list of ViewRootImpl objects. */
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

    private static float progress(final Instrumentation instrumentation,
                                  final ReaderView reader) {
        final float[] value = new float[1];
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() { value[0] = reader.getProgress(); }
        });
        return value[0];
    }

    private static void tap(Instrumentation instrumentation, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        try { instrumentation.sendPointerSync(event); }
        finally { event.recycle(); }
        long up = SystemClock.uptimeMillis();
        event = MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, y, 0);
        try { instrumentation.sendPointerSync(event); }
        finally { event.recycle(); }
    }

    private static void press(Instrumentation instrumentation, int keyCode) {
        long downTime = SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new KeyEvent(downTime, downTime,
                KeyEvent.ACTION_DOWN, keyCode, 0));
        long upTime = SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new KeyEvent(downTime, upTime,
                KeyEvent.ACTION_UP, keyCode, 0));
    }

    private static void rethrow(Throwable error) throws Exception {
        if (error == null) return;
        if (error instanceof Exception) throw (Exception) error;
        if (error instanceof Error) throw (Error) error;
        throw new AssertionError(error);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
