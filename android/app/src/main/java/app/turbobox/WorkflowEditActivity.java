package app.turbobox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Writes one workflow: its page, its schedule and its steps, and tries it. */
public class WorkflowEditActivity extends Activity {
    static final String EXTRA_ID = "id";

    /** A new workflow starts with a few ordinary steps to change, not an empty list. */
    private static final String EXAMPLE = "wait 5s\n? click Accept\nclick Join within 1m\nunmute\nrecord start\nhangup 2h\n";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Workflow wf;
    private boolean isNew;
    private EditText name;
    private final List<String> lines = new ArrayList<>();   // the steps, one line each (what the parser reads)
    private LinearLayout stepsBox;
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
            if (!pins.isEmpty()) wf.pinIds.add(pins.get(0).id);
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
        name.setHint("Morning check-in");
        name.setSingleLine(true);
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.FAINT);
        body.addView(name, Ui.lp(this, 4, 12));

        body.addView(Ui.label(this, "Pages"));
        pageValue = Ui.text(this, pageText(), 17f, Ui.GREEN, "sans-serif-medium");
        pageValue.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        pageValue.setOnClickListener(v -> choosePage());
        body.addView(pageValue, Ui.lp(this, 0, 8));

        timed = Ui.toggle(this);
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
        }, wf.hour, wf.minute, android.text.format.DateFormat.is24HourFormat(this)).show());
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
        for (String l : wf.steps.split("\\r?\\n")) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("#")) lines.add(t);
        }
        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(stepsBox, Ui.lp(this, 0, 6));
        renderSteps();
        TextView add = Ui.pill(this, "+ Add a step", Ui.GREEN);
        add.setOnClickListener(v -> addStepMenu());
        body.addView(add, Ui.lp(this, 0, 4));
        body.addView(Ui.hint(this, "Steps run from the top, one after another. Click, type and wait steps keep looking for what they need and "
                + "give up after their time (20 seconds, 60 to wait for something to show). Arrows move a step, the pencil changes it."), Ui.lp(this, 4, 12));

        timedHotkey(body);

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
        List<String> names = new ArrayList<>();
        for (String id : wf.pinIds) {
            Pin p = PinStore.find(this, id);
            if (p != null && !Profiles.isLocked(this, p.profileId)) names.add(p.name);
        }
        return names.isEmpty() ? "Choose pinned pages" : String.join(", ", names);
    }

    private String timeText() {
        return Ui.formatTime(this, wf.hour, wf.minute);
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
        PinGrid.pick(this, "Run on", pins, new LinkedHashSet<>(wf.pinIds), true, chosen -> {
            wf.pinIds.clear();
            for (Pin p : pins) if (chosen.contains(p.id)) wf.pinIds.add(p.id);   // the home screen's order
            pageValue.setText(pageText());
        });
    }

    // ---- adding a step with buttons --------------------------------------------------------------------

    /** Every kind of step as a button, grouped; tapping one asks what it needs. */
    private void addStepMenu() {
        LinearLayout col = new LinearLayout(this);
        Dialog d = Ui.sheet(this, col);
        col.addView(Ui.text(this, "Add a step", 20f, Ui.TEXT, "semibold"), Ui.lp(this, 0, 4));
        col.addView(Ui.hint(this, "It is added after the line your cursor is on."), Ui.lp(this, 0, 8));
        String group = "";
        for (StepTemplates.Template t : StepTemplates.all()) {
            if (!t.group.equals(group)) {
                group = t.group;
                col.addView(Ui.label(this, group), Ui.lp(this, 14, 4));
            }
            TextView b = Ui.pill(this, t.title, 0);
            b.setOnClickListener(v -> {
                d.dismiss();
                askStep(t);
            });
            col.addView(b, Ui.lp(this, 4, 0));
            col.addView(Ui.hint(this, t.help), Ui.lp(this, 2, 0));
        }
        d.show();
    }

    /** The questions a step needs (how long, which words...), then the line goes into the steps box. */
    private void askStep(StepTemplates.Template t) {
        askStep(t, null, line -> {
            lines.add(line);
            renderSteps();
        });
    }

    private void askStep(StepTemplates.Template t, StepTemplates.Edit edit, java.util.function.Consumer<String> onLine) {
        if (t.fields.length == 0 && !t.pageStep) {
            onLine.accept(t.line(t.defaults()));
            return;
        }
        LinearLayout col = new LinearLayout(this);
        Dialog d = Ui.sheet(this, col);
        col.addView(Ui.text(this, t.title, 20f, Ui.TEXT, "semibold"));
        col.addView(Ui.hint(this, t.help), Ui.lp(this, 2, 6));
        final Supplier[] answers = new Supplier[t.fields.length];
        for (int i = 0; i < t.fields.length; i++) {
            StepTemplates.Field f = t.fields[i];
            col.addView(Ui.label(this, f.label), Ui.lp(this, 12, 4));
            answers[i] = fieldInput(col, edit == null || i >= edit.values.length ? f : withDefault(f, edit.values[i]));
            if (f.hint != null) col.addView(Ui.hint(this, f.hint), Ui.lp(this, 2, 0));
        }
        Switch optional = null;
        Supplier within = null;
        if (t.pageStep) {
            optional = Ui.toggle(this);
            optional.setText("Fine to skip if it is not there");
            optional.setTextColor(Ui.TEXT);
            optional.setChecked(edit != null && edit.optional);
            col.addView(optional, Ui.lp(this, 14, 0));
            col.addView(Ui.label(this, "Give up after"), Ui.lp(this, 12, 4));
            within = timeInput(col, edit == null ? 0 : edit.withinSeconds);
            col.addView(Ui.hint(this, "0 uses the usual time: 20 seconds, 60 for waiting for something to show."), Ui.lp(this, 2, 0));
        }
        final Switch optionalFinal = optional;
        final Supplier withinFinal = within;
        TextView add = Ui.pill(this, edit == null ? "Add step" : "Save step", Ui.GREEN);
        add.setOnClickListener(v -> {
            String[] values = new String[answers.length];
            for (int i = 0; i < values.length; i++) values[i] = answers[i].get();
            String line = t.line(values);
            if (line == null) {
                Toast.makeText(this, "Fill in what it should look for.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (t.pageStep) {
                line = StepTemplates.decorate(line, optionalFinal.isChecked(), Long.parseLong(withinFinal.get()));
            }
            d.dismiss();
            onLine.accept(line);
        });
        col.addView(add, Ui.lp(this, 18, 0));
        d.show();
    }

    private static StepTemplates.Field withDefault(StepTemplates.Field f, String value) {
        return new StepTemplates.Field(f.type, f.label, f.hint, value, f.options);
    }

    private interface Supplier {
        String get();
    }

    /** One input for a field, added to {@code col}; returns what to read the answer with. */
    private Supplier fieldInput(LinearLayout col, StepTemplates.Field f) {
        switch (f.type) {
            case TIME:
                return timeInput(col, Long.parseLong(f.def == null || f.def.isEmpty() ? "0" : f.def));
            case CHOICE: {
                final String[] picked = {f.def};
                TextView value = Ui.text(this, f.def + "  ›", 17f, Ui.GREEN, "sans-serif-medium");
                value.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
                value.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(f.label)
                        .setItems(f.options, (dd, which) -> {
                            picked[0] = f.options[which];
                            value.setText(picked[0] + "  ›");
                        }).show());
                col.addView(value);
                return () -> picked[0];
            }
            default: {
                EditText e = new EditText(this);
                e.setSingleLine(true);
                e.setTextColor(Ui.TEXT);
                e.setHintTextColor(Ui.FAINT);
                e.setText(f.def);
                e.setSelectAllOnFocus(true);
                if (f.hint != null) e.setHint(f.hint);
                if (f.type == StepTemplates.FieldType.NUMBER) e.setInputType(InputType.TYPE_CLASS_NUMBER);
                else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                col.addView(e);
                return () -> e.getText().toString();
            }
        }
    }

    /** Hours, minutes and seconds boxes starting at {@code seconds}; the answer is the total in seconds. */
    private Supplier timeInput(LinearLayout col, long seconds) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText[] boxes = new EditText[3];
        String[] units = {"h", "min", "s"};
        long[] start = {seconds / 3600, seconds % 3600 / 60, seconds % 60};
        for (int i = 0; i < 3; i++) {
            EditText e = new EditText(this);
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            e.setSingleLine(true);
            e.setGravity(Gravity.CENTER);
            e.setTextColor(Ui.TEXT);
            e.setText(String.valueOf(start[i]));
            e.setSelectAllOnFocus(true);
            e.setEms(3);
            boxes[i] = e;
            row.addView(e);
            TextView u = Ui.text(this, units[i], 14f, Ui.DIM, "sans-serif");
            u.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 14), 0);
            row.addView(u);
        }
        col.addView(row);
        return () -> String.valueOf(StepTemplates.toSeconds(boxes[0].getText().toString(), boxes[1].getText().toString(),
                boxes[2].getText().toString()));
    }

    private void renderSteps() {
        stepsBox.removeAllViews();
        if (lines.isEmpty()) {
            stepsBox.addView(Ui.hint(this, "No steps yet."), Ui.lp(this, 0, 6));
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            final int at = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.rounded(this, Ui.CARD, Ui.STROKE, 12));
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            TextView number = Ui.text(this, String.valueOf(i + 1), 13f, Ui.FAINT, "sans-serif-medium");
            number.setMinWidth(Ui.dp(this, 20));
            row.addView(number);
            TextView text = Ui.text(this, StepTemplates.describe(lines.get(i)), 15f, Ui.TEXT, "sans-serif");
            text.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
            text.setOnClickListener(v -> editStep(at));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            int w = Ui.dp(this, 40);
            addRowButton(row, IconButton.Icon.UP, "Move up", w, i > 0, () -> moveStep(at, -1));
            addRowButton(row, IconButton.Icon.DOWN, "Move down", w, i < lines.size() - 1, () -> moveStep(at, 1));
            addRowButton(row, IconButton.Icon.EDIT, "Change this step", w, true, () -> editStep(at));
            addRowButton(row, IconButton.Icon.TRASH, "Remove this step", w, true, () -> {
                lines.remove(at);
                renderSteps();
            });
            stepsBox.addView(row, Ui.lp(this, 0, 6));
        }
    }

    private void addRowButton(LinearLayout row, IconButton.Icon icon, String what, int width, boolean enabled, Runnable action) {
        IconButton b = new IconButton(this, icon, what);
        b.setBackground(null);
        b.setEnabled(enabled);
        b.setOnClickListener(v -> action.run());
        row.addView(b, new LinearLayout.LayoutParams(width, Ui.dp(this, 40)));
    }

    private void moveStep(int at, int by) {
        int to = at + by;
        if (to < 0 || to >= lines.size()) return;
        String l = lines.remove(at);
        lines.add(to, l);
        renderSteps();
    }

    /** Opens the form the step was made with, filled in; a line no form can rebuild is edited as plain text. */
    private void editStep(int at) {
        StepTemplates.Edit e = StepTemplates.toEdit(lines.get(at));
        if (e == null) {
            EditText raw = new EditText(this);
            raw.setText(lines.get(at));
            raw.setTextColor(Ui.TEXT);
            new AlertDialog.Builder(this).setTitle("Change this step").setView(raw)
                    .setPositiveButton("Save", (d, w) -> {
                        String t = raw.getText().toString().trim();
                        if (!t.isEmpty()) lines.set(at, t);
                        renderSteps();
                    }).setNegativeButton("Cancel", null).show();
            return;
        }
        askStep(e.template, e, line -> {
            lines.set(at, line);
            renderSteps();
        });
    }

    private void timedHotkey(LinearLayout body) {
        Switch hot = Ui.toggle(this);
        hot.setText("Show a button for it on the page screen");
        hot.setChecked(wf.hotkey);
        hot.setOnCheckedChangeListener((b, on) -> wf.hotkey = on);
        body.addView(hot, Ui.lp(this, 4, 0));
        body.addView(Ui.hint(this, "A one-tap button above the pages that runs this without asking. A timer is just a workflow like this "
                + "with a Hang up step."), Ui.lp(this, 2, 8));
    }

    /** Checks the steps and saves. Shows the first problem, with its line, and returns false. */
    private boolean save() {
        wf.name = name.getText().toString().trim();
        wf.steps = String.join("\n", lines);
        if (wf.name.isEmpty()) {
            name.setError("Give it a name");
            return false;
        }
        boolean anyPage = false;
        for (String id : wf.pinIds) anyPage |= PinStore.find(this, id) != null;
        try {
            List<WorkflowParser.Step> parsed = WorkflowParser.parse(wf.steps);
            if (parsed.isEmpty()) {
                Toast.makeText(this, "Add at least one step.", Toast.LENGTH_LONG).show();
                return false;
            }
            boolean needsPage = false;
            for (WorkflowParser.Step st : parsed) needsPage |= WorkflowParser.needsPage(st.kind);
            if (needsPage && !anyPage) {
                Toast.makeText(this, "Choose the page this workflow works on (some steps need one).", Toast.LENGTH_LONG).show();
                return false;
            }
        } catch (WorkflowParser.ParseError e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
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
