package me.crema.novelia.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.StateSet;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import me.crema.novelia.AppFont;

/** Small black-and-white primitives for the e-ink interface. */
public final class InkUi {
    private InkUi() {}

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context context, String value, int sp) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.BLACK);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTypeface(AppFont.get(context));
        return view;
    }

    public static View rule(Context context) {
        View view = new View(context);
        view.setBackgroundColor(Color.rgb(190, 190, 190));
        view.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return view;
    }

    public static Button button(Context context, String label, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(colors(Color.BLACK, Color.WHITE, Color.GRAY));
        button.setAllCaps(false);
        button.setTypeface(AppFont.get(context));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        button.setBackground(states(false));
        button.setOnClickListener(listener);
        return button;
    }

    /** Turns a regular outlined button into the high-contrast primary treatment. */
    public static void primary(Button button) {
        button.setTextColor(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_pressed},
                new int[]{android.R.attr.state_focused},
                StateSet.WILD_CARD
        }, new int[]{Color.LTGRAY, Color.BLACK, Color.BLACK, Color.WHITE}));
        button.setBackground(states(true));
    }

    /** Unboxed action with nonanimated, visible focus/press feedback. */
    public static void quiet(Button button) {
        button.setTextColor(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled}, StateSet.WILD_CARD
        }, new int[]{Color.GRAY, Color.BLACK}));
        button.setBackground(quietBackground());
    }

    public static StateListDrawable quietBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(Color.rgb(225,225,225)));
        states.addState(new int[]{android.R.attr.state_focused},
                new android.graphics.drawable.ColorDrawable(Color.rgb(235,235,235)));
        states.addState(StateSet.WILD_CARD, new android.graphics.drawable.ColorDrawable(Color.WHITE));
        return states;
    }

    private static ColorStateList colors(int normal, int active, int disabled) {
        return new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_pressed},
                new int[]{android.R.attr.state_focused},
                StateSet.WILD_CARD
        }, new int[]{disabled, active, active, normal});
    }

    private static StateListDrawable states(boolean primary) {
        StateListDrawable result = new StateListDrawable();
        result.addState(new int[]{-android.R.attr.state_enabled},
                shape(primary ? Color.rgb(90, 90, 90) : Color.rgb(235, 235, 235),
                        Color.rgb(170, 170, 170)));
        result.addState(new int[]{android.R.attr.state_pressed},
                shape(primary ? Color.WHITE : Color.BLACK, Color.BLACK));
        result.addState(new int[]{android.R.attr.state_focused},
                shape(primary ? Color.WHITE : Color.BLACK, Color.BLACK));
        result.addState(StateSet.WILD_CARD,
                shape(primary ? Color.BLACK : Color.WHITE, Color.BLACK));
        return result;
    }

    private static GradientDrawable shape(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(0);
        drawable.setStroke( dpForStroke(), stroke);
        return drawable;
    }

    // A one-pixel border remains hairline-like on the target e-ink display.
    private static int dpForStroke() { return 1; }
}
