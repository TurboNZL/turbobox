package app.turbobox;

import android.app.Presentation;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.nio.ByteBuffer;

/**
 * Lets a page keep rendering, and be recorded, with the phone's screen off or another app in front. The page's view is
 * moved into a {@link Presentation} on a private virtual display of the app's own; that display is composited whether or
 * not the real screen is on, so the browser sees a visible window (no throttling, video drawn) and the pictures can be
 * read from an {@link ImageReader} instead of copied from a window that no longer shows anything.
 *
 * The virtual display is sized to the recording and given a density that keeps the page's layout width the same as on
 * the phone's screen. Nothing here is visible to the user. Main thread only. Written without a device: if a phone refuses
 * (some do not allow a presentation from the background) {@link #start} returns false and the recorder falls back to
 * drawing the view onto a bitmap.
 */
final class OffscreenHost {
    private final Context app;
    private final View view;
    private int width;
    private int height;
    private int densityDpi;
    private int cssWidth;   // the page's layout width in CSS pixels; the density follows from it (0 = use the density given)
    private final String name;
    private ImageReader reader;
    private VirtualDisplay display;
    private Presentation presentation;
    private FrameLayout root;
    private Bitmap frame;
    private String error = "";
    private android.view.TextureView mirror;   // the on-screen picture of this display (null = nobody is looking, frames go to the reader)
    private android.view.Surface mirrorSurface;

    OffscreenHost(Context app, String name, View view, int width, int height, int densityDpi) {
        this.app = app;
        this.name = name;
        this.view = view;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
    }

    /**
     * Changes the display's size in place (the page keeps its state: it only gets a resize). Used when the display follows the
     * window; a fixed display never calls this.
     */
    /** The layout width the page should see, in CSS pixels, whatever the display's pixel size: the density is derived from it. */
    void setCssWidth(int css) {
        cssWidth = css;
    }

    static int dpiFor(int pixelWidth, int css, int fallback) {
        if (css <= 0) return fallback;
        return Math.max(30, Math.min(800, Math.round(pixelWidth * 160f / css)));
    }

    void resize(int w, int h, int dpi) {
        w = Math.max(16, Math.min(3000, w));
        h = Math.max(16, Math.min(3000, h));
        if (dpi <= 0) dpi = densityDpi;   // the density is set when the display is made (a changed page size makes a new display), never changed under a live page
        if (display == null || (w == width && h == height && dpi == densityDpi)) return;
        try {
            ImageReader old = reader;
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            width = w;
            height = h;
            densityDpi = dpi;
            display.resize(w, h, dpi);
            if (mirror != null && mirrorSurface != null) {
                android.graphics.SurfaceTexture st = mirror.getSurfaceTexture();
                if (st != null) st.setDefaultBufferSize(w, h);
            } else {
                display.setSurface(reader.getSurface());
            }
            if (view.getLayoutParams() != null) {
                view.getLayoutParams().width = w;
                view.getLayoutParams().height = h;
                view.requestLayout();
            }
            fillDisplay();
            if (old != null) old.close();
        } catch (RuntimeException ignored) {
            // The display is going away.
        }
    }

    private boolean locked;   // the size is fixed by the page's workflow recording size: the window never resizes it

    void setLocked(boolean on) {
        locked = on;
    }

    boolean isLocked() {
        return locked;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** The view that shows this display on screen right now, or null. */
    android.view.TextureView mirror() {
        return mirror;
    }

    /**
     * Shows the display live in a texture view (null puts it back on the reader, so it keeps rendering with nothing on
     * screen). The display itself, and the page in it, never change size or state whichever way this goes.
     */
    void output(android.view.TextureView tv, android.graphics.SurfaceTexture st) {
        try {
            if (display == null) return;
            if (mirrorSurface != null) mirrorSurface.release();
            mirrorSurface = null;
            mirror = tv;
            if (tv != null && st != null) {
                st.setDefaultBufferSize(width, height);
                mirrorSurface = new android.view.Surface(st);
                display.setSurface(mirrorSurface);
            } else {
                mirror = null;
                display.setSurface(reader.getSurface());
            }
        } catch (RuntimeException ignored) {
            // The display is going away.
        }
    }

    /**
     * A Presentation is a dialog, and a dialog's window is sized in dp by its theme: on a display with a low density that is far
     * fewer pixels than the display has, so the page used to be drawn into a corner of it (small and low resolution). The window is
     * made exactly as big as the display, in pixels, with no frame or margin.
     */
    private void fillDisplay() {
        android.view.Window w = presentation == null ? null : presentation.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
        w.setLayout(width, height);
        w.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        android.view.WindowManager.LayoutParams lp = w.getAttributes();
        lp.x = 0;
        lp.y = 0;
        lp.width = width;
        lp.height = height;
        w.setAttributes(lp);
    }

    /** Hands a touch (already in this display's pixels) to the page. */
    boolean touch(android.view.MotionEvent e) {
        return view.dispatchTouchEvent(e);
    }

    String error() {
        return error;
    }

    boolean start() {
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            display = dm.createVirtualDisplay("turbobox-" + name, width, height, densityDpi, reader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            Display d = display.getDisplay();
            presentation = new Presentation(app, d);
            presentation.setCancelable(false);
            root = new FrameLayout(presentation.getContext());
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            if (view.getContext() instanceof MutableContextWrapper) {
                ((MutableContextWrapper) view.getContext()).setBaseContext(presentation.getContext());
            }
            root.addView(view, new FrameLayout.LayoutParams(width, height));
            presentation.setContentView(root);
            fillDisplay();
            presentation.show();
            fillDisplay();
            return true;
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            stop();
            return false;
        }
    }

    /**
     * Nobody reads the pictures (the page is only being kept visible): hand each one back as it arrives, so the display
     * never waits for a consumer.
     */
    void drain() {
        ImageReader r = reader;
        if (r == null) return;
        r.setOnImageAvailableListener(rd -> {
            Image i = null;
            try {
                i = rd.acquireLatestImage();
            } catch (RuntimeException ignored) {
                // The reader is closing.
            } finally {
                if (i != null) i.close();
            }
        }, new android.os.Handler(android.os.Looper.getMainLooper()));
    }

    /** The newest picture the page has drawn since the last call (full size of the virtual display), or null when it has not changed. */
    Bitmap latest() {
        ImageReader r = reader;
        if (r == null) return null;
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return null;
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int stride = plane.getRowStride() / plane.getPixelStride();
            Bitmap b = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(buf);
            frame = stride == width ? b : Bitmap.createBitmap(b, 0, 0, width, height);
            return frame;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (img != null) img.close();
        }
    }

    /** Gives the view back (its parent is only cleared if it is still ours: a screen may already have taken it) and releases everything. */
    void stop() {
        try {
            if (root != null && view.getParent() == root) {
                root.removeView(view);
                if (view.getContext() instanceof MutableContextWrapper) {
                    ((MutableContextWrapper) view.getContext()).setBaseContext(app);
                }
            }
        } catch (RuntimeException ignored) {
            // The view is going away anyway.
        }
        try {
            if (presentation != null) presentation.dismiss();
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        if (mirrorSurface != null) mirrorSurface.release();
        mirrorSurface = null;
        mirror = null;
        if (display != null) display.release();
        if (reader != null) reader.close();
        presentation = null;
        display = null;
        reader = null;
        root = null;
    }
}
