package app.turbobox;

import android.content.Context;
import android.content.SharedPreferences;

/** The app's settings. Microphone, echo cancellation and mic volume are shared by every session. */
final class Prefs {
    private Prefs() {
    }

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("turbobox", Context.MODE_PRIVATE);
    }

    // ---- microphone (the same keys' meaning as in Tether's web session) ----
    static String micKey(Context c) { return sp(c).getString("mic", ""); }
    static void setMicKey(Context c, String v) { sp(c).edit().putString("mic", v).apply(); }
    static boolean aec(Context c) { return sp(c).getBoolean("aec", true); }
    static void setAec(Context c, boolean v) { sp(c).edit().putBoolean("aec", v).apply(); }
    static float micGain(Context c) { return sp(c).getFloat("micGain", 1f); }
    static void setMicGain(Context c, float v) { sp(c).edit().putFloat("micGain", v).apply(); }
    static float brightness(Context c) { return sp(c).getFloat("brightness", 1f); }
    static void setBrightness(Context c, float v) { sp(c).edit().putFloat("brightness", v).apply(); }

    // ---- standby: keep the foreground service up so scheduled workflows can use the microphone ----
    static boolean standby(Context c) { return sp(c).getBoolean("standby", false); }
    static void setStandby(Context c, boolean v) { sp(c).edit().putBoolean("standby", v).apply(); }

    // ---- recording buffer ----
    static int bufferMinutes(Context c) { return sp(c).getInt("bufferMinutes", 60); }
    static void setBufferMinutes(Context c, int v) { sp(c).edit().putInt("bufferMinutes", v).apply(); }
    static int bufferQuality(Context c) { return sp(c).getInt("bufferQuality", 1); }
    static void setBufferQuality(Context c, int v) { sp(c).edit().putInt("bufferQuality", v).apply(); }
    /** What to do when a recording reaches its length: false = keep recording and drop the oldest, true = stop. */
    static boolean bufferStopAtLimit(Context c) { return sp(c).getBoolean("bufferStop", false); }
    static void setBufferStopAtLimit(Context c, boolean v) { sp(c).edit().putBoolean("bufferStop", v).apply(); }
    /** Picture width of the recording; 0 = not chosen yet (the old "quality" setting decides). */
    static int bufferWidth(Context c) { return sp(c).getInt("bufferWidth", 0); }
    static void setBufferWidth(Context c, int v) { sp(c).edit().putInt("bufferWidth", v).apply(); }
    /** Pictures per second; 0 = not chosen yet. */
    static int bufferFps(Context c) { return sp(c).getInt("bufferFps", 0); }
    static void setBufferFps(Context c, int v) { sp(c).edit().putInt("bufferFps", v).apply(); }
    static int keepRuns(Context c) { return sp(c).getInt("keepRuns", 3); }
    static void setKeepRuns(Context c, int v) { sp(c).edit().putInt("keepRuns", v).apply(); }

    // ---- transcription (speech to text, saved as WebVTT next to a recording) ----
    /** One-shot: transcribe the next recording that starts, then switch off. */
    static boolean transcribeNext(Context c) { return sp(c).getBoolean("transcribeNext", false); }
    static void setTranscribeNext(Context c, boolean v) { sp(c).edit().putBoolean("transcribeNext", v).apply(); }
    /** BCP-47 language tag, "" = the phone's language. */
    static String transcribeLang(Context c) { return sp(c).getString("transcribeLang", ""); }
    static void setTranscribeLang(Context c, String v) { sp(c).edit().putString("transcribeLang", v).apply(); }

    // ---- updates ----
    static String updateToken(Context c) { return sp(c).getString("updateToken", ""); }
    static void setUpdateToken(Context c, String v) { sp(c).edit().putString("updateToken", v).apply(); }
    static long lastUpdateCheck(Context c) { return sp(c).getLong("lastUpdateCheck", 0); }
    static void setLastUpdateCheck(Context c, long v) { sp(c).edit().putLong("lastUpdateCheck", v).apply(); }
}
