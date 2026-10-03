package me.crema.novelia.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Non-scrolling, locally paged list with optional actions beyond this dataset. */
public class PagedListView extends LinearLayout {
    public static final int MAX_ROWS = 100;
    private static final int FOOTER_DP = 56;
    private static final int MIN_ROW_DP = 80;

    public static final class Row {
        public final String title;
        public final String detail;
        public final Runnable action;
        public final int detailLines;
        public final String quickLabel;
        public final Runnable quickAction;

        public Row(String title, String detail, Runnable action) {
            this(title, detail, action, 1);
        }

        public Row(String title, String detail, Runnable action, int detailLines) {
            this(title, detail, action, detailLines, "", null);
        }

        public Row(String title, String detail, Runnable action, int detailLines,
                   String quickLabel, Runnable quickAction) {
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
            this.action = action;
            this.detailLines = detailLines > 1 ? 2 : 1;
            this.quickLabel = quickLabel;
            this.quickAction = quickAction;
        }
    }

    private final LinearLayout body;
    private final LinearLayout footer;
    private final Button previousButton;
    private final Button nextButton;
    private final TextView range;
    private List<Row> rows = Collections.emptyList();
    private Runnable previousAction;
    private Runnable nextAction;
    private Runnable screenChanged;
    private String note = "";
    private String emptyMessage = "목록이 없습니다";
    private int first = 0;
    private int capacity = 1;
    private int pendingScreen = Integer.MIN_VALUE;
    private int lastBodyHeight = -1;
    private int lastMinRowHeight = -1;

