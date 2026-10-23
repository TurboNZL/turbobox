package app.turbobox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Profiles: separate identities inside the app, like a browser's. Each has its own pinned pages, its own sign-ins (cookies
 * and storage live in a WebView profile of their own) and its own settings (see {@link Prefs#activate}). Nothing is shared
 * between profiles. The default profile is the one everything belonged to before profiles existed.
 */
final class Profiles {
    static final String DEFAULT = "default";
    private static final String KEY = "profiles";
    private static final String FILTER = "homeFilter";

    static final class Profile {
        final String id;
        String name;

        Profile(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private Profiles() {
    }

    /** All profiles, the default first. */
    static List<Profile> load(Context c) {
        List<Profile> out = new ArrayList<>();
        out.add(new Profile(DEFAULT, nameOfDefault(c)));
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString("id");
                if (!id.isEmpty() && !id.equals(DEFAULT)) out.add(new Profile(id, o.optString("name", "Profile")));
            }
        } catch (JSONException ignored) {
            // A damaged list reads as just the default.
        }
        return out;
    }

    private static String nameOfDefault(Context c) {
        return Prefs.sp(c).getString("defaultProfileName", "Default");
    }

    private static void save(Context c, List<Profile> list) {
        JSONArray a = new JSONArray();
        for (Profile p : list) {
            if (p.id.equals(DEFAULT)) {
                Prefs.sp(c).edit().putString("defaultProfileName", p.name).apply();
                continue;
            }
            try {
                a.put(new JSONObject().put("id", p.id).put("name", p.name));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        Prefs.sp(c).edit().putString(KEY, a.toString()).apply();
    }

    static Profile find(Context c, String id) {
        for (Profile p : load(c)) if (p.id.equals(id)) return p;
        return load(c).get(0);
    }

    static Profile add(Context c, String name) {
        List<Profile> list = load(c);
        Profile p = new Profile(UUID.randomUUID().toString().substring(0, 8), name);
        list.add(p);
        save(c, list);
        return p;
    }

    static void rename(Context c, String id, String name) {
        List<Profile> list = load(c);
        for (Profile p : list) if (p.id.equals(id)) p.name = name;
        save(c, list);
    }

    /** Removes a profile and its pinned pages. Its sign-ins and settings are deleted too. The default one cannot be removed. */
    static void remove(Context c, String id) {
        if (DEFAULT.equals(id)) return;
        List<Profile> list = load(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        save(c, list);
        for (Pin pin : PinStore.load(c)) {
            if (!pin.profileId.equals(id)) continue;
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.close("its profile was removed");
            PinStore.remove(c, pin.id);
            Favicons.delete(c, pin.id);
        }
        c.getApplicationContext().deleteSharedPreferences("turbobox_p_" + id);
        Session.deleteProfileData(id);
        if (id.equals(filter(c))) setFilter(c, DEFAULT);
    }

    /** The profile the home screen shows, or "" for all of them. */
    static String filter(Context c) {
        return Prefs.sp(c).getString(FILTER, DEFAULT);
    }

    static void setFilter(Context c, String id) {
        Prefs.sp(c).edit().putString(FILTER, id).apply();
        Prefs.activate(id.isEmpty() ? DEFAULT : id);
    }

    /** The name of the WebView profile that holds this profile's cookies, or null for the shared default store. */
    static String webProfileName(String profileId) {
        return profileId == null || profileId.isEmpty() || DEFAULT.equals(profileId) ? null : "tb_p_" + profileId;
    }
}
