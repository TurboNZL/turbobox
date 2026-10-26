package app.turbobox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Profiles: separate identities inside the app, like a browser's. Each has its own pinned pages and its own sign-ins (cookies
 * and storage live in a WebView profile of their own). App settings are global. Nothing else is shared
 * between profiles. The default profile is the one everything belonged to before profiles existed.
 */
final class Profiles {
    static final String DEFAULT = "default";
    private static final String KEY = "profiles";
    private static final String FILTER = "homeFilter";

    static final class Profile {
        final String id;
        String name;
        String salt = "";   // with hash: the password, if it has one
        String hash = "";

        boolean hasPassword() {
            return !hash.isEmpty();
        }

        Profile(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private Profiles() {
    }

    /** All profiles, the default first. */
    static List<Profile> load(Context c) {
        wipeOldPasswords(c);
        List<Profile> out = new ArrayList<>();
        Profile def = new Profile(DEFAULT, nameOfDefault(c));
        def.salt = Prefs.sp(c).getString("defaultProfileSalt", "");
        def.hash = Prefs.sp(c).getString("defaultProfileHash", "");
        out.add(def);
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString("id");
                if (!id.isEmpty() && !id.equals(DEFAULT)) {
                    Profile p = new Profile(id, o.optString("name", "Profile"));
                    p.salt = o.optString("salt");
                    p.hash = o.optString("hash");
                    out.add(p);
                }
            }
        } catch (JSONException ignored) {
            // A damaged list reads as just the default.
        }
        return out;
    }

    /** Profiles are locked by a number code now; a typed password from an earlier version cannot be entered on the pad, so it is dropped. */
    private static boolean wiped;

    private static void wipeOldPasswords(Context c) {
        if (wiped) return;
        wiped = true;
        if (Prefs.sp(c).getBoolean("pinCodes", false)) return;
        Prefs.sp(c).edit().putBoolean("pinCodes", true).remove("defaultProfileSalt").remove("defaultProfileHash").apply();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                a.getJSONObject(i).put("salt", "").put("hash", "");
            }
            Prefs.sp(c).edit().putString(KEY, a.toString()).apply();
        } catch (JSONException ignored) {
            // Nothing readable to wipe.
        }
    }

    private static String nameOfDefault(Context c) {
        return Prefs.sp(c).getString("defaultProfileName", "Default");
    }

    private static void save(Context c, List<Profile> list) {
        JSONArray a = new JSONArray();
        for (Profile p : list) {
            if (p.id.equals(DEFAULT)) {
                Prefs.sp(c).edit().putString("defaultProfileName", p.name).putString("defaultProfileSalt", p.salt)
                        .putString("defaultProfileHash", p.hash).apply();
                continue;
            }
            try {
                a.put(new JSONObject().put("id", p.id).put("name", p.name).put("salt", p.salt).put("hash", p.hash));
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
        c.getApplicationContext().deleteSharedPreferences("turbobox_p_" + id);   // settings files of an earlier version
        Session.deleteProfileData(id);
        if (id.equals(filter(c))) setFilter(c, DEFAULT);
    }

    // ---- codes (number pad) ----------------------------------------------------------------------------------------------
    // A profile with a code is locked until it is opened with it. Unlocked is remembered in memory only, so it is
    // locked again when the app is restarted, by Lock now, and after the app has been left alone for a couple of minutes.

    private static final java.util.Set<String> UNLOCKED = new java.util.HashSet<>();
    private static final long RELOCK_AFTER_MS = 2 * 60_000;
    private static long leftAt;

    static boolean isLocked(Context c, String id) {
        return !UNLOCKED.contains(id) && find(c, id).hasPassword();
    }

    /** True (and unlocked) when the password is right. */
    static boolean unlock(Context c, String id, String password) {
        Profile p = find(c, id);
        if (!p.hasPassword() || Passwords.verify(password, p.salt, p.hash)) {
            UNLOCKED.add(id);
            return true;
        }
        return false;
    }

    /** Sets a password (null or empty removes it). The profile stays unlocked for now. */
    static void setPassword(Context c, String id, String password) {
        List<Profile> list = load(c);
        for (Profile p : list) {
            if (!p.id.equals(id)) continue;
            if (password == null || password.isEmpty()) {
                p.salt = "";
                p.hash = "";
            } else {
                String[] h = Passwords.create(password);
                p.salt = h[0];
                p.hash = h[1];
            }
        }
        save(c, list);
        UNLOCKED.add(id);
    }

    static boolean verify(Context c, String id, String password) {
        Profile p = find(c, id);
        return !p.hasPassword() || Passwords.verify(password, p.salt, p.hash);
    }

    static void lock(String id) {
        UNLOCKED.remove(id);
    }

    static void lockAll() {
        UNLOCKED.clear();
    }

    /** The app's screens call this when they go to the background and when they come back. */
    static void leaving() {
        leftAt = System.currentTimeMillis();
    }

    static void returning() {
        if (leftAt != 0 && System.currentTimeMillis() - leftAt > RELOCK_AFTER_MS) lockAll();
        leftAt = 0;
    }

    /** The profile the home screen shows, or "" for all of them. */
    static String filter(Context c) {
        return Prefs.sp(c).getString(FILTER, DEFAULT);
    }

    static void setFilter(Context c, String id) {
        Prefs.sp(c).edit().putString(FILTER, id).apply();
    }

    /** The name of the WebView profile that holds this profile's cookies, or null for the shared default store. */
    static String webProfileName(String profileId) {
        return profileId == null || profileId.isEmpty() || DEFAULT.equals(profileId) ? null : "tb_p_" + profileId;
    }
}
