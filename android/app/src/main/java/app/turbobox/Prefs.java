package app.turbobox;

import android.content.Context;
import android.content.SharedPreferences;

/** The app's settings. Microphone, echo cancellation and mic volume are shared by every session. */
final class Prefs {
    private Prefs() {
    }

    /** App-wide settings: Standby, updates, and the list of profiles. The same whichever profile is in use. */
    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("turbobox", Context.MODE_PRIVATE);
    }

    private static String activeProfile = Profiles.DEFAULT;

    /**
     * Switches which profile's settings the getters below read and write (microphone, volumes, recording, screen options...).
     * Each profile has its own, and a new profile starts from the defaults, so nothing carries over between them. The
     * default profile keeps the file the app always used.
     */
    static void activate(String profileId) {
        activeProfile = profileId == null || profileId.isEmpty() ? Profiles.DEFAULT : profileId;
    }

    static String activeProfile() {
        return activeProfile;
    }

    static SharedPreferences ps(Context c) {
        if (Profiles.DEFAULT.equals(activeProfile)) return sp(c);
        return c.getApplicationContext().getSharedPreferences("turbobox_p_" + activeProfile, Context.MODE_PRIVATE);
    }

    // ---- microphone (the same keys' meaning as in Tether's web session) ----
    static String micKey(Context c) { return ps(c).getString("mic", ""); }
    static void setMicKey(Context c, String v) { ps(c).edit().putString("mic", v).apply(); }
    static boolean aec(Context c) { return ps(c).getBoolean("aec", true); }
    static void setAec(Context c, boolean v) { ps(c).edit().putBoolean("aec", v).apply(); }
    static float micGain(Context c) { return ps(c).getFloat("micGain", 1f); }
    static void setMicGain(Context c, float v) { ps(c).edit().putFloat("micGain", v).apply(); }
    static float brightness(Context c) { return ps(c).getFloat("brightness", 1f); }
    static void setBrightness(Context c, float v) { ps(c).edit().putFloat("brightness", v).apply(); }

    // ---- standby: keep the foreground service up so scheduled workflows can use the microphone ----
    static boolean standby(Context c) { return sp(c).getBoolean("standby", false); }
    static void setStandby(Context c, boolean v) { sp(c).edit().putBoolean("standby", v).apply(); }

    /** Keep a page visible to the browser with the screen off, so a video (YouTube) keeps playing. On by default. */
    static boolean keepPlaying(Context c) { return ps(c).getBoolean("keepPlaying", true); }
    static void setKeepPlaying(Context c, boolean v) { ps(c).edit().putBoolean("keepPlaying", v).apply(); }

    // ---- the session screen ----
    /** The options bar is put away, leaving a small button. */
    static boolean barHidden(Context c) { return ps(c).getBoolean("barHidden", false); }
    static void setBarHidden(Context c, boolean v) { ps(c).edit().putBoolean("barHidden", v).apply(); }
    /** Where the show-bar button was left, as fractions of the screen (x, y). */
    static float floatX(Context c) { return ps(c).getFloat("floatX", 0.88f); }
    static float floatY(Context c) { return ps(c).getFloat("floatY", 0.12f); }
    static void setFloatPos(Context c, float x, float y) { ps(c).edit().putFloat("floatX", x).putFloat("floatY", y).apply(); }
    /** What the Mini (picture in picture) window shows while Split is on: see SplitLayout (0 like the split, 1 side by side, 2 stacked, 3 one over the other). */
    static int pipLayout(Context c) { return ps(c).getInt("pipLayout", 0); }
    static void setPipLayout(Context c, int v) { ps(c).edit().putInt("pipLayout", v).apply(); }

    // ---- recording buffer ----
    static int bufferMinutes(Context c) { return ps(c).getInt("bufferMinutes", 60); }
    static void setBufferMinutes(Context c, int v) { ps(c).edit().putInt("bufferMinutes", v).apply(); }
    static int bufferQuality(Context c) { return ps(c).getInt("bufferQuality", 1); }
    static void setBufferQuality(Context c, int v) { ps(c).edit().putInt("bufferQuality", v).apply(); }
    /** Start the recording buffer by itself whenever a page opens (a pin can override it either way). On by default. */
    static boolean recordByDefault(Context c) { return ps(c).getBoolean("recordByDefault", true); }
    static void setRecordByDefault(Context c, boolean v) { ps(c).edit().putBoolean("recordByDefault", v).apply(); }
    /** What to do when a recording reaches its length: false = keep recording and drop the oldest, true = stop. */
    static boolean bufferStopAtLimit(Context c) { return ps(c).getBoolean("bufferStop", false); }
    static void setBufferStopAtLimit(Context c, boolean v) { ps(c).edit().putBoolean("bufferStop", v).apply(); }
    /** Picture width of the recording; 0 = not chosen yet (the old "quality" setting decides). */
    static int bufferWidth(Context c) { return ps(c).getInt("bufferWidth", 0); }
    static void setBufferWidth(Context c, int v) { ps(c).edit().putInt("bufferWidth", v).apply(); }
    /** Pictures per second; 0 = not chosen yet. */
    static int bufferFps(Context c) { return ps(c).getInt("bufferFps", 0); }
    static void setBufferFps(Context c, int v) { ps(c).edit().putInt("bufferFps", v).apply(); }
    static int keepRuns(Context c) { return ps(c).getInt("keepRuns", 3); }
    static void setKeepRuns(Context c, int v) { ps(c).edit().putInt("keepRuns", v).apply(); }

    // ---- transcription (speech to text, saved as WebVTT next to a recording) ----
    /** One-shot: transcribe the next recording that starts, then switch off. */
    static boolean transcribeNext(Context c) { return ps(c).getBoolean("transcribeNext", false); }
    static void setTranscribeNext(Context c, boolean v) { ps(c).edit().putBoolean("transcribeNext", v).apply(); }
    /** BCP-47 language tag, "" = the phone's language. */
    static String transcribeLang(Context c) { return ps(c).getString("transcribeLang", ""); }
    static void setTranscribeLang(Context c, String v) { ps(c).edit().putString("transcribeLang", v).apply(); }

    // ---- updates ----
    static String updateToken(Context c) { return sp(c).getString("updateToken", ""); }
    static void setUpdateToken(Context c, String v) { sp(c).edit().putString("updateToken", v).apply(); }
    static long lastUpdateCheck(Context c) { return sp(c).getLong("lastUpdateCheck", 0); }
    static void setLastUpdateCheck(Context c, long v) { sp(c).edit().putLong("lastUpdateCheck", v).apply(); }
}
