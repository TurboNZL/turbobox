package app.turbobox;

import android.content.Context;

/** What each button of the Mini window does (Settings > Mini window buttons). Android shows the first few (usually three). */
final class PipButtons {
    static final int SLOTS = 4;
    static final String[] IDS = {"pause", "mute", "record", "both", "play", "fwd", "back", "none"};
    static final String[] NAMES = {"Pause / resume the pages", "Mute the microphone", "Record / stop", "Pause and record", "Play / pause the video",
            "Forward 10 seconds", "Back 10 seconds", "Nothing"};
    private static final String[] DEFAULTS = {"pause", "mute", "fwd", "back"};

    private PipButtons() {
    }

    static String get(Context c, int slot) {
        return Prefs.sp(c).getString("pipButton" + slot, DEFAULTS[slot]);
    }

    static void set(Context c, int slot, String fn) {
        Prefs.sp(c).edit().putString("pipButton" + slot, fn).apply();
    }

    static String name(Context c, String fn) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(fn)) return NAMES[i];
        if (fn.startsWith("workflow:")) {
            Workflow w = WorkflowStore.find(c, fn.substring(9));
            return w == null ? "A workflow that was deleted" : "Run: " + w.name;
        }
        return fn;
    }
}
