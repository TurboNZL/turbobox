package app.turbobox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** The saved split layouts, kept as JSON in the app's preferences. */
final class LayoutStore {
    private static final String KEY = "layouts";

    private LayoutStore() {
    }

    static List<SavedLayout> load(Context c) {
        List<SavedLayout> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(SavedLayout.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
            // A damaged list reads as empty.
        }
        return out;
    }

    private static void save(Context c, List<SavedLayout> list) {
        JSONArray a = new JSONArray();
        for (SavedLayout l : list) a.put(l.toJson());
        Prefs.sp(c).edit().putString(KEY, a.toString()).apply();
    }

    static SavedLayout find(Context c, String id) {
        if (id == null) return null;
        for (SavedLayout l : load(c)) if (l.id.equals(id)) return l;
        return null;
    }

    static void put(Context c, SavedLayout layout) {
        List<SavedLayout> list = load(c);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(layout.id)) {
                list.set(i, layout);
                found = true;
            }
        }
        if (!found) list.add(layout);
        save(c, list);
    }

    static void remove(Context c, String id) {
        List<SavedLayout> list = load(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        save(c, list);
    }
}
