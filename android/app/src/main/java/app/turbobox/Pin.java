package app.turbobox;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.UUID;

/** A page pinned to the main screen: one tap opens it in a session. */
final class Pin {
    final String id;
    String name;
    String url;
    float zoom = 1f;        // 0.5..3, the layout width is pageWidth / zoom
    int pageWidth = 1280;   // the virtual screen width the page believes it has, in CSS pixels (desktop sites)
    boolean allowDrm = true; // protected video (Widevine): the page may play it. Such video is black in a recording and may not play on the private display
    boolean cursorMode;     // drive the page with a pointer (trackpad style) instead of touching it directly
    boolean shrinkOnType = true; // typing shrinks the page area to the space the keyboard leaves
    boolean noAdblock;      // this page is exempt from ad blocking
    boolean confirmClose;   // ask before closing this page (off by default)
    float volume = 1f;      // 0..2, gain on the page's audio
    int recordMode;         // recording when the page opens: 0 = follow the default in Settings, 1 = always, 2 = never
    boolean allowMic = true;      // privacy: may the page use the microphone (what it hears of you)
    boolean allowCamera;          // privacy: may the page use the camera (off until you allow it)
    String profileId = Profiles.DEFAULT;   // the profile it belongs to: its own sign-ins and settings
    boolean temporary;            // opened from "Open a page": never saved, no icon kept
    boolean wipeOnClose;          // incognito only: delete its cookies and data when it closes (otherwise the separate profile is kept for next time)
    boolean incognito;            // its own cookies and storage, thrown away when it closes
    boolean mobile;               // ask sites for their phone layout instead of the desktop one (desktop is the default)
    float zoomMini;               // zoom in the Mini window; 0 = the same as the normal one
    int aspectMini = -1;          // forced shape in the Mini window: -1 = the same as the normal one, else an index into ASPECTS (0 = fill)
    int aspect;                   // forced shape of the page's area: index into ASPECTS, 0 = fill the space

    /** Shapes a page can be forced into: label, width, height. The first means "no forced shape". */
    static final String[] ASPECT_LABELS = {"Off (fill the space)", "16:9 (video)", "4:3", "21:9 (ultrawide)", "1:1 (square)", "9:16 (portrait video)", "3:2"};
    static final float[] ASPECTS = {0f, 16f / 9f, 4f / 3f, 21f / 9f, 1f, 9f / 16f, 3f / 2f};

    Pin(String id, String name, String url) {
        this.id = id;
        this.name = name;
        this.url = url;
    }

    static Pin create(String name, String url) {
        return new Pin(UUID.randomUUID().toString().substring(0, 8), name, url);
    }

    JSONObject toJson() {
        try {
            return new JSONObject().put("id", id).put("name", name).put("url", url)
                    .put("zoom", zoom).put("volume", volume).put("recordMode", recordMode)
                    .put("allowMic", allowMic).put("allowCamera", allowCamera).put("aspect", aspect).put("zoomMini", zoomMini).put("aspectMini", aspectMini).put("mobile", mobile).put("pageWidth", pageWidth).put("confirmClose", confirmClose).put("noAdblock", noAdblock).put("shrinkOnType", shrinkOnType).put("cursorMode", cursorMode).put("allowDrm", allowDrm).put("profile", profileId);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static Pin fromJson(JSONObject o) {
        Pin p = new Pin(o.optString("id"), o.optString("name"), o.optString("url"));
        p.zoom = (float) o.optDouble("zoom", 1.0);
        p.volume = (float) o.optDouble("volume", 1.0);
        // Pins saved before recording was on by default only had a yes/no box: yes still means always.
        p.recordMode = o.has("recordMode") ? o.optInt("recordMode") : o.optBoolean("autoRecord", false) ? 1 : 0;
        p.allowMic = o.optBoolean("allowMic", true);
        p.allowCamera = o.optBoolean("allowCamera", false);
        p.mobile = o.optBoolean("mobile", false);
        p.pageWidth = Math.max(480, Math.min(3840, o.optInt("pageWidth", 1280)));
        if (Math.abs(p.zoom - 1f) > 0.01f) {   // zoom and page size are one setting now: what the page lays itself out at
            p.pageWidth = Math.max(480, Math.min(3840, Math.round(p.pageWidth / Math.max(0.5f, p.zoom))));
            p.zoom = 1f;
        }
        p.shrinkOnType = o.optBoolean("shrinkOnType", true);
        p.confirmClose = o.optBoolean("confirmClose", false);
        p.noAdblock = o.optBoolean("noAdblock", false);
        p.cursorMode = o.optBoolean("cursorMode", false);
        p.allowDrm = o.optBoolean("allowDrm", true);
        p.profileId = o.optString("profile", Profiles.DEFAULT);
        if (p.profileId.isEmpty()) p.profileId = Profiles.DEFAULT;
        p.aspect = Math.max(0, Math.min(ASPECTS.length - 1, o.optInt("aspect", 0)));
        p.zoomMini = (float) o.optDouble("zoomMini", 0.0);
        p.aspectMini = Math.max(-1, Math.min(ASPECTS.length - 1, o.optInt("aspectMini", -1)));
        return p;
    }

    String host() {
        try {
            String h = new URI(url).getHost();
            return h == null ? url : h;
        } catch (URISyntaxException e) {
            return url;
        }
    }

    /** Does this page start recording by itself when it opens? */
    boolean recordsOnOpen(boolean defaultOn) {
        return recordMode == 1 || (recordMode == 0 && defaultOn);
    }

    static String recordModeText(int mode, boolean defaultOn) {
        return mode == 1 ? "Always record this page" : mode == 2 ? "Never record this page"
                : "Follow the default (" + (defaultOn ? "record" : "do not record") + ")";
    }

    /** One letter for the tile. */
    String initial() {
        String n = name == null ? "" : name.trim();
        return n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    /**
     * What the user typed, as an address a WebView can open: "meet.example.com/room" becomes
     * "https://meet.example.com/room". Null when it is not an http(s) address with a host.
     */
    static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.contains(" ")) return null;
        if (!s.contains("://")) s = "https://" + s;
        try {
            URI u = new URI(s);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            if (u.getHost() == null || u.getHost().isEmpty()) return null;
            return s;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
