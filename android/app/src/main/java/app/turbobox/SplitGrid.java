package app.turbobox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.Arrays;

/**
 * Lays out the split view: rows of panes with draggable dividers between them. Drag a divider to give one page more room;
 * the shares are remembered per arrangement ({@link #setOnSaved}). A pane with a fixed shape gives what it leaves empty to
 * its neighbours when reclaim is on ({@link SplitMath}). The Mini window uses the same grid without dividers.
 */
final class SplitGrid extends ViewGroup {
    interface OnSaved {
        void saved(String key, String encoded);
    }

    private final Paint gap = new Paint();
    private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float thickness;
    private final float slop;
    private int[] rows = {1};
    private float[] rowShare = {1f};
    private float[][] colShare = {{1f}};
    private float[] ratio = new float[0];
    private boolean reclaim;
    private boolean dividers;
    private String key = "";
    private OnSaved onSaved;

    // Where things ended up, in pixels (kept for drawing dividers and for hit tests).
    private int[] rowTop = new int[1];
    private int[] rowHeight = new int[1];
    private int[][] colLeft = new int[1][1];
    private int[][] colWidth = new int[1][1];

    // A drag in progress.
    private int dragRow = -1;      // the row boundary being dragged, or -1
    private int dragCol = -1;      // the column boundary being dragged (in dragColRow), or -1
    private int dragColRow;
    private float dragFrom;
    private float[] dragStart;

    SplitGrid(Context c) {
        super(c);
        thickness = Ui.dpf(c, 10);
        slop = Ui.dpf(c, 16);
        gap.setColor(Ui.BG);
        grip.setColor(0xFF4A4A56);
        setWillNotDraw(false);
    }

    void setOnSaved(OnSaved l) {
        onSaved = l;
    }

    /** The arrangement, the saved shares for it (or null) under {@code key}, and whether dividers show and can be dragged. */
    void configure(int[] newRows, String newKey, String saved, boolean withDividers) {
        boolean same = Arrays.equals(rows, newRows) && newKey.equals(key);
        rows = newRows.clone();
        key = newKey;
        dividers = withDividers;
        if (!same || saved != null) {
            String[] parts = saved == null ? new String[0] : saved.split(";");
            float[] r = parts.length == rows.length + 1 ? SplitMath.decode(parts[0], rows.length) : null;
            rowShare = r != null ? r : SplitMath.normalize(null, rows.length);
            colShare = new float[rows.length][];
            for (int i = 0; i < rows.length; i++) {
                float[] c = r != null ? SplitMath.decode(parts[i + 1], rows[i]) : null;
                colShare[i] = c != null ? c : SplitMath.normalize(null, rows[i]);
            }
        }
        requestLayout();
    }

    /** Width over height each pane (in order) must keep, 0 = free. */
    void setRatios(float[] r, boolean reclaimEmpty) {
        ratio = r.clone();
        reclaim = reclaimEmpty;
        requestLayout();
    }

    private String encode() {
        StringBuilder b = new StringBuilder(SplitMath.encode(rowShare));
        for (float[] c : colShare) b.append(';').append(SplitMath.encode(c));
        return b.toString();
    }

