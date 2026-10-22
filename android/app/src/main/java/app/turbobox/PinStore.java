package app.turbobox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** The pinned pages, kept as JSON in the app's preferences. */
final class PinStore {
    static final int MAX = 8;
    private static final String KEY = "pins";

    private PinStore() {
    }

    static List<Pin> load(Context c) {
        List<Pin> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Pin.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
            // A damaged list reads as empty rather than crashing the home screen.
        }
        return out;
    }

    static void save(Context c, List<Pin> pins) {
        JSONArray a = new JSONArray();
        for (Pin p : pins) a.put(p.toJson());
        Prefs.sp(c).edit().putString(KEY, a.toString()).apply();
    }

    static Pin find(Context c, String id) {
        if (id == null) return null;
        for (Pin p : load(c)) if (p.id.equals(id)) return p;
        return null;
    }

    /** Adds or replaces (by id). False when the list is full. */
    static boolean put(Context c, Pin pin) {
        List<Pin> pins = load(c);
        for (int i = 0; i < pins.size(); i++) {
            if (pins.get(i).id.equals(pin.id)) {
                pins.set(i, pin);
                save(c, pins);
                return true;
            }
        }
        if (pins.size() >= MAX) return false;
        pins.add(pin);
        save(c, pins);
        return true;
    }

    static void remove(Context c, String id) {
        List<Pin> pins = load(c);
        for (int i = pins.size() - 1; i >= 0; i--) if (pins.get(i).id.equals(id)) pins.remove(i);
        save(c, pins);
    }
}
