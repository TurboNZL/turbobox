package app.turbobox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** The saved workflows: run one now, edit it, switch its schedule on or off. */
public class WorkflowsActivity extends Activity {
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        setContentView(Ui.screen(this, "Workflows", body));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        body.removeAllViews();
        body.addView(Ui.hint(this, "A workflow opens one or more of your pinned pages and follows the steps you write: click this, "
                + "type that, wait, record, hang up. It can start by itself at a time you choose, with the screen off."));

        TextView add = Ui.pill(this, "New workflow", Ui.GREEN);
        add.setOnClickListener(v -> startActivity(new Intent(this, WorkflowEditActivity.class)));
        body.addView(add, Ui.lp(this, 12, 8));

        List<Workflow> list = WorkflowStore.load(this);
        boolean anyTimed = false;
        for (Workflow w : list) anyTimed |= w.timed && w.enabled;
        if (anyTimed) renderReadiness();
        if (list.isEmpty()) body.addView(Ui.hint(this, "No workflows yet."), Ui.lp(this, 12, 0));
        for (Workflow w : list) body.addView(card(w), Ui.lp(this, 10, 0));
    }

    /** What a scheduled workflow needs from the phone, said once, with the button that fixes it. */
    private void renderReadiness() {
        LinearLayout card = Ui.card(this);
        boolean ok = true;
        if (!WorkflowScheduler.canScheduleExact(this)) {
            ok = false;
            card.addView(Ui.text(this, "Exact alarms are off", 15f, Ui.AMBER, "sans-serif-medium"));
            card.addView(Ui.hint(this, "Without them Android may start a workflow several minutes late."), Ui.lp(this, 2, 6));
            TextView fix = Ui.pill(this, "Allow exact alarms", 0);
            fix.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
                }
            });
            card.addView(fix, Ui.lp(this, 0, 8));
        }
        if (!Prefs.standby(this)) {
            ok = false;
            card.addView(Ui.text(this, "Standby is off", 15f, Ui.AMBER, "sans-serif-medium"));
            card.addView(Ui.hint(this, "A workflow that starts while the phone is locked can click and play, but Android may keep "
                    + "the microphone away from it. Standby keeps TurboBox ready (one small notification, almost no battery) so "
                    + "scheduled calls can use the microphone."), Ui.lp(this, 2, 6));
            TextView on = Ui.pill(this, "Turn Standby on", Ui.GREEN);
            on.setOnClickListener(v -> {
                Prefs.setStandby(this, true);
                SessionService.ensure(this);
                render();
            });
            card.addView(on, Ui.lp(this, 0, 0));
        }
        if (!ok) body.addView(card, Ui.lp(this, 10, 0));
    }

    private LinearLayout card(Workflow w) {
        LinearLayout c = Ui.card(this);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, w.name.isEmpty() ? "(no name)" : w.name, 17f, Ui.TEXT, "sans-serif-medium"));
        List<String> pageNames = new java.util.ArrayList<>();
        for (String id : w.pinIds) {
            Pin pin = PinStore.find(this, id);
            if (pin != null) pageNames.add(pin.name);
        }
        names.addView(Ui.text(this, (pageNames.isEmpty() ? "No page chosen" : String.join(", ", pageNames)) + "  -  " + w.scheduleText(this),
                13f, Ui.DIM, "sans-serif"), Ui.lp(this, 3, 0));
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (w.timed) {
            Switch on = new Switch(this);
            on.setChecked(w.enabled);
            on.setOnCheckedChangeListener((b, checked) -> {
                w.enabled = checked;
                WorkflowStore.put(this, w);
                render();
            });
            head.addView(on);
        }
        c.addView(head);

        if (w.lastRunAt > 0) {
            String when = DateFormat.getMediumDateFormat(this).format(w.lastRunAt) + " " + DateFormat.getTimeFormat(this).format(w.lastRunAt);
            TextView last = Ui.text(this, "Last run " + when + ": " + (w.lastOk ? "done" : w.lastMessage), 12f,
                    w.lastOk ? Ui.GREEN : Ui.AMBER, "sans-serif");
            c.addView(last, Ui.lp(this, 8, 0));
        }

        LinearLayout row = new LinearLayout(this);
        boolean running = WorkflowRunner.isRunning(w.id);
        TextView run = Ui.pill(this, running ? "Stop" : "Run now", running ? Ui.RED : Ui.GREEN);
        run.setOnClickListener(v -> {
            if (running) {
                WorkflowRunner.stop(w.id);
            } else if (!WorkflowRunner.start(this, w.id, true)) {
                Toast.makeText(this, "It could not start - open it to see why.", Toast.LENGTH_LONG).show();
            }
            render();
        });
        row.addView(run, Ui.weight(this, 0));
        TextView edit = Ui.pill(this, "Edit", 0);
        edit.setOnClickListener(v -> startActivity(new Intent(this, WorkflowEditActivity.class).putExtra(WorkflowEditActivity.EXTRA_ID, w.id)));
        row.addView(edit, Ui.weight(this, 8));
        c.addView(row, Ui.lp(this, 10, 0));
        return c;
    }
}
