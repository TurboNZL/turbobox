package app.turbobox;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Plans the alarms that start timed workflows. One alarm per workflow, always for its next occurrence; it is
 * planned again when it fires, after a reboot, after an update and when the clock or zone changes.
 */
final class WorkflowScheduler {
    static final String ACTION_RUN = "app.turbobox.RUN_WORKFLOW";
    static final String EXTRA_ID = "id";

    private WorkflowScheduler() {
    }

    /** Epoch millis of the first hh:mm after {@code nowMs} on a chosen day (bit 0 = Monday); -1 with no days. */
    static long nextRun(long nowMs, int hour, int minute, int daysMask, ZoneId zone) {
        if ((daysMask & Workflow.ALL_DAYS) == 0) return -1;
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        for (int add = 0; add <= 8; add++) {
            ZonedDateTime t = now.plusDays(add).withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            DayOfWeek d = t.getDayOfWeek();
            if ((daysMask & (1 << (d.getValue() - 1))) != 0 && t.toInstant().toEpochMilli() > nowMs) {
                return t.toInstant().toEpochMilli();
            }
        }
        return -1;
    }

    private static PendingIntent intentFor(Context c, String id) {
        Intent i = new Intent(c, WorkflowReceiver.class).setAction(ACTION_RUN).putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean canScheduleExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        return c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static void schedule(Context c, Workflow wf) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        cancel(c, wf.id);
        if (!wf.timed || !wf.enabled) return;
        long when = nextRun(System.currentTimeMillis(), wf.hour, wf.minute, wf.days, ZoneId.systemDefault());
        if (when < 0) return;
        PendingIntent pi = intentFor(c, wf.id);
        if (canScheduleExact(c)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } else {
            // Without the exact-alarm permission Android may run it some minutes late; Settings says so.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    static void cancel(Context c, String id) {
        c.getSystemService(AlarmManager.class).cancel(intentFor(c, id));
    }

    static void scheduleAll(Context c) {
        for (Workflow w : WorkflowStore.load(c)) schedule(c, w);
    }

    /** The soonest planned run, for the standby notification: {workflow name, epoch millis} or null. */
    static Object[] soonest(Context c) {
        long best = Long.MAX_VALUE;
        String name = null;
        for (Workflow w : WorkflowStore.load(c)) {
            if (!w.timed || !w.enabled) continue;
            long t = nextRun(System.currentTimeMillis(), w.hour, w.minute, w.days, ZoneId.systemDefault());
            if (t >= 0 && t < best) {
                best = t;
                name = w.name;
            }
        }
        return name == null ? null : new Object[]{name, best};
    }
}
