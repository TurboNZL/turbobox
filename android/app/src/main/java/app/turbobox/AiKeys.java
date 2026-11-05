package app.turbobox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Gemini keys the person saved, each labelled with the Google account it was made under, and which one is in use. A key belongs to the
 * account (and project) it was made in, so keeping several lets one install use a different account than the one set up for other things.
 * Kept in the app's private settings; never logged.
 */
final class AiKeys {
    private AiKeys() {
    }

    static final class Key {
        final String label;
        final String key;

        Key(String label, String key) {
            this.label = label;
            this.key = key;
        }

        /** "AIza...x1Yz": enough to tell keys apart. */
        String masked() {
            return key.length() <= 10 ? "..." : key.substring(0, 4) + "..." + key.substring(key.length() - 4);
        }
    }

    static List<Key> all(Context c) {
        List<Key> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString("aiKeys", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(new Key(a.getJSONObject(i).optString("label"), a.getJSONObject(i).optString("key")));
        } catch (JSONException ignored) {
            // None saved.
        }
        return out;
    }

    private static void save(Context c, List<Key> keys) {
        JSONArray a = new JSONArray();
        try {
            for (Key k : keys) a.put(new JSONObject().put("label", k.label).put("key", k.key));
        } catch (JSONException ignored) {
            return;
        }
        Prefs.sp(c).edit().putString("aiKeys", a.toString()).apply();
    }

    /** Adds (or replaces the one with the same label) and makes it the key in use. */
    static void add(Context c, String label, String key) {
        List<Key> keys = all(c);
        for (int i = keys.size() - 1; i >= 0; i--) if (keys.get(i).label.equals(label)) keys.remove(i);
        keys.add(new Key(label, key.trim()));
        save(c, keys);
        use(c, label);
    }

    static void remove(Context c, String label) {
        List<Key> keys = all(c);
        for (int i = keys.size() - 1; i >= 0; i--) if (keys.get(i).label.equals(label)) keys.remove(i);
        save(c, keys);
        if (label.equals(Prefs.sp(c).getString("aiKeyInUse", ""))) Prefs.sp(c).edit().putString("aiKeyInUse", keys.isEmpty() ? "" : keys.get(0).label).apply();
    }

    static void use(Context c, String label) {
        Prefs.sp(c).edit().putString("aiKeyInUse", label).apply();
    }

    /** The key in use, or null. */
    static Key current(Context c) {
        List<Key> keys = all(c);
        String want = Prefs.sp(c).getString("aiKeyInUse", "");
        for (Key k : keys) if (k.label.equals(want)) return k;
        return keys.isEmpty() ? null : keys.get(0);
    }

    /** Does this look like a Gemini key (what Google's key page shows)? */
    static boolean looksLikeKey(String s) {
        return s != null && s.trim().matches("AIza[0-9A-Za-z_-]{30,60}");
    }

    static boolean allowSend(Context c) { return Prefs.sp(c).getBoolean("aiAllowSend", false); }
    static void setAllowSend(Context c, boolean v) { Prefs.sp(c).edit().putBoolean("aiAllowSend", v).apply(); }
    static String model(Context c) { return Prefs.sp(c).getString("aiModel", GeminiClient.DEFAULT_MODEL); }
    static void setModel(Context c, String m) { Prefs.sp(c).edit().putString("aiModel", GeminiClient.cleanModel(m)).apply(); }
}
