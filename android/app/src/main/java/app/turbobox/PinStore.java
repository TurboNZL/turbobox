package app.turbobox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** The pinned pages, kept as JSON in the app's preferences. */
final class PinStore {
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

    /** Adds or replaces (by id). There is no limit on how many pages can be pinned. */
    static void put(Context c, Pin pin) {
        if (pin.temporary) return;   // an unpinned page is never written down
        List<Pin> pins = load(c);
        for (int i = 0; i < pins.size(); i++) {
            if (pins.get(i).id.equals(pin.id)) {
                pins.set(i, pin);
                save(c, pins);
                return;
            }
        }
        pins.add(pin);
        save(c, pins);
    }

    /**
     * Moves the pin with this id {@code delta} places (negative = earlier) in the list, stopping at the ends.
     * Returns whether anything moved. Pure, so it is tested without Android.
     */
    static boolean move(List<Pin> pins, String id, int delta) {
        int from = -1;
        for (int i = 0; i < pins.size(); i++) if (pins.get(i).id.equals(id)) from = i;
        if (from < 0) return false;
        int to = Math.max(0, Math.min(pins.size() - 1, from + delta));
        if (to == from) return false;
        pins.add(to, pins.remove(from));
        return true;
    }

    /**
     * Like {@link #move} but counting only the pins of one profile (the home screen shows one at a time): the pin swaps places
     * with its neighbour among them. "" counts every pin.
     */
    static boolean moveAmong(List<Pin> pins, String id, int delta, String profileId) {
        List<Integer> slots = new ArrayList<>();
        int at = -1;
        for (int i = 0; i < pins.size(); i++) {
            if (!profileId.isEmpty() && !pins.get(i).profileId.equals(profileId)) continue;
            if (pins.get(i).id.equals(id)) at = slots.size();
            slots.add(i);
        }
        if (at < 0) return false;
        int to = Math.max(0, Math.min(slots.size() - 1, at + delta));
        if (to == at) return false;
        Pin moved = pins.remove((int) slots.get(at));
        pins.add(slots.get(to), moved);
        return true;
    }

    static void moveSavedAmong(Context c, String id, int delta, String profileId) {
        List<Pin> pins = load(c);
        if (moveAmong(pins, id, delta, profileId)) save(c, pins);
    }

    /** Moves one pin in the saved list; see {@link #move(List, String, int)}. */
    static void moveSaved(Context c, String id, int delta) {
        List<Pin> pins = load(c);
        if (move(pins, id, delta)) save(c, pins);
    }

    static void remove(Context c, String id) {
        List<Pin> pins = load(c);
        for (int i = pins.size() - 1; i >= 0; i--) if (pins.get(i).id.equals(id)) pins.remove(i);
        save(c, pins);
    }
}
