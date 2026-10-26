package app.turbobox;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.KeyEvent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the sessions alive while the screen is off or the app is in the background: a foreground service (so
 * Android lets the process keep capturing the microphone), a partial wake lock and a Wi-Fi lock while a page or
 * a workflow is running, and one notification with Mute / Close all and the hang-up countdown. It is also what
 * "Standby" keeps up: the service started while the app is on screen is allowed to use the microphone later,
 * when an alarm starts a workflow with the phone locked.
 */
public class SessionService extends Service {
    static final String ACTION_CLOSE_ALL = "app.turbobox.CLOSE_ALL";
    static final String ACTION_TOGGLE_MUTE = "app.turbobox.TOGGLE_MUTE";
    static final String ACTION_STOP_STANDBY = "app.turbobox.STOP_STANDBY";

    private static final String CHANNEL_ID = "sessions";           // something is happening: pages, a timer, a workflow
    private static final String CHANNEL_STANDBY = "standby";        // nothing is: a quiet ongoing notice you can hide
    private static final int NOTIFICATION_ID = 2;

    private static SessionService instance;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private MediaSession mediaSession;
    private long lastButtonAt;

    /** Does anything need the process kept alive? */
    static boolean needed(Context c) {
        return Sessions.anyActive() || WorkflowRunner.anyRunning() || Prefs.standby(c);
    }

    /** Starts the service when something needs it, stops it when nothing does, and updates the notification. */
    static void ensure(Context c) {
        Context app = c.getApplicationContext();
        Intent i = new Intent(app, SessionService.class);
        if (needed(app)) {
            try {
                app.startForegroundService(i);
            } catch (RuntimeException e) {
                // Android refuses a foreground service started from the background without an exemption
                // (an exact alarm is one). The work itself still runs in this process for now.
            }
        } else {
            app.stopService(i);
        }
    }

