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
            if (wf.timed && wf.enabled) WorkflowRunner.start(context, wf.id, false);
        } else {
            // Boot, app update, clock or zone change: the planned alarms are gone or wrong.
            WorkflowScheduler.scheduleAll(context);
            if (Prefs.standby(context)) SessionService.ensure(context);
        }
    }
}
