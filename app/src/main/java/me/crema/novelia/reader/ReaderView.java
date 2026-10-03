package me.crema.novelia.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * Standalone native Android text reader. Renders one page at a time with a
 * single bounded {@code StaticLayout} (at most
 * {@link TextPager#MAX_PAGE_CHARS} characters), so neither pagination nor
 * drawing ever lays out the whole book.
 *
 * <p>No AndroidX, no dependencies, no animations, and no touch handling:
 * page navigation is driven by the parent activity through {@link #nextPage()}
 * and {@link #previousPage()}. Progress is a {@code 0..1} character fraction
 * of the whole book, so it survives font size, line spacing, page size, and
 * re-pagination changes.</p>
 *
 * <h2>Integration with a parent activity</h2>
 * <pre>
 * ReaderView reader = findViewById(R.id.reader);
 * reader.setContent(title, text);
 * reader.setTextSizeSp(20);
 * reader.setLineSpacing(1.3f);
 * reader.setOnProgressChangedListener(new ReaderView.OnProgressChangedListener() {
 *     public void onProgressChanged(ReaderView view, float progress, int pageIndex, int pageCount) {
 *         // update page labels, save progress, disable page buttons at ends
 *     }
 * });
 * // wire buttons:
 * previousButton.setOnClickListener(v -&gt; reader.previousPage());
 * nextButton.setOnClickListener(v -&gt; reader.nextPage());
 * // on Activity.onSaveInstanceState():
 * saved.putFloat("novelia.progress", reader.getProgress());
 * // on Activity.onCreate()/onRestoreInstanceState(), after setContent:
 * reader.restoreProgress(saved.getFloat("novelia.progress", 0f));
 * </pre>
 */
public class ReaderView extends View {

    /** Maximum accepted text length; longer books are rejected. */
    public static final int MAX_TEXT_LENGTH = 300000;

    /** Minimum font size in sp. */
    public static final int MIN_TEXT_SIZE_SP = 16;
    /** Maximum font size in sp. */
    public static final int MAX_TEXT_SIZE_SP = 36;

    private static final float MIN_SCALED_DENSITY = 0.8f;

    /** Callback for progress / navigation state changes. */
    public interface OnProgressChangedListener {
        /** Called when the reader's position changes (may be posted). */
        void onProgressChanged(ReaderView view, float progress, int pageIndex, int pageCount);
    }

    private final PageLayoutEngine engine = new PageLayoutEngine();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable notifyRunner = new Runnable() {
        @Override
        public void run() {
            notifyProgressChanged();
        }
    };

    private String title = "";
    private String text = "";
    private TextPager pager;
    private int pageIndex = 0;
    private int lastWidth = -1;
    private int lastHeight = -1;
    private boolean pendingLayout = true;
    private boolean notifyPending = false;

    private int pendingCharOffset = -1;
    private OnProgressChangedListener listener;

    public ReaderView(Context context) {
        super(context);
        init();
    }

    public ReaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ReaderView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        engine.attach(this);
        engine.setTypeface(me.crema.novelia.AppFont.get(getContext()));
        engine.setScaledDensity(getScaledDensitySafe());
        engine.setTextSizeSp(22);
        engine.setLineSpacing(1.0f);
        engine.setColor(Color.BLACK);
        setBackgroundColor(Color.WHITE);
        int margin = Math.round(14 * getResources().getDisplayMetrics().density);
        setPadding(margin, margin, margin, margin);
        pager = TextPager.create(text);
        setWillNotDraw(false);
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Loads a book. Safe to call again at any time: drops all pagination and
     * starts from the beginning. Books longer than {@link #MAX_TEXT_LENGTH}
     * characters are rejected (returns false) rather than truncated, so no
     * UTF-16 surrogate pair is ever split at the tail.
     *
     * @return false if {@code text} is longer than {@link #MAX_TEXT_LENGTH};
     *         the view keeps its previous content in that case
     */
    public boolean setContent(String title, String text) {
        String safe = text == null ? "" : text;
        if (safe.length() > MAX_TEXT_LENGTH) {
            return false;
        }
        this.title = title == null ? "" : title;
        this.text = safe;
        pager = TextPager.create(this.text);
        pendingCharOffset = 0;
        pageIndex = 0;
        lastWidth = -1;
        lastHeight = -1;
        pendingLayout = true;
        notifyPending = false;
        requestLayout();
        invalidate();
        return true;
    }

    /** The book title (unused by the view itself, handy for parents). */
    public String getTitle() {
        return title;
    }

    /** Sets the font size in sp (16..36, otherwise clamped). */
    public void setTextSizeSp(int sizeSp) {
        int clamped = Math.max(MIN_TEXT_SIZE_SP, Math.min(MAX_TEXT_SIZE_SP, sizeSp));
        rememberOffset();
        engine.setTextSizeSp(clamped);
        pendingLayout = true;
        lastWidth = -1;
        lastHeight = -1;
        invalidate();
    }

    /** Sets line spacing as a multiple of the normal line height (1.0 default). */
    public void setLineSpacing(float multiplier) {
        rememberOffset();
        engine.setLineSpacing(multiplier);
        pendingLayout = true;
        lastWidth = -1;
        lastHeight = -1;
        invalidate();
    }

    /** Advances to the next page if there is one. */
    public void nextPage() {
        applyPendingLayout();
        if (pager.page(pageIndex + 1) != null) {
            pageIndex++;
            notifyPending = true;
            invalidate();
        }
    }

    /** Returns to the previous page if there is one. */
    public void previousPage() {
        applyPendingLayout();
        if (pageIndex > 0) {
            pageIndex--;
            notifyPending = true;
            invalidate();
        }
    }

    /** Current progress in {@code [0f, 1f]} over the whole book. */
    public float getProgress() {
        if (text.length() == 0) {
            return 0f;
        }
        if (pendingCharOffset >= 0) return clamp01(pendingCharOffset / (float) text.length());
        PageBreak current = pager.page(pageIndex);
        int start = current != null ? current.getStart() : 0;
        return clamp01(start / (float) text.length());
    }

    /**
     * Restores a saved progress value (from {@link #getProgress()}). May be
     * called any time, including before the first layout: the character
     * offset is kept pending and applied when pages are (re)computed, so
     * progress is preserved across size/font/spacing changes.
     */
    public void restoreProgress(float progress) {
        float p = Float.isNaN(progress) ? 0f : Math.max(0f, Math.min(1f, progress));
        pendingCharOffset = Math.round(p * text.length());
        pendingLayout = true;
        invalidate();
    }

    /** Whether the current page is the last page. */
    public boolean isLastPage() {
        if (pager.totalPages() == 0) {
            return true;
        }
        int total = pager.totalPages();
        if (pageIndex >= total - 1) {
            return true;
        }
        return false;
    }

    /** Whether the current page is the first page. */
    public boolean isFirstPage() {
        return pager.totalPages() == 0 || pageIndex <= 0;
    }

    /** Sets or replaces the progress listener. */
    public void setOnProgressChangedListener(OnProgressChangedListener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // View lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(Math.max(1, getSuggestedMinimumWidth()), widthMeasureSpec);
        int height = resolveSize(Math.max(1, getSuggestedMinimumHeight()), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != lastWidth || h != lastHeight) {
            lastWidth = w;
            lastHeight = h;
            rememberOffset();
            pendingLayout = true;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pendingLayout) {
            applyPendingLayout();
        }
        if (text.length() == 0) {
            return;
        }
        PageBreak page = pager.page(pageIndex);
        if (page == null) {
            return;
        }
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int width = Math.max(1, getWidth() - left - getPaddingRight());
        int height = Math.max(1, getHeight() - top - getPaddingBottom());
        engine.drawPage(canvas, page.getText(), left, top, width, height);
        if (notifyPending) {
            notifyPending = false;
            postNotify();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mainHandler.removeCallbacks(notifyRunner);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Captures the current reading offset before pagination is dropped. Must
     * run before {@code pendingLayout = true} invalidations so resize/font/
     * spacing changes never reset the position.
     */
    private void rememberOffset() {
        // Preserve explicit restore requests and offsets waiting for relayout.
        if (pendingCharOffset >= 0) return;
        PageBreak current = pager.page(pageIndex);
        if (current != null) {
            pendingCharOffset = current.getStart();
        } else {
            pendingCharOffset = 0;
        }
    }

    /** Applies the pending layout and restores the pending position. */
    private void applyPendingLayout() {
        if (!pendingLayout) {
            return;
        }
        pendingLayout = false;
        if (getWidth() <= 0 || getHeight() <= 0) {
            pendingLayout = true; // not measurable yet; retry on next draw
            return;
        }
        int width = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
        int height = Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom());
        engine.setLayoutWidth(width);
        pager.configure(engine, height);
        // Restore the pending position, clamped to a valid character offset.
        int offset = pendingCharOffset >= 0 ? pendingCharOffset : 0;
        pendingCharOffset = -1;
        pageIndex = pager.findPageIndex(offset);
        notifyPending = true;
        invalidate();
    }

    private float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private void postNotify() {
        mainHandler.removeCallbacks(notifyRunner);
        mainHandler.post(notifyRunner);
    }

    private void notifyProgressChanged() {
        if (listener == null || text.length() == 0) {
            return;
        }
        listener.onProgressChanged(this, getProgress(), pageIndex, pager.totalPages());
    }

    private float getScaledDensitySafe() {
        try {
            return Math.max(MIN_SCALED_DENSITY, getResources().getDisplayMetrics().scaledDensity);
        } catch (Throwable t) {
            return 1.0f;
        }
    }
}
