package app.turbobox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Standby, battery and alarm permissions, the recording buffer's size, and app updates. */
public class SettingsActivity extends Activity implements AppUpdater.Listener {
    private static final int[] BUFFER_MINUTES = {15, 30, 60, 90, 120, 180, 240, 360, 480, 720};
    private static final String[][] LANGUAGES = {
            {"", "The phone's language"}, {"en-US", "English (US)"}, {"en-GB", "English (UK)"}, {"en-AU", "English (Australia)"},
            {"en-NZ", "English (New Zealand)"}, {"es-ES", "Spanish"}, {"fr-FR", "French"}, {"de-DE", "German"}, {"it-IT", "Italian"},
            {"pt-BR", "Portuguese (Brazil)"}, {"nl-NL", "Dutch"}, {"pl-PL", "Polish"}, {"ru-RU", "Russian"}, {"ja-JP", "Japanese"},
            {"ko-KR", "Korean"}, {"zh-CN", "Chinese (Mandarin)"}, {"hi-IN", "Hindi"}, {"ar-SA", "Arabic"}, {"tr-TR", "Turkish"}};
    private static final int[] KEEP_RUNS = {1, 2, 3, 5, 8, 12};

    private int pendingRotation = -1;
    private LinearLayout body;
    private TextView updateStatus;
    private TextView updateButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        setContentView(Ui.screen(this, "Settings", body));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        AppUpdater.setListener(this);
        onState(AppUpdater.state());
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppUpdater.setListener(null);
    }

    private void moveBar(int at, int by) {
        java.util.List<String> order = BarItems.order(this);
        int to = at + by;
        if (to < 0 || to >= order.size()) return;
        String id = order.remove(at);
        order.add(to, id);
        BarItems.save(this, order, BarItems.off(this));
        render();
    }

    private void render() {
        body.removeAllViews();
        // Standby
        LinearLayout standby = Ui.card(this);
        Switch sw = Ui.toggle(this);
        sw.setText("Standby");
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(17f);
        sw.setChecked(Prefs.standby(this));
        sw.setOnCheckedChangeListener((b, on) -> {
            Prefs.setStandby(this, on);
            SessionService.ensure(this);
        });
        standby.addView(sw);
        standby.addView(Ui.hint(this, "Keeps TurboBox ready in the background (one small notification, almost no battery). "
                + "Needed so a workflow that starts while the phone is locked is allowed to use the microphone."), Ui.lp(this, 6, 0));
        body.addView(standby, Ui.lp(this, 4, 0));

        // Battery and alarms
        LinearLayout power = Ui.card(this);
        power.addView(Ui.text(this, "Staying alive with the screen off", 17f, Ui.TEXT, "sans-serif-medium"));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean exempt = pm.isIgnoringBatteryOptimizations(getPackageName());
        power.addView(Ui.hint(this, exempt ? "Battery optimisation is off for TurboBox. Good."
                : "Android may stop a page that has been in the background for hours. Allow TurboBox to ignore battery optimisation."), Ui.lp(this, 4, 8));
        if (!exempt) {
            TextView fix = Ui.pill(this, "Allow", Ui.GREEN);
            fix.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()))));
            power.addView(fix, Ui.lp(this, 0, 8));
        }
        boolean exact = WorkflowScheduler.canScheduleExact(this);
        power.addView(Ui.hint(this, exact ? "Exact alarms are allowed, so scheduled workflows start on time."
                : "Exact alarms are off: a scheduled workflow may start several minutes late."), Ui.lp(this, 4, 8));
        if (!exact && Build.VERSION.SDK_INT >= 31) {
            TextView fix = Ui.pill(this, "Allow exact alarms", Ui.GREEN);
            fix.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()))));
            power.addView(fix, Ui.lp(this, 0, 0));
        }
        body.addView(power, Ui.lp(this, 12, 0));

        // Sound
        LinearLayout sound = Ui.card(this);
        sound.addView(Ui.text(this, "Sound", 17f, Ui.TEXT, "sans-serif-medium"));
        sound.addView(Ui.hint(this, "Pages always see one microphone and one speaker. Here you choose the real ones behind them."), Ui.lp(this, 4, 8));
        TextView micRow = Ui.text(this, "Microphone: " + MicPicker.summary(this), 15f, Ui.TEXT, "sans-serif");
        micRow.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        micRow.setOnClickListener(v -> MicPicker.show(this, this::render));
        sound.addView(micRow);
        TextView volLabel = Ui.text(this, "Phone volume " + Ui.pct(NativeVolume.get(this)), 15f, Ui.TEXT, "sans-serif");
        sound.addView(volLabel, Ui.lp(this, 8, 0));
        sound.addView(Ui.slider(this, 1f, NativeVolume.get(this), (value, fromUser) -> {
            volLabel.setText("Phone volume " + Ui.pct(value));
            if (fromUser) NativeVolume.set(this, value);
        }));
        sound.addView(Ui.hint(this, "Sets the media and the call volume together, so it works while a Bluetooth headset is in call mode and the volume buttons only move one of them."), Ui.lp(this, 4, 0));
        Switch virt = Ui.toggle(this);
        virt.setText("Virtual devices for pages (experimental)");
        virt.setChecked(Prefs.virtualDevices(this));
        virt.setOnCheckedChangeListener((b, on) -> Prefs.setVirtualDevices(this, on));
        sound.addView(virt, Ui.lp(this, 12, 0));
        sound.addView(Ui.hint(this, "On: pages see one fixed microphone and speaker. Off: pages see the browser's own device list. Turn it off if a site stops working. Applies when a page is opened."), Ui.lp(this, 0, 0));
        Switch hb = Ui.toggle(this);
        hb.setText("Hold the headphone play/pause button");
        hb.setChecked(Prefs.headsetButton(this));
        hb.setOnCheckedChangeListener((b, on) -> {
            Prefs.setHeadsetButton(this, on);
            SessionService.ensure(this);
            SessionService.refresh();
        });
        sound.addView(hb, Ui.lp(this, 8, 0));
        java.util.List<Workflow> flows = WorkflowStore.load(this);
        String chosenFlow = Prefs.headphoneWorkflow(this);
        String chosenName = "Play and pause the page";
        for (Workflow w : flows) if (w.id.equals(chosenFlow)) chosenName = "Run: " + w.name;
        TextView headset = Ui.text(this, "Headphone play/pause: " + chosenName + "  \u203A", 15f, Ui.GREEN, "sans-serif-medium");
        headset.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
        headset.setOnClickListener(v -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            names.add("Play and pause the page");
            for (Workflow w : flows) names.add("Run: " + w.name);
            new AlertDialog.Builder(this).setTitle("When the headphone button is pressed")
                    .setItems(names.toArray(new String[0]), (d, which) -> {
                        Prefs.setHeadphoneWorkflow(this, which == 0 ? "" : flows.get(which - 1).id);
                        SessionService.ensure(this);
                        render();
                    }).show();
        });
        sound.addView(headset);
        sound.addView(Ui.hint(this, "TurboBox keeps hold of the button while a page is open (or Standby is on), so a music app left over from earlier "
                + "does not get it instead."), Ui.lp(this, 0, 0));
        body.addView(sound, Ui.lp(this, 12, 0));

        // Ad blocking
        LinearLayout ads = Ui.card(this);
        Switch adSwitch = Ui.toggle(this);
        adSwitch.setText("Block ads and trackers");
        adSwitch.setTextSize(17f);
        adSwitch.setChecked(AdBlock.enabled(this));
        adSwitch.setOnCheckedChangeListener((b, on) -> {
            AdBlock.setEnabled(this, on);
            render();
        });
        ads.addView(adSwitch);
        ads.addView(Ui.hint(this, "Uses the same filter lists as uBlock Origin. Requests from known ad and tracking servers get nothing back, and ad "
                + "boxes are hidden. A page can be exempted in its own settings. Ads that a site stitches into the video itself cannot be blocked this way."), Ui.lp(this, 6, 4));
        if (AdBlock.enabled(this)) {
            for (AdBlock.ListInfo l : AdBlock.LISTS) {
                Switch ls = Ui.toggle(this);
                ls.setText(l.name);
                ls.setChecked(AdBlock.listOn(this, l));
                ls.setOnCheckedChangeListener((b, on) -> AdBlock.setListOn(this, l, on));
                ads.addView(ls, Ui.lp(this, 4, 0));
            }
            TextView adStatus = Ui.hint(this, AdBlock.status().isEmpty() ? "Starting..." : AdBlock.status());
            ads.addView(adStatus, Ui.lp(this, 8, 4));
            TextView upd = Ui.pill(this, "Update the lists now", 0);
            upd.setOnClickListener(v -> {
                adStatus.setText("Downloading...");
                AdBlock.update(this, text -> adStatus.setText(text + " " + AdBlock.status()));
            });
            ads.addView(upd, Ui.lp(this, 4, 0));
        }
        body.addView(ads, Ui.lp(this, 12, 0));

        // Top bar
        LinearLayout bar = Ui.card(this);
        bar.addView(Ui.text(this, "Top bar", 17f, Ui.TEXT, "sans-serif-medium"));
        bar.addView(Ui.hint(this, "Move buttons with the arrows, switch off the ones you never use. Seven fit on a row."), Ui.lp(this, 4, 6));
        java.util.List<String> order = BarItems.order(this);
        java.util.List<String> off = BarItems.off(this);
        for (int i = 0; i < order.size(); i++) {
            final int at = i;
            final String id = order.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            Switch on = Ui.toggle(this);
            on.setText(BarItems.name(id));
            on.setChecked(!off.contains(id));
            on.setEnabled(!id.equals("settings"));
            on.setOnCheckedChangeListener((b, checked) -> {
                java.util.List<String> o = BarItems.off(this);
                o.remove(id);
                if (!checked) o.add(id);
                BarItems.save(this, BarItems.order(this), o);
            });
            row.addView(on, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            IconButton up = new IconButton(this, IconButton.Icon.UP, "Move up");
            up.setBackground(null);
            up.setEnabled(at > 0);
            up.setOnClickListener(v -> moveBar(at, -1));
            IconButton down = new IconButton(this, IconButton.Icon.DOWN, "Move down");
            down.setBackground(null);
            down.setEnabled(at < order.size() - 1);
            down.setOnClickListener(v -> moveBar(at, 1));
            row.addView(up, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
            row.addView(down, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
            bar.addView(row);
        }
        body.addView(bar, Ui.lp(this, 12, 0));

        // Mini window buttons
        LinearLayout pipCard = Ui.card(this);
        pipCard.addView(Ui.text(this, "Mini window buttons", 17f, Ui.TEXT, "sans-serif-medium"));
        pipCard.addView(Ui.hint(this, "What each button on the Mini window does. Android shows the first three on most phones, so put the ones you use most first."), Ui.lp(this, 4, 4));
        for (int i = 0; i < PipButtons.SLOTS; i++) {
            final int slot = i;
            TextView row = Ui.text(this, (i + 1) + ".  " + PipButtons.name(this, PipButtons.get(this, i)) + "  \u203A", 15f, Ui.GREEN, "sans-serif-medium");
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            row.setOnClickListener(v -> {
                java.util.List<String> ids = new java.util.ArrayList<>(java.util.Arrays.asList(PipButtons.IDS));
                java.util.List<String> names = new java.util.ArrayList<>(java.util.Arrays.asList(PipButtons.NAMES));
                for (Workflow w : WorkflowStore.load(this)) {
                    ids.add("workflow:" + w.id);
                    names.add("Run the workflow: " + w.name);
                }
                new AlertDialog.Builder(this).setTitle("Button " + (slot + 1))
                        .setItems(names.toArray(new String[0]), (d, which) -> {
                            PipButtons.set(this, slot, ids.get(which));
                            render();
                        }).show();
            });
            pipCard.addView(row);
        }
        body.addView(pipCard, Ui.lp(this, 12, 0));

        // The session screen
        LinearLayout screen = Ui.card(this);
        screen.addView(Ui.text(this, "Pages on screen", 17f, Ui.TEXT, "sans-serif-medium"));
        Switch keep = Ui.toggle(this);
        keep.setText("Keep video playing with the screen off");
        keep.setTextColor(Ui.TEXT);
        keep.setChecked(Prefs.keepPlaying(this));
        keep.setOnCheckedChangeListener((b, on) -> {
            Prefs.setKeepPlaying(this, on);
            for (Session s : Sessions.active()) {
                if (!on) s.leaveHost();
                s.refreshVisibility();
            }
        });
        screen.addView(keep, Ui.lp(this, 8, 2));
        Switch pd = Ui.toggle(this);
        pd.setText("Pages live on a private display");
        pd.setChecked(Prefs.privateDisplay(this));
        pd.setOnCheckedChangeListener((b, on) -> Prefs.setPrivateDisplay(this, on));
        screen.addView(pd, Ui.lp(this, 8, 2));
        screen.addView(Ui.hint(this, "Every page is drawn on its own fixed-size display and the screen shows a live picture of it, so turning the "
                + "screen off, pausing or switching tabs changes nothing for the page. Touches are passed in; typing opens a text box. Applies to pages "
                + "opened afterwards. Turn it off if touching or typing misbehaves on a page."), Ui.lp(this, 0, 8));
        Switch follow = Ui.toggle(this);
        follow.setText("Private display follows the window");
        follow.setChecked(Prefs.displayFollows(this));
        follow.setOnCheckedChangeListener((b, on) -> Prefs.setDisplayFollows(this, on));
        screen.addView(follow, Ui.lp(this, 4, 2));
        screen.addView(Ui.hint(this, "Off: the display keeps one fixed size and shape (most static; the picture is fitted into the window). "
                + "On: it takes the size of the space the page is shown in, so hiding the bar, a split or rotating gives the page that size."), Ui.lp(this, 0, 8));
        String[] rotations = {"Follow the phone's rotation setting", "Always rotate with the phone", "Portrait only"};
        if (pendingRotation < 0) pendingRotation = Prefs.rotation(this);
        TextView rot = Ui.text(this, "Rotation: " + rotations[pendingRotation] + "  \u203A", 15f, Ui.GREEN, "sans-serif-medium");
        rot.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        rot.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Rotation of the page screen")
                .setSingleChoiceItems(rotations, pendingRotation, (d, which) -> {
                    pendingRotation = which;   // kept until Update is pressed
                    d.dismiss();
                    render();
                }).show());
        screen.addView(rot);
        boolean changed = pendingRotation != Prefs.rotation(this);
        String[] forceNames = {"normal", "turned the other way", "held as it was"};
        int force = Prefs.forceRotate(this);
        TextView rotButton = Ui.pill(this, changed ? "Update rotation" : "Force rotate (now: " + forceNames[force] + ")", changed ? Ui.GREEN : 0);
        rotButton.setOnClickListener(v -> {
            if (changed) {
                Prefs.setRotation(this, pendingRotation);
                Toast.makeText(this, "Rotation updated.", Toast.LENGTH_SHORT).show();
            } else {
                Prefs.cycleForce(this, getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
            }
            render();
        });
        screen.addView(rotButton, Ui.lp(this, 4, 4));
        screen.addView(Ui.hint(this, "Pick a rotation, then press Update to use it. With nothing to update the button forces a turn: the other way, "
                + "then held as it was, then normal again. The page screen's rotate icon does the same."), Ui.lp(this, 0, 4));
        screen.addView(Ui.hint(this, "Each orientation remembers its own arrangement of the pages; until you arrange one, it starts stacked or as a grid."), Ui.lp(this, 0, 6));
        screen.addView(Ui.hint(this, "Sites like YouTube pause a video when the browser says nobody can see the page. With this on, the page "
                + "is told it is visible and is kept on a private display of its own while the screen is off, so it carries on."), Ui.lp(this, 0, 8));
        body.addView(screen, Ui.lp(this, 12, 0));

        // Recording buffer
        LinearLayout buffer = Ui.card(this);
        buffer.addView(Ui.text(this, "Recording buffer", 17f, Ui.TEXT, "sans-serif-medium"));
        buffer.addView(Ui.hint(this, "What a page shows and plays is kept in short pieces in this phone's cache so you can watch what "
                + "you missed."), Ui.lp(this, 4, 8));
        Switch auto = Ui.toggle(this);
        auto.setText("Record every page when it opens");
        auto.setTextColor(Ui.TEXT);
        auto.setChecked(Prefs.recordByDefault(this));
        auto.setOnCheckedChangeListener((b, on) -> Prefs.setRecordByDefault(this, on));
        buffer.addView(auto, Ui.lp(this, 0, 2));
        buffer.addView(Ui.hint(this, "Off by default: a page only records when you press Rec, a workflow says so, or the page is set to "
                + "always record. Turn this on and every page records as soon as it opens, so Step away always has something to "
                + "replay. A pinned page can override this (long-press it > Page settings)."), Ui.lp(this, 0, 8));
        buffer.addView(choiceRow("Record up to", minutesText(Prefs.bufferMinutes(this)), this::chooseMinutes));
        buffer.addView(choiceRow("When that is reached", Prefs.bufferStopAtLimit(this) ? "Stop recording" : "Keep recording, drop the oldest",
                this::chooseAtLimit));
        buffer.addView(choiceRow("Resolution", BufferRecorder.widthLabel(BufferRecorder.width(this)), this::chooseWidth));
        buffer.addView(choiceRow("Frame rate", BufferRecorder.fpsLabel(BufferRecorder.fps(this)), this::chooseFps));
        buffer.addView(choiceRow("Recordings kept", String.valueOf(Prefs.keepRuns(this)), this::chooseRuns));
        long mb = BufferStore.totalBytes(BufferRecorder.root(this)) / 1_048_576;
        int w = BufferRecorder.width(this), h = Math.round(w * 9f / 16f), f = BufferRecorder.fps(this);
        double perMin = BufferRecorder.bitrate(w, h, f) / 8.0 * 60 / 1_048_576 + 0.4;
        buffer.addView(Ui.hint(this, String.format(Locale.US, "Using %d MB now. At these settings a minute is roughly %.1f MB, so "
                + "%s is roughly %d MB.", mb, perMin, minutesText(Prefs.bufferMinutes(this)), Math.round(perMin * Prefs.bufferMinutes(this)))),
                Ui.lp(this, 6, 0));
        body.addView(buffer, Ui.lp(this, 12, 0));

        // Site data
        LinearLayout sites = Ui.card(this);
        sites.addView(Ui.text(this, "Site data", 17f, Ui.TEXT, "sans-serif-medium"));
        sites.addView(Ui.hint(this, "Sign-ins and what sites keep in this app's browser, listed by site, each with a button to clear it."), Ui.lp(this, 4, 8));
        TextView openSites = Ui.pill(this, "Manage site data", Ui.GREEN);
        openSites.setOnClickListener(v -> startActivity(new Intent(this, SiteDataActivity.class)));
        sites.addView(openSites);
        body.addView(sites, Ui.lp(this, 12, 0));

        // Transcription
        LinearLayout tr = Ui.card(this);
        tr.addView(Ui.text(this, "Live transcription", 17f, Ui.TEXT, "sans-serif-medium"));
        tr.addView(Ui.hint(this, "Turns what the page says into text while it records, and saves it as a WebVTT subtitle file next to the "
                + "recording (Recordings > Share captions). It uses Android's speech recognition and needs Android 13 or newer. "
                + "Times are approximate, and a recognizer that beeps when it starts may beep."), Ui.lp(this, 4, 8));
        Switch next = Ui.toggle(this);
        next.setText("Transcribe the next recording");
        next.setTextColor(Ui.TEXT);
        next.setChecked(Prefs.transcribeNext(this));
        next.setOnCheckedChangeListener((b, on) -> Prefs.setTranscribeNext(this, on));
        tr.addView(next, Ui.lp(this, 0, 4));
        tr.addView(Ui.hint(this, "One time only: it switches itself off when that recording starts. A workflow can also use the "
                + "step \"transcribe on\"."), Ui.lp(this, 0, 6));
        tr.addView(choiceRow("Speech language", languageName(Prefs.transcribeLang(this)), this::chooseLanguage));
        body.addView(tr, Ui.lp(this, 12, 0));

        // Updates
        LinearLayout upd = Ui.card(this);
        upd.addView(Ui.text(this, "App updates", 17f, Ui.TEXT, "sans-serif-medium"));
        upd.addView(Ui.hint(this, "Version " + BuildConfig.VERSION_NAME + ". Updates come from the project's GitHub releases."), Ui.lp(this, 4, 8));
        updateStatus = Ui.text(this, "", 14f, Ui.DIM, "sans-serif");
        upd.addView(updateStatus, Ui.lp(this, 0, 8));
        updateButton = Ui.pill(this, "Check for updates", Ui.GREEN);
        updateButton.setOnClickListener(v -> {
            if (AppUpdater.state().status.equals("ready")) AppUpdater.install(this);
            else AppUpdater.check(this);
        });
        upd.addView(updateButton);
        long last = Prefs.lastUpdateCheck(this);
        if (last > 0) {
            upd.addView(Ui.hint(this, "Last checked " + DateFormat.getMediumDateFormat(this).format(last) + " "
                    + DateFormat.getTimeFormat(this).format(last)), Ui.lp(this, 8, 0));
        }
        EditText token = new EditText(this);
        token.setHint("GitHub token (only for a private repository)");
        token.setSingleLine(true);
        token.setTextColor(Ui.TEXT);
        token.setHintTextColor(Ui.FAINT);
        token.setText(Prefs.updateToken(this));
        token.setOnFocusChangeListener((v, focus) -> {
            if (!focus) Prefs.setUpdateToken(this, token.getText().toString().trim());
        });
        upd.addView(token, Ui.lp(this, 8, 0));
        body.addView(upd, Ui.lp(this, 12, 0));

        body.addView(Ui.hint(this, "TurboBox is MIT licensed and built on TurboNZL/turboengine. The pinned pages, the microphone "
                + "handling and the mute lock come from Tether's web session."), Ui.lp(this, 16, 0));
    }

    private LinearLayout choiceRow(String label, String value, Runnable onTap) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        r.addView(Ui.hint(this, label));
        r.addView(Ui.text(this, value + "  ›", 16f, Ui.GREEN, "sans-serif-medium"), Ui.lp(this, 2, 0));
        r.setClickable(true);
        r.setOnClickListener(v -> onTap.run());
        return r;
    }


    private static String minutesText(int m) {
        return m < 60 ? m + " minutes" : m % 60 == 0 ? (m / 60) + (m == 60 ? " hour" : " hours") : (m / 60) + " h " + (m % 60) + " min";
    }

    private void chooseMinutes() {
        String[] items = new String[BUFFER_MINUTES.length];
        int checked = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = minutesText(BUFFER_MINUTES[i]);
            if (BUFFER_MINUTES[i] == Prefs.bufferMinutes(this)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("Record up to")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    Prefs.setBufferMinutes(this, BUFFER_MINUTES[which]);
                    d.dismiss();
                    render();
                }).show();
    }

    private void chooseAtLimit() {
        String[] items = {"Keep recording, drop the oldest (a rolling window)", "Stop recording"};
        new AlertDialog.Builder(this).setTitle("When the limit is reached")
                .setSingleChoiceItems(items, Prefs.bufferStopAtLimit(this) ? 1 : 0, (d, which) -> {
                    Prefs.setBufferStopAtLimit(this, which == 1);
                    d.dismiss();
                    render();
                }).show();
    }

    private void chooseWidth() {
        String[] items = new String[BufferRecorder.WIDTHS.length];
        int checked = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = BufferRecorder.widthLabel(BufferRecorder.WIDTHS[i]);
            if (BufferRecorder.WIDTHS[i] == BufferRecorder.width(this)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("Resolution")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    Prefs.setBufferWidth(this, BufferRecorder.WIDTHS[which]);
                    d.dismiss();
                    render();
                }).show();
    }

    private void chooseFps() {
        String[] items = new String[BufferRecorder.FPS.length];
        int checked = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = BufferRecorder.fpsLabel(BufferRecorder.FPS[i]);
            if (BufferRecorder.FPS[i] == BufferRecorder.fps(this)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("Frame rate")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    Prefs.setBufferFps(this, BufferRecorder.FPS[which]);
                    d.dismiss();
                    render();
                }).show();
    }

    private static String languageName(String tag) {
        for (String[] l : LANGUAGES) if (l[0].equals(tag)) return l[1];
        return tag;
    }

    private void chooseLanguage() {
        String[] items = new String[LANGUAGES.length + 1];
        int checked = -1;
        for (int i = 0; i < LANGUAGES.length; i++) {
            items[i] = LANGUAGES[i][1];
            if (LANGUAGES[i][0].equals(Prefs.transcribeLang(this))) checked = i;
        }
        items[LANGUAGES.length] = "Another language tag...";
        new AlertDialog.Builder(this).setTitle("Speech language")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    if (which < LANGUAGES.length) {
                        Prefs.setTranscribeLang(this, LANGUAGES[which][0]);
                        render();
                        return;
                    }
                    EditText tag = new EditText(this);
                    tag.setHint("e.g. sv-SE");
                    tag.setSingleLine(true);
                    new AlertDialog.Builder(this).setTitle("Language tag").setView(tag)
                            .setPositiveButton("Save", (dd, w) -> {
                                Prefs.setTranscribeLang(this, tag.getText().toString().trim());
                                render();
                            }).setNegativeButton("Cancel", null).show();
                }).show();
    }

    private void chooseRuns() {
        String[] items = new String[KEEP_RUNS.length];
        int checked = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = String.format(Locale.US, "%d recordings", KEEP_RUNS[i]);
            if (KEEP_RUNS[i] == Prefs.keepRuns(this)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("Recordings kept")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    Prefs.setKeepRuns(this, KEEP_RUNS[which]);
                    d.dismiss();
                    render();
                }).show();
    }

    @Override
    public void onState(AppUpdater.State s) {
        if (updateStatus == null) return;
        switch (s.status) {
            case "checking":
                updateStatus.setText("Checking...");
                updateButton.setText("Checking...");
                break;
            case "downloading":
                updateStatus.setText("Downloading " + s.version + (s.percent >= 0 ? " (" + s.percent + "%)" : ""));
                updateButton.setText("Downloading...");
                break;
            case "ready":
                updateStatus.setText("Version " + s.version + " is ready.");
                updateButton.setText("Install " + s.version);
                break;
            case "none":
                updateStatus.setText(s.message != null ? s.message : "You have the newest version.");
                updateButton.setText("Check for updates");
                break;
            case "error":
                updateStatus.setText(s.message == null ? "Something went wrong." : s.message);
                updateStatus.setTextColor(Ui.RED);
                updateButton.setText("Try again");
                return;
            default:
                updateStatus.setText("");
                updateButton.setText("Check for updates");
                break;
        }
        updateStatus.setTextColor(Ui.DIM);
    }
}
