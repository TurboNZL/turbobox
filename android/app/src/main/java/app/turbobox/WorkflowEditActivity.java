package app.turbobox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** Writes one workflow: its page, its schedule and its steps, and tries it. */
public class WorkflowEditActivity extends Activity {
    static final String EXTRA_ID = "id";

    private static final String EXAMPLE = "# One step per line. Words after click / waitfor are matched against what you see.\n"
            + "# open loads the page again; wait 30s, 5m, 1h; a leading ? means \"fine if it is not there\".\n"
            + "wait 5s\n"
            + "? click Accept\n"
            + "click Join within 1m\n"
            + "unmute\n"
            + "record start\n"
            + "hangup 2h\n";

    private static final String[][] TEMPLATES = {
            {"Wait", "wait 30s"},
            {"Click a button by its words", "click Join"},
            {"Click by CSS selector", "click css:#join-button"},
            {"Wait until something shows", "waitfor Leave"},
            {"Type into a field", "type css:#name = Your name"},
            {"Press a key", "key Enter"},
            {"Scroll", "scroll down 600"},
            {"Mute the call", "mute"},
            {"Unmute the call", "unmute"},
            {"Set the page volume", "volume 100"},
            {"Hang up after a while", "hangup 1h30m"},
            {"Start the recording buffer", "record start"},
            {"Stop the recording buffer", "record stop"},
            {"Mark the recording", "mark Lecture starts"},
            {"Load the page again", "open"},
            {"Run a script on the page", "js document.title"},
            {"Close the page (hang up)", "close"},
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Workflow wf;
    private boolean isNew;
    private EditText name;
    private EditText steps;
    private TextView pageValue;
    private TextView timeValue;
    private TextView logView;
    private TextView runButton;
    private final TextView[] dayButtons = new TextView[7];
    private Switch timed;
    private LinearLayout timedBox;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            showLog();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        String id = getIntent().getStringExtra(EXTRA_ID);
        wf = id == null ? null : WorkflowStore.find(this, id);
        isNew = wf == null;
        if (isNew) {
            wf = Workflow.create();
            wf.steps = EXAMPLE;
            java.util.List<Pin> pins = PinStore.load(this);
            if (!pins.isEmpty()) wf.pinId = pins.get(0).id;
        }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        build(body);
        setContentView(Ui.screen(this, isNew ? "New workflow" : "Edit workflow", body));
    }

    @Override
    protected void onResume() {
        super.onResume();
        poll.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    private void build(LinearLayout body) {
        body.addView(Ui.label(this, "Name"));
        name = new EditText(this);
        name.setText(wf.name);
        name.setHint("Morning lecture");
        name.setSingleLine(true);
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.FAINT);
        body.addView(name, Ui.lp(this, 4, 12));

        body.addView(Ui.label(this, "Page"));
        pageValue = Ui.text(this, pageText(), 17f, Ui.GREEN, "sans-serif-medium");
        pageValue.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        pageValue.setOnClickListener(v -> choosePage());
        body.addView(pageValue, Ui.lp(this, 0, 8));

        timed = new Switch(this);
        timed.setText("Start by itself at a set time");
        timed.setTextColor(Ui.TEXT);
        timed.setChecked(wf.timed);
        body.addView(timed, Ui.lp(this, 4, 4));
        timedBox = new LinearLayout(this);
        timedBox.setOrientation(LinearLayout.VERTICAL);
        timeValue = Ui.text(this, timeText(), 26f, Ui.TEXT, "sans-serif");
        timeValue.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        timeValue.setOnClickListener(v -> new TimePickerDialog(this, (view, h, m) -> {
            wf.hour = h;
            wf.minute = m;
            timeValue.setText(timeText());
        }, wf.hour, wf.minute, true).show());
        timedBox.addView(timeValue);
        LinearLayout days = new LinearLayout(this);
        String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            TextView d = Ui.pill(this, names[i], 0);
            d.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
            d.setTextSize(12f);
            d.setOnClickListener(v -> {
                wf.days ^= bit;
                paintDays();
            });
            dayButtons[i] = d;
            days.addView(d, Ui.weight(this, i == 0 ? 0 : 4));
        }
        timedBox.addView(days);
        timedBox.addView(Ui.hint(this, "The phone does not need to be awake. Pick no day to switch the schedule off."), Ui.lp(this, 6, 0));
        body.addView(timedBox);
        timed.setOnCheckedChangeListener((b, on) -> {
            wf.timed = on;
            timedBox.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
        });
        timedBox.setVisibility(wf.timed ? android.view.View.VISIBLE : android.view.View.GONE);
        paintDays();

