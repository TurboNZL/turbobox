package app.turbobox;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.view.KeyEvent;

import java.util.Locale;

/** Pressing a media key as if a headset had, and opening another app, for workflow steps. */
final class MediaKeys {
    private MediaKeys() {
    }

    private static int code(String key) {
        switch (key.toLowerCase(Locale.ROOT)) {
            case "play": return KeyEvent.KEYCODE_MEDIA_PLAY;
            case "pause": return KeyEvent.KEYCODE_MEDIA_PAUSE;
            case "next": return KeyEvent.KEYCODE_MEDIA_NEXT;
            case "previous": return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            default: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        }
    }

    /** Sends the key to whichever media app Android currently hands them to (the one that played last). */
    static void press(Context c, String key) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int code = code(key);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    static String packageFor(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        switch (n) {
            case "spotify": return "com.spotify.music";
            case "youtube music": case "ytmusic": return "com.google.android.apps.youtube.music";
            case "youtube": return "com.google.android.youtube";
            case "podcasts": case "pocket casts": return "au.com.shiftyjelly.pocketcasts";
            default: return name.trim();
        }
    }

    /** Opens the app; says what happened (Android may refuse to open an app over a locked screen). */
    static String openApp(Context c, String name) {
        String pkg = packageFor(name);
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return "no app \"" + pkg + "\" is installed";
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "opened " + pkg;
        } catch (RuntimeException e) {
            return "Android did not let " + pkg + " open now (" + e.getMessage() + ")";
        }
    }
}
