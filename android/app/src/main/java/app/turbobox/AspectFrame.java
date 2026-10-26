package app.turbobox;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Holds a page's view and fits it into a box of a chosen shape, centred, with the space around it left empty. Ratio 0
 * means "fill whatever space there is".
 */
final class AspectFrame extends FrameLayout {
    private float ratio;
    private int childW;
    private int childH;
    private boolean locked;
    private int lockW, lockH;

    AspectFrame(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
    }

    /** Width divided by height, or 0 to fill the space. */
    void setRatio(float r) {
        if (Math.abs(r - ratio) < 0.001f) return;
        ratio = r;
        requestLayout();
    }

    /**
     * Locked: the page keeps the size it has right now whatever size this frame becomes, and is drawn scaled to fit (like the
     * Mini window, which only makes the picture smaller). Unlocking lays it out at the frame's size again.
     */
    void setLocked(boolean on) {
        if (on == locked) return;
        if (on) {
            if (childW < 16 || childH < 16) return;
            lockW = childW;
            lockH = childH;
        } else {
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).setScaleX(1f);
                getChildAt(i).setScaleY(1f);
                getChildAt(i).setTranslationX(0f);
                getChildAt(i).setTranslationY(0f);
            }
        }
        locked = on;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        childW = w;
        childH = h;
        if (locked) {
            childW = lockW;
            childH = lockH;
        } else if (ratio > 0 && w > 0 && h > 0) {
            if (w / (float) h > ratio) childW = Math.round(h * ratio);
            else childH = Math.round(w / ratio);
        }
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (locked && childW > 0 && childH > 0) {
            float k = Math.min((r - l) / (float) childW, (b - t) / (float) childH);
            float dx = ((r - l) - childW * k) / 2f, dy = ((b - t) - childH * k) / 2f;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                c.layout(0, 0, childW, childH);
                c.setPivotX(0f);
                c.setPivotY(0f);
                c.setScaleX(k);
                c.setScaleY(k);
                c.setTranslationX(dx);
                c.setTranslationY(dy);
            }
            return;
        }
        int left = ((r - l) - childW) / 2;
        int top = ((b - t) - childH) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(left, top, left + childW, top + childH);
        }
    }

    /** The shape the page really has right now. */
    int pageWidth() {
        return childW;
    }

    int pageHeight() {
        return childH;
    }
}
