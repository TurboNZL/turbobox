package app.turbobox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs workflows: opens the pinned page in a free slot (or uses the slot it is already open in), then does the
 * steps one after another. Click, type and wait steps are tried again every half second until they work or run
 * out of time. The page, the steps and the waiting all go on with the screen off: the foreground service and a
 * wake lock keep the process running, and every step is driven from here, not from page timers (which Android
 * slows down for a page nobody is looking at). Main thread only.
 */
final class WorkflowRunner {
    private static final String CHANNEL_ID = "workflows";
    private static final long OPEN_TIMEOUT_MS = 90_000;
    private static final long RETRY_MS = 500;
    private static final long ANSWER_MS = 4_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Run> RUNS = new HashMap<>();
    private static final Map<String, List<String>> LOGS = new HashMap<>();
    private static PowerManager.WakeLock wakeLock;

    private WorkflowRunner() {
    }

    static boolean anyRunning() {
        return !RUNS.isEmpty();
    }

    static boolean isRunning(String workflowId) {
        return RUNS.containsKey(workflowId);
    }

    /** One line per running workflow for the notification. */
    static List<String> statusLines() {
        List<String> out = new ArrayList<>();
        for (Run r : RUNS.values()) {
            out.add("Workflow " + r.wf.name + ": step " + Math.max(1, r.index + 1) + " of " + r.steps.size());
        }
        return out;
    }

    /** The log of the latest run of this workflow (kept in memory until the app is closed). */
    static List<String> log(String workflowId) {
        List<String> l = LOGS.get(workflowId);
        return l == null ? new ArrayList<String>() : new ArrayList<>(l);
    }

    static void stop(String workflowId) {
        Run r = RUNS.get(workflowId);
        if (r != null) r.finish(false, "stopped");
    }

