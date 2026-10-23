package app.turbobox;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Places each child at a rectangle given as fractions of this view (left, top, width, height); later children are in front. */
final class FractionLayout extends ViewGroup {
    private float[][] rects = new float[0][];

    FractionLayout(Context c) {
        super(c);
    }

    void setRects(float[][] r) {
        rects = r;
        requestLayout();
    }

    private float[] rectOf(int i) {
        return i < rects.length ? rects[i] : new float[]{0f, 0f, 1f, 1f};
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            float[] r = rectOf(i);
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(Math.round(w * r[2]), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(h * r[3]), MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            float[] f = rectOf(i);
            View v = getChildAt(i);
            int x = Math.round(w * f[0]), y = Math.round(h * f[1]);
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }
}