    /** Rebuild the notification after a timer, mute or recording changed. */
    static void refresh() {
        SessionService s = instance;
        if (s != null) s.apply(false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "turbobox:sessions");
        wakeLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(
                Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "turbobox:sessions");
        wifiLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CLOSE_ALL.equals(action)) {
            for (Session s : Sessions.active()) s.close("closed from the notification");
        } else if (ACTION_TOGGLE_MUTE.equals(action)) {
            Sessions.toggleAllMuted();
        } else if (ACTION_STOP_STANDBY.equals(action)) {
            Prefs.setStandby(this, false);
        }
        apply(true);
        return Prefs.standby(this) ? START_STICKY : START_NOT_STICKY;
    }

    /** Foreground with the current notification; stops itself when nothing needs it any more. */
    private void apply(boolean starting) {
        boolean need = needed(this);
        if (!need && !starting) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Pages and workflows", NotificationManager.IMPORTANCE_LOW));
        // Its own channel, as quiet as a channel gets (no sound, no status bar icon), so Android's notification settings can
        // hide it ("Standby" off) while the service itself keeps running.
        NotificationChannel standby = new NotificationChannel(CHANNEL_STANDBY, "Standby", NotificationManager.IMPORTANCE_MIN);
        standby.setShowBadge(false);
        standby.setDescription("Shown while TurboBox is only waiting for a workflow. Turn it off here to hide it; Standby keeps working.");
        nm.createNotificationChannel(standby);
        Notification n = buildNotification();
        if (starting) {
            startForegroundCompat(n); // must happen within seconds of startForegroundService, even if we stop next
        } else {
            nm.notify(NOTIFICATION_ID, n);
        }
        if (!need) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        syncMediaSession();
        boolean busy = Sessions.anyActive() || WorkflowRunner.anyRunning();
        if (busy) {
            if (!wakeLock.isHeld()) wakeLock.acquire();
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } else {
            if (wakeLock.isHeld()) wakeLock.release();
            if (wifiLock.isHeld()) wifiLock.release();
        }
    }

    // ---- the headset's play/pause button -----------------------------------------------------------------------
    // A media session that is active (and "playing" while a page plays) is the one Android hands the headset's button to,
    // so the press reaches the app rather than a music player that was left over. It starts the workflow chosen in
    // Settings, or else plays and pauses the page in front.

    private void syncMediaSession() {
        boolean want = Prefs.headsetButton(this) && (Sessions.anyActive() || (Prefs.standby(this) && !Prefs.headphoneWorkflow(this).isEmpty()));
        if (!want) {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;
            }
            return;
        }
        if (mediaSession == null) {
            mediaSession = new MediaSession(this, "TurboBox");
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent intent) {
                    KeyEvent e = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (e == null || e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
                    int k = e.getKeyCode();
                    if (k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE
                            || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_HEADSETHOOK) {
                        headsetPressed();
                    }
                    return true;
                }
            });
        }
        boolean playing = false;
        for (Session s : Sessions.active()) playing |= s.isMediaPlaying();
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                .build());
        if (!mediaSession.isActive()) mediaSession.setActive(true);
    }

    private void headsetPressed() {
        long now = System.currentTimeMillis();
        if (now - lastButtonAt < 800) return;   // one press can arrive as play and pause both
        lastButtonAt = now;
        String id = Prefs.headphoneWorkflow(this);
        if (!id.isEmpty() && WorkflowStore.find(this, id) != null) {
            WorkflowRunner.start(this, id, false);
            return;
        }
        for (Session s : Sessions.active()) {
            if (s.isMediaPlaying() || Sessions.active().size() == 1) {
                s.mediaToggle();
                return;
            }
        }
    }

    /**
     * Microphone type when the phone allows it (the microphone permission is granted and the app was in the
     * foreground when this service started), otherwise media playback, which still keeps a video playing and a
     * workflow clicking with the screen off.
     */
    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT < 29) {
            startForeground(NOTIFICATION_ID, n);
            return;
        }
        boolean micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (micOk) {
            try {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                return;
            } catch (RuntimeException e) {
                // Started from the background: no microphone type allowed now.
            }
        }
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    private Notification buildNotification() {
        List<Session> active = Sessions.active();
        List<String> lines = new ArrayList<>();
        long soonest = -1;
        for (Session s : active) {
            lines.add(s.statusLine());
            long left = s.getRemainingMs();
            if (left >= 0 && (soonest < 0 || left < soonest)) soonest = left;
        }
        List<String> workflows = WorkflowRunner.statusLines();
        lines.addAll(workflows);
        boolean idle = active.isEmpty() && workflows.isEmpty();
        if (idle) {
            Object[] next = WorkflowScheduler.soonest(this);
            lines.add(next == null ? "Ready for workflows"
                    : "Next workflow " + next[0] + " at " + android.text.format.DateFormat.getTimeFormat(this).format((Long) next[1]));
        }

        Intent openIntent = new Intent(this, active.isEmpty() ? MainActivity.class : SessionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);
        String title = idle ? "TurboBox standby"
                : active.isEmpty() ? "TurboBox - workflow running"
                : active.size() == 1 ? "TurboBox - 1 page" : "TurboBox - " + active.size() + " pages";
        Notification.Builder b = new Notification.Builder(this, idle ? CHANNEL_STANDBY : CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_box)
                .setContentTitle(title)
                .setContentText(lines.get(0))
                .setStyle(new Notification.BigTextStyle().bigText(String.join("\n", lines)))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(idle ? Notification.CATEGORY_SERVICE : Notification.CATEGORY_CALL);
        if (!active.isEmpty()) {
            b.addAction(action(Sessions.allMuted() ? "Unmute" : "Mute", ACTION_TOGGLE_MUTE, 4));
            b.addAction(action(active.size() > 1 ? "Close all" : "Close page", ACTION_CLOSE_ALL, 3));
        } else if (idle && Prefs.standby(this)) {
            b.addAction(action("Stop standby", ACTION_STOP_STANDBY, 5));
        }
        if (soonest >= 0) {
            // A live countdown that the system keeps ticking, so the notification needs no per-second updates.
            long endsAt = System.currentTimeMillis() + soonest;
            b.setWhen(endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true);
        }
        return b.build();
    }

    private Notification.Action action(String label, String action, int requestCode) {
        return new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_box), label,
                PendingIntent.getService(this, requestCode, new Intent(this, SessionService.class).setAction(action),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
