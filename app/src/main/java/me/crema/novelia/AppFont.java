package me.crema.novelia;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

/** KitKat system images and old e-readers may have incomplete Hangul fallback. */
public final class AppFont {
    private static Typeface regular;
    private AppFont() {}

    public static synchronized Typeface get(Context context) {
        if (regular == null) {
            regular = Typeface.createFromAsset(context.getApplicationContext().getAssets(),
                    "fonts/NanumGothic-Regular.ttf");
        }
        return regular;
    }

    public static void apply(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Typeface font = get(view.getContext());
            if (text.getTypeface() != font) text.setTypeface(font);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
    }

    /** Also covers lazily created AlertDialog/ListView children. */
    public static void install(final View root) {
        apply(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() { apply(root); }
                });
    }
}
