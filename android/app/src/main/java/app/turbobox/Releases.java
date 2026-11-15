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
     * The newest published (not draft) release that carries a TurboBox app, pre-releases included, as {version, asset API
     * address}. The version is read from the app's file name (turbobox-2026.11.13-android.apk), not from the release's tag:
     * a test release can carry the previous real app under a newer tag, and then there is nothing to update to. Files with
     * any other name (test apps) are never taken. GitHub lists newest first. Null when there is none.
     */
    static String[] newestWithApk(JSONArray releases) {
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null || r.optBoolean("draft")) continue;
            JSONArray assets = r.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.optJSONObject(j);
                if (a == null) continue;
                String name = a.optString("name");
                if (name.startsWith("turbobox-") && name.endsWith("-android.apk")) {
                    String version = name.substring("turbobox-".length(), name.length() - "-android.apk".length());
                    return new String[]{version, a.optString("url")};
                }
            }
        }
        return null;
    }
}
