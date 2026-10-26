package app.turbobox;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Holds the page area. Normally it just fills the space. In the Mini window it keeps its one child at the size it had on screen and
 * shows only the part that has pages in it (the crop), scaled to fill the window exactly, so nothing is laid out again and there are
 * no empty bars: three pages stacked in odd places give a window of exactly their combined shape.
 */
final class PipStage extends FrameLayout {
    private boolean pip;
    private int lockW, lockH;
    private Rect crop = new Rect();

    PipStage(Context c) {
        super(c);
        setClipChildren(true);
    }

    void enter(int w, int h, Rect cropRect) {
        if (w < 16 || h < 16) return;
        pip = true;
        lockW = w;
        lockH = h;
        crop = new Rect(cropRect);
        requestLayout();
    }

    void exit() {
        if (!pip) return;
        pip = false;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.setScaleX(1f);
            c.setScaleY(1f);
            c.setTranslationX(0f);
            c.setTranslationY(0f);
        }
        requestLayout();
    }

    boolean isPip() {
        return pip;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (!pip) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(lockW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lockH, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (!pip) {
            super.onLayout(changed, l, t, r, b);
            return;
        }
        int w = r - l, h = b - t;
        float cw = Math.max(1, crop.width()), ch = Math.max(1, crop.height());
        float k = Math.min(w / cw, h / ch);
        float dx = (w - cw * k) / 2f - crop.left * k, dy = (h - ch * k) / 2f - crop.top * k;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.layout(0, 0, lockW, lockH);
            c.setPivotX(0f);
            c.setPivotY(0f);
            c.setScaleX(k);
            c.setScaleY(k);
            c.setTranslationX(dx);
            c.setTranslationY(dy);
        }
    }
}
