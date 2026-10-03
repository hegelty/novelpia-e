package me.crema.novelia;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Field;

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
        instrumentation.waitForIdleSync();
        reader.getLocationOnScreen(location);
        if (reader.getWidth() <= 0 || reader.getHeight() <= 0) {
            throw new AssertionError("reader has no bounds for physical input");
        }

        // A real tap enters ViewRoot touch mode. Tap the middle of the body;
        // this opens reader tools without changing page progress.
        tap(instrumentation, location[0] + reader.getWidth() / 2f,
                location[1] + reader.getHeight() / 2f);
        instrumentation.waitForIdleSync();
        final boolean firstTapEnteredTouchMode = reader.isInTouchMode();
        final boolean readerFocusableInTouchMode = reader.isFocusableInTouchMode();
        final boolean readerFocusedAfterTouch = reader.isFocused();

        float before = progress(instrumentation, reader);
        press(instrumentation, KeyEvent.KEYCODE_PAGE_DOWN);
        instrumentation.waitForIdleSync();
        float after = progress(instrumentation, reader);
        require(after > before, "first injected PAGE_DOWN after a real touch did not advance");
        require(firstTapEnteredTouchMode, "first pointer tap did not enter touch mode");
        require(readerFocusableInTouchMode, "reader is not focusable in touch mode");
        require(readerFocusedAfterTouch, "reader did not hold focus when tapped");

        // Re-enter touch mode from the actual input queue, then make sure the
        // next PAGE_DOWN also reaches the reader after a preceding touch.
        tap(instrumentation, location[0] + reader.getWidth() / 2f,
                location[1] + reader.getHeight() / 2f);
        instrumentation.waitForIdleSync();
        requireTouchFocusableFocus(reader);
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
        before = progress(instrumentation, reader);
        press(instrumentation, KeyEvent.KEYCODE_PAGE_DOWN);
        instrumentation.waitForIdleSync();
        require(Math.abs(progress(instrumentation, reader) - before) < 0.0001f,
                "PAGE_DOWN paged reader while key-mapping dialog was open");
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() { dialog[0].dismiss(); }
        });
        instrumentation.waitForIdleSync();

        // A focused ReaderView must still let an unhandled configured key
        // reach MainActivity.dispatchKeyEvent(). Use F1 to avoid default maps.
        final Field bindingsField = MainActivity.class.getDeclaredField("keyBindings");
        bindingsField.setAccessible(true);
        final KeyBindings oldBindings = (KeyBindings) bindingsField.get(activity);
        final KeyBindings menuBindings = oldBindings
                .withBinding(KeyBindings.Action.MENU, KeyEvent.KEYCODE_F1, 0);
        final Field toolsField = MainActivity.class.getDeclaredField("readerTools");
        toolsField.setAccessible(true);
        final View tools = (View) toolsField.get(activity);
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                try {
                    bindingsField.set(activity, menuBindings);
                    tools.setVisibility(View.GONE);
                    reader.requestFocus();
                } catch (Throwable t) { error[0] = t; }
            }
        });
        instrumentation.waitForIdleSync();
        rethrow(error[0]);
        press(instrumentation, KeyEvent.KEYCODE_F1);
        instrumentation.waitForIdleSync();
        require(tools.getVisibility() == View.VISIBLE,
                "configured MENU key was swallowed by focused ReaderView");
        instrumentation.runOnMainSync(new Runnable() {
            @Override public void run() {
                try { bindingsField.set(activity, oldBindings); }
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

    private static void requireTouchFocusableFocus(ReaderView reader) {
        if (!reader.isInTouchMode()) throw new AssertionError("tap did not enter touch mode");
        if (!reader.isFocusableInTouchMode())
            throw new AssertionError("reader is not focusable in touch mode");
        if (!reader.isFocused()) throw new AssertionError("reader did not retain focus in touch mode");
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
