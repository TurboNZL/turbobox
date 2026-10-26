package app.turbobox;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Which buttons the page screen's top bar has, and in what order (Settings > Top bar). No Android classes besides Context for the settings. */
final class BarItems {
    static final String[] ALL = {"pause", "both", "record", "captions", "mute", "timer", "mini", "layout", "lock", "rotate", "volume", "hide", "settings", "close"};
    static final String[] NAMES = {"Pause", "Pause and record", "Record", "Live captions", "Microphone mute", "Timer", "Mini window", "Arrange pages",
            "Lock the page", "Force rotation", "Volumes", "Hide the bar", "Page settings", "Close the page"};

    private BarItems() {
    }

    static String name(String id) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].equals(id)) return NAMES[i];
        return id;
    }

    /** The saved order with any button that was added since appended at the end. */
    static List<String> order(Context c) {
        return merge(Prefs.sp(c).getString("barOrder", ""));
    }

    static List<String> merge(String saved) {
        List<String> out = new ArrayList<>();
        for (String id : saved.split(",")) if (Arrays.asList(ALL).contains(id) && !out.contains(id)) out.add(id);
        for (String id : ALL) if (!out.contains(id)) out.add(id);
        return out;
    }

    static boolean isOff(Context c, String id) {
        return Arrays.asList(Prefs.sp(c).getString("barOff", "").split(",")).contains(id);
    }

    static List<String> visibleOrder(Context c) {
        List<String> out = new ArrayList<>();
        for (String id : order(c)) if (!isOff(c, id) || id.equals("settings")) out.add(id);   // the settings button can never be lost
        return out;
    }

    static void save(Context c, List<String> order, List<String> off) {
        Prefs.sp(c).edit().putString("barOrder", String.join(",", order)).putString("barOff", String.join(",", off)).apply();
    }

    static List<String> off(Context c) {
        List<String> out = new ArrayList<>();
        for (String id : Prefs.sp(c).getString("barOff", "").split(",")) if (!id.isEmpty()) out.add(id);
        return out;
    }
}
