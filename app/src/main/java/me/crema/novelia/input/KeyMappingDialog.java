package me.crema.novelia.input;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import me.crema.novelia.ui.InkUi;

/**
 * Fixed, no-scroll key capture dialog for the physical e-ink reader.
 *
 * <p>Four fixed rows (previous/next/menu/refresh), each with a label, a
 * capture button and a small clear button inside a plain {@code LinearLayout}
 * (no {@code ScrollView}); capture state never pages the reader and idle keys
 * keep their system behavior, so volume stays a system volume control until
 * the user explicitly maps it here.</p>
 *
 * <p>Capture is driven with {@code dialog.setOnKeyListener}. A capture stages
 * into an immutable {@link KeyBindings} copy; BACK, timeout or a new capture
 * rolls the staging back to the pre-capture layout. HOME/POWER cannot be
 * intercepted and stay with the system. The matching {@code ACTION_UP} of a
 * captured (or rejected) key is consumed exactly once via a token that is
 * independent of the capture state, and repeat {@code ACTION_DOWN}s are
 * consumed until that UP arrives, so the release never activates a default
 * button. While the dialog is open the parent never pages live.</p>
 *
 * <p>Only an explicit Apply delivers the staged result through
 * {@link Callback#onApply(KeyBindings)}; Cancel/Back/outside dismiss discards
 * it, and "기본값" restores the factory layout in the staging area without
 * persisting anything.</p>
 */
public final class KeyMappingDialog {

    /** Receive the staged, immutable result; only explicit Apply invokes it. */
    public interface Callback {
        /** @param bindings immutable proposed layout */
        void onApply(KeyBindings bindings);
    }

    private static final long CAPTURE_TIMEOUT_MS = 15000L;

    private static final String[] ACTION_NAMES = {
        "이전 페이지", "다음 페이지", "메뉴", "새로고침"
    };

    private KeyMappingDialog() {
        // Static entry point only.
    }

    /**
     * Opens the dialog with a staged copy of {@code current} and returns the
     * created dialog for instrumentation captures. The caller only receives
     * results through {@code callback} on explicit Apply.
     */
    public static AlertDialog show(final Activity activity, final KeyBindings current,
                                   final Callback callback) {
        if (activity == null) throw new IllegalArgumentException("activity is null");
        if (current == null) throw new IllegalArgumentException("bindings is null");

        final KeyBindings.Action[] actions = assignableActions();
        final KeyBindings[] staged = new KeyBindings[] { current };
        final KeyBindings[] captureBase = new KeyBindings[] { current };
        final String[] capturedAction = new String[] { null };     // in-flight capture target
        final int[] downToken = new int[] { 0 };                   // key whose UP must be consumed
        final boolean[] consumeBackUp = new boolean[] { false };
        final boolean[] dismissed = new boolean[] { false };
        final Button[] captureButtons = new Button[actions.length];
        final TextView[] hints = new TextView[actions.length];
        final String[] labels = new String[actions.length];
        final Runnable[] captureTimer = new Runnable[] { null };
        final View[] timerHost = new View[] { null };
        final Runnable[] finishCapture = new Runnable[] { null };
        final Runnable[] cancelCapture = new Runnable[] { null };

        // Initialized before any click can run; never null when invoked.
        finishCapture[0] = new Runnable() {
            @Override public void run() {
                if (timerHost[0] != null && captureTimer[0] != null) {
                    timerHost[0].removeCallbacks(captureTimer[0]);
                }
                captureTimer[0] = null;
            }
        };
        cancelCapture[0] = new Runnable() {
            @Override public void run() {
                if (capturedAction[0] != null) {
                    // Roll the staging back to the pre-capture layout.
                    staged[0] = captureBase[0];
                    int row = rowOf(actions, KeyBindings.Action.valueOf(capturedAction[0]));
                    if (row >= 0) {
                        captureButtons[row].setText(labels[row]);
                        hints[row].setVisibility(View.GONE);
                        hints[row].setText("");
                    }
                    capturedAction[0] = null;
                }
                finishCapture[0].run();
            }
        };

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(InkUi.dp(activity, 12), InkUi.dp(activity, 8),
                InkUi.dp(activity, 12), 0);

        for (int i = 0; i < actions.length; i++) {
            final int index = i;
            final KeyBindings.Action action = actions[i];

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = InkUi.text(activity, ACTION_NAMES[i], 17);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

            labels[i] = labelFor(activity, staged[0], action);
            final Button[] holder = new Button[1];
            holder[0] = InkUi.button(activity, labels[i], new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (dismissed[0]) return;
                    cancelCapture[0].run(); // discard a previous in-flight capture
                    captureBase[0] = staged[0];
                    capturedAction[0] = action.name();
                    downToken[0] = 0;
                    consumeBackUp[0] = false;
                    final Button capture = holder[0];
                    capture.setText("키를 누르세요…");
                    hints[index].setText("취소: 뒤로 키 · 15초 후 자동 취소");
                    hints[index].setVisibility(View.VISIBLE);
                    timerHost[0] = capture;
                    captureTimer[0] = new Runnable() {
                        @Override public void run() {
                            if (!dismissed[0]) cancelCapture[0].run();
                        }
                    };
                    capture.postDelayed(captureTimer[0], CAPTURE_TIMEOUT_MS);
                }
            });
            captureButtons[i] = holder[0];
            row.addView(holder[0], new LinearLayout.LayoutParams(0, InkUi.dp(activity, 48), 1.4f));