    private float gapSize() {
        return dividers ? thickness : 0f;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        int n = rows.length;
        int g = Math.round(gapSize());
        rowTop = new int[n];
        rowHeight = new int[n];
        colLeft = new int[n][];
        colWidth = new int[n][];

        // Row heights. A row with one pane of a fixed shape needs only width / ratio.
        int[] caps = new int[n];
        int next = 0;
        for (int i = 0; i < n; i++) {
            if (reclaim && rows[i] == 1 && next < ratio.length && ratio[next] > 0) caps[i] = Math.round(w / ratio[next]);
            next += rows[i];
        }
        int[] heights = SplitMath.allocate(rowShare, caps, Math.max(0, h - g * (n - 1)));
        int y = 0;
        next = 0;
        for (int i = 0; i < n; i++) {
            rowTop[i] = y;
            rowHeight[i] = heights[i];
            int cols = rows[i];
            int[] widthCaps = new int[cols];
            for (int j = 0; j < cols; j++) {
                int k = next + j;
                if (reclaim && k < ratio.length && ratio[k] > 0) widthCaps[j] = Math.round(heights[i] * ratio[k]);
            }
            int[] widths = SplitMath.allocate(colShare[i], widthCaps, Math.max(0, w - g * (cols - 1)));
            colLeft[i] = new int[cols];
            colWidth[i] = widths;
            int x = 0;
            for (int j = 0; j < cols; j++) {
                colLeft[i][j] = x;
                x += widths[j] + g;
            }
            y += heights[i] + g;
            next += cols;
        }
        next = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < rows[i]; j++, next++) {
                View v = next < getChildCount() ? getChildAt(next) : null;
                if (v != null) v.measure(MeasureSpec.makeMeasureSpec(colWidth[i][j], MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rowHeight[i], MeasureSpec.EXACTLY));
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int next = 0;
        for (int i = 0; i < rows.length; i++) {
            for (int j = 0; j < rows[i]; j++, next++) {
                if (next >= getChildCount()) return;
                getChildAt(next).layout(colLeft[i][j], rowTop[i], colLeft[i][j] + colWidth[i][j], rowTop[i] + rowHeight[i]);
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!dividers) return;
        c.drawRect(0, 0, getWidth(), getHeight(), gap);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (!dividers) return;
        float len = Ui.dpf(getContext(), 36), thick = Ui.dpf(getContext(), 4);
        RectF r = new RectF();
        for (int i = 0; i + 1 < rows.length; i++) {
            float y = rowTop[i] + rowHeight[i] + thickness / 2;
            r.set(getWidth() / 2f - len / 2, y - thick / 2, getWidth() / 2f + len / 2, y + thick / 2);
            c.drawRoundRect(r, thick, thick, grip);
        }
        for (int i = 0; i < rows.length; i++) {
            for (int j = 0; j + 1 < rows[i]; j++) {
                float x = colLeft[i][j] + colWidth[i][j] + thickness / 2;
                float cy = rowTop[i] + rowHeight[i] / 2f;
                r.set(x - thick / 2, cy - len / 2, x + thick / 2, cy + len / 2);
                c.drawRoundRect(r, thick, thick, grip);
            }
        }
    }

    /** Is this point on (or near) a divider? Sets the drag target when it is. */
    private boolean hit(float x, float y) {
        if (!dividers) return false;
        for (int i = 0; i + 1 < rows.length; i++) {
            float mid = rowTop[i] + rowHeight[i] + thickness / 2;
            if (Math.abs(y - mid) <= slop) {
                dragRow = i;
                dragCol = -1;
                return true;
            }
        }
        for (int i = 0; i < rows.length; i++) {
            if (y < rowTop[i] || y > rowTop[i] + rowHeight[i]) continue;
            for (int j = 0; j + 1 < rows[i]; j++) {
                float mid = colLeft[i][j] + colWidth[i][j] + thickness / 2;
                if (Math.abs(x - mid) <= slop) {
                    dragRow = -1;
                    dragCol = j;
                    dragColRow = i;
                    return true;
                }
            }
        }
        return false;
    }

    /** The shares as they are on screen now (a pane held to its shape has less than its share). */
    private void adoptActual() {
        float h = 0;
        for (int v : rowHeight) h += v;
        float[] rs = new float[rows.length];
        for (int i = 0; i < rows.length; i++) rs[i] = h > 0 ? rowHeight[i] / h : 1f / rows.length;
        rowShare = SplitMath.normalize(rs, rows.length);
        for (int i = 0; i < rows.length; i++) {
            float w = 0;
            for (int v : colWidth[i]) w += v;
            float[] cs = new float[rows[i]];
            for (int j = 0; j < rows[i]; j++) cs[j] = w > 0 ? colWidth[i][j] / w : 1f / rows[i];
            colShare[i] = SplitMath.normalize(cs, rows[i]);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && hit(e.getX(), e.getY())) {
            begin(e);
            return true;
        }
        return false;
    }

    private void begin(MotionEvent e) {
        adoptActual();
        if (dragRow >= 0) {
            dragFrom = e.getY();
            dragStart = rowShare.clone();
        } else {
            dragFrom = e.getX();
            dragStart = colShare[dragColRow].clone();
        }
        getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (dragRow < 0 && dragCol < 0) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                if (dragRow >= 0) {
                    float total = Math.max(1f, getHeight() - gapSize() * (rows.length - 1));
                    rowShare = SplitMath.drag(dragStart, dragRow, (e.getY() - dragFrom) / total);
                } else {
                    float total = Math.max(1f, getWidth() - gapSize() * (rows[dragColRow] - 1));
                    colShare[dragColRow] = SplitMath.drag(dragStart, dragCol, (e.getX() - dragFrom) / total);
                }
                requestLayout();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragRow = dragCol = -1;
                if (onSaved != null) onSaved.saved(key, encode());
                return true;
            default:
                return true;
        }
    }
}
