package app.turbobox;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import java.util.ArrayList;
import java.util.List;

/** Launcher shortcuts for the pinned pages: the long-press menu of the app icon, and icons on the home screen. */
final class Shortcuts {
    private Shortcuts() {
    }

    /** The page's own icon when it has one, else the app's. */
    private static Icon iconFor(Context c, Pin pin) {
        android.graphics.Bitmap b = Favicons.load(c, pin.id);
        if (b == null) return Icon.createWithResource(c, R.mipmap.ic_launcher);
        // Adaptive icons crop to the middle 2/3: put the page's icon on a light square with room around it.
        int size = 216;
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawColor(0xFFF2F2F5);
        int inner = Math.round(size * 0.5f);
        float k = Math.min(inner / (float) b.getWidth(), inner / (float) b.getHeight());
        float w = b.getWidth() * k, h = b.getHeight() * k;
        canvas.drawBitmap(b, null, new android.graphics.RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return Icon.createWithAdaptiveBitmap(out);
    }

    private static ShortcutInfo build(Context c, Pin pin) {
        Intent open = new Intent(c, SessionActivity.class)
                .setAction(SessionActivity.ACTION_OPEN_PIN)
                .putExtra(SessionActivity.EXTRA_PIN, pin.id);
        return new ShortcutInfo.Builder(c, "pin-" + pin.id)
                .setShortLabel(pin.name)
                .setLongLabel(pin.name + " (" + pin.host() + ")")
                .setIcon(iconFor(c, pin))
                .setIntent(open)
                .build();
    }

    /** Puts the first pins (as many as the launcher allows) in the app icon's long-press menu. */
    static void refresh(Context c) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null) return;
        List<ShortcutInfo> list = new ArrayList<>();
        int max = Math.min(sm.getMaxShortcutCountPerActivity(), 4);
        for (Pin p : PinStore.load(c)) {
            if (list.size() >= max) break;
            list.add(build(c, p));
        }
        try {
            sm.setDynamicShortcuts(list);
        } catch (RuntimeException ignored) {
            // The launcher may refuse; the pinned tiles inside the app still work.
        }
    }

    /** Asks the launcher to put an icon for this page on the home screen. False when it does not support that. */
    static boolean requestHomeIcon(Context c, Pin pin) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) return false;
        return sm.requestPinShortcut(build(c, pin), null);
    }
}
