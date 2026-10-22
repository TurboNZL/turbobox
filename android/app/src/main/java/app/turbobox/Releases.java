package app.turbobox;

import org.json.JSONArray;
import org.json.JSONObject;

/** What the update check reads out of GitHub's release list. No Android classes, so it is tested on the JVM. */
final class Releases {
    private Releases() {
    }

    /** "2026.10.10" is newer than "2026.10.9": compare number by number. */
    static boolean isNewer(String remote, String local) {
        int[] a = parts(remote), b = parts(local);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String v) {
        String s = v.startsWith("v") ? v.substring(1) : v;
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        String[] p = s.split("\\.");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /**
     * The newest published (not draft) release that carries an .apk, pre-releases included, as {tag, asset API
     * address}. GitHub lists newest first. Null when there is none.
     */
    static String[] newestWithApk(JSONArray releases) {
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null || r.optBoolean("draft")) continue;
            JSONArray assets = r.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.optJSONObject(j);
                if (a != null && a.optString("name").endsWith(".apk")) {
                    return new String[]{r.optString("tag_name"), a.optString("url")};
                }
            }
        }
        return null;
    }
}
