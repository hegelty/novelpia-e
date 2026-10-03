package me.crema.novelia.display;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** Wrap app content (including reader) to adjust its composited pixels in software. */
public class DisplayTuningLayout extends LinearLayout {
    private DisplayProfile profile = DisplayProfile.defaults();
    private final Paint layerPaint = new Paint();

    public DisplayTuningLayout(Context context) {
        super(context);
        setProfile(profile);
    }
    public DisplayTuningLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setProfile(profile);
    }

    public void setProfile(DisplayProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        this.profile = profile;
        layerPaint.setColorFilter(profile.isIdentity() ? null
                : new ColorMatrixColorFilter(new ColorMatrix(profile.matrix())));
        invalidate();
    }

    @Override public void draw(Canvas canvas) {
        if (profile.isIdentity() || getWidth() == 0 || getHeight() == 0) {
            super.draw(canvas);
            return;
        }
        int saved = canvas.saveLayer(0, 0, getWidth(), getHeight(), layerPaint,
                Canvas.ALL_SAVE_FLAG);
        super.draw(canvas);
        canvas.restoreToCount(saved);
    }
}
