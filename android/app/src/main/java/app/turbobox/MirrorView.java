package app.turbobox;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.ViewGroup;

/**
 * The on-screen picture of a page that lives on its private display: the display is drawn into a texture view (fitted into this
 * view, never stretched) and touches are turned into the display's own pixels and given to the page. Several of these can
 * overlap like any views. The page is never resized: only the picture is.
 */
final class MirrorView extends ViewGroup {
    private final TextureView texture;
    private OffscreenHost host;
    private SurfaceTexture kept;   // survives the view being taken out of the layout and put back, so there is no black flash
    private float scale = 1f, offX, offY;
    private final Runnable sizeToWindow = this::syncSize;

    MirrorView(Context c) {
        super(c);
        texture = new TextureView(c);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                kept = st;
                if (host != null) host.output(texture, st);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return false;   // kept: the display goes on drawing into it and the picture is there the moment the view is back
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        addView(texture);
        setBackgroundColor(0xFF000000);
    }

    /** The view that draws the page (without the bars around it), for working out where the page really is on screen. */
    android.view.View contentView() {
        return texture;
    }

    void setHost(OffscreenHost h) {
        host = h;
        if (h != null && texture.isAvailable()) h.output(texture, texture.getSurfaceTexture());
        requestLayout();
    }

    /** Stops showing the display (the view is leaving the screen). */
    void release() {
        if (host != null && host.mirror() == texture) host.output(null, null);
        host = null;
        if (kept != null) {
            kept.release();
            kept = null;
        }
    }

    /** Fixed: the display keeps the shape it was made in. Follows: it takes this view's size, so the page is laid out for the space it really has. */
    private void syncSize() {
        OffscreenHost h = host;
        int w = getWidth(), ht = getHeight();
        if (h == null || w <= 0 || ht <= 0) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (h.isLocked()) {
            requestLayout();   // a workflow recording size: the display keeps its shape, the picture is only fitted into the pane
            return;
        }
        if (Prefs.displayFollows(getContext())) {
            h.resize(w, ht, 0);
        } else {
            h.resize(Math.min(dm.widthPixels, dm.heightPixels), Math.max(dm.widthPixels, dm.heightPixels), 0);
        }
        requestLayout();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        if (host == null || w <= 0 || h <= 0) return;
        removeCallbacks(sizeToWindow);
        postDelayed(sizeToWindow, 150);   // after the window has settled (a bar hiding, a split changing)
        scale = Math.min(w / (float) host.width(), h / (float) host.height());
        int dw = Math.round(host.width() * scale), dh = Math.round(host.height() * scale);
        offX = (w - dw) / 2f;
        offY = (h - dh) / 2f;
        texture.layout(Math.round(offX), Math.round(offY), Math.round(offX) + dw, Math.round(offY) + dh);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (host == null || scale <= 0f) return false;
        MotionEvent m = MotionEvent.obtain(e);
        Matrix mx = new Matrix();
        mx.postTranslate(-offX, -offY);
        mx.postScale(1f / scale, 1f / scale);
        m.transform(mx);
        boolean used = host.touch(m);
        m.recycle();
        getParent().requestDisallowInterceptTouchEvent(true);
        return used || true;
    }
}
