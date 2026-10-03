package me.crema.novelia.reader;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * Android-backed {@link TextMeasurer} and renderer for {@link ReaderView}.
 *
 * <p>Every measurable unit is a single bounded {@link StaticLayout} of at most
 * {@link #MAX_LAYOUT_CHARS} characters, so neither pagination nor drawing ever
 * lays out the whole book. The page boundary is computed with the real layout's
 * line positions ({@code getLineBottom}) and the page itself is drawn from the
 * same bounded text with one layout — wrapped lines are never hand-stepped, so
 * paragraphs cannot overlap or misrender.</p>
 */
final class PageLayoutEngine implements TextMeasurer {

    /** Upper bound on characters in one {@link StaticLayout} call. */
    static final int MAX_LAYOUT_CHARS = 8192;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private float lineSpacing = 1.0f;
    private int layoutWidthPx = -1;

    private int lastSizeSp = -1;
    private float lastScaledDensity = Float.NaN;

    private ReaderView owner;

    void attach(ReaderView view) {
        this.owner = view;
    }

    void setLayoutWidth(int widthPx) {
        this.layoutWidthPx = Math.max(1, widthPx);
    }

    void setScaledDensity(float scaledDensity) {
        if (scaledDensity > 0f) {
            lastScaledDensity = scaledDensity;
            if (lastSizeSp >= 0) {
                paint.setTextSize(lastSizeSp * scaledDensity);
            }
        }
    }

    void setTypeface(android.graphics.Typeface typeface) {
        paint.setTypeface(typeface);
    }

    void setColor(int color) {
        paint.setColor(color);
    }

    void setTextSizeSp(int sizeSp) {
        if (sizeSp >= 4) {
            lastSizeSp = sizeSp;
            float density = lastScaledDensity;
            if (!(density > 0f)) {
                density = getScaledDensity();
            }
            paint.setTextSize(sizeSp * density);
        }
    }

    void setLineSpacing(float multiplier) {
        this.lineSpacing = Math.max(0.6f, multiplier);
    }

    private int layoutWidth() {
        return layoutWidthPx > 0 ? layoutWidthPx : 1;
    }

    @Override
    public int boundaryForHeight(CharSequence text, float heightPx) {
        if (text == null || text.length() == 0 || !(heightPx > 0f)) {
            return 0;
        }
        StaticLayout layout = staticLayout(text);
        int lines = layout.getLineCount();
        if (lines <= 1) {
            return text.length();
        }
        // Greatest line whose bottom fits the page height; if even the first
        // line overflows, take the first line anyway so pages always advance.
        int last = -1;
        for (int i = 0; i < lines; i++) {
            if (layout.getLineBottom(i) <= heightPx + 0.5f) {
                last = i;
            } else {
                break;
            }
        }
        if (last < 0) {
            return layout.getLineEnd(0);
        }
        if (last >= lines - 1) {
            return text.length();
        }
        return layout.getLineEnd(last);
    }

    @Override
    public int lineCount(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        return staticLayout(text).getLineCount();
    }

    /** Draws one page with a single bounded layout, clipped to the page area. */
    void drawPage(Canvas canvas, String text, int left, int top, int widthPx, float heightPx) {
        if (canvas == null || text == null || text.length() == 0 || widthPx <= 0 || !(heightPx > 0f)) {
            return;
        }
        StaticLayout layout = staticLayout(text, widthPx);
        canvas.save();
        canvas.clipRect(left, top, left + widthPx, top + (int) Math.ceil(heightPx));
        canvas.translate(left, top);
        layout.draw(canvas);
        canvas.restore();
    }

    private StaticLayout staticLayout(CharSequence text) {
        return staticLayout(text, layoutWidth());
    }

    private StaticLayout staticLayout(CharSequence text, int widthPx) {
        CharSequence bounded = text.length() > MAX_LAYOUT_CHARS
                ? text.subSequence(0, MAX_LAYOUT_CHARS)
                : text;
        return new StaticLayout(bounded, paint, Math.max(1, widthPx),
                Layout.Alignment.ALIGN_NORMAL, lineSpacing, 0f, true);
    }

    private float getScaledDensity() {
        if (owner != null) {
            return owner.getResources().getDisplayMetrics().scaledDensity;
        }
        return 1.0f;
    }
}
