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
    private final int width;
    private final int height;
    private final int densityDpi;
    private final String name;
    private ImageReader reader;
    private VirtualDisplay display;
    private Presentation presentation;
    private FrameLayout root;
    private Bitmap frame;
    private String error = "";

    OffscreenHost(Context app, String name, View view, int width, int height, int densityDpi) {
        this.app = app;
        this.name = name;
        this.view = view;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
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
            presentation.show();
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
        if (display != null) display.release();
        if (reader != null) reader.close();
        presentation = null;
        display = null;
        reader = null;
        root = null;
    }
}
