package me.crema.novelia.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import me.crema.novelia.AppFont;
import me.crema.novelia.ui.InkUi;

/** Stages adjustments until Apply; Cancel never writes preferences. */
public final class DisplaySettingsDialog {
    public interface Callback { void onApply(DisplayProfile profile); }

    private DisplaySettingsDialog() { }

    public static AlertDialog show(final Activity activity, DisplayProfile original, final Callback callback) {
        final DisplayProfile[] staged = { original == null ? DisplayProfile.defaults() : original };
        LinearLayout content = column(activity);
        content.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 10));
        TextView title = label(activity, "화면 조절", 20);
        title.setPadding(0, 0, 0, dp(activity, 8));
        content.addView(title);
        TextView note = label(activity, "앱 화면의 색만 바꿉니다. 기기 조명 밝기는 바뀌지 않습니다.", 13);
        content.addView(note);

        final DisplayTuningLayout preview = new DisplayTuningLayout(activity);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6));
        preview.setBackgroundColor(Color.WHITE);
        TextView sample = label(activity, "가나다 ABC  밝은 글 · 어두운 글", 16);
        sample.setTextColor(Color.BLACK);
        preview.addView(sample);
        TextView gray = label(activity, "중간 회색 글자  ▪  색상 미리보기", 14);
        gray.setTextColor(Color.GRAY);
        preview.addView(gray);
        LinearLayout colors = new LinearLayout(activity);
        for (int color : new int[] { 0xffa65858, 0xff59927a, 0xff6b79a5 }) {
            View swatch = new View(activity);
            swatch.setBackgroundColor(color);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(activity, 14), 1);
            lp.setMargins(0, dp(activity, 3), dp(activity, 5), 0);
            colors.addView(swatch, lp);
        }
        preview.addView(colors);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, -2);
        previewParams.setMargins(0, dp(activity, 8), 0, dp(activity, 6));
        content.addView(preview, previewParams);

        final TextView[] values = new TextView[3];
        final Button[] minusButtons = new Button[3];
        final Button[] plusButtons = new Button[3];
        String[] names = { "화면 명도", "대비", "채도" };
        final int[] increments = { 10, 10, 25 };
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(activity, 3), 0, dp(activity, 3));
            TextView name = label(activity, names[i], 16);
            row.addView(name, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
            name.setGravity(Gravity.CENTER_VERTICAL);
            Button minus = button(activity, "−");
            minusButtons[i] = minus;
            row.addView(minus, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
            TextView value = label(activity, "", 15);
            value.setGravity(Gravity.CENTER);
            values[i] = value;
            row.addView(value, new LinearLayout.LayoutParams(dp(activity, 68), dp(activity, 48)));
            Button plus = button(activity, "+");
            plusButtons[i] = plus;
            row.addView(plus, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
            minus.setOnClickListener(v -> {
                staged[0] = change(staged[0], index, -increments[index]);
                refresh(staged[0], values, minusButtons, plusButtons, preview);
            });
            plus.setOnClickListener(v -> {
                staged[0] = change(staged[0], index, increments[index]);
                refresh(staged[0], values, minusButtons, plusButtons, preview);
            });
            content.addView(row);
        }
        TextView hint = label(activity, "흑백 화면에서는 채도 변화가 보이지 않을 수 있습니다.", 12);
        hint.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        content.addView(hint);
        refresh(staged[0], values, minusButtons, plusButtons, preview);

        final AlertDialog dialog = new AlertDialog.Builder(activity).setView(content).create();
        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button reset = button(activity, "기본값");
        Button cancel = button(activity, "취소");
        Button apply = button(activity, "적용");
        InkUi.primary(apply);
        actions.addView(reset, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
        actions.addView(apply, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
        content.addView(actions);
        reset.setOnClickListener(v -> {
            staged[0] = DisplayProfile.defaults();
            refresh(staged[0], values, minusButtons, plusButtons, preview);
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        apply.setOnClickListener(v -> {
            if (callback != null) callback.onApply(staged[0]);
            dialog.dismiss();
        });
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        AppFont.install(dialog.getWindow().getDecorView());
        return dialog;
    }

    private static DisplayProfile change(DisplayProfile p, int index, int delta) {
        return new DisplayProfile(p.brightness + (index == 0 ? delta : 0),
                p.contrast + (index == 1 ? delta : 0),
                p.saturation + (index == 2 ? delta : 0));
    }

    private static void refresh(DisplayProfile p, TextView[] values, Button[] minus,
                                Button[] plus, DisplayTuningLayout preview) {
        values[0].setText((p.brightness > 0 ? "+" : "") + p.brightness);
        values[1].setText(p.contrast + "%");
        values[2].setText(p.saturation + "%");
        minus[0].setEnabled(p.brightness > -40);
        plus[0].setEnabled(p.brightness < 40);
        minus[1].setEnabled(p.contrast > 80);
        plus[1].setEnabled(p.contrast < 160);
        minus[2].setEnabled(p.saturation > 0);
        plus[2].setEnabled(p.saturation < 100);
        preview.setProfile(p);
    }

    private static LinearLayout column(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.WHITE);
        return layout;
    }

    private static TextView label(Activity activity, String text, int sp) {
        return InkUi.text(activity, text, sp);
    }

    private static Button button(Activity activity, String text) {
        return InkUi.button(activity, text, null);
    }

    private static int dp(Activity activity, int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
