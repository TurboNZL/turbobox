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

    private static ShortcutInfo build(Context c, Pin pin) {
        Intent open = new Intent(c, SessionActivity.class)
                .setAction(SessionActivity.ACTION_OPEN_PIN)
                .putExtra(SessionActivity.EXTRA_PIN, pin.id);
        return new ShortcutInfo.Builder(c, "pin-" + pin.id)
                .setShortLabel(pin.name)
                .setLongLabel(pin.name + " (" + pin.host() + ")")
                .setIcon(Icon.createWithResource(c, R.mipmap.ic_launcher))
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
