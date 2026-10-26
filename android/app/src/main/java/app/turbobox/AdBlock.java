package app.turbobox;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Ad and tracker blocking for pages, using the same filter lists as uBlock Origin ({@link FilterEngine}). The lists are
 * downloaded by the app (Settings > Ad blocking), kept in the app's files and refreshed every few days; until the first
 * download a small built-in list stands in. Blocking happens in the page's request interception, hiding in a style
 * the page gets, and YouTube's in-page ad data is pruned by session.js.
 */
final class AdBlock {
    static final class ListInfo {
        final String id;
        final String name;
        final String url;
        final boolean defaultOn;

        ListInfo(String id, String name, String url, boolean defaultOn) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.defaultOn = defaultOn;
        }
    }

    static final ListInfo[] LISTS = {
            new ListInfo("easylist", "EasyList (ads)", "https://raw.githubusercontent.com/easylist/easylist/gh-pages/easylist.txt", true),
            new ListInfo("easyprivacy", "EasyPrivacy (trackers)", "https://raw.githubusercontent.com/easylist/easylist/gh-pages/easyprivacy.txt", true),
            new ListInfo("ubo", "uBlock filters (ads, quality of life)", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt", true),
            new ListInfo("ubo-privacy", "uBlock privacy", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt", true),
            new ListInfo("ubo-badware", "uBlock badware and scams", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt", false),
    };

    private static final long REFRESH_MS = 3L * 24 * 3600 * 1000;
    private static volatile FilterEngine engine;
    private static volatile boolean building;
    private static volatile String status = "";

    interface Listener {
        void onStatus(String text);
    }

    private AdBlock() {
    }

    static boolean enabled(Context c) {
        return Prefs.sp(c).getBoolean("adblock", true);
    }

    static void setEnabled(Context c, boolean on) {
        Prefs.sp(c).edit().putBoolean("adblock", on).apply();
        if (on) ensure(c);
    }

    static boolean listOn(Context c, ListInfo l) {
        return Prefs.sp(c).getBoolean("adblock_" + l.id, l.defaultOn);
    }

    static void setListOn(Context c, ListInfo l, boolean on) {
        Prefs.sp(c).edit().putBoolean("adblock_" + l.id, on).apply();
        rebuild(c);
    }

    static String status() {
        return status;
    }

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "adblock");
        d.mkdirs();
        return d;
    }

    /** The engine if it is built; null while it is still loading (requests are let through meanwhile). */
    static FilterEngine engine() {
        return engine;
    }

    /** Builds the engine in the background if it is not there yet, and refreshes old lists. Safe to call often. */
    static void ensure(Context c) {
        if (!enabled(c)) return;
        if (engine == null) rebuild(c);
        long last = Prefs.sp(c).getLong("adblockUpdated", 0);
        if (System.currentTimeMillis() - last > REFRESH_MS) update(c, null);
    }

    static void rebuild(Context c) {
        final Context app = c.getApplicationContext();
        if (building) return;
        building = true;
        new Thread(() -> {
            try {
                FilterEngine e = new FilterEngine();
                int lists = 0;
                for (ListInfo l : LISTS) {
                    if (!listOn(app, l)) continue;
                    File f = new File(dir(app), l.id + ".txt");
                    if (!f.exists()) continue;
                    e.addList(new String(readAll(new java.io.FileInputStream(f)), StandardCharsets.UTF_8));
                    lists++;
                }
                if (lists == 0) {
                    try (InputStream in = app.getAssets().open("adblock_seed.txt")) {
                        e.addList(new String(readAll(in), StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        // No built-in list: nothing is blocked until one is downloaded.
                    }
                }
                engine = e;
                status = e.networkRules() + " blocking rules, " + e.cosmeticRules() + " hiding rules from " + (lists == 0 ? "the built-in list" : lists + " list" + (lists == 1 ? "" : "s"));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                status = "Could not read the lists: " + e.getMessage();
            } finally {
                building = false;
            }
        }, "adblock-build").start();
    }

    /** Downloads the enabled lists again, then rebuilds. {@code done} (may be null) is told how it went, on the main thread. */
    static void update(Context c, Listener done) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            int ok = 0;
            int failed = 0;
            for (ListInfo l : LISTS) {
                if (!listOn(app, l)) continue;
                try {
                    download(l.url, new File(dir(app), l.id + ".txt"));
                    ok++;
                } catch (IOException e) {
                    failed++;
                }
            }
            if (ok > 0) Prefs.sp(app).edit().putLong("adblockUpdated", System.currentTimeMillis()).apply();
            final String text = failed == 0 ? "Lists updated." : ok + " updated, " + failed + " could not be downloaded.";
            if (ok > 0) {
                building = false;
                rebuild(app);
            }
            if (done != null) new Handler(Looper.getMainLooper()).post(() -> done.onStatus(text));
        }, "adblock-update").start();
    }

    private static void download(String address, File target) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "TurboBox");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] data = readAll(c.getInputStream());
            if (data.length < 1000) throw new IOException("list too short");
            File tmp = new File(target.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            if (!tmp.renameTo(target)) throw new IOException("could not save");
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[32768];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    // ---- per request and per page ----------------------------------------------------------------------------

    /** The resource type a request is, as well as it can be told from what WebView gives. */
    static int typeOf(boolean mainFrame, String url, String accept) {
        if (mainFrame) return FilterEngine.DOCUMENT;
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        path = path.toLowerCase(java.util.Locale.ROOT);
        if (path.endsWith(".js") || path.endsWith(".mjs")) return FilterEngine.SCRIPT;
        if (path.endsWith(".css")) return FilterEngine.STYLESHEET;
        if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif") || path.endsWith(".webp")
                || path.endsWith(".svg") || path.endsWith(".ico") || path.endsWith(".avif")) return FilterEngine.IMAGE;
        if (path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf")) return FilterEngine.FONT;
        if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".mp3") || path.endsWith(".ts")
                || path.endsWith(".m4s") || path.endsWith(".aac")) return FilterEngine.MEDIA;
        if (accept != null) {
            if (accept.contains("text/css")) return FilterEngine.STYLESHEET;
            if (accept.startsWith("image/")) return FilterEngine.IMAGE;
            if (accept.contains("text/html")) return FilterEngine.SUBDOCUMENT;
        }
        return FilterEngine.UNKNOWN;
    }

    /** True when the request should be answered with nothing. {@code pageUrl} is the page's address (may be null). */
    static boolean shouldBlock(String url, String pageUrl, boolean mainFrame, String accept) {
        FilterEngine e = engine;
        if (e == null || mainFrame) return false;
        String pageHost = pageUrl == null ? "" : FilterEngine.hostOf(pageUrl.toLowerCase(java.util.Locale.ROOT));
        return e.shouldBlock(url, pageHost, typeOf(false, url, accept));
    }

    /** JavaScript that hides the page's ad elements, or null when there is nothing to do. */
    static String hidingScript(String pageUrl) {
        FilterEngine e = engine;
        if (e == null || pageUrl == null) return null;
        String host = FilterEngine.hostOf(pageUrl.toLowerCase(java.util.Locale.ROOT));
        if (host.isEmpty()) return null;
        List<String> selectors = e.hideSelectors(host, 4000);
        if (selectors.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("window.__turbobox && window.__turbobox.hide([");
        for (int i = 0; i < selectors.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(org.json.JSONObject.quote(selectors.get(i)));
        }
        return sb.append("])").toString();
    }
}
