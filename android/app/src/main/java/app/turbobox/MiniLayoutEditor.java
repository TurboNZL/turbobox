package app.turbobox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Drawn over the pages themselves while a layout is being arranged: one frame per page. Drag a frame to move that page, drag its
 * corner to resize it. The page last touched is selected and can be brought forward or sent back; the pages are drawn in that
 * order, so the one in front is on top, as in the real window. The pages underneath stay visible and live.
 */
final class MiniLayoutEditor extends View {
    interface OnChange {
        void changed(float[][] rects, int[] order, boolean[] floats);
    }

    private static final int[] COLORS = {0xFFA6E22E, 0xFF66D9EF, 0xFFFD971F, 0xFFF92672};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float handle;
    private final OnChange onChange;
    private float[][] rects = MiniLayout.defaults();
    private int[] order = {0, 1};      // back to front
    private boolean[] floats = new boolean[MiniLayout.PAGES];   // pages that float: movable, not resizable, not touchable
    private boolean snap;
    private int pages = 2;
    private int selected = -1;
    private boolean resizing;
    private boolean dragging;
    private float lastX, lastY;
    private float[] raw;   // where the page would be without snapping: snapping is shown, never accumulated

    MiniLayoutEditor(Context c, OnChange onChange) {
        super(c);
        this.onChange = onChange;
        handle = Ui.dpf(c, 30);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.dpf(c, 3));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Ui.dpf(c, 22));
        label.setFakeBoldText(true);
        setBackgroundColor(0x00000000);   // the pages show through
    }

    /** Edges meet when brought close (pages can still overlap). Off by default. */
    void setSnap(boolean on) {
        snap = on;
    }

    /** Makes the selected page float (or not): it can be moved around like a picture in picture, but not resized or used. */
    boolean toggleFloat() {
        if (selected < 0) return false;
        floats[selected] = !floats[selected];
        invalidate();
        onChange.changed(rects, order, floats);
        return true;
    }

    boolean isFloating(int page) {
        return page >= 0 && page < floats.length && floats[page];
    }

    void set(float[][] r, int[] z, boolean[] f) {
        if (f != null) floats = f;
        rects = r;
        pages = Math.max(1, Math.min(MiniLayout.PAGES, z.length));
        order = z.clone();
        if (selected >= pages) selected = -1;
        invalidate();
    }

    void select(int page) {
        selected = page >= 0 && page < pages ? page : -1;
        invalidate();
    }

    int selected() {
        return selected;
    }

    /** Moves the selected page one step toward the front (+1) or the back (-1). */
    boolean move(int step) {
        if (selected < 0) return false;
        int at = -1;
        for (int i = 0; i < order.length; i++) if (order[i] == selected) at = i;
        int to = at + step;
        if (at < 0 || to < 0 || to >= order.length) return false;
        int t = order[at];
        order[at] = order[to];
        order[to] = t;
        invalidate();
        onChange.changed(rects, order, floats);
        return true;
    }

    private RectF px(int i) {
        float[] r = rects[i];
        return new RectF(r[0] * getWidth(), r[1] * getHeight(), (r[0] + r[2]) * getWidth(), (r[1] + r[3]) * getHeight());
    }

    @Override
    protected void onDraw(Canvas c) {
        for (int k = 0; k < pages; k++) {
            int i = order[k];
            RectF r = px(i);
            int col = COLORS[i % COLORS.length];
            fill.setColor(col);
            fill.setAlpha(i == selected ? 90 : 40);
            c.drawRect(r, fill);
            edge.setColor(col);
            edge.setAlpha(i == selected ? 255 : 170);
            c.drawRect(r, edge);
            label.setColor(col);
            c.drawText("Page " + (i + 1) + (floats[i] ? " (floating)" : ""), r.centerX(), r.top + label.getTextSize() * 1.2f, label);
            if (!floats[i]) {
                fill.setColor(col);
                fill.setAlpha(255);
                float h2 = handle / 2f;   // the resize corners: all four
                c.drawRect(r.left, r.top, r.left + h2, r.top + h2, fill);
                c.drawRect(r.right - h2, r.top, r.right, r.top + h2, fill);
                c.drawRect(r.right - h2, r.bottom - h2, r.right, r.bottom, fill);
                c.drawRect(r.left, r.bottom - h2, r.left + h2, r.bottom, fill);
            }
        }
    }

    private int corner = -1;   // 0 top left, 1 top right, 2 bottom right, 3 bottom left; -1 = moving
    private android.view.ScaleGestureDetector pinch;
    private boolean pinching;

    private int cornerAt(RectF r, float x, float y) {
        float[][] pts = {{r.left, r.top}, {r.right, r.top}, {r.right, r.bottom}, {r.left, r.bottom}};
        for (int i = 0; i < 4; i++) if (Math.abs(x - pts[i][0]) < handle && Math.abs(y - pts[i][1]) < handle) return i;
        return -1;
    }

    private void apply(float[] want, boolean l, boolean t, boolean r, boolean b) {
        float[] fixed = MiniLayout.clamp(want);
        float[] out = fixed;
        if (snap && !pinching) out = (l || t || r || b) ? MiniLayout.snapEdges(fixed, rects, selected, l, t, r, b, 0.03f) : MiniLayout.snap(fixed, rects, selected, false, 0.03f);
        rects[selected] = out;
        invalidate();
        onChange.changed(rects, order, floats);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (pinch == null) {
            pinch = new android.view.ScaleGestureDetector(getContext(), new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(android.view.ScaleGestureDetector d) {
                    if (selected < 0 || raw == null) return true;
                    pinching = true;
                    // Pinching resizes about the centre and keeps the page's shape.
                    float k = d.getScaleFactor();
                    float cx = raw[0] + raw[2] / 2f, cy = raw[1] + raw[3] / 2f;
                    float w = raw[2] * k, h = raw[3] * k;
                    raw = new float[]{cx - w / 2f, cy - h / 2f, w, h};
                    float[] c = MiniLayout.clamp(raw);
                    apply(c, false, false, false, false);
                    return true;
                }
            });
        }
        pinch.onTouchEvent(e);
        if (e.getPointerCount() > 1 || pinch.isInProgress()) {
            pinching = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                pinching = false;
                corner = -1;
                for (int k = pages - 1; k >= 0; k--) {   // the one in front first
                    int i = order[k];
                    RectF r = px(i);
                    int cn = floats[i] ? -1 : cornerAt(r, x, y);
                    if (!r.contains(x, y) && cn < 0) continue;
                    selected = i;
                    raw = rects[i].clone();
                    corner = cn;
                    dragging = true;
                    break;
                }
                lastX = x;
                lastY = y;
                invalidate();
                if (dragging) getParent().requestDisallowInterceptTouchEvent(true);
                return dragging;
            case MotionEvent.ACTION_MOVE: {
                if (pinching) return true;
                if (!dragging || selected < 0 || getWidth() == 0) return true;
                float dx = (x - lastX) / getWidth(), dy = (y - lastY) / getHeight();
                lastX = x;
                lastY = y;
                float[] r = raw != null ? raw : rects[selected].clone();
                boolean l = false, t = false, rt = false, b = false;
                if (corner >= 0) {
                    float left = r[0], top = r[1], right = r[0] + r[2], bottom = r[1] + r[3];
                    if (corner == 0 || corner == 3) { left += dx; l = true; } else { right += dx; rt = true; }
                    if (corner == 0 || corner == 1) { top += dy; t = true; } else { bottom += dy; b = true; }
                    if (right - left < MiniLayout.MIN) { if (l) left = right - MiniLayout.MIN; else right = left + MiniLayout.MIN; }
                    if (bottom - top < MiniLayout.MIN) { if (t) top = bottom - MiniLayout.MIN; else bottom = top + MiniLayout.MIN; }
                    r = new float[]{left, top, right - left, bottom - top};
                } else {
                    r[0] += dx;
                    r[1] += dy;
                }
                raw = r;
                apply(r, l, t, rt, b);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                pinching = false;
                corner = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
