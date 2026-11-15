package app.turbobox;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;

/**
 * Loads one address once per browser identity, each in a clean throwaway profile, and reports what the site did with it: the page it
 * served (title and the start of its text), an HTTP status, or the network error. Shows at once whether a site is judging what the
 * browser says (the identities then differ) or something a page cannot change (they all fail alike).
 */
final class SiteProbe {
    interface Done {
        void done(String report);
    }

    private static final String[] NAMES = {"Chrome, Linux", "Chrome, Windows", "Edge, Windows", "Chrome, Mac", "Phone (mobile site)"};
    private static final String PROFILE = "tb_probe";
    private static final long TIMEOUT_MS = 25000;

    private final Activity act;
    private final String url;
    private final Done done;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private final String phoneUa;
    private int index;
    private WebView view;
    private boolean answered;

    private SiteProbe(Activity act, String url, Done done) {
        this.act = act;
        this.url = url;
        this.done = done;
        this.phoneUa = WebSettings.getDefaultUserAgent(act);
    }

    static void run(Activity act, String url, Done done) {
        new SiteProbe(act, url, done).next();
    }

    private void next() {
        if (view != null) {
            view.destroy();
            view = null;
        }
        if (index >= NAMES.length) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) ProfileStore.getInstance().deleteProfile(PROFILE);
            } catch (RuntimeException ignored) {
                // Left for the next test to empty.
            }
            done.done(report.toString());
            return;
        }
        final int which = index++;
        answered = false;
        final boolean phone = which == 4;
        final int id = phone ? 0 : which;
        try {
            view = new WebView(act);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(PROFILE).getCookieManager().removeAllCookies(null);
                WebViewCompat.setProfile(view, PROFILE);
            }
            view.clearCache(true);
            WebSettings st = view.getSettings();
            st.setJavaScriptEnabled(true);
            st.setDomStorageEnabled(true);
            st.setUserAgentString(Session.uaString(phone, id, phoneUa));
            Session.applyClientHints(st, phoneUa, !phone, id);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(st, Collections.<String>emptySet());
            }
            if (!phone && id > 0 && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                String plat = id == 3 ? "MacIntel" : "Win32";
                WebViewCompat.addDocumentStartJavaScript(view,
                        "try{var p=Object.getPrototypeOf(navigator);Object.defineProperty(p,'platform',{get:function(){return '" + plat + "'}});"
                                + "Object.defineProperty(p,'maxTouchPoints',{get:function(){return 0}});}catch(e){}",
                        Collections.singleton("*"));
            }
        } catch (RuntimeException e) {
            line(which, "could not be set up: " + e.getMessage());
            main.post(this::next);
            return;
        }
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) finish(which, "network error " + e.getDescription());
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                if (r.isForMainFrame()) finish(which, "HTTP " + e.getStatusCode());
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                // The page's own script decides what to show, so give it a moment, then read what is on screen.
                main.postDelayed(() -> {
                    if (answered || view != v) return;
                    v.evaluateJavascript("(function(){try{return document.title+' | '+(document.body?document.body.innerText.replace(/\\s+/g,' ').slice(0,140):'');}catch(e){return 'unreadable';}})()",
                            value -> finish(which, "loaded: " + (value == null ? "" : value)));
                }, 4000);
            }

            @Override
            public void onPageStarted(WebView v, String u, Bitmap f) {
            }
        });
        main.postDelayed(() -> finish(which, "no answer in " + (TIMEOUT_MS / 1000) + " s"), TIMEOUT_MS);
        view.loadUrl(url);
    }

    private void finish(int which, String text) {
        if (answered || which != index - 1) return;
        answered = true;
        line(which, text);
        main.post(this::next);
    }

    private void line(int which, String text) {
        report.append(NAMES[which]).append(": ").append(text).append('\n');
    }
}