    /** Starts a workflow; false (with the reason logged) when it cannot start. */
    static boolean start(Context context, String workflowId, boolean manual) {
        Context app = context.getApplicationContext();
        Workflow wf = WorkflowStore.find(app, workflowId);
        if (wf == null || RUNS.containsKey(workflowId)) return false;
        LOGS.put(workflowId, new ArrayList<String>());
        List<WorkflowParser.Step> steps;
        try {
            steps = WorkflowParser.parse(wf.steps);
        } catch (WorkflowParser.ParseError e) {
            Run dead = new Run(app, wf, new ArrayList<WorkflowParser.Step>());
            dead.log(e.getMessage());
            dead.report(false, e.getMessage());
            return false;
        }
        Run run = new Run(app, wf, steps);
        RUNS.put(workflowId, run);
        acquire(app);
        SessionService.ensure(app);
        run.log((manual ? "Started by hand" : "Started by its schedule") + ", " + steps.size() + " steps");
        MAIN.post(run::begin);
        if (manual) {
            // Started by hand: show the page, so it has a window to be recorded from (a timed run with the screen off has none).
            app.startActivity(new Intent(app, SessionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setAction(SessionActivity.ACTION_OPEN_PIN).putExtra(SessionActivity.EXTRA_PIN, wf.pinId));
        }
        return true;
    }

    private static void acquire(Context app) {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "turbobox:workflow");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L); // a stuck run cannot hold the phone awake forever
    }

    private static void releaseIfIdle() {
        if (RUNS.isEmpty() && wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private static final class Run {
        final Context app;
        final Workflow wf;
        final List<WorkflowParser.Step> steps;
        int index = -1;
        Session session;
        boolean finished;
        long waitUntil;
        int attemptToken;

        Run(Context app, Workflow wf, List<WorkflowParser.Step> steps) {
            this.app = app;
            this.wf = wf;
            this.steps = steps;
        }

        void log(String line) {
            List<String> l = LOGS.get(wf.id);
            if (l == null) {
                l = new ArrayList<>();
                LOGS.put(wf.id, l);
            }
            l.add(String.format(Locale.US, "%tT  %s", System.currentTimeMillis(), line));
            if (l.size() > 200) l.remove(0);
        }

        void begin() {
            if (finished) return;
            Pin pin = PinStore.find(app, wf.pinId);
            if (pin == null) {
                finish(false, "the pinned page for this workflow no longer exists");
                return;
            }
            session = Sessions.forPin(pin.id);
            if (session == null) {
                int slot = Sessions.firstFreeSlot();
                if (slot < 0) {
                    finish(false, "both slots are busy; close a page first");
                    return;
                }
                session = Sessions.get(slot);
                log("Opening " + pin.name + " in slot " + (char) ('A' + slot));
                session.open(app, pin);
                waitUntil = System.currentTimeMillis() + OPEN_TIMEOUT_MS;
                awaitLoad(this::next);
            } else {
                log("Using " + pin.name + ", already open in slot " + (char) ('A' + session.slot));
                next();
            }
        }

        /** Calls {@code then} once the page has loaded, or after a long wait if it never reports that. */
        void awaitLoad(Runnable then) {
            if (finished) return;
            if (!session.isActive()) {
                finish(false, "the page was closed");
            } else if (session.isPageLoaded()) {
                then.run();
            } else if (System.currentTimeMillis() > waitUntil) {
                // Some pages never report "finished" (a stream, a long poll). The steps retry anyway, so go on.
                log("The page is still loading after " + OPEN_TIMEOUT_MS / 1000 + " s; carrying on");
                then.run();
            } else {
                MAIN.postDelayed(() -> awaitLoad(then), RETRY_MS);
            }
        }

        void next() {
            if (finished) return;
            index++;
            SessionService.refresh();
            if (index >= steps.size()) {
                finish(true, "done");
                return;
            }
            MAIN.postDelayed(this::runStep, 300);
        }

        void runStep() {
            if (finished) return;
            WorkflowParser.Step s = steps.get(index);
            if (!session.isActive() && s.kind != WorkflowParser.Kind.OPEN) {
                finish(false, "the page was closed before step " + (index + 1));
                return;
            }
            log("Step " + (index + 1) + ": " + s.raw);
            switch (s.kind) {
                case OPEN:
                    if (!session.isActive()) {
                        Pin pin = PinStore.find(app, wf.pinId);
                        if (pin == null) {
                            finish(false, "the pinned page no longer exists");
                            return;
                        }
                        session.open(app, pin);
                    } else {
                        session.reload();
                    }
                    waitUntil = System.currentTimeMillis() + OPEN_TIMEOUT_MS;
                    awaitLoad(this::next);
                    break;
                case WAIT:
                    MAIN.postDelayed(this::next, s.seconds * 1000);
                    break;
                case MUTE:
                    session.setMuted(true);
                    next();
                    break;
                case UNMUTE:
                    session.setMuted(false);
                    next();
                    break;
                case VOLUME:
                    session.setVolume(s.number / 100f);
                    next();
                    break;
                case HANGUP:
                    session.arm(s.seconds * 1000);
                    next();
                    break;
                case RECORD_START:
                    session.startRecording();
                    next();
                    break;
                case RECORD_STOP:
                    session.stopRecording();
                    next();
                    break;
                case STEP_AWAY:
                    if (!session.stepAway()) {
                        finish(false, "step " + (index + 1) + " (" + s.raw + "): the recording could not start");
                        return;
                    }
                    next();
                    break;
                case STEP_BACK:
                    session.stepBack();
                    next();
                    break;
                case TRANSCRIBE_ON:
                    session.setTranscribing(true);
                    next();
                    break;
                case TRANSCRIBE_OFF:
                    session.setTranscribing(false);
                    next();
                    break;
                case MARK:
                    session.mark(s.text.isEmpty() ? "Workflow " + wf.name : s.text);
                    next();
                    break;
                case CLOSE:
                    session.close("closed by workflow " + wf.name);
                    next();
                    break;
                default:
                    attempt(s, System.currentTimeMillis() + s.withinMs);
                    break;
            }
        }

        /** Tries a page step; on failure tries again until its time is up. */
        void attempt(WorkflowParser.Step s, long deadline) {
            if (finished) return;
            final int token = ++attemptToken;
            final boolean[] answered = {false};
            MAIN.postDelayed(() -> {
                // A click that navigates can take the answer with it; count that as a miss and look again.
                if (!answered[0] && token == attemptToken && !finished) {
                    answered[0] = true;
                    failed(s, deadline, "no answer from the page");
                }
            }, ANSWER_MS);
            session.evalStep(s.toJson(), (ok, message) -> {
                if (answered[0] || finished || token != attemptToken) return;
                answered[0] = true;
                if (ok) {
                    if (!message.isEmpty() && s.kind != WorkflowParser.Kind.WAITFOR) log("  " + message);
                    next();
                } else {
                    failed(s, deadline, message);
                }
            });
        }

        void failed(WorkflowParser.Step s, long deadline, String message) {
            if (System.currentTimeMillis() < deadline) {
                MAIN.postDelayed(() -> attempt(s, deadline), RETRY_MS);
            } else if (s.optional) {
                log("  skipped (optional): " + message);
                next();
            } else {
                finish(false, "step " + (index + 1) + " (" + s.raw + "): " + message);
            }
        }

        void finish(boolean ok, String message) {
            if (finished) return;
            finished = true;
            attemptToken++;
            log(ok ? "Finished" : "Stopped: " + message);
            RUNS.remove(wf.id);
            releaseIfIdle();
            report(ok, message);
            SessionService.ensure(app);
        }

        void report(boolean ok, String message) {
            WorkflowStore.recordResult(app, wf.id, ok, message);
            if (ok) return;
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Workflow problems", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(app, wf.id.hashCode(),
                    new Intent(app, WorkflowsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_box)
                    .setContentTitle("Workflow \"" + wf.name + "\" did not finish")
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            nm.notify(wf.id.hashCode(), n);
        }
    }
}
