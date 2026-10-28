package app.turbobox;

import android.app.Activity;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Workflows from the page's side: a workflow is made once, on its own, and each page says which workflows it takes part in and when
 * (days and time). It writes the same data the workflow editor does (the workflow's pages and each page's own schedule), so a
 * workflow's pages can still be managed from the workflow itself.
 */
final class PageWorkflows {
    private PageWorkflows() {
    }

    /** The "Workflows" section of a page's settings. */
    static void section(Activity act, LinearLayout col, Pin pin, int tint) {
        LinearLayout box = Ui.section(act, col, IconButton.Icon.TIMER, "Workflows", tint);
        render(act, box, pin);
    }

    private static void render(Activity act, LinearLayout box, Pin pin) {
        box.removeAllViews();
        List<Workflow> all = WorkflowStore.load(act);
        if (all.isEmpty()) {
            box.addView(Ui.hint(act, "No workflows yet. Make one under Workflows on the home screen, then switch it on for this page here."));
            return;
        }
        box.addView(Ui.hint(act, "Tap a workflow to use it on this page and choose when it runs."), Ui.lp(act, 0, 4));
        for (Workflow wf : all) {
            TextView row = Ui.text(act, wf.name.isEmpty() ? "Workflow" : wf.name, 16f, Ui.TEXT, "sans-serif-medium");
            row.setPadding(0, Ui.dp(act, 8), 0, 0);
            box.addView(row);
            TextView state = Ui.text(act, stateText(act, wf, pin) + "  ›", 14f, wf.pinIds.contains(pin.id) ? Ui.GREEN : Ui.DIM, "sans-serif");
            state.setPadding(0, 0, 0, Ui.dp(act, 8));
            box.addView(state);
            View.OnClickListener open = v -> edit(act, wf, pin, () -> render(act, box, pin));
            row.setOnClickListener(open);
            state.setOnClickListener(open);
        }
    }

    private static String stateText(Activity act, Workflow wf, Pin pin) {
        if (!wf.pinIds.contains(pin.id)) return "Not used on this page";
        Workflow.PageSchedule ps = wf.pageSchedules.get(pin.id);
        if (ps != null) return ps.enabled ? "On this page: " + Workflow.daysText(ps.days) + " " + Ui.formatTime(act, ps.hour, ps.minute) : "On this page, its time is off";
        return wf.timed ? "On this page, with the workflow's time: " + wf.scheduleText(act) : "On this page, started by hand";
    }

    private static void edit(Activity act, Workflow wf, Pin pin, Runnable done) {
        final Workflow.PageSchedule ps = wf.pageSchedules.containsKey(pin.id) ? wf.pageSchedules.get(pin.id) : new Workflow.PageSchedule();
        final boolean[] use = {wf.pinIds.contains(pin.id)};
        final boolean[] timed = {wf.pageSchedules.containsKey(pin.id)};
        LinearLayout col = new LinearLayout(act);
        Dialog dlg = Ui.sheet(act, col);
        col.addView(Ui.text(act, wf.name.isEmpty() ? "Workflow" : wf.name, 20f, Ui.TEXT, "semibold"), Ui.lp(act, 0, 2));
        col.addView(Ui.hint(act, "On " + pin.name), Ui.lp(act, 0, 6));
        col.addView(Ui.switchRow(act, "Use this workflow on this page", "Off: the page is taken out of the workflow", use[0], Ui.GREEN, (b, on) -> use[0] = on));
        col.addView(Ui.switchRow(act, "Run at a set time", "Off: it follows the workflow's own schedule (or is started by hand)", timed[0], Ui.GREEN, (b, on) -> timed[0] = on),
                Ui.lp(act, 8, 0));
        TextView time = Ui.text(act, Ui.formatTime(act, ps.hour, ps.minute), 26f, Ui.TEXT, "sans-serif");
        time.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
        time.setOnClickListener(v -> new TimePickerDialog(act, (view, h, m) -> {
            ps.hour = h;
            ps.minute = m;
            time.setText(Ui.formatTime(act, h, m));
        }, ps.hour, ps.minute, android.text.format.DateFormat.is24HourFormat(act)).show());
        col.addView(time);
        LinearLayout days = new LinearLayout(act);
        String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            TextView d = Ui.pill(act, names[i], (ps.days & bit) != 0 ? Ui.GREEN : 0);
            d.setPadding(0, Ui.dp(act, 9), 0, Ui.dp(act, 9));
            d.setTextSize(12f);
            d.setOnClickListener(v -> {
                ps.days ^= bit;
                Ui.tint(act, d, (ps.days & bit) != 0 ? Ui.GREEN : 0);
            });
            days.addView(d, Ui.weight(act, i == 0 ? 0 : 4));
        }
        col.addView(days, Ui.lp(act, 4, 0));
        col.addView(Ui.hint(act, "The phone does not need to be awake. The workflow's pages can also be changed from the workflow itself."), Ui.lp(act, 8, 0));
        TextView save = Ui.pill(act, "Save", Ui.GREEN);
        save.setOnClickListener(v -> {
            Workflow fresh = WorkflowStore.find(act, wf.id);   // the stored one: it may have changed since the sheet opened
            if (fresh != null) {
                if (use[0]) {
                    if (!fresh.pinIds.contains(pin.id)) fresh.pinIds.add(pin.id);
                    if (timed[0]) fresh.pageSchedules.put(pin.id, ps); else fresh.pageSchedules.remove(pin.id);
                } else {
                    fresh.pinIds.remove(pin.id);
                    fresh.pageSchedules.remove(pin.id);
                }
                WorkflowStore.put(act, fresh);
                wf.pinIds.clear();
                wf.pinIds.addAll(fresh.pinIds);
                wf.pageSchedules.clear();
                wf.pageSchedules.putAll(fresh.pageSchedules);
            }
            dlg.dismiss();
            done.run();
        });
        col.addView(save, Ui.lp(act, 16, 0));
        dlg.show();
    }
}
