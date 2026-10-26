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
    private float scale = 1f, offX, offY;

    MirrorView(Context c) {
        super(c);
        texture = new TextureView(c);
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if (host != null) host.output(texture, st);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (host != null) host.output(null, null);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        addView(texture);
        setBackgroundColor(0xFF000000);
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
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        if (host == null || w <= 0 || h <= 0) return;
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
