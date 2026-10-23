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

    private void render() {
        body.removeAllViews();
        // Standby
        LinearLayout standby = Ui.card(this);
        Switch sw = new Switch(this);
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

        // The session screen
        LinearLayout screen = Ui.card(this);
        screen.addView(Ui.text(this, "Pages on screen", 17f, Ui.TEXT, "sans-serif-medium"));
        Switch keep = new Switch(this);
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
        screen.addView(Ui.hint(this, "Sites like YouTube pause a video when the browser says nobody can see the page. With this on, the page "
                + "is told it is visible and is kept on a private display of its own while the screen is off, so it carries on."), Ui.lp(this, 0, 8));
        Switch reclaim = new Switch(this);
        reclaim.setText("Let other pages use the empty space");
        reclaim.setTextColor(Ui.TEXT);
        reclaim.setChecked(Prefs.reclaimEmpty(this));
        reclaim.setOnCheckedChangeListener((b, on) -> Prefs.setReclaimEmpty(this, on));
        screen.addView(reclaim, Ui.lp(this, 4, 2));
        screen.addView(Ui.hint(this, "When a page in a split is held to a shape (a forced aspect ratio, or a video in full screen), it takes "
                + "only the room that shape needs and the other pages grow into the rest, instead of black bars. The Mini window "
                + "also takes the shape of a single page held to one. Drag the dividers between pages in a split to resize them."), Ui.lp(this, 0, 4));
        Switch own = new Switch(this);
        own.setText("Split uses your own layout");
        own.setTextColor(Ui.TEXT);
        own.setChecked(Prefs.splitOwn(this));
        own.setOnCheckedChangeListener((b, on) -> Prefs.setSplitOwn(this, on));
        screen.addView(own, Ui.lp(this, 8, 2));
        screen.addView(choiceRow("Your own split layout", "Drag and resize the pages", this::editSplitLayout));
        screen.addView(Ui.hint(this, "Instead of the grid with dividers, Split places each page where you put it (pages overlap if you let them). "
                + "Separate from the Mini window's layout. Pages are numbered in the order of their tabs."), Ui.lp(this, 0, 4));
        TextView resetSplit = Ui.pill(this, "Reset split dividers", 0);
        resetSplit.setOnClickListener(v -> {
            Prefs.clearSplitLayouts(this);
            Toast.makeText(this, "Dividers back in the middle", Toast.LENGTH_SHORT).show();
        });
        screen.addView(resetSplit, Ui.lp(this, 4, 8));
        screen.addView(choiceRow("Mini window while Split is on", pipLayoutText(Prefs.pipLayout(this)), this::choosePipLayout));
        screen.addView(Ui.hint(this, "With Split on, the Mini window shows the pages you chose to split. Without Split it shows the page in front. Also on the Mini button's long press."), Ui.lp(this, 0, 0));
        screen.addView(choiceRow("Your own Mini layout", "Drag and resize the pages", this::editMiniLayout));
        screen.addView(Ui.hint(this, "Used when the Mini window layout above is \"Your own layout\". Pages are numbered in the order of their tabs."), Ui.lp(this, 0, 0));
        body.addView(screen, Ui.lp(this, 12, 0));

        // Recording buffer
        LinearLayout buffer = Ui.card(this);
        buffer.addView(Ui.text(this, "Recording buffer", 17f, Ui.TEXT, "sans-serif-medium"));
        buffer.addView(Ui.hint(this, "What a page shows and plays is kept in short pieces in this phone's cache so you can watch what "
                + "you missed."), Ui.lp(this, 4, 8));
        Switch auto = new Switch(this);
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
        Switch next = new Switch(this);
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

    /** A full-screen sheet with the Mini window as a box: drag each page where it should go, drag its corner to resize it. */
    private void editMiniLayout() {
        editLayout(true);
    }

    private void editSplitLayout() {
        editLayout(false);
    }

    /** The Mini window's layout (mini) or the in-app split's (not mini): separate rectangles, saved separately. */
    private void editLayout(boolean mini) {
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 16));
        col.addView(Ui.text(this, mini ? "Your own Mini layout" : "Your own split layout", 20f, Ui.TEXT, "semibold"), Ui.lp(this, 0, 4));
        col.addView(Ui.hint(this, "Drag a page to move it, drag its corner to resize it. Page 1 is at the back, page 4 at the front. "
                + (mini ? "Used when the Mini window opens with Split on and more than one page chosen. Done switches the Mini window to this layout."
                : "Used by Split in the app when two or more pages are chosen (the Mini window has its own). Done switches Split to this layout.")),
                Ui.lp(this, 0, 8));
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        float[][][] current = {mini ? MiniLayout.decode(Prefs.miniRects(this)) : MiniLayout.decode(Prefs.splitRects(this), MiniLayout.splitDefaults())};
        TextView shape = Ui.text(this, "", 16f, Ui.GREEN, "sans-serif-medium");
        Runnable[] place = new Runnable[1];
        place[0] = () -> {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            // The split fills the screen below the bar; the box takes roughly that shape.
            int[] sh = mini ? MiniLayout.shape(Prefs.miniShape(this)) : new int[]{dm.widthPixels, Math.round(dm.heightPixels * 0.8f)};
            if (mini) shape.setText("Window shape: " + MiniLayout.SHAPE_LABELS[Math.max(0, Math.min(MiniLayout.SHAPE_LABELS.length - 1, Prefs.miniShape(this)))] + "  ›");
            int maxW = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32);
            int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f);
            int w = Math.min(maxW, Math.round(maxH * sh[0] / (float) sh[1]));
            int h = Math.round(w * sh[1] / (float) sh[0]);
            holder.removeAllViews();
            MiniLayoutEditor ed = new MiniLayoutEditor(this, rects -> {
                current[0] = rects;
                if (mini) Prefs.setMiniRects(this, MiniLayout.encode(rects));
                else Prefs.setSplitRects(this, MiniLayout.encode(rects));
            });
            ed.set(current[0], MiniLayout.PAGES);
            holder.addView(ed, new android.widget.FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER));
        };
        shape.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        shape.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Window shape")
                .setSingleChoiceItems(MiniLayout.SHAPE_LABELS, Prefs.miniShape(this), (dd, which) -> {
                    Prefs.setMiniShape(this, which);
                    dd.dismiss();
                    place[0].run();
                }).show());
        if (mini) col.addView(shape);
        col.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView reset = Ui.pill(this, "Reset to the default", 0);
        reset.setOnClickListener(v -> {
            current[0] = mini ? MiniLayout.defaults() : MiniLayout.splitDefaults();
            if (mini) Prefs.setMiniRects(this, "");
            else Prefs.setSplitRects(this, "");
            place[0].run();
        });
        col.addView(reset, Ui.lp(this, 8, 0));
        TextView done = Ui.pill(this, "Done", Ui.GREEN);
        done.setOnClickListener(v -> {
            d.dismiss();
            if (mini && Prefs.pipLayout(this) != SplitLayout.OVERLAY) {
                Prefs.setPipLayout(this, SplitLayout.OVERLAY);
                render();
            } else if (!mini && !Prefs.splitOwn(this)) {
                Prefs.setSplitOwn(this, true);
                render();
            }
        });
        col.addView(done, Ui.lp(this, 8, 0));
        d.setContentView(col);
        d.show();
        place[0].run();
    }

    private static String pipLayoutText(int v) {
        return v == 1 ? "Side by side" : v == 2 ? "Stacked" : v == 3 ? "Your own layout (edit below)" : "Like the split view";
    }

    private void choosePipLayout() {
        String[] items = {pipLayoutText(0), pipLayoutText(1), pipLayoutText(2), pipLayoutText(3)};
        new AlertDialog.Builder(this).setTitle("Mini window while Split is on")
                .setSingleChoiceItems(items, Prefs.pipLayout(this), (d, which) -> {
                    Prefs.setPipLayout(this, which);
                    d.dismiss();
                    render();
                }).show();
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
