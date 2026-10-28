package app.turbobox;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The app's own brightness: a black veil, drawn over every screen, sheet and dialog of this app (never the phone's brightness). The veil is
 * the foreground of each window's decor view, so it covers whatever the window shows. The page screen has its veil of its own over
 * the pages and is skipped here; its sheets and dialogs are not.
 */
final class Dimmer implements Application.ActivityLifecycleCallbacks {
    private static final List<WeakReference<Window>> WINDOWS = new ArrayList<>();

    static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Dimmer());
    }

    /** The veil's strength for the current setting (0 = none). */
    static int alpha(Context c) {
        float b = Math.max(0f, Math.min(1f, Prefs.brightness(c)));
        return Math.round((1f - b) * 0.9f * 255f);
    }

    /** Puts the veil on a window (of an activity, a dialog or a sheet) now and whenever {@link #refresh} runs. */
    static void apply(Context c, Window w) {
        if (w == null) return;
        boolean known = false;
        for (WeakReference<Window> r : WINDOWS) if (r.get() == w) known = true;
        if (!known) WINDOWS.add(new WeakReference<>(w));
        View decor = w.peekDecorView();
        if (decor == null) return;
        int a = alpha(c);
        decor.setForeground(a == 0 ? null : new ColorDrawable(Color.argb(a, 0, 0, 0)));
    }

    /** The setting changed: every open window takes the new strength. */
    static void refresh(Context c) {
        for (int i = WINDOWS.size() - 1; i >= 0; i--) {
            Window w = WINDOWS.get(i).get();
            if (w == null) WINDOWS.remove(i); else apply(c, w);
        }
    }

    @Override
    public void onActivityResumed(Activity a) {
        if (!(a instanceof SessionActivity)) apply(a, a.getWindow());
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
    }

    @Override
    public void onActivityStarted(Activity a) {
    }

    @Override
    public void onActivityPaused(Activity a) {
    }

    @Override
    public void onActivityStopped(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
    }
}