            Button clear = InkUi.button(activity, "해제", new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (dismissed[0]) return;
                    cancelCapture[0].run();
                    // A small clear row makes a volume mapping removable
                    // without resetting the whole layout to defaults.
                    staged[0] = staged[0].clearBinding(action);
                    labels[index] = labelFor(activity, staged[0], action);
                    captureButtons[index].setText(labels[index]);
                    hints[index].setVisibility(View.GONE);
                    hints[index].setText("");
                }
            });
            row.addView(clear, new LinearLayout.LayoutParams(InkUi.dp(activity, 60),
                    InkUi.dp(activity, 48)));
            body.addView(row);

            TextView hint = InkUi.text(activity, "", 12);
            hint.setTextColor(Color.DKGRAY);
            hint.setPadding(InkUi.dp(activity, 6), 0, 0, InkUi.dp(activity, 2));
            hint.setVisibility(View.GONE);
            hints[i] = hint;
            body.addView(hint);
        }

        TextView system = InkUi.text(activity,
                "홈/전원은 시스템 키입니다. 볼륨 키는 지정하기 전까지 시스템 볼륨으로 동작합니다.",
                12);
        system.setTextColor(Color.DKGRAY);
        system.setPadding(InkUi.dp(activity, 6), InkUi.dp(activity, 6),
                InkUi.dp(activity, 6), InkUi.dp(activity, 6));
        body.addView(system);

        // Custom InkUi footer: Apply keeps the primary treatment, the other
        // buttons stay outlined; no Holo default button skins anywhere.
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, InkUi.dp(activity, 4), 0, InkUi.dp(activity, 8));

        final AlertDialog[] dialogRef = new AlertDialog[1];
        Button apply = InkUi.button(activity, "적용", new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (dismissed[0] || dialogRef[0] == null) return;
                cancelCapture[0].run(); // an unfinished capture is discarded
                if (callback != null) callback.onApply(staged[0]);
                dialogRef[0].dismiss();
            }
        });
        InkUi.primary(apply);

        Button reset = InkUi.button(activity, "기본값", new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (dismissed[0]) return;
                cancelCapture[0].run();
                staged[0] = KeyBindings.defaults();
                for (int i = 0; i < actions.length; i++) {
                    labels[i] = labelFor(activity, staged[0], actions[i]);
                    captureButtons[i].setText(labels[i]);
                    hints[i].setVisibility(View.GONE);
                    hints[i].setText("");
                }
            }
        });

        Button cancel = InkUi.button(activity, "취소", new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (dismissed[0] || dialogRef[0] == null) return;
                dialogRef[0].dismiss();
            }
        });

        footer.addView(reset, new LinearLayout.LayoutParams(0, InkUi.dp(activity, 48), 1f));
        footer.addView(cancel, new LinearLayout.LayoutParams(0, InkUi.dp(activity, 48), 1f));
        footer.addView(apply, new LinearLayout.LayoutParams(0, InkUi.dp(activity, 48), 1f));
        body.addView(footer);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("물리 버튼")
                .setView(body)
                .create();
        dialogRef[0] = dialog;

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface dialogInterface,
                                           int keyCode, KeyEvent event) {
                return handleKey(activity, staged, captureBase, capturedAction, downToken, consumeBackUp,
                        dismissed, captureButtons, hints, labels,
                        cancelCapture, finishCapture, actions, event);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                dismissed[0] = true;
                finishCapture[0].run(); // remove pending callbacks even after dismiss
                dialog.setOnKeyListener(null);
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            me.crema.novelia.AppFont.install(dialog.getWindow().getDecorView());
            int titleId = activity.getResources().getIdentifier("alertTitle", "id", "android");
            View title = dialog.findViewById(titleId);
            if (title instanceof TextView) ((TextView) title).setTextColor(Color.BLACK);
            int dividerId = activity.getResources().getIdentifier("titleDivider", "id", "android");
            View divider = dialog.findViewById(dividerId);
            if (divider != null) divider.setBackgroundColor(Color.BLACK);
        }
        return dialog;
    }

    private static boolean handleKey(Activity activity, KeyBindings[] staged,
                                     KeyBindings[] captureBase,
                                     String[] capturedAction, int[] downToken,
                                     boolean[] consumeBackUp, boolean[] dismissed,
                                     Button[] captureButtons, TextView[] hints,
                                     String[] labels, Runnable[] cancelCapture,
                                     Runnable[] finishCapture, KeyBindings.Action[] actions,
                                     KeyEvent event) {
        if (dismissed[0]) return false;
        int keyCode = event.getKeyCode();
        int scanCode = event.getScanCode();
        int token = keyCode * 65536 + scanCode;
        int action = event.getAction();

        // Matching ACTION_UP consumes exactly once; repeat ACTION_DOWNs are
        // consumed until that UP arrives so the release can never become the
        // default button click.
        if (action == KeyEvent.ACTION_UP && downToken[0] != 0 && token == downToken[0]) {
            downToken[0] = 0;
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN && downToken[0] != 0 && token == downToken[0]) {
            return true;
        }
        // The BACK UP that cancels a capture is consumed so the dialog stays open.
        if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_UP && consumeBackUp[0]) {
            consumeBackUp[0] = false;
            return true;
        }
        if (isSystemOnlyKey(keyCode)) return false; // HOME/POWER stay with the system

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (capturedAction[0] != null) {
                cancelCapture[0].run();
                consumeBackUp[0] = true;
                return true; // consume DOWN; matching UP consumed above
            }
            return false; // idle Back closes the dialog normally
        }
        if (action != KeyEvent.ACTION_DOWN) return false;
        if (capturedAction[0] == null) return false; // idle: never interfere with keys
        if (event.getRepeatCount() != 0) {
            downToken[0] = token;
            return true;
        }

        KeyBindings.Action target = KeyBindings.Action.valueOf(capturedAction[0]);
        int row = rowOf(actions, target);
        try {
            staged[0] = captureBase[0].withBinding(target, keyCode, scanCode);
            labels[row] = KeyBindings.describe(keyCode, scanCode).toString();
            capturedAction[0] = null;
            finishCapture[0].run();
            captureButtons[row].setText("선택 · " + labels[row]);
            hints[row].setVisibility(View.GONE);
            hints[row].setText("");
            downToken[0] = token;
            return true;
        } catch (IllegalArgumentException rejected) {
            Toast.makeText(activity,
                    rejected.getMessage(),
                    Toast.LENGTH_LONG).show();
            cancelCapture[0].run();
            downToken[0] = token;
            return true;
        }
    }


    private static boolean isSystemOnlyKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_POWER:
                return true;
            default:
                return false;
        }
    }

    private static KeyBindings.Action[] assignableActions() {
        KeyBindings.Action[] values = KeyBindings.Action.values();
        KeyBindings.Action[] actions = new KeyBindings.Action[4];
        int index = 0;
        for (KeyBindings.Action value : values) {
            if (value != KeyBindings.Action.NONE) actions[index++] = value;
        }
        return actions;
    }

    private static int rowOf(KeyBindings.Action[] actions, KeyBindings.Action action) {
        for (int i = 0; i < actions.length; i++) {
            if (actions[i] == action) return i;
        }
        return -1;
    }

    private static String labelFor(Context context, KeyBindings bindings,
                                   KeyBindings.Action action) {
        if (!bindings.isAssigned(action)) return "미지정 · 누르기";
        return "바꾸기 · " + KeyBindings.describe(bindings.keyCodeOf(action),
                bindings.scanCodeOf(action));
    }
}
