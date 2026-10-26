package app.turbobox;

import android.content.Context;
import android.content.SharedPreferences;

/** The app's settings. They are global: the same for every page and every profile (only a pinned page's own settings differ). */
final class Prefs {
    private Prefs() {
    }

    /** The settings file. */
    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("turbobox", Context.MODE_PRIVATE);
    }

    /** Every setting below is the app's, whichever profile a page belongs to. */
    static SharedPreferences ps(Context c) {
        return sp(c);
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

    /** The workflow the headset's play/pause button starts (its id), or "" for the button to play and pause the page. */
    static String headphoneWorkflow(Context c) { return sp(c).getString("headphoneWorkflow", ""); }
    static void setHeadphoneWorkflow(Context c, String v) { sp(c).edit().putString("headphoneWorkflow", v).apply(); }

    /** Pages see one fixed virtual microphone/speaker/camera set (on); off gives them the browser's real device list and own capture. Applies to pages opened afterwards. */
    static boolean virtualDevices(Context c) { return sp(c).getBoolean("virtualDevices", true); }
    static void setVirtualDevices(Context c, boolean v) { sp(c).edit().putBoolean("virtualDevices", v).apply(); }
    /** TurboBox holds the headset's play/pause button while pages are open. */
    static boolean headsetButton(Context c) { return sp(c).getBoolean("headsetButton", true); }
    static void setHeadsetButton(Context c, boolean v) { sp(c).edit().putBoolean("headsetButton", v).apply(); }

    /** Experimental: every page lives on its own fixed-size private display, shown on screen as a live picture. Applies to pages opened afterwards. */
    static boolean privateDisplay(Context c) { return sp(c).getBoolean("privateDisplay", false); }
    static void setPrivateDisplay(Context c, boolean v) { sp(c).edit().putBoolean("privateDisplay", v).apply(); }

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

    /** Your own arrangement of the Mini window (see MiniLayout), "" = the default. */
    /** The Mini window's shape in your own arrangement: index into MiniLayout.SHAPES. */
    static int miniShape(Context c) { return ps(c).getInt("miniShape", 0); }
    static void setMiniShape(Context c, int v) { ps(c).edit().putInt("miniShape", v).apply(); }
    /** How a new split starts: 0 = the layout last used with this many pages, 1 = stacked, 2 = grid (two across, then down). Nothing applies by itself. */
    static int splitDefault(Context c) { return ps(c).getInt("splitDefault", 0); }
    static void setSplitDefault(Context c, int v) { ps(c).edit().putInt("splitDefault", v).apply(); }
    /** The arrangement last used with {@code pages} pages (MiniLayout.pack), for the full screen or the Mini window; "" = none yet. */
    /**
     * The full-screen arrangement is remembered separately for portrait and landscape; if one orientation has none yet it
     * simply starts as the default (stacked or grid). The Mini window's is the same whichever way the phone is held.
     */
    private static String arrKey(boolean mini, int pages, boolean landscape) { return (mini ? "lastMini_" : "lastFull_" + (landscape ? "L" : "P")) + pages; }
    static String lastArrangement(Context c, boolean mini, int pages, boolean landscape) {
        String v = ps(c).getString(arrKey(mini, pages, landscape), "");
        if (v.isEmpty() && !mini && !landscape) v = ps(c).getString("lastFull_" + pages, "");   // saved before orientations were told apart
        return v;
    }
    static void setLastArrangement(Context c, boolean mini, int pages, boolean landscape, String v) { ps(c).edit().putString(arrKey(mini, pages, landscape), v).apply(); }
    /** Edges snap together when pages are moved in the layout editor (off by default). */
    static boolean snapEdges(Context c) { return ps(c).getBoolean("snapEdges", false); }
    static void setSnapEdges(Context c, boolean v) { ps(c).edit().putBoolean("snapEdges", v).apply(); }
    /** Rotation of the page screen: 0 follow the phone's rotation lock, 1 rotate with the sensor whatever the lock says, 2 portrait only. */
    static int rotation(Context c) { return ps(c).getInt("rotation", 0); }
    static void setRotation(Context c, int v) { ps(c).edit().putInt("rotation", v).apply(); }
    static String splitLayout(Context c, String key) { return ps(c).getString("split_" + key, null); }
    static void setSplitLayout(Context c, String key, String v) { ps(c).edit().putString("split_" + key, v).apply(); }
    static void clearSplitLayouts(Context c) {
        SharedPreferences.Editor e = ps(c).edit();
        for (String k : ps(c).getAll().keySet()) if (k.startsWith("split_")) e.remove(k);
        e.apply();
    }
    /** A page held to a shape (forced ratio, full-screen video) gives the empty space around it to the other pages in a split. On by default. */
    static boolean reclaimEmpty(Context c) { return ps(c).getBoolean("reclaimEmpty", true); }
    static void setReclaimEmpty(Context c, boolean v) { ps(c).edit().putBoolean("reclaimEmpty", v).apply(); }

    // ---- recording buffer ----
    static int bufferMinutes(Context c) { return ps(c).getInt("bufferMinutes", 60); }
    static void setBufferMinutes(Context c, int v) { ps(c).edit().putInt("bufferMinutes", v).apply(); }
    static int bufferQuality(Context c) { return ps(c).getInt("bufferQuality", 1); }
    static void setBufferQuality(Context c, int v) { ps(c).edit().putInt("bufferQuality", v).apply(); }
    /** Start the recording buffer by itself whenever a page opens (a pin can override it either way). Off by default. */
    static boolean recordByDefault(Context c) { return ps(c).getBoolean("recordByDefault", false); }
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
