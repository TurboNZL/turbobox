package app.turbobox;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * Holds a page's view and fits it into a box of a chosen shape, centred, with the space around it left empty. Ratio 0
 * means "fill whatever space there is".
 */
final class AspectFrame extends FrameLayout {
    private float ratio;
    private int childW;
    private int childH;

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

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        childW = w;
        childH = h;
        if (ratio > 0 && w > 0 && h > 0) {
            if (w / (float) h > ratio) childW = Math.round(h * ratio);
            else childH = Math.round(w / ratio);
        }
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
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
