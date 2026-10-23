package app.turbobox;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The sites that hold data in TurboBox's browser (cookies, so a sign-in, and page storage), so they can be listed and cleared
 * one by one. Android cannot list what a WebView stores, so the sites are noted as pages open: every site a page has been
 * on, in the store its sign-ins live in, plus the pinned pages.
 */
final class SiteData {
    private static final String KEY = "siteData";
    private static final Set<String> KNOWN = new HashSet<>();
    private static boolean loaded;

    static final class Site {
        final String host;
        final String store;   // the WebView profile its data lives in, "" = the shared one
        int cookies;

        Site(String host, String store) {
            this.host = host;
            this.store = store;
        }

        String key() {
            return store + "|" + host;
        }
    }

    private SiteData() {
    }

    /** The host and each parent domain with at least two labels: a.b.example.com -> a.b.example.com, b.example.com, example.com. */
    static List<String> hostAndParents(String host) {
        List<String> out = new ArrayList<>();
        if (host == null || host.isEmpty()) return out;
        String h = host.toLowerCase(Locale.ROOT);
        out.add(h);
        if (h.matches("[0-9.]+") || h.contains(":")) return out;   // an address has no parent domain
        String[] labels = h.split("\\.");
        for (int i = 1; i + 1 < labels.length; i++) {
            StringBuilder b = new StringBuilder();
            for (int j = i; j < labels.length; j++) b.append(j == i ? "" : ".").append(labels[j]);
            out.add(b.toString());
        }
        return out;
    }

    /** Cookie names out of a Cookie header ("a=1; b=2"). */
    static List<String> cookieNames(String header) {
        List<String> out = new ArrayList<>();
        if (header == null) return out;
        for (String part : header.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            String name = eq < 0 ? p : p.substring(0, eq).trim();
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return out;
    }

    private static void ensureLoaded(Context c) {
        if (loaded) return;
        loaded = true;
        for (Site s : saved(c)) KNOWN.add(s.key());
    }

    private static List<Site> saved(Context c) {
        List<Site> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String host = o.optString("host");
                if (!host.isEmpty()) out.add(new Site(host, o.optString("store")));
            }
        } catch (JSONException ignored) {
            // A damaged list reads as empty; sites are noted again as pages open.
        }
        return out;
    }

    private static void write(Context c, List<Site> list) {
        JSONArray a = new JSONArray();
        for (Site s : list) {
            try {
                a.put(new JSONObject().put("host", s.host).put("store", s.store));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        Prefs.sp(c).edit().putString(KEY, a.toString()).apply();
    }

    /** A page started loading this address in this store. Pages that leave nothing behind (incognito, wiped) are not noted. */
    static void seen(Context c, String store, String url) {
        String host = hostOf(url);
        if (host == null) return;
        ensureLoaded(c);
        Site s = new Site(host, store == null ? "" : store);
        if (!KNOWN.add(s.key())) return;
        List<Site> list = saved(c);
        list.add(s);
        write(c, list);
    }

    static String hostOf(String url) {
        if (url == null) return null;
        try {
            java.net.URI u = new java.net.URI(url);
            String scheme = u.getScheme();
            String h = u.getHost();
            if (h == null || h.isEmpty() || scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return null;
            return h.toLowerCase(Locale.ROOT);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** Every site noted so far plus the pinned pages, with how many cookies each holds. */
    static List<Site> list(Context c) {
        ensureLoaded(c);
        List<Site> all = saved(c);
        Set<String> have = new HashSet<>();
        for (Site s : all) have.add(s.key());
        for (Pin p : PinStore.load(c)) {
            String host = hostOf(p.url);
            if (host == null) continue;
            Site s = new Site(host, Profiles.webProfileName(p.profileId) == null ? "" : Profiles.webProfileName(p.profileId));
            if (have.add(s.key())) all.add(s);
        }
        for (Site s : all) s.cookies = cookieNames(cookiesOf(s).getCookie("https://" + s.host + "/")).size();
        all.sort((a, b) -> a.host.compareTo(b.host) != 0 ? a.host.compareTo(b.host) : a.store.compareTo(b.store));
        return all;
    }

    private static CookieManager cookiesOf(Site s) {
        if (!s.store.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                androidx.webkit.Profile p = ProfileStore.getInstance().getProfile(s.store);
                if (p != null) return p.getCookieManager();
            } catch (RuntimeException ignored) {
                // Falls back to the shared store below.
            }
        }
        return CookieManager.getInstance();
    }

    private static WebStorage storageOf(Site s) {
        if (!s.store.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                androidx.webkit.Profile p = ProfileStore.getInstance().getProfile(s.store);
                if (p != null) return p.getWebStorage();
            } catch (RuntimeException ignored) {
                // Falls back to the shared store below.
            }
        }
        return WebStorage.getInstance();
    }

    /**
     * Removes this site's cookies and page storage from its store. Cookies shared with a parent domain (sign-ins usually are)
     * go with it, so other sites of the same company sign out too.
     */
    static void delete(Context c, Site s) {
        CookieManager cm = cookiesOf(s);
        String url = "https://" + s.host + "/";
        List<String> domains = hostAndParents(s.host);
        for (String name : cookieNames(cm.getCookie(url))) {
            cm.setCookie(url, name + "=; Max-Age=0; Path=/");
            for (String d : domains) cm.setCookie(url, name + "=; Max-Age=0; Path=/; Domain=" + d);
        }
        cm.flush();
        WebStorage ws = storageOf(s);
        ws.deleteOrigin("https://" + s.host);
        ws.deleteOrigin("http://" + s.host);
        forget(c, s);
    }

    private static void forget(Context c, Site s) {
        ensureLoaded(c);
        KNOWN.remove(s.key());
        List<Site> list = saved(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).key().equals(s.key())) list.remove(i);
        write(c, list);
    }

    /** Every cookie and all page storage in every store. */
    static void deleteAll(Context c) {
        Set<String> stores = new HashSet<>();
        for (Site s : list(c)) stores.add(s.store);
        stores.add("");
        for (String store : stores) {
            Site probe = new Site("", store);
            cookiesOf(probe).removeAllCookies(null);
            cookiesOf(probe).flush();
            storageOf(probe).deleteAllData();
        }
        KNOWN.clear();
        write(c, new ArrayList<>());
    }
}
