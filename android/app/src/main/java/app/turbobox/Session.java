package app.turbobox;

import android.content.Context;
import android.content.MutableContextWrapper;
import android.media.AudioDeviceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One embedded browser session (there are {@link Sessions#COUNT} slots): a pinned page in a WebView, a hang-up
 * timer, a mute that cannot be undone by the page, the phone microphone played into the page, and the
 * recording buffer. It owns the WebView so the page keeps running when the activity is stopped (screen off, app
 * in the background); {@link SessionService} keeps the process alive. All methods run on the main thread.
 *
 * Carried over from Tether's WebSession (the web-call half of that app); the differences are that there can be
 * two of these at once, that listeners are a list, and that a session can be driven by a workflow
 * ({@link #evalStep}) and recorded ({@link #startRecording}).
 */
final class Session {
    interface Listener {
        /** Countdown update; {@code remainingMs} is -1 when no timer is running. */
        default void onTimer(Session s, long remainingMs) {}

        /** A page was opened in this slot (a workflow may do it while a screen is showing others). */
        default void onOpened(Session s) {}

        /** The page was closed, by the timer, the Close button, the notification or a workflow. */
        default void onClosed(Session s, String reason) {}

        /** Mute changed, possibly from the notification rather than the screen. */
        default void onMuted(Session s, boolean muted) {}

        /** Live level (0..1 RMS) of the call's outgoing audio. */
        default void onLevel(Session s, float rms) {}

        /** What the page sees of this browser (for checking the desktop emulation). */
        default void onInfo(Session s, String text) {}

        /** Something the user should know, e.g. the chosen microphone could not be opened. */
        default void onNotice(Session s, String text) {}

        /** Record-and-play-back test: state is "recording", "playing", "done" or "error". */
        default void onLoopback(Session s, String state, float peak, String verdict, String message) {}

        /** The page was frozen or let go (Step away / Back). */
        default void onFrozen(Session s, boolean frozen) {}

        /** The recording buffer started or stopped. */
        default void onRecording(Session s, boolean on, String note) {}

        /** The page asked for full screen (a video's full-screen button): show {@code view} over everything. {@code null} = leave it. */
        default void onFullscreen(Session s, View view) {}

        /** The page went to another address, or the back / forward history changed. */
        default void onNavigation(Session s) {}

        /** The forced shape of the page's area changed. */
        default void onAspect(Session s) {}
    }

    interface StepCallback {
        void done(boolean ok, String message);
    }

    final int slot;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private Context app;
    private WebView webView;
    private Pin pin;
    private boolean active;
    private String startHost = "";
    private final java.util.Set<String> allowedRoots = new java.util.HashSet<>();   // sites that may be given the microphone and camera
    private boolean muted;
    private float volume = 1f;        // 0..2, applied to the page's audio inside the page
    private String micKey = "";       // "" = the page's own capture; otherwise an input device, captured natively
    private boolean aec = true;
    private float zoom = 1f;          // 0.5..3: the page's layout width is 1280 / zoom
    private float micGain = 1f;       // 0..3: gain on what the other person hears
    private final VirtualMic vmic = new VirtualMic();
    private boolean pageWantsMic;     // the page currently holds the virtual microphone
    private boolean pageLoaded;       // a real page has finished loading (not the blank page)
    private int muteMisses;           // consecutive checks the page did not answer
    private boolean muteWarned;       // one warning per page load, never a stream of them
    private boolean callMeter;
    private String script;            // assets/session.js, read once
    private long hangupAt;            // SystemClock.elapsedRealtime() deadline, 0 = no timer
    private long notifiedMinutes = -1;
    private BufferRecorder recorder;
    private boolean transcribePending;   // captions were asked for before the recording started
    private Window frameWindow;       // the visible activity's window, the recorder's picture source
    long awayAtWall;                  // when "Step away" was tapped (wall clock), 0 = not away
    boolean catchingUp;               // "Back" was tapped and the missed part is being replayed: still frozen
    private boolean frozen;           // the live page is frozen and silent on this phone; the recording goes on
    private boolean micAllowed = true;    // privacy: the page may use the microphone
    private boolean cameraAllowed;        // privacy: the page may use the camera
    private int viewCssWidth;         // the page's own area in CSS pixels, so a small window lays out right
    private int aspect;               // forced shape of the page's area, an index into Pin.ASPECTS (0 = fill)
    private View customView;          // the page's full-screen view while it is full screen
    private WebChromeClient.CustomViewCallback customCallback;
    private OffscreenHost keepHost;   // the page on a private display while no screen shows it and it is not recording
    private boolean keepHostFailed;

    Session(int slot) {
        this.slot = slot;
    }

    // ---- listeners and state -------------------------------------------------------------------

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    boolean isActive() {
        return active;
    }

    Pin pin() {
        return pin;
    }

    String title() {
        return pin == null ? "Page" : pin.name;
    }

    /** "A | Snapchat" while more than one page is open, just "Snapchat" for a single page. */
    String label() {
        return Sessions.tag(this) + title();
    }

    /** A page a workflow borrowed a slot for because A and B were busy: it goes away when it closes. */
    boolean isTemporary() {
        return slot >= Sessions.PERMANENT;
    }

    /** The label with its state in brackets: "A | Snapchat [muted] [recording] [hangs up in 42 min]". */
    String statusLine() {
        StringBuilder sb = new StringBuilder(label());
        if (isMuted()) sb.append(" [muted]");
        if (isRecording()) sb.append(" [recording]");
        long left = getRemainingMs();
        if (left >= 0) sb.append(" [hangs up in ").append(formatMinutes(left)).append(']');
        return sb.toString();
    }

    boolean isMuted() {
        return muted;
    }

    boolean isPageLoaded() {
        return pageLoaded;
    }

    float volume() {
        return volume;
    }

    float zoom() {
        return zoom;
    }

    int aspect() {
        return aspect;
    }

    /** Forces the page's area into a shape (an index into {@link Pin#ASPECTS}, 0 = fill the space); kept with the pin. */
    void setAspect(int index) {
        aspect = Math.max(0, Math.min(Pin.ASPECTS.length - 1, index));
        if (pin != null) {
            pin.aspect = aspect;
            PinStore.put(app, pin);
        }
        for (Listener l : listeners) l.onAspect(this);
    }

    // ---- history ---------------------------------------------------------------------------------

    boolean canGoBack() {
        return webView != null && active && webView.canGoBack();
    }

    boolean canGoForward() {
        return webView != null && active && webView.canGoForward();
    }

    void goBack() {
        if (canGoBack()) webView.goBack();
    }

    void goForward() {
        if (canGoForward()) webView.goForward();
    }

    // ---- full screen -----------------------------------------------------------------------------

    boolean isFullscreen() {
        return customView != null;
    }

    /** Leaves the page's full-screen view (the Back key does this first). */
    void exitFullscreen() {
        if (customView == null) return;
        WebChromeClient.CustomViewCallback cb = customCallback;
        customView = null;
        customCallback = null;
        for (Listener l : listeners) l.onFullscreen(this, null);
        if (cb != null) cb.onCustomViewHidden();
    }

    /** The shared WebView if it exists; never creates one. */
    WebView peekWebView() {
        return webView;
    }

    // ---- timer ---------------------------------------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active || hangupAt == 0) return;
            // A real-time deadline, not a tick count, so a late tick cannot drift it.
            long left = hangupAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                close("hang-up timer reached");
                return;
            }
            for (Listener l : listeners) l.onTimer(Session.this, left);
            long minutes = (left + 59_999) / 60_000;
            if (minutes != notifiedMinutes) {
                notifiedMinutes = minutes;
                SessionService.refresh();
            }
            main.postDelayed(this, 1000);
        }
    };

    /** Time left on the timer, or -1 when none is running. */
    long getRemainingMs() {
        return hangupAt == 0 ? -1 : Math.max(0, hangupAt - SystemClock.elapsedRealtime());
    }

    /** Hang up after {@code delayMs}; 0 or less turns the timer off. */
    void arm(long delayMs) {
        main.removeCallbacks(tick);
        notifiedMinutes = -1;
        if (delayMs <= 0 || !active) {
            hangupAt = 0;
            for (Listener l : listeners) l.onTimer(this, -1);
            SessionService.refresh();
            return;
        }
        hangupAt = SystemClock.elapsedRealtime() + delayMs;
        tick.run();
    }

    /** "42 min" or "1 h 05 min", rounded up to the minute. */
    static String formatMinutes(long ms) {
        long minutes = Math.max(1, (ms + 59_999) / 60_000);
        return minutes < 60 ? minutes + " min"
                : String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60);
    }

    // ---- mute ----------------------------------------------------------------------------------

    private final Runnable muteCheck = new Runnable() {
        @Override
        public void run() {
            if (!active || !silenced()) return;
            checkMute();
            main.postDelayed(this, 3000);
        }
    };

    /**
     * Mutes or unmutes the outgoing audio only; the page, the call and the foreground service keep
     * running, so unmuting is instant.
     */
    void setMuted(boolean value) {
        muted = value;
        applyMute();                   // the native microphone path goes silent too, whatever the page does
        main.removeCallbacks(muteCheck);
        muteMisses = 0;
        muteWarned = false;
        if (silenced()) {
            checkMute();
            main.postDelayed(muteCheck, 3000);
        }
        for (Listener l : listeners) l.onMuted(this, value);
        SessionService.refresh();
    }

    /**
     * Asks the page whether the mute is real. The page answers straight away (this is not slowed down in the
     * background like page timers are). A leak is fixed at once and reported; a page that cannot be asked is
     * reported only if a real page is loaded, only after several misses in a row, and only once per page load.
     */
    private void checkMute() {
        if (webView == null || !active || !silenced()) return;
        webView.evaluateJavascript("window.__turbobox ? window.__turbobox.muteStatus() : null", value -> {
            if (!active || !silenced()) return;
            boolean answered = false;
            boolean silent = true;
            if (value != null && !value.equals("null")) {
                try {
                    silent = new JSONObject(value).optBoolean("silent", true);
                    answered = true;
                } catch (JSONException ignored) {
                    // Treated as no answer.
                }
            }
            if (answered) {
                muteMisses = 0;
                if (!silent) {
                    js("__turbobox.setMute(true)"); // lock it again
                    notice("MUTE LEAK FIXED - something tried to un-mute the call.");
                }
                return;
            }
            if (!pageLoaded) return; // no page, no call: nothing to vouch for
            if (++muteMisses >= 3 && !muteWarned) {
                muteWarned = true;
                notice("MUTE NOT CONFIRMED - the page is not answering. Close the page if you must be sure.");
            }
        });
    }

    // ---- privacy: what the page may use ---------------------------------------------------------

    boolean micAllowed() {
        return micAllowed;
    }

    boolean cameraAllowed() {
        return cameraAllowed;
    }

    /**
     * Microphone privacy. Off: the page is refused the microphone, and a call that already has it is cut at once
     * (the outgoing audio is locked silent, whatever the page does, and the native capture stops).
     */
    void setMicAllowed(boolean allowed) {
        micAllowed = allowed;
        if (pin != null) {
            pin.allowMic = allowed;
            PinStore.put(app, pin);
        }
        applyMute();
        js("__turbobox.configure({micAllowed:" + allowed + "})");
        if (!allowed) stopCapture();
        else if (pageWantsMic) startCapture();
        notice(allowed ? "The page may use the microphone again." : "Microphone blocked for this page.");
    }

    /** Camera privacy. The page is only handed the camera when this is on (and Android's camera permission is granted). */
    void setCameraAllowed(boolean allowed) {
        cameraAllowed = allowed;
        if (pin != null) {
            pin.allowCamera = allowed;
            PinStore.put(app, pin);
        }
        js("__turbobox.configure({cameraAllowed:" + allowed + "})");
        notice(allowed ? "The page may ask for the camera." : "Camera blocked for this page. A call that already has it keeps it until the page reloads.");
    }

    /** What the outgoing audio is locked to: muted by the user, or the microphone is blocked. */
    private boolean silenced() {
        return muted || !micAllowed;
    }

    private void applyMute() {
        vmic.setMuted(silenced());
        js("__turbobox.setMute(" + silenced() + ")");
    }

    // ---- freeze: stepping away ------------------------------------------------------------------

    boolean isFrozen() {
        return frozen;
    }

    /**
     * Frozen: this phone stops showing and playing the live page (the activity covers it with its last picture and
     * the page's own sound is switched off here), while the recording goes on taking the live picture and sound.
     */
    void setFrozen(boolean on) {
        if (frozen == on) return;
        frozen = on;
        js("__turbobox.setLocalMute(" + on + ")");
        for (Listener l : listeners) l.onFrozen(this, on);
    }

    // ---- audio settings ------------------------------------------------------------------------

    /** Microphone volume, 0..3: gain on what the other person hears (above 1 boosts a quiet mic). */
    void setMicGain(float gain) {
        micGain = Math.max(0f, Math.min(3f, gain));
        js(String.format(Locale.US, "__turbobox.setMicGain(%.3f)", micGain));
    }

    /**
     * Records {@code seconds} the way the call captures (including the mic volume), plays it back through
     * the page, and rates the level. Progress arrives in {@link Listener#onLoopback}.
     */
    void startLoopback(int seconds) {
        js("__turbobox.loopback(" + seconds + ")");
    }

    /** Gain on the page's audio, 0..2 (above 1 boosts it). */
    void setVolume(float gain) {
        volume = Math.max(0f, Math.min(2f, gain));
        js(String.format(Locale.US, "__turbobox.setVolume(%.3f)", volume));
    }

    /** Page zoom, 0.5..3: lays the page out again at 1280 / zoom CSS pixels wide (not a magnifier). */
    void setZoom(float value) {
        zoom = Math.max(0.5f, Math.min(3f, value));
        js(String.format(Locale.US, "__turbobox.setZoom(%.3f)", zoom));
    }

    /**
     * Choose the microphone: "" for the page's own capture, or a key from {@link Devices#key} to capture that
     * input natively and hand it to the page. Switches a live call without renegotiating.
     */
    void setMicChoice(String key) {
        micKey = key == null ? "" : key;
        js("__turbobox.configure({micLabel:" + JSONObject.quote(micLabel()) + "})");
        js("__turbobox.setVirtualMic(" + !micKey.isEmpty() + ")");
        if (micKey.isEmpty()) {
            stopCapture();
        } else if (pageWantsMic) {
            stopCapture();
            startCapture();
        }
    }

    /** Echo cancellation and noise suppression on or off; switches the live capture. */
    void setProcessing(boolean on) {
        aec = on;
        js("__turbobox.setProcessing(" + on + ")");
        if (vmic.isRunning()) {
            stopCapture();
            startCapture();
        }
    }

    /** Stream the call's outgoing level to {@link Listener#onLevel}. */
    void setCallMeter(boolean on) {
        callMeter = on;
        js("__turbobox.setMeter(" + on + ")");
    }

    void requestInfo() {
        js("__turbobox.info()");
    }

    private void js(String code) {
        if (webView != null) webView.evaluateJavascript("window.__turbobox && " + code, null);
    }

    private void notice(String text) {
        for (Listener l : listeners) l.onNotice(this, text);
    }

    // ---- native microphone ---------------------------------------------------------------------

    /** The chosen input's name, which is also what the page lists as its microphone. */
    private String micLabel() {
        AudioDeviceInfo d = Devices.find(app, micKey);
        return d == null ? "" : Devices.describe(d);
    }

    private void startCapture() {
        if (micKey.isEmpty() || vmic.isRunning() || !active) return;
        AudioDeviceInfo device = Devices.find(app, micKey);
        if (device == null) notice("The chosen microphone is not available now; using the system default input.");
        if (!micAllowed) return;
        vmic.setMuted(silenced());
        InputRoute.apply(app, device);
        vmic.start(device, aec, new VirtualMic.Listener() {
            @Override
            public void onNotice(String message) {
                notice(message);
            }

            @Override
            public void onFrame(String base64Pcm) {
                js("__turbobox.pcm('" + base64Pcm + "')");
            }

            @Override
            public void onError(String message) {
                notice("Microphone: " + message);
            }
        });
    }

    private void stopCapture() {
        vmic.stop();
    }

    /** Puts the Bluetooth route of this session's input back after another session closed and cleared it. */
    void reapplyRoute() {
        if (active && vmic.isRunning()) InputRoute.apply(app, Devices.find(app, micKey));
    }

    // ---- page ----------------------------------------------------------------------------------

    /** The shared WebView, created on first use. Attach it to a view with {@link #attach}. */
    WebView webView(Context context) {
        if (webView == null) {
            app = context.getApplicationContext();
            webView = createWebView();
        }
        return webView;
    }

    /** Point the WebView's dialogs and permission prompts at the visible activity. */
    void attach(Context activity) {
        if (webView != null) ((MutableContextWrapper) webView.getContext()).setBaseContext(activity);
    }

    /** Release the activity so a stopped or destroyed screen is not leaked. */
    void detach() {
        if (keepHost == null && webView != null && app != null) ((MutableContextWrapper) webView.getContext()).setBaseContext(app);
    }

    /** A page that stays running when no screen is showing it. */
    void keepRunning() {
        if (webView != null && active) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    /** Open the pinned page and keep it alive in the background until {@link #close}. */
    void open(Context context, Pin page) {
        app = context.getApplicationContext();
        pin = page;
        Devices.ensureMicChoice(app);
        micKey = Prefs.micKey(app);
        aec = Prefs.aec(app);
        micGain = Prefs.micGain(app);
        zoom = page.zoom;
        aspect = page.aspect;
        volume = page.volume;
        micAllowed = page.allowMic;
        cameraAllowed = page.allowCamera;
        frozen = false;
        catchingUp = false;
        WebView wv = webView(context);
        if (wv.getWidth() == 0) {
            // Started by a workflow with no screen showing it: give the page a screen-sized area to lay out in, or
            // every element would measure zero and nothing could be found or clicked. A real layout replaces it.
            android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
            wv.layout(0, 0, dm.widthPixels, dm.heightPixels);
            viewCssWidth = Math.round(dm.widthPixels / dm.density);
        }
        Uri uri = Uri.parse(page.url);
        startHost = uri.getHost() == null ? "" : uri.getHost();
        allowedRoots.clear();
        allowSite(page.url);
        active = true;
        hangupAt = 0;
        muted = false;
        pageLoaded = false;
        SessionService.ensure(app);
        wv.loadUrl(page.url);
        keepRunning();
        if (page.recordsOnOpen(Prefs.recordByDefault(app))) startRecording();
        // Started by a workflow with no screen open: host it. (Posted, because a tap on a tile has the screen attach its window
        // in this same call, and then there is nothing to host.)
        main.post(() -> {
            if (frameWindow == null) hostWithoutScreen();
        });
        for (Listener l : listeners) l.onOpened(this);
    }

    /** Load the pinned address again (a workflow's "open" step). */
    void reload() {
        if (!active || webView == null || pin == null) return;
        pageLoaded = false;
        webView.loadUrl(pin.url);
    }

    /** Ends the call on this end by closing the page; the cookies (the login) are kept. */
    void close(String reason) {
        if (!active) return;
        active = false;
        main.removeCallbacks(tick);
        hangupAt = 0;
        muted = false;
        vmic.setMuted(false);
        main.removeCallbacks(muteCheck);
        pageLoaded = false;
        pageWantsMic = false;
        awayAtWall = 0;
        catchingUp = false;
        frozen = false;
        exitFullscreen();
        leaveHost();
        stopRecording();
        stopCapture();
        InputRoute.clear(app);
        for (Session other : Sessions.all()) if (other != this) other.reapplyRoute();
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.clearHistory();
        }
        for (Listener l : listeners) {
            l.onTimer(this, -1);
            l.onMuted(this, false);
            l.onClosed(this, reason);
        }
        SessionService.ensure(app);
    }

    // ---- workflows -----------------------------------------------------------------------------

    /**
     * Runs one workflow step on the page and reports whether it worked. The page answers straight away
     * (evaluateJavascript is not slowed down in the background like page timers are); the caller retries.
     */
    void evalStep(JSONObject step, StepCallback cb) {
        if (webView == null || !active) {
            cb.done(false, "the page is not open");
            return;
        }
        webView.evaluateJavascript("window.__turbobox ? window.__turbobox.step(" + step
                + ") : {ok:false,msg:'the page script is not ready yet'}", value -> {
            try {
                JSONObject o = new JSONObject(value);
                cb.done(o.optBoolean("ok"), o.optString("msg"));
            } catch (JSONException | NullPointerException e) {
                cb.done(false, "the page did not answer");
            }
        });
    }

    // ---- recording buffer ----------------------------------------------------------------------

    boolean isRecording() {
        return recorder != null && recorder.isRecording();
    }

    BufferRecorder recorder() {
        return recorder;
    }

    /** The visible activity's window, which is where the recorder takes its pictures from; null when none shows. */
    void setFrameWindow(Window w) {
        frameWindow = w;
        if (recorder != null) recorder.setSource(w, webView);
        if (w != null) leaveHost(); else hostWithoutScreen();
    }

    // ---- staying visible with no screen ----------------------------------------------------------

    /**
     * No screen shows the page (the phone's screen is off, another app is in front, or a workflow opened it) and it is
     * not being recorded, which would host it already. Pages like YouTube stop their video when the browser says
     * nobody can see them, so the page is moved onto a private display of our own that is composited whatever the
     * physical screen does; nothing is drawn on the phone and nobody reads the pictures.
     */
    void hostWithoutScreen() {
        if (!active || keepHost != null || keepHostFailed || webView == null || app == null || isRecording()) return;
        if (!Prefs.keepPlaying(app)) return;
        android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
        int srcW = webView.getWidth() >= 16 ? webView.getWidth() : dm.widthPixels;
        int srcH = webView.getHeight() >= 16 ? webView.getHeight() : dm.heightPixels;
        float k = Math.min(1f, 480f / srcW);
        int dpi = Math.max(60, Math.round(dm.densityDpi * k));
        OffscreenHost host = new OffscreenHost(app, "keep" + slot, webView, Math.max(16, Math.round(srcW * k)),
                Math.max(16, Math.round(srcH * k)), dpi);
        if (host.start()) {
            host.drain();
            keepHost = host;
        } else {
            keepHostFailed = true;
            notice("The page cannot be kept visible with the screen off on this phone (" + host.error() + "), so a video may pause.");
        }
    }

    /** Tells the page its visibility setting again and hosts it (or not) after the setting changed. */
    void refreshVisibility() {
        js("__turbobox.configure({keepVisible:" + Prefs.keepPlaying(app) + "})");
        if (frameWindow == null) hostWithoutScreen();
    }

    /** A screen shows the page again (or it closed): take it off the private display. */
    void leaveHost() {
        OffscreenHost h = keepHost;
        keepHost = null;
        if (h != null) h.stop();
    }

    /** Starts the rolling recording of this page: what is on screen, and the sound the page plays. */
    void startRecording() {
        if (!active || isRecording() || app == null) return;
        leaveHost();   // the recorder hosts the page itself when no screen can be copied
        int w = webView == null || webView.getWidth() < 16 ? 720 : webView.getWidth();
        int h = webView == null || webView.getHeight() < 16 ? 1280 : webView.getHeight();
        BufferRecorder r = new BufferRecorder(app, slot, title(), w, h, new BufferRecorder.Listener() {
            @Override
            public void onNotice(String message) {
                main.post(() -> notice(message));
            }

            @Override
            public void onError(String message) {
                main.post(() -> {
                    js("__turbobox.setTap(false)");
                    for (Listener l : listeners) l.onRecording(Session.this, false, message);
                    SessionService.refresh();
                });
            }
        });
        if (!r.start()) {
            notice("Could not start the recording: " + r.lastError());
            return;
        }
        recorder = r;
        recorder.setSource(frameWindow, webView);
        js("__turbobox.setTap(true)");
        if (transcribePending || Prefs.transcribeNext(app)) {
            transcribePending = false;
            Prefs.setTranscribeNext(app, false);   // the one-time switch in Settings
            startTranscription();
        }
        for (Listener l : listeners) l.onRecording(this, true, "");
        SessionService.refresh();
    }

    boolean isTranscribing() {
        return recorder != null && recorder.isTranscribing();
    }

    /** Live captions (speech to text, saved as WebVTT) for the recording; asked for before it starts, they begin with it. */
    void setTranscribing(boolean on) {
        if (on) {
            if (isRecording()) startTranscription(); else transcribePending = true;
        } else {
            transcribePending = false;
            if (recorder != null) recorder.stopTranscribing();
        }
        for (Listener l : listeners) l.onRecording(this, isRecording(), "");
    }

    private void startTranscription() {
        if (recorder == null) return;
        recorder.startTranscribing(Prefs.transcribeLang(app), text -> notice(text));
        for (Listener l : listeners) l.onRecording(this, isRecording(), "");
    }

    void stopRecording() {
        if (recorder == null) return;
        BufferRecorder r = recorder;
        recorder = null;
        js("__turbobox.setTap(false)");
        r.stop();
        for (Listener l : listeners) l.onRecording(this, false, "");
        SessionService.refresh();
        // The recorder was hosting the page with no screen; its own clean-up is already queued, so host it after that.
        if (frameWindow == null) main.post(this::hostWithoutScreen);
    }

    // ---- stepping away -----------------------------------------------------------------------------

    /**
     * Step away: make sure the page is recording, mark the moment, and freeze and silence the live page on this phone
     * (the recording goes on). False when it could not start recording.
     */
    boolean stepAway() {
        if (!active || awayAtWall != 0) return active;
        if (!isRecording()) startRecording();
        if (!isRecording()) return false;
        mark("Stepped away");
        awayAtWall = System.currentTimeMillis();
        setFrozen(true);
        return true;
    }

    /** Back without a replay (a workflow's "step back"): the live page is shown and heard again. */
    void stepBack() {
        awayAtWall = 0;
        catchingUp = false;
        setFrozen(false);
    }

    /** A bookmark in the buffer (the moment you stepped away, a topic starting). */
    void mark(String label) {
        if (recorder != null) recorder.mark(label);
    }

    // ---- WebView -------------------------------------------------------------------------------

    private WebView createWebView() {
        WebView wv = new WebView(new MutableContextWrapper(app));
        WebSettings st = wv.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        // Ask for the desktop layout of the site, and let it be pinch-zoomed on a phone.
        String mobileUa = st.getUserAgentString();
        st.setUserAgentString(desktopUserAgent(mobileUa));
        applyDesktopClientHints(st, mobileUa);
        // WebView adds "X-Requested-With: <this app's package>" to every request. Google's sign-in (YouTube) and others read
        // it to refuse "embedded browsers", so send it to no site at all.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(st, Collections.<String>emptySet());
        }
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        st.setSupportZoom(true);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(wv, true);

        // Tell the page how wide its area really is (a floating window or half of a split screen is narrower than the
        // screen), so it lays out to fit instead of showing one corner.
        wv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l;
            if (w > 0 && w != or - ol) {
                viewCssWidth = Math.round(w / v.getResources().getDisplayMetrics().density);
                js("__turbobox.setViewWidth(" + viewCssWidth + ")");
            }
        });
        wv.addJavascriptInterface(new Bridge(), "TurboBoxBridge");
        // Before any page script runs, where the browser supports it; the callbacks below cover the rest
        // (the script is idempotent).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, sessionScript(), Collections.singleton("*"));
        }
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                allowSite(url);
                pageLoaded = false;
                muteMisses = 0;
                muteWarned = false;
                pageWantsMic = false; // a new document starts without the virtual mic; it will ask again
                stopCapture();
                view.evaluateJavascript(pageScript(), null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = !"about:blank".equals(url);
                view.evaluateJavascript(pageScript(), null);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                for (Listener l : listeners) l.onNavigation(Session.this);
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Only for the page that was opened (and its own subdomains), only what its privacy switches allow.
                List<String> allowed = new ArrayList<>();
                if (hostAllowed(request.getOrigin())) {
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && micAllowed) allowed.add(r);
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && cameraAllowed
                                && app.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            allowed.add(r);
                        }
                    }
                }
                if (allowed.isEmpty()) {
                    request.deny();
                } else {
                    request.grant(allowed.toArray(new String[0]));
                }
            }

            // The page's own full-screen button (a video's, YouTube's): the screen shows the view the page hands over.
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null || listeners.isEmpty()) {
                    callback.onCustomViewHidden();   // nothing to show it on (no screen is open) or already full screen
                    return;
                }
                customView = view;
                customCallback = callback;
                for (Listener l : listeners) l.onFullscreen(Session.this, view);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public void onReceivedIcon(WebView view, android.graphics.Bitmap icon) {
                if (pin != null && icon != null) Favicons.saveIfBetter(app, pin.id, icon);
            }
        });
        return wv;
    }

    /** assets/session.js: desktop emulation, mic selection, mute, volume, level meters, audio tap, workflow steps. */
    private String sessionScript() {
        if (script == null) {
            try (InputStream in = app.getAssets().open("session.js")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                script = out.toString("UTF-8");
            } catch (IOException e) {
                script = "";
            }
        }
        return script;
    }

    /** The script plus the current settings, so a freshly loaded page starts in the right state. */
    private String pageScript() {
        try {
            JSONObject settings = new JSONObject()
                    .put("muted", silenced()).put("volume", volume).put("aec", aec).put("zoom", zoom)
                    .put("micGain", micGain).put("virtual", !micKey.isEmpty()).put("micLabel", micLabel())
                    .put("tap", isRecording()).put("micAllowed", micAllowed).put("cameraAllowed", cameraAllowed).put("localMute", frozen)
                    .put("keepVisible", Prefs.keepPlaying(app));
            if (viewCssWidth > 0) settings.put("viewWidth", viewCssWidth);
            return sessionScript() + "\nwindow.__turbobox.configure(" + settings + ");"
                    + (callMeter ? "\nwindow.__turbobox.setMeter(true);" : "");
        } catch (JSONException e) {
            return sessionScript();
        }
    }

    /** Messages from the page's script (levels, the mic list, page info, audio); they arrive off the main thread. */
    private final class Bridge {
        @JavascriptInterface
        public void post(final String type, final String data) {
            main.post(() -> dispatch(type, data));
        }
    }

    private void dispatch(String type, String data) {
        if (type == null || data == null) return;
        try {
            switch (type) {
                case "level":
                    for (Listener l : listeners) l.onLevel(this, Float.parseFloat(data));
                    break;
                case "audio":
                    if (recorder != null) recorder.feedAudio(Base64.decode(data, Base64.DEFAULT));
                    break;
                case "loopback": {
                    JSONObject o = new JSONObject(data);
                    for (Listener l : listeners) {
                        l.onLoopback(this, o.optString("state"), (float) o.optDouble("peak", 0),
                                o.optString("verdict"), o.optString("message"));
                    }
                    break;
                }
                case "vmic":
                    pageWantsMic = "on".equals(data);
                    if (pageWantsMic) startCapture(); else stopCapture();
                    break;
                case "info": {
                    JSONObject o = new JSONObject(data);
                    String text = "user agent: " + o.optString("ua")
                            + "\nplatform: " + o.optString("platform")
                            + "\nlayout width: " + o.optInt("width") + " px (pixel ratio " + o.optString("dpr") + ")"
                            + "\nviewport: " + o.optString("viewport")
                            + "\nmicrophone: " + (micKey.isEmpty() ? "page default" : "native input, " + micKey)
                            + "\nclient hint mobile: " + o.optString("uaMobile")
                            + "\npage: " + o.optString("host")
                            + "\nmic API: " + o.optString("mediaDevices")
                            + "\ncalls open: " + o.optInt("calls")
                            + "\naudio: " + o.optString("audio");
                    for (Listener l : listeners) l.onInfo(this, text);
                    break;
                }
                default:
                    break;
            }
        } catch (JSONException | IllegalArgumentException ignored) {
            // Malformed message from the page: ignore.
        }
    }

    /**
     * May this origin have the microphone or camera? The pinned page's own site and its subdomains, and any site the page
     * itself has navigated to (call apps redirect, e.g. to another domain of the same company, and then ask from there).
     */
    private boolean hostAllowed(Uri origin) {
        String host = origin == null ? null : origin.getHost();
        if (host == null || allowedRoots.isEmpty()) return false;
        String root = rootDomain(host);
        return allowedRoots.contains(root);
    }

    private void allowSite(String url) {
        String host = url == null ? null : Uri.parse(url).getHost();
        if (host != null && !host.isEmpty()) allowedRoots.add(rootDomain(host));
    }

    /** "a.b.example.com" -> "example.com"; "a.example.co.nz" -> "example.co.nz". */
    static String rootDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        String last = parts[parts.length - 1], second = parts[parts.length - 2];
        int keep = last.length() == 2 && second.matches("co|com|org|net|gov|ac|edu|ne|or|go") ? 3 : 2;
        keep = Math.min(keep, parts.length);
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - keep; i < parts.length; i++) sb.append(sb.length() == 0 ? "" : ".").append(parts[i]);
        return sb.toString();
    }

    private static String desktopUserAgent(String mobile) {
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(mobile == null ? "" : mobile);
        String major = m.find() ? m.group(1) : "120";
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + major + ".0.0.0 Safari/537.36";
    }

    /** Client hints say "desktop Linux Chrome" too, since some sites read them instead of the UA string. */
    private static void applyDesktopClientHints(WebSettings st, String mobileUa) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return;
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(mobileUa == null ? "" : mobileUa);
        String major = m.find() ? m.group(1) : "120";
        String full = major + ".0.0.0";
        List<UserAgentMetadata.BrandVersion> brands = new ArrayList<>();
        brands.add(new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium").setMajorVersion(major).setFullVersion(full).build());
        brands.add(new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Google Chrome").setMajorVersion(major).setFullVersion(full).build());
        brands.add(new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Not_A Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build());
        UserAgentMetadata md = new UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(full)
                .setPlatform("Linux")
                .setPlatformVersion("6.5.0")
                .setArchitecture("x86")
                .setBitness(64)
                .setModel("")
                .setMobile(false)
                .build();
        WebSettingsCompat.setUserAgentMetadata(st, md);
    }
}
