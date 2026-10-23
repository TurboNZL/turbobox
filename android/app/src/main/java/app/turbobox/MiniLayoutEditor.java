package app.turbobox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Draws the Mini window as a box with one rectangle per page. Drag a rectangle to move it, drag its corner to resize it.
 * Page 4 is in front of page 3 and so on, as in the real window.
 */
final class MiniLayoutEditor extends View {
    interface OnChange {
        void changed(float[][] rects);
    }

    private static final int[] COLORS = {0xFFA6E22E, 0xFF66D9EF, 0xFFFD971F, 0xFFF92672};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float handle;
    private final OnChange onChange;
    private float[][] rects = MiniLayout.defaults();
    private int pages = 2;
    private int active = -1;
    private boolean resizing;
    private float lastX, lastY;

    MiniLayoutEditor(Context c, OnChange onChange) {
        super(c);
        this.onChange = onChange;
        handle = Ui.dpf(c, 30);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.dpf(c, 2));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Ui.dpf(c, 22));
        label.setFakeBoldText(true);
        setBackgroundColor(0xFF000000);
    }

    void set(float[][] r, int pageCount) {
        rects = r;
        pages = Math.max(1, Math.min(MiniLayout.PAGES, pageCount));
        invalidate();
    }

    float[][] rects() {
        return rects;
    }

    private RectF px(int i) {
        float[] r = rects[i];
        return new RectF(r[0] * getWidth(), r[1] * getHeight(), (r[0] + r[2]) * getWidth(), (r[1] + r[3]) * getHeight());
    }

    @Override
    protected void onDraw(Canvas c) {
        for (int i = 0; i < pages; i++) {
            RectF r = px(i);
            int col = COLORS[i];
            fill.setColor(col);
            fill.setAlpha(i == active ? 120 : 70);
            c.drawRect(r, fill);
            edge.setColor(col);
            c.drawRect(r, edge);
            label.setColor(col);
            c.drawText("Page " + (i + 1), r.centerX(), r.centerY() + label.getTextSize() / 3, label);
            fill.setColor(col);
            fill.setAlpha(255);
            c.drawRect(r.right - handle / 2, r.bottom - handle / 2, r.right, r.bottom, fill);   // the resize corner
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                active = -1;
                for (int i = pages - 1; i >= 0; i--) {   // the one in front first
                    RectF r = px(i);
                    if (!r.contains(x, y) && !(Math.abs(x - r.right) < handle && Math.abs(y - r.bottom) < handle)) continue;
                    active = i;
                    resizing = x > r.right - handle && y > r.bottom - handle;
                    break;
                }
                lastX = x;
                lastY = y;
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (active < 0 || getWidth() == 0) return true;
                float dx = (x - lastX) / getWidth(), dy = (y - lastY) / getHeight();
                lastX = x;
                lastY = y;
                float[] r = rects[active].clone();
                if (resizing) {
                    r[2] += dx;
                    r[3] += dy;
                } else {
                    r[0] += dx;
                    r[1] += dy;
                }
                rects[active] = MiniLayout.clamp(r);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (active >= 0) onChange.changed(rects);
                active = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
