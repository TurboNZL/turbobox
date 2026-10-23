package app.turbobox;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The settings sheet of a session: microphone (with a live level test and a record-and-play-back test), echo
 * cancellation, microphone volume, this page's volume and zoom, brightness, and page info. Microphone settings are
 * shared by every session; volume and zoom belong to the page that is in front and are kept with its pin.
 */
final class SessionSheet implements Session.Listener {
    private static final int LOOPBACK_SECONDS = 4;

    private final Activity act;
    private final Session session;
    private final Runnable onBrightness;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final MicTester micTester = new MicTester();
    private final MicPreview micPreview = new MicPreview();
    private Dialog dialog;
    private LevelMeterView meter;
    private TextView status;
    private TextView micValue;
    private TextView infoView;
    private TextView loopbackStatus;
    private TextView loopbackAction;
    private TextView gainValue;
    private SeekBar gainSlider;
    private float micGain;
    private float suggestedGain;
    private long testStartedAt;
    private boolean heardSound;
    private Runnable zoomApply;
    private TextView navBack;
    private TextView navForward;

    SessionSheet(Activity act, Session session, Runnable onBrightness) {
        this.act = act;
        this.session = session;
        this.onBrightness = onBrightness;
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    boolean isShowing() {
        return dialog != null;
    }

    void show() {
        if (dialog != null) return;
        Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(act);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 20);
        // A wide empty strip on the right: sliders take every drag that starts on them, so this is where a finger
        // can rest to scroll the sheet.
        col.setPadding(pad, Ui.dp(act, 18), Ui.dp(act, 64), Ui.dp(act, 28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF121217);
        float r = Ui.dpf(act, 24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(Ui.dp(act, 1), Ui.STROKE);
        col.setBackground(bg);
        scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));

        // Title, and a little back / forward box so moving around the site needs no trip to the page's own buttons
        LinearLayout head = new LinearLayout(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(act, "Settings - " + session.title(), 20f, Ui.TEXT, "semibold");
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout navBox = new LinearLayout(act);
        navBox.setBackground(Ui.rounded(act, Ui.CARD, Ui.STROKE, 100));
        navBox.setPadding(Ui.dp(act, 4), Ui.dp(act, 2), Ui.dp(act, 4), Ui.dp(act, 2));
        navBack = navButton("‹", "Back", session::goBack);
        navForward = navButton("›", "Forward", session::goForward);
        navBox.addView(navBack);
        navBox.addView(navForward);
        head.addView(navBox);
        col.addView(head, Ui.lp(act, 0, 14));
        updateNav();

        // Privacy: what this page may use
        col.addView(Ui.label(act, "Privacy - this page may use"));
        Switch micSwitch = new Switch(act);
        micSwitch.setText("Microphone");
        micSwitch.setTextColor(Ui.TEXT);
        micSwitch.setChecked(session.micAllowed());
        micSwitch.setOnCheckedChangeListener((b, on) -> session.setMicAllowed(on));
        col.addView(micSwitch, Ui.lp(act, 8, 4));
        Switch camSwitch = new Switch(act);
        camSwitch.setText("Camera");
        camSwitch.setTextColor(Ui.TEXT);
        camSwitch.setChecked(session.cameraAllowed());
        camSwitch.setOnCheckedChangeListener((b, on) -> {
            session.setCameraAllowed(on);
            if (on && act.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(new String[]{Manifest.permission.CAMERA}, 3);
            }
        });
        col.addView(camSwitch, Ui.lp(act, 4, 4));
        col.addView(Ui.hint(act, "Off means the page is refused, like pressing Block in a browser. Turning the microphone off also "
                + "stops a page that is already using it. The camera is off until you allow it here and in Android."), Ui.lp(act, 2, 10));

        TextView reload = Ui.pill(act, "Reload the page", 0);
        reload.setOnClickListener(v -> {
            session.reload();
            dismiss();
        });
        col.addView(reload, Ui.lp(act, 0, 4));
        col.addView(Ui.hint(act, "A page that was refused often keeps saying \"blocked\" until it is reloaded. The page is told when a switch "
                + "changes, but if it still does not see the microphone or camera, reload it (this restarts whatever the page was doing)."), Ui.lp(act, 2, 18));

        // Mic level test
        col.addView(Ui.label(act, "Microphone level"));
        meter = new LevelMeterView(act);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, Ui.dp(act, 14));
        mp.topMargin = Ui.dp(act, 8);
        col.addView(meter, mp);
        status = Ui.text(act, "Speak - the bar should move.", 13f, Ui.FAINT, "sans-serif");
        col.addView(status, Ui.lp(act, 8, 16));

        // Microphone choice (shared)
        col.addView(Ui.label(act, "Microphone (all pages)"));
        micValue = Ui.text(act, micSummary(), 17f, Ui.TEXT, "sans-serif");
        micValue.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
        micValue.setClickable(true);
        micValue.setOnClickListener(v -> chooseMic());
        col.addView(micValue, Ui.lp(act, 0, 4));
        Switch aec = new Switch(act);
        aec.setText("Echo cancellation & noise suppression");
        aec.setTextColor(Ui.DIM);
        aec.setChecked(Prefs.aec(act));
        aec.setOnCheckedChangeListener((b, on) -> {
            Prefs.setAec(act, on);
            for (Session s : Sessions.active()) s.setProcessing(on);
        });
        col.addView(aec, Ui.lp(act, 0, 12));

        // Microphone volume, and a playback test to hear how loud it is
        micGain = Prefs.micGain(act);
        gainValue = Ui.text(act, Ui.pct(micGain), 30f, Ui.TEXT, "sans-serif");
        col.addView(Ui.label(act, "Microphone volume"));
        col.addView(gainValue, Ui.lp(act, 4, 2));
        gainSlider = Ui.slider(act, 3f, micGain, (value, fromUser) -> {
            gainValue.setText(Ui.pct(value));
            micGain = value;
            if (!fromUser) return;
            Prefs.setMicGain(act, value);
            for (Session s : Sessions.active()) s.setMicGain(value);
        });
        col.addView(gainSlider, new LinearLayout.LayoutParams(-1, -2));
        col.addView(Ui.hint(act, "How loudly the other person hears you. Past 100% boosts a quiet mic; the bar above shows the result."),
                Ui.lp(act, 0, 10));
        TextView playback = Ui.pill(act, "Record " + LOOPBACK_SECONDS + " s and play it back", 0);
        playback.setOnClickListener(v -> startLoopback());
        col.addView(playback, Ui.lp(act, 0, 6));
        loopbackStatus = Ui.hint(act, "Hear yourself the way the other person will. Works even while you are muted.");
        col.addView(loopbackStatus, Ui.lp(act, 0, 6));
        // After a test that was faint or too loud: one tap to set the volume that would have been right.
        loopbackAction = Ui.pill(act, "", Ui.GREEN);
        loopbackAction.setVisibility(View.GONE);
        loopbackAction.setOnClickListener(v -> applySuggestedGain());
        col.addView(loopbackAction, Ui.lp(act, 0, 16));

        // This page's volume
        Pin pin = session.pin();
        float vol = session.volume();
        TextView volValue = Ui.text(act, Ui.pct(vol), 30f, Ui.TEXT, "sans-serif");
        col.addView(Ui.label(act, "Page volume"));
        col.addView(volValue, Ui.lp(act, 4, 2));
        col.addView(Ui.slider(act, 2f, vol, (value, fromUser) -> {
            volValue.setText(Ui.pct(value));
            if (!fromUser) return;
            session.setVolume(value);
            if (pin != null) {
                pin.volume = value;
                PinStore.put(act, pin);
            }
        }), new LinearLayout.LayoutParams(-1, -2));
        col.addView(Ui.hint(act, "Only this page's sound. Past 100% boosts it. Kept with the pinned page."), Ui.lp(act, 0, 8));
        TextView unmute = Ui.pill(act, "Turn the page's sound on", 0);
        unmute.setOnClickListener(v -> session.unmutePageMedia());
        col.addView(unmute, Ui.lp(act, 0, 4));
        col.addView(Ui.hint(act, "For a video the site started muted. This switches the page's own mute off (the page's other mute "
                + "buttons keep working). Your microphone's Mute is separate."), Ui.lp(act, 2, 14));

        // Brightness (shared)
        float bright = Prefs.brightness(act);
        TextView brightValue = Ui.text(act, Ui.pct(bright), 30f, Ui.TEXT, "sans-serif");
        col.addView(Ui.label(act, "Brightness"));
        col.addView(brightValue, Ui.lp(act, 4, 2));
        col.addView(Ui.slider(act, 1f, bright, (value, fromUser) -> {
            brightValue.setText(Ui.pct(value));
            if (!fromUser) return;
            Prefs.setBrightness(act, value);
            onBrightness.run();
        }), new LinearLayout.LayoutParams(-1, -2));
        col.addView(Ui.hint(act, "Dims this app below your screen's minimum brightness."), Ui.lp(act, 0, 14));

        // Page zoom: lays the page out again at a different width, like browser zoom (not a magnifier)
        float zoom = Math.max(0.5f, session.zoom());
        TextView zoomValue = Ui.text(act, Ui.pct(zoom), 30f, Ui.TEXT, "sans-serif");
        col.addView(Ui.label(act, "Page zoom"));
        col.addView(zoomValue, Ui.lp(act, 4, 2));
        col.addView(Ui.slider(act, 3f, zoom, (value, fromUser) -> {
            float z = Math.max(0.5f, value);
            zoomValue.setText(Ui.pct(z));
            if (!fromUser) return;
            if (pin != null) {
                pin.zoom = z;
                PinStore.put(act, pin);
            }
            // Laying the page out again is heavy: wait until the slider has settled.
            if (zoomApply != null) ui.removeCallbacks(zoomApply);
            zoomApply = () -> session.setZoom(z);
            ui.postDelayed(zoomApply, 250);
        }), new LinearLayout.LayoutParams(-1, -2));
        col.addView(Ui.hint(act, "Makes the whole page bigger or smaller by changing the width it is laid out at, like "
                + "browser zoom. Pinch still magnifies. Kept with the pinned page."), Ui.lp(act, 0, 14));

        // Forced shape: the page's area is fitted into a box of this shape (bars around it) instead of filling the space
        TextView aspectValue = Ui.text(act, Pin.ASPECT_LABELS[session.aspect()] + "  ›", 17f, Ui.GREEN, "sans-serif-medium");
        aspectValue.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
        aspectValue.setClickable(true);
        aspectValue.setOnClickListener(v -> new AlertDialog.Builder(act).setTitle("Force aspect ratio")
                .setSingleChoiceItems(Pin.ASPECT_LABELS, session.aspect(), (dd, which) -> {
                    session.setAspect(which);
                    aspectValue.setText(Pin.ASPECT_LABELS[which] + "  ›");
                    dd.dismiss();
                }).show());
        col.addView(Ui.label(act, "Aspect ratio"));
        col.addView(aspectValue, Ui.lp(act, 0, 2));
        col.addView(Ui.hint(act, "Fits the page into a box of this shape, centred with bars around it, and lays the page out to fit it "
                + "(a 16:9 box suits a video). Kept with the pinned page."), Ui.lp(act, 0, 14));

        // Desktop sites are the default; this page can ask for the phone layout instead
        Switch mobileSwitch = new Switch(act);
        mobileSwitch.setText("Mobile site");
        mobileSwitch.setTextColor(Ui.TEXT);
        mobileSwitch.setChecked(session.isMobile());
        mobileSwitch.setOnCheckedChangeListener((b, on) -> session.setMobile(on));
        col.addView(mobileSwitch, Ui.lp(act, 0, 4));
        col.addView(Ui.hint(act, "Off (the default) asks sites for their desktop layout. On asks for the phone layout, like a normal phone "
                + "browser. The page is loaded again to switch. Kept with the pinned page."), Ui.lp(act, 2, 14));

        // Page info: what the page sees, to check the desktop layout
        TextView info = Ui.pill(act, "Show page info", 0);
        info.setOnClickListener(v -> session.requestInfo());
        col.addView(info, Ui.lp(act, 0, 8));
        infoView = Ui.text(act, "", 11.5f, Ui.DIM, "monospace");
        infoView.setTextIsSelectable(true);
        infoView.setVisibility(View.GONE);
        col.addView(infoView);

        d.setContentView(scroll);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.35f);
        }
        d.setOnDismissListener(x -> {
            micTester.stop();
            micPreview.cancel();
            session.removeListener(this);
            dialog = null;
        });
        dialog = d;
        session.addListener(this);
        d.show();
        startMicTest();
    }

    // ---- microphone: the phone's real inputs, and a level test ---------------------------------

    private AudioDeviceInfo chosenInput() {
        return Devices.find(act, Prefs.micKey(act));
    }

    private String micSummary() {
        Devices.ensureMicChoice(act);
        AudioDeviceInfo d = chosenInput();
        return d == null ? "Chosen input not available" : Devices.describe(d);
    }

    private void chooseMic() {
        List<AudioDeviceInfo> inputs = Devices.inputs(act);
        List<String> items = new ArrayList<>();
        for (AudioDeviceInfo d : inputs) items.add(Devices.describe(d));
        String key = Prefs.micKey(act);
        int checked = 0;
        for (int i = 0; i < inputs.size(); i++) if (Devices.key(inputs.get(i)).equals(key)) checked = i;
        new AlertDialog.Builder(act)
                .setTitle("Microphone")
                .setSingleChoiceItems(items.toArray(new String[0]), checked, (d, which) -> {
                    String chosen = Devices.key(inputs.get(which));
                    Prefs.setMicKey(act, chosen);
                    for (Session s : Sessions.active()) s.setMicChoice(chosen);
                    if (micValue != null) micValue.setText(micSummary());
                    InputRoute.apply(act, chosenInput());
                    startMicTest(); // test the one just picked
                    d.dismiss();
                })
                .show();
    }

    private void startMicTest() {
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onMicTestError("microphone permission denied - allow it in Android settings > Apps > TurboBox");
            return;
        }
        micTester.stop();
        testStartedAt = System.currentTimeMillis();
        heardSound = false;
        if (status != null) {
            status.setText("Speak - the bar should move.");
            status.setTextColor(Ui.FAINT);
        }
        micTester.start(chosenInput(), new MicTester.Listener() {
            @Override
            public void onLevel(float rms) {
                if (meter == null || status == null) return;
                float heard = Math.min(1f, rms * micGain); // what the other person will hear
                meter.setRms(heard);
                if (rms > 0.01f) heardSound = true;
                if (heardSound) {
                    status.setText("Mic is working.");
                    status.setTextColor(Ui.GREEN);
                } else if (System.currentTimeMillis() - testStartedAt > 4000) {
                    status.setText("No sound yet. Speak, or pick another microphone above.");
                    status.setTextColor(Ui.AMBER);
                }
            }

            @Override
            public void onError(String message) {
                onMicTestError(message);
            }
        });
    }

    private void onMicTestError(String message) {
        if (status == null) return;
        status.setText("Could not open the microphone: " + message);
        status.setTextColor(Ui.RED);
    }

    private void startLoopback() {
        if (loopbackStatus == null) return;
        if (loopbackAction != null) loopbackAction.setVisibility(View.GONE);
        // The app reads the chosen input itself, so the preview works whether or not the call is muted.
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onMicTestError("microphone permission denied - allow it in Android settings > Apps > TurboBox");
            return;
        }
        micPreview.cancel();
        micTester.stop(); // one reader at a time keeps the level bar from fighting the preview
        micPreview.start(chosenInput(), Prefs.aec(act), micGain, LOOPBACK_SECONDS, (state, peak, verdict, message) -> {
            onLoopback(session, state, peak, verdict, message);
            if (state.equals("done") || state.equals("error")) startMicTest(); // the level bar resumes
        });
    }

    @Override
    public void onLoopback(Session s, String state, float peak, String verdict, String message) {
        if (loopbackStatus == null) return;
        String level = peak > 0 ? String.format(Locale.US, " (peak %d dB)", Math.round(20.0 * Math.log10(peak))) : "";
        switch (state) {
            case "recording":
                loopbackStatus.setText("Recording - speak normally...");
                loopbackStatus.setTextColor(Ui.AMBER);
                break;
            case "playing":
                loopbackStatus.setText("Playing it back" + level + " - it uses your phone's media volume, so press the volume keys now if you hear nothing.");
                loopbackStatus.setTextColor(Ui.AMBER);
                break;
            case "done":
                switch (verdict) {
                    case "clipping":
                        loopbackStatus.setText("Too loud" + level + " - it will distort. Turn the microphone volume down.");
                        loopbackStatus.setTextColor(Ui.RED);
                        break;
                    case "good":
                        loopbackStatus.setText("Good level" + level + ".");
                        loopbackStatus.setTextColor(Ui.GREEN);
                        break;
                    case "quiet":
                        loopbackStatus.setText("Faint" + level + " - the other person would hear you quietly. A phone's raw mic is quieter than a normal call, which boosts it for you.");
                        loopbackStatus.setTextColor(Ui.AMBER);
                        break;
                    default:
                        loopbackStatus.setText("Almost silent" + level + " - check the microphone choice and the microphone volume.");
                        loopbackStatus.setTextColor(Ui.RED);
                        break;
                }
                offerGainFix(peak, verdict);
                break;
            default:
                loopbackStatus.setText("Test failed: " + message);
                loopbackStatus.setTextColor(Ui.RED);
                break;
        }
    }

    /** If the test was faint or too loud, offer the microphone volume that would put the peak near -6 dB. */
    private void offerGainFix(float peak, String verdict) {
        if (loopbackAction == null) return;
        loopbackAction.setVisibility(View.GONE);
        boolean worthFixing = "quiet".equals(verdict) || "clipping".equals(verdict)
                || ("silent".equals(verdict) && peak > 0.006f); // above about -44 dB there is something to scale up
        if (!worthFixing || peak <= 0f) return;
        float wanted = micGain * 0.5f / peak;               // the test already includes the current volume
        suggestedGain = Math.max(0.1f, Math.min(3f, wanted));
        if (Math.abs(suggestedGain - micGain) < 0.05f) return;
        loopbackAction.setText(String.format(Locale.US, "Set microphone volume to %d%%%s",
                Math.round(suggestedGain * 100f), wanted > 3f ? " (the most it can do)" : ""));
        loopbackAction.setVisibility(View.VISIBLE);
    }

    private void applySuggestedGain() {
        if (gainSlider == null || gainValue == null) return;
        micGain = suggestedGain;
        gainSlider.setProgress(Math.round(micGain * 100f));
        gainValue.setText(Ui.pct(micGain));
        Prefs.setMicGain(act, micGain);
        for (Session s : Sessions.active()) s.setMicGain(micGain);
        loopbackAction.setVisibility(View.GONE);
        loopbackStatus.setText("Set to " + Ui.pct(micGain) + ". Run the test again to check it.");
        loopbackStatus.setTextColor(Ui.DIM);
    }

    private TextView navButton(String glyph, String description, Runnable action) {
        TextView b = Ui.text(act, glyph, 26f, Ui.TEXT, "sans-serif-medium");
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setPadding(Ui.dp(act, 16), Ui.dp(act, 2), Ui.dp(act, 16), Ui.dp(act, 4));
        b.setClickable(true);
        b.setOnClickListener(v -> {
            action.run();
            ui.postDelayed(this::updateNav, 400);   // the history changes a moment after the page starts to move
        });
        return b;
    }

    private void updateNav() {
        if (navBack == null) return;
        navBack.setAlpha(session.canGoBack() ? 1f : 0.3f);
        navForward.setAlpha(session.canGoForward() ? 1f : 0.3f);
    }

    @Override
    public void onNavigation(Session s) {
        updateNav();
    }

    @Override
    public void onInfo(Session s, String text) {
        if (infoView == null) return;
        infoView.setText(text);
        infoView.setVisibility(View.VISIBLE);
    }
}
