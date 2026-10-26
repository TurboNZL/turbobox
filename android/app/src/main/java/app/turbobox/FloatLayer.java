package app.turbobox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Over the pages when some of them float. A floating page is like a picture in picture inside the window: it can be dragged
 * around but not resized, and touching it does nothing to the page itself (the touch is used up here), so a call that
 * floats in a corner cannot be tapped by accident. Touches anywhere else go through to the pages underneath.
 */
final class FloatLayer extends View {
    interface OnMoved {
        void moved(float[][] rects);
    }

    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OnMoved onMoved;
    private float[][] rects = new float[0][];
    private int[] order = new int[0];          // back to front
    private boolean[] floats = new boolean[0];
    private boolean snap;
    private int dragging = -1;
    private float lastX, lastY;

    FloatLayer(Context c, OnMoved onMoved) {
        super(c);
        this.onMoved = onMoved;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.dpf(c, 1.5f));
        edge.setColor(0x66FFFFFF);
    }

    void set(float[][] r, int[] z, boolean[] f, boolean snapEdges) {
        rects = r;
        order = z;
        floats = f;
        snap = snapEdges;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        for (int i = 0; i < rects.length; i++) {
            if (!floats[i]) continue;
            float[] r = rects[i];
            c.drawRoundRect(new RectF(r[0] * getWidth(), r[1] * getHeight(), (r[0] + r[2]) * getWidth(), (r[1] + r[3]) * getHeight()), 8, 8, edge);
        }
    }

    /** The page whose rectangle holds the point and is in front of the others that do, or -1. */
    private int topAt(float x, float y) {
        for (int k = order.length - 1; k >= 0; k--) {
            int i = order[k];
            float[] r = rects[i];
            if (x >= r[0] * getWidth() && x <= (r[0] + r[2]) * getWidth() && y >= r[1] * getHeight() && y <= (r[1] + r[3]) * getHeight()) return i;
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int i = topAt(e.getX(), e.getY());
                if (i < 0 || !floats[i]) return false;   // not a floating page: the touch goes on to the page below
                dragging = i;
                lastX = e.getX();
                lastY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging < 0 || getWidth() == 0) return true;
                float dx = (e.getX() - lastX) / getWidth(), dy = (e.getY() - lastY) / getHeight();
                lastX = e.getX();
                lastY = e.getY();
                float[] r = rects[dragging].clone();
                r[0] += dx;
                r[1] += dy;
                float[] fixed = MiniLayout.clamp(r);
                rects[dragging] = snap ? MiniLayout.snap(fixed, rects, dragging, false, 0.03f) : fixed;
                onMoved.moved(rects);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = -1;
                return true;
            default:
                return true;
        }
    }
}
