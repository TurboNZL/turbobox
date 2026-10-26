package app.turbobox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarm for a timed workflow, and the events after which the alarms must be planned again. */
public class WorkflowReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (WorkflowScheduler.ACTION_RUN.equals(action)) {
            String id = intent.getStringExtra(WorkflowScheduler.EXTRA_ID);
            Workflow wf = WorkflowStore.find(context, id);
            if (wf == null) return;
            WorkflowScheduler.schedule(context, wf);        // the next occurrence, before this one starts
            String pin = intent.getStringExtra(WorkflowScheduler.EXTRA_PIN);
            if (pin != null) {
                // A page with its own time: only that page runs the steps now.
                Workflow.PageSchedule ps = wf.pageSchedules.get(pin);
                if (ps != null && ps.enabled && wf.pinIds.contains(pin)) WorkflowRunner.startPages(context, wf.id, java.util.Collections.singleton(pin), false);
            } else if (wf.timed && wf.enabled) {
                // The workflow's own time: the pages that do not have a time of their own.
                WorkflowRunner.startPages(context, wf.id, null, true);
            }
        } else {
            // Boot, app update, clock or zone change: the planned alarms are gone or wrong.
            WorkflowScheduler.scheduleAll(context);
            if (Prefs.standby(context)) SessionService.ensure(context);
        }
    }
}