    public PagedListView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setBackgroundColor(Color.WHITE);
        body = new LinearLayout(context);
        body.setOrientation(VERTICAL);
        body.setPadding(InkUi.dp(context, 24), 0, InkUi.dp(context, 24), 0);
        addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        footer = new LinearLayout(context);
        footer.setOrientation(HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(InkUi.dp(context, 24), 0, InkUi.dp(context, 24), 0);
        previousButton = InkUi.button(context, "이전", new OnClickListener() {
            @Override public void onClick(View view) { previous(); }
        });
        nextButton = InkUi.button(context, "다음", new OnClickListener() {
            @Override public void onClick(View view) { next(); }
        });
        range = InkUi.text(context, "", 13);
        range.setGravity(Gravity.CENTER);
        footer.addView(previousButton, new LinearLayout.LayoutParams(0,
                InkUi.dp(context, FOOTER_DP), 1));
        footer.addView(range, new LinearLayout.LayoutParams(0,
                InkUi.dp(context, FOOTER_DP), 1.2f));
        footer.addView(nextButton, new LinearLayout.LayoutParams(0,
                InkUi.dp(context, FOOTER_DP), 1));
        addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, InkUi.dp(context, FOOTER_DP)));
        render();
    }

    /** initialScreen is zero-based; -1 selects the last local screen after layout. */
    public void setRows(List<Row> values, int initialScreen) {
        List<Row> copy = new ArrayList<Row>();
        if (values != null) {
            if (values.size() > MAX_ROWS) {
                throw new IllegalArgumentException("PagedListView supports at most " + MAX_ROWS + " rows");
            }
            copy.addAll(values);
        }
        int anchor = rows.isEmpty() ? 0 : first;
        rows = Collections.unmodifiableList(copy);
        if (initialScreen == -1) {
            pendingScreen = -1;
        } else if (initialScreen >= 0) {
            pendingScreen = initialScreen;
        } else {
            pendingScreen = Integer.MIN_VALUE;
            first = clampFirst(anchor);
        }
        requestLayout();
        render();
    }

    public void setBoundaryActions(Runnable previous, Runnable next) {
        previousAction = previous;
        nextAction = next;
        updateFooter();
    }

    public void setNote(String value) {
        note = value == null ? "" : value.trim();
        updateFooter();
    }

    public void setEmptyMessage(String value) {
        emptyMessage = value == null ? "" : value;
        render();
    }

    public boolean next() {
        if (first + capacity < rows.size()) {
            first = Math.min(rows.size() - 1, first + capacity);
            render();
            changed();
            return true;
        }
        if (nextAction != null) {
            nextAction.run();
            return true;
        }
        return false;
    }

    public boolean previous() {
        if (first > 0) {
            first = Math.max(0, first - capacity);
            render();
            changed();
            return true;
        }
        if (previousAction != null) {
            previousAction.run();
            return true;
        }
        return false;
    }

    public int getScreen() {
        return first / Math.max(1, capacity);
    }
    public int getScreenCount() {
        return PageWindow.count(rows.size(), Math.max(1, capacity));
    }
    public int getVisibleCount() { return Math.min(capacity, Math.max(0, rows.size() - first)); }
    public Button getPreviousButton() { return previousButton; }
    public Button getNextButton() { return nextButton; }
    public void setOnScreenChanged(Runnable listener) { screenChanged = listener; }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int height = Math.max(0, getMeasuredHeight() - getPaddingTop() - getPaddingBottom()
                - footer.getMeasuredHeight());
        float scale = getResources().getConfiguration().fontScale;
        int baseHeight = MIN_ROW_DP;
        for (Row row : rows) {
            if (row.detailLines > 1) { baseHeight = 108; break; }
        }
        int minRow = Math.max(InkUi.dp(getContext(), baseHeight),
                Math.round(InkUi.dp(getContext(), baseHeight) * Math.max(1f, scale)));
        int rowDivider = InkUi.dp(getContext(), 1);
        int nextCapacity = Math.max(1, height / Math.max(1, minRow + rowDivider));
        boolean sizeChanged = height != lastBodyHeight || minRow != lastMinRowHeight;
        if (sizeChanged
                || pendingScreen != Integer.MIN_VALUE) {
            int oldFirst = first;
            int oldScreen = getScreen();
            lastBodyHeight = height;
            lastMinRowHeight = minRow;
            if (sizeChanged) capacity = nextCapacity;
            // Re-evaluate using the prior item anchor when the available capacity changes.
            first = clampFirst(oldFirst);
            if (pendingScreen != Integer.MIN_VALUE) {
                first = PageWindow.start(pendingScreen, rows.size(), capacity);
                pendingScreen = Integer.MIN_VALUE;
            }
            render();
            // render() replaces body children; measure the new hierarchy before drawing.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (oldScreen != getScreen()) notifyScreenChangedAfterLayout();
        }
    }

    private int clampFirst(int value) {
        return PageWindow.containing(value, rows.size(), Math.max(1,capacity));
    }

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        if (rows.isEmpty()) {
            TextView empty = InkUi.text(getContext(), emptyMessage, 16);
            empty.setGravity(Gravity.CENTER);
            body.addView(empty, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            int end = Math.min(rows.size(), first + capacity);
            int visible = end - first;
            int divider = InkUi.dp(getContext(), 1);
            int rowHeight = Math.max(1, (body.getMeasuredHeight()
                    - divider * Math.max(0, capacity - 1)) / Math.max(1, capacity));
            for (int i = first; i < end; i++) {
                final Row row = rows.get(i);
                LinearLayout item = new LinearLayout(getContext());
                item.setOrientation(HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(0, InkUi.dp(getContext(), 8), 0, InkUi.dp(getContext(), 8));
                item.setBackground(InkUi.quietBackground());
                LinearLayout textBlock = new LinearLayout(getContext());
                textBlock.setOrientation(VERTICAL);
                textBlock.setGravity(Gravity.CENTER_VERTICAL);
                TextView title = InkUi.text(getContext(), row.title, 17);
                title.setTypeface(title.getTypeface(), Typeface.BOLD);
                title.setMaxLines(2);
                title.setEllipsize(TextUtils.TruncateAt.END);
                textBlock.addView(title, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                if (!row.detail.isEmpty()) {
                    TextView detail = InkUi.text(getContext(), row.detail, 13);
                    detail.setMaxLines(row.detailLines);
                    detail.setEllipsize(TextUtils.TruncateAt.END);
                    detail.setTextColor(Color.DKGRAY);
                    LayoutParams detailParams = new LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    detailParams.topMargin = InkUi.dp(getContext(), 6);
                    textBlock.addView(detail, detailParams);
                }
                item.addView(textBlock, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                if (row.quickAction != null) {
                    Button quick = InkUi.button(getContext(), row.quickLabel, v -> row.quickAction.run());
                    quick.setContentDescription(row.title + " · " + row.quickLabel);
                    LayoutParams quickParams = new LayoutParams(InkUi.dp(getContext(), 80),
                            InkUi.dp(getContext(), 48));
                    quickParams.leftMargin = InkUi.dp(getContext(), 12);
                    item.addView(quick, quickParams);
                }
                if (row.action != null) {
                    item.setClickable(true);
                    item.setFocusable(true);
                    item.setOnClickListener(new OnClickListener() {
                        @Override public void onClick(View view) { row.action.run(); }
                    });
                }
                body.addView(item, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
                if (i + 1 < end) body.addView(InkUi.rule(getContext()),
                        new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, divider));
            }
            if (visible < capacity) {
                body.addView(new View(getContext()),
                        new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            }
        }
        updateFooter();
    }

    private void updateFooter() {
        if (range == null) return;
        String value;
        if (rows.isEmpty()) value = "0 / 0";
        else value = (first + 1) + "–" + Math.min(rows.size(), first + getVisibleCount()) + " / " + rows.size();
        if (!note.isEmpty()) value += " · " + note;
        range.setText(value);
        previousButton.setEnabled(first > 0 || previousAction != null);
        nextButton.setEnabled(first + getVisibleCount() < rows.size() || nextAction != null);
    }

    private void changed() { if (screenChanged != null) screenChanged.run(); }

    private void notifyScreenChangedAfterLayout() {
        post(new Runnable() {
            @Override public void run() { changed(); }
        });
    }
}
