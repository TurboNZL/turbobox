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

import java.util.Locale;

/** Standby, battery and alarm permissions, the recording buffer's size, and app updates. */
public class SettingsActivity extends Activity implements AppUpdater.Listener {
    private static final int[] BUFFER_MINUTES = {15, 30, 60, 90, 120, 180, 240};
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

        // Recording buffer
        LinearLayout buffer = Ui.card(this);
        buffer.addView(Ui.text(this, "Recording buffer", 17f, Ui.TEXT, "sans-serif-medium"));
        buffer.addView(Ui.hint(this, "What a page shows and plays is kept in short pieces in this phone's cache so you can watch what "
                + "you missed. Only the newest part of each recording is kept."), Ui.lp(this, 4, 8));
        buffer.addView(choiceRow("Keep the newest", Prefs.bufferMinutes(this) + " minutes", this::chooseMinutes));
        buffer.addView(choiceRow("Quality", BufferRecorder.QUALITIES[Math.max(0, Math.min(2, Prefs.bufferQuality(this)))].label, this::chooseQuality));
        buffer.addView(choiceRow("Recordings kept", String.valueOf(Prefs.keepRuns(this)), this::chooseRuns));
        long mb = BufferStore.totalBytes(BufferRecorder.root(this)) / 1_048_576;
        buffer.addView(Ui.hint(this, "Using " + mb + " MB now. A minute is about 2 to 9 MB, depending on the quality."), Ui.lp(this, 6, 0));
        body.addView(buffer, Ui.lp(this, 12, 0));

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

    private void chooseMinutes() {
        String[] items = new String[BUFFER_MINUTES.length];
        int checked = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = BUFFER_MINUTES[i] + " minutes";
            if (BUFFER_MINUTES[i] == Prefs.bufferMinutes(this)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("Keep the newest")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    Prefs.setBufferMinutes(this, BUFFER_MINUTES[which]);
                    d.dismiss();
                    render();
                }).show();
    }

    private void chooseQuality() {
        String[] items = new String[BufferRecorder.QUALITIES.length];
        for (int i = 0; i < items.length; i++) items[i] = BufferRecorder.QUALITIES[i].label;
        new AlertDialog.Builder(this).setTitle("Quality")
                .setSingleChoiceItems(items, Math.max(0, Math.min(2, Prefs.bufferQuality(this))), (d, which) -> {
                    Prefs.setBufferQuality(this, which);
                    d.dismiss();
                    render();
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