        body.addView(Ui.label(this, "Steps"), Ui.lp(this, 16, 4));
        steps = new EditText(this);
        steps.setText(wf.steps);
        steps.setTypeface(android.graphics.Typeface.MONOSPACE);
        steps.setTextSize(13f);
        steps.setTextColor(Ui.TEXT);
        steps.setMinLines(9);
        steps.setGravity(Gravity.TOP | Gravity.START);
        steps.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        steps.setHorizontallyScrolling(true);
        steps.setBackground(Ui.rounded(this, Ui.CARD, Ui.STROKE, 12));
        steps.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        body.addView(steps, Ui.lp(this, 0, 6));
        TextView add = Ui.pill(this, "Add a step", 0);
        add.setOnClickListener(v -> addStepMenu());
        body.addView(add, Ui.lp(this, 0, 4));
        body.addView(Ui.hint(this, "click, waitfor and type look for the words on the page (or css:SELECTOR) and retry for up to "
                + "20 s (waitfor: 60 s); add \"within 2m\" to change that. Guides > Workflows lists every step."), Ui.lp(this, 4, 12));

        LinearLayout actions = new LinearLayout(this);
        TextView save = Ui.pill(this, "Save", Ui.GREEN);
        save.setOnClickListener(v -> {
            if (save()) {
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        actions.addView(save, Ui.weight(this, 0));
        runButton = Ui.pill(this, "Save and run now", 0);
        runButton.setOnClickListener(v -> runNow());
        actions.addView(runButton, Ui.weight(this, 8));
        body.addView(actions, Ui.lp(this, 4, 0));
        if (!isNew) {
            TextView delete = Ui.pill(this, "Delete this workflow", Ui.RED);
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Delete " + (wf.name.isEmpty() ? "this workflow" : wf.name) + "?")
                    .setPositiveButton("Delete", (d, w) -> {
                        WorkflowRunner.stop(wf.id);
                        WorkflowStore.remove(this, wf.id);
                        finish();
                    })
                    .setNegativeButton("Keep", null)
                    .show());
            body.addView(delete, Ui.lp(this, 14, 0));
        }

        body.addView(Ui.label(this, "Log of the latest run"), Ui.lp(this, 22, 4));
        logView = Ui.text(this, "", 12f, Ui.DIM, "monospace");
        logView.setTextIsSelectable(true);
        body.addView(logView);
    }

    private String pageText() {
        Pin p = PinStore.find(this, wf.pinId);
        return p == null ? "Choose a pinned page" : p.name + "  (" + p.host() + ")";
    }

    private String timeText() {
        return String.format(Locale.US, "%02d:%02d", wf.hour, wf.minute);
    }

    private void paintDays() {
        for (int i = 0; i < 7; i++) Ui.tint(this, dayButtons[i], (wf.days & (1 << i)) != 0 ? Ui.GREEN : 0);
    }

    private void choosePage() {
        List<Pin> pins = PinStore.load(this);
        if (pins.isEmpty()) {
            Toast.makeText(this, "Pin a page on the home screen first.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[pins.size()];
        int checked = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = pins.get(i).name + "  (" + pins.get(i).host() + ")";
            if (pins.get(i).id.equals(wf.pinId)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Page")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    wf.pinId = pins.get(which).id;
                    pageValue.setText(pageText());
                    d.dismiss();
                })
                .show();
    }

    private void addStepMenu() {
        String[] items = new String[TEMPLATES.length];
        for (int i = 0; i < items.length; i++) items[i] = TEMPLATES[i][0] + "   -   " + TEMPLATES[i][1];
        new AlertDialog.Builder(this)
                .setTitle("Add a step")
                .setItems(items, (d, which) -> {
                    String text = steps.getText().toString();
                    if (!text.isEmpty() && !text.endsWith("\n")) text += "\n";
                    steps.setText(text + TEMPLATES[which][1] + "\n");
                    steps.setSelection(steps.getText().length());
                })
                .show();
    }

    /** Checks the steps and saves. Shows the first problem, with its line, and returns false. */
    private boolean save() {
        wf.name = name.getText().toString().trim();
        wf.steps = steps.getText().toString();
        if (wf.name.isEmpty()) {
            name.setError("Give it a name");
            return false;
        }
        if (PinStore.find(this, wf.pinId) == null) {
            Toast.makeText(this, "Choose the page this workflow opens.", Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            if (WorkflowParser.parse(wf.steps).isEmpty()) {
                steps.setError("Add at least one step");
                return false;
            }
        } catch (WorkflowParser.ParseError e) {
            steps.setError(e.getMessage());
            steps.requestFocus();
            return false;
        }
        WorkflowStore.put(this, wf);
        isNew = false;
        return true;
    }

    private void runNow() {
        if (WorkflowRunner.isRunning(wf.id)) {
            WorkflowRunner.stop(wf.id);
            return;
        }
        if (!save()) return;
        WorkflowRunner.start(this, wf.id, true);
        showLog();
    }

    private void showLog() {
        if (logView == null) return;
        List<String> lines = WorkflowRunner.log(wf.id);
        logView.setText(lines.isEmpty() ? "Nothing yet. Save and run now to try it." : String.join("\n", lines));
        runButton.setText(WorkflowRunner.isRunning(wf.id) ? "Stop" : "Save and run now");
    }
}
