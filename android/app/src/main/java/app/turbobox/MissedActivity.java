package app.turbobox;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.PixelFormat;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * The recordings: a list of the runs in the buffer, and a player for one. Opened from "Back" on the session
 * screen, it starts at the moment you stepped away and plays the missed part at the speed you pick, then keeps
 * going into whatever has been recorded since, until it has caught up.
 *
 * Playback is a chain of the run's 30 second segments through {@link MediaPlayer}; the seek bar spans the whole run
 * (gaps where recording was off are skipped). A segment still being written is not playable, so "Latest" asks the
 * recorder to finish it first.
 */
public class MissedActivity extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_RUN = "run";
    static final String EXTRA_FROM_WALL = "fromWall";
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 2.5f, 3f};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout listBody;
    private LinearLayout player;
    private BufferStore.Run run;
    private boolean openedDirectly;

    // player
    private SurfaceView surfaceView;
    private FrameLayout stage;
    private SeekBar seek;
    private TextView timeText;
    private TextView statusText;
    private TextView captionText;
    private List<Vtt.Cue> cues = new java.util.ArrayList<>();
    private TextView playButton;
    private TextView speedButton;
    private boolean surfaceReady;
    private MediaPlayer mp;
    private boolean prepared;
    private boolean playing = true;
    private int speedIndex;
    private List<BufferStore.Segment> segs;
    private long currentStartWall = -1;     // identifies the playing segment even when older ones are trimmed away
    private long pendingFromWall;           // start here once a segment covers it (0 = start at the beginning)
    private boolean started;
    private boolean userSeeking;
    private long resumeAtMs = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            rescan();
            updateProgress();
            ui.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        String dir = getIntent().getStringExtra(EXTRA_RUN);
        if (dir != null) {
            for (BufferStore.Run r : BufferStore.listRuns(BufferRecorder.root(this))) {
                if (r.dir.getAbsolutePath().equals(dir)) run = r;
            }
            pendingFromWall = getIntent().getLongExtra(EXTRA_FROM_WALL, 0);
            openedDirectly = run != null;
        }
        if (run != null) showPlayer(run); else showList();
    }

    @Override
    public void onBackPressed() {
        if (run != null && !openedDirectly) {
            leavePlayer();
            showList();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (run != null && player != null) {
            ui.removeCallbacks(tick);
            ui.post(tick);
            if (surfaceReady && mp == null && started) resumeAfterStop();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        if (mp != null) {
            resumeAtMs = timelineMs();
            releasePlayer();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        releasePlayer();
        super.onDestroy();
    }

    // ---- the list of runs --------------------------------------------------------------------

    private void showList() {
        run = null;
        listBody = new LinearLayout(this);
        listBody.setOrientation(LinearLayout.VERTICAL);
        setContentView(Ui.screen(this, "Recordings", listBody));
        BufferStore.removeStrays(BufferRecorder.root(this), 5 * 60_000L, System.currentTimeMillis());
        List<BufferStore.Run> runs = BufferStore.listRuns(BufferRecorder.root(this));
        listBody.addView(Ui.hint(this, "TurboBox keeps what a page showed and played while its recording buffer was on, in this "
                + "phone's cache: the newest " + Prefs.bufferMinutes(this) + " minutes of each recording, and the last "
                + Prefs.keepRuns(this) + " recordings. Android may clear a cache when storage runs low."));
        if (runs.isEmpty()) {
            listBody.addView(Ui.text(this, "Nothing recorded yet. Tap Rec on a page, or Step away, or pin the page with "
                    + "\"Start the recording buffer when it opens\".", 15f, Ui.DIM, "sans-serif"), Ui.lp(this, 18, 0));
            return;
        }
        for (BufferStore.Run r : runs) listBody.addView(runCard(r), Ui.lp(this, 10, 0));
        long bytes = BufferStore.totalBytes(BufferRecorder.root(this));
        listBody.addView(Ui.hint(this, String.format(Locale.US, "Using %.0f MB.", bytes / 1_048_576.0)), Ui.lp(this, 14, 0));
        TextView wipe = Ui.pill(this, "Delete all recordings", Ui.RED);
        wipe.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete all recordings?")
                .setPositiveButton("Delete", (d, w) -> {
                    for (BufferStore.Run r : BufferStore.listRuns(BufferRecorder.root(this))) {
                        if (!isLive(r)) BufferStore.deleteRun(r);
                    }
                    showList();
                })
                .setNegativeButton("Keep", null)
                .show());
        listBody.addView(wipe, Ui.lp(this, 8, 0));
    }

    /** Is a session still recording into this run? */
    private static boolean isLive(BufferStore.Run r) {
        for (Session s : Sessions.all()) {
            BufferRecorder rec = s.recorder();
            if (rec != null && rec.isRecording() && r.dir.equals(rec.runDir())) return true;
        }
        return false;
    }

    private View runCard(BufferStore.Run r) {
        List<BufferStore.Segment> s = BufferStore.segments(r);
        LinearLayout c = Ui.card(this);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, r.name.isEmpty() ? "Recording" : r.name, 17f, Ui.TEXT, "sans-serif-medium"));
        String when = DateFormat.getMediumDateFormat(this).format(r.startedAt) + " " + DateFormat.getTimeFormat(this).format(r.startedAt);
        names.addView(Ui.text(this, when + "  -  " + clock(BufferStore.totalMs(s)) + (isLive(r) ? "  -  recording now" : ""), 13f,
                isLive(r) ? Ui.RED : Ui.DIM, "sans-serif"), Ui.lp(this, 3, 0));
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(head);
        LinearLayout row = new LinearLayout(this);
        TextView play = Ui.pill(this, "Play", Ui.GREEN);
        play.setOnClickListener(v -> {
            if (s.isEmpty()) {
                Toast.makeText(this, "Nothing finished yet - the first piece is written after about 30 seconds.", Toast.LENGTH_LONG).show();
                return;
            }
            showPlayer(r);
        });
        row.addView(play, Ui.weight(this, 0));
        TextView del = Ui.pill(this, "Delete", 0);
        del.setOnClickListener(v -> {
            if (isLive(r)) {
                Toast.makeText(this, "It is still recording. Stop the recording first.", Toast.LENGTH_LONG).show();
                return;
            }
            BufferStore.deleteRun(r);
            showList();
        });
        row.addView(del, Ui.weight(this, 8));
        File vtt = new File(r.dir, "transcript.vtt");
        if (vtt.exists() && vtt.length() > Vtt.HEADER.length()) {
            TextView caps = Ui.pill(this, "Captions", 0);
            caps.setOnClickListener(v -> showCaptions(r, vtt));
            row.addView(caps, Ui.weight(this, 8));
        }
        c.addView(row, Ui.lp(this, 10, 0));
        return c;
    }

    /** The captions of a recording as readable text, with a button that sends the .vtt file. */
    private void showCaptions(BufferStore.Run r, File vtt) {
        List<Vtt.Cue> list = loadCues(r);
        StringBuilder sb = new StringBuilder();
        for (Vtt.Cue c : list) sb.append(Vtt.timestamp(c.startMs), 0, 8).append("   ").append(c.text).append("\n\n");
        TextView t = Ui.text(this, sb.length() == 0 ? "No speech was recognised yet." : sb.toString(), 15f, Ui.TEXT, "sans-serif");
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle("Captions - " + (r.name.isEmpty() ? "recording" : r.name))
                .setView(sv)
                .setPositiveButton("Share .vtt file", (d, w) -> shareCaptions(r, vtt))
                .setNegativeButton("Close", null)
                .show();
    }

    private void shareCaptions(BufferStore.Run r, File vtt) {
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", vtt);
        startActivity(android.content.Intent.createChooser(new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/vtt").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .putExtra(android.content.Intent.EXTRA_SUBJECT, (r.name.isEmpty() ? "Recording" : r.name) + " captions")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share captions"));
    }

    // ---- the player --------------------------------------------------------------------------

    private void showPlayer(BufferStore.Run r) {
        run = r;
        started = false;
        resumeAtMs = -1;
        segs = BufferStore.segments(r);
        currentStartWall = -1;
        cues = loadCues(r);
        player = new LinearLayout(this);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setBackgroundColor(Ui.BG);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 16);
        head.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 6));
        TextView back = Ui.text(this, "‹ Back", 15f, Ui.GREEN, "sans-serif-medium");
        back.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        back.setOnClickListener(v -> onBackPressed());
        head.addView(back);
        head.addView(Ui.text(this, r.name.isEmpty() ? "Recording" : r.name, 20f, Ui.TEXT, "semibold"));
        player.addView(head, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(0xFF000000);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        surfaceView.getHolder().addCallback(this);
        stage.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        player.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        captionText = Ui.text(this, "", 16f, Ui.TEXT, "sans-serif-medium");
        captionText.setGravity(Gravity.CENTER);
        captionText.setPadding(pad, Ui.dp(this, 8), pad, 0);
        captionText.setMinLines(2);
        player.addView(captionText, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));
        statusText = Ui.text(this, "", 13f, Ui.DIM, "sans-serif");
        statusText.setPadding(pad, Ui.dp(this, 4), pad, 0);
        player.addView(statusText, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));
        seek = new SeekBar(this);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.GREEN));
        seek.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 2));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                if (fromUser) timeText.setText(clock(progress) + " / " + clock(BufferStore.totalMs(segs)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                userSeeking = false;
                seekTo(b.getProgress(), playing);
            }
        });
        player.addView(seek, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));
        timeText = Ui.text(this, "", 13f, Ui.DIM, "sans-serif");
        timeText.setGravity(Gravity.CENTER);
        player.addView(timeText, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 4));
        TextView back10 = Ui.pill(this, "-10 s", 0);
        back10.setOnClickListener(v -> seekTo(Math.max(0, timelineMs() - 10_000), playing));
        controls.addView(back10, Ui.weight(this, 0));
        playButton = Ui.pill(this, "Pause", Ui.GREEN);
        playButton.setOnClickListener(v -> togglePlay());
        controls.addView(playButton, Ui.weight(this, 6));
        TextView fwd10 = Ui.pill(this, "+10 s", 0);
        fwd10.setOnClickListener(v -> seekTo(timelineMs() + 10_000, playing));
        controls.addView(fwd10, Ui.weight(this, 6));
        speedButton = Ui.pill(this, speedText(), 0);
        speedButton.setOnClickListener(v -> {
            speedIndex = (speedIndex + 1) % SPEEDS.length;
            speedButton.setText(speedText());
            applySpeed();
        });
        controls.addView(speedButton, Ui.weight(this, 6));
        player.addView(controls, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout more = new LinearLayout(this);
        more.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 16));
        TextView marks = Ui.pill(this, "Marks", 0);
        marks.setOnClickListener(v -> showMarks());
        more.addView(marks, Ui.weight(this, 0));
        TextView latest = Ui.pill(this, "Latest", 0);
        latest.setOnClickListener(v -> jumpToLatest());
        more.addView(latest, Ui.weight(this, 6));
        player.addView(more, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(player);
        statusText.setText(pendingFromWall != 0 ? "Getting the part you missed ready..." : "");
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void leavePlayer() {
        ui.removeCallbacks(tick);
        releasePlayer();
        pendingFromWall = 0;
        player = null;
    }

    private String speedText() {
        float s = SPEEDS[speedIndex];
        return (s == Math.floor(s) ? String.format(Locale.US, "%.0f", s) : String.format(Locale.US, "%.2f", s).replaceAll("0+$", "")) + "x";
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (mp != null) {
            mp.setDisplay(holder);
        } else if (run != null && player != null) {
            ui.post(this::maybeStart);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (mp != null) mp.setDisplay(null);
    }

    /** Looks for new segments (the recorder keeps writing) and starts playback as soon as there is something to play. */
    private void rescan() {
        if (run == null || player == null) return;
        segs = BufferStore.segments(run);
        cues = loadCues(run);
        seek.setMax((int) Math.max(1, BufferStore.totalMs(segs)));
        maybeStart();
    }

    private void maybeStart() {
        if (started || !surfaceReady || run == null || segs == null || segs.isEmpty()) return;
        long offset;
        if (resumeAtMs >= 0) {
            offset = resumeAtMs;
        } else if (pendingFromWall != 0) {
            BufferStore.Segment last = segs.get(segs.size() - 1);
            if (last.startWallMs + last.durationMs < pendingFromWall) return; // what you missed is still being written
            offset = BufferStore.offsetOfWall(segs, pendingFromWall);
        } else {
            offset = 0;
        }
        started = true;
        statusText.setText("");
        seekTo(offset, true);
    }

    private void resumeAfterStop() {
        started = false;
        maybeStart();
    }

    /** Plays from {@code timelineMs} on the run's own timeline. */
    private void seekTo(long timelineMs, boolean play) {
        segs = BufferStore.segments(run);
        BufferStore.Position p = BufferStore.locate(segs, timelineMs);
        if (p == null) return;
        playing = play;
        playSegment(p.index, p.offsetMs);
    }

    private void playSegment(int index, long offsetMs) {
        if (!surfaceReady || index < 0 || index >= segs.size()) return;
        BufferStore.Segment seg = segs.get(index);
        releasePlayer();
        currentStartWall = seg.startWallMs;
        MediaPlayer m = new MediaPlayer();
        mp = m;
        prepared = false;
        try {
            m.setDataSource(seg.file.getAbsolutePath());
            m.setDisplay(surfaceView.getHolder());
            m.setOnVideoSizeChangedListener((x, w, h) -> fitStage(w, h));
            m.setOnPreparedListener(x -> {
                if (mp != x) return;
                prepared = true;
                if (offsetMs > 0) x.seekTo(offsetMs, MediaPlayer.SEEK_CLOSEST);
                if (playing) startPlayback();
                updatePlayButton();
            });
            m.setOnCompletionListener(x -> {
                if (mp == x) segmentFinished();
            });
            m.setOnErrorListener((x, what, extra) -> {
                if (mp == x) {
                    statusText.setText("This piece could not be played (error " + what + "). Skipping it.");
                    segmentFinished();
                }
                return true;
            });
            m.prepareAsync();
        } catch (IOException | IllegalStateException | SecurityException e) {
            statusText.setText("Could not open the recording: " + e.getMessage());
        }
    }

    private void startPlayback() {
        if (mp == null || !prepared) return;
        mp.start();
        applySpeed();
    }

    private void applySpeed() {
        if (mp == null || !prepared || !playing) return;
        try {
            // On a prepared player a non-zero speed also starts it; it is only set while playing.
            mp.setPlaybackParams(new PlaybackParams().setSpeed(SPEEDS[speedIndex]));
        } catch (IllegalStateException | IllegalArgumentException e) {
            statusText.setText("This phone cannot change the speed here.");
        }
    }

    private void togglePlay() {
        if (mp == null) {
            if (segs != null && !segs.isEmpty()) seekTo(0, true);
            return;
        }
        playing = !playing;
        if (playing) startPlayback();
        else if (prepared) mp.pause();
        updatePlayButton();
    }

    private void updatePlayButton() {
        playButton.setText(playing ? "Pause" : "Play");
    }

    /** The playing segment ended: go on with the next one, or look for newer ones, or say it has caught up. */
    private void segmentFinished() {
        segs = BufferStore.segments(run);
        int next = -1;
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).startWallMs > currentStartWall) {
                next = i;
                break;
            }
        }
        if (next >= 0) {
            playSegment(next, 0);
            return;
        }
        // Nothing newer is finished yet. Ask a live recorder to finish its current piece and look again.
        releasePlayer();
        playing = false;
        updatePlayButton();
        boolean live = flushLive();
        statusText.setText(live ? "You have caught up with the recording. Looking for what has been recorded since..."
                : "End of the recording.");
        if (live) ui.postDelayed(this::playNewerIfAny, 4000);
    }

    private void playNewerIfAny() {
        if (run == null || player == null || mp != null) return;
        segs = BufferStore.segments(run);
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).startWallMs > currentStartWall) {
                statusText.setText("");
                playing = true;
                playSegment(i, 0);
                return;
            }
        }
        statusText.setText("You are up to date (the recording is written in 30 second pieces).");
    }

    private boolean flushLive() {
        for (Session s : Sessions.all()) {
            BufferRecorder rec = s.recorder();
            if (rec != null && rec.isRecording() && rec.runDir() != null && rec.runDir().equals(run.dir)) {
                rec.requestFlush();
                return true;
            }
        }
        return false;
    }

    private void jumpToLatest() {
        flushLive();
        statusText.setText("Finishing the latest piece...");
        ui.postDelayed(() -> {
            if (run == null || player == null) return;
            segs = BufferStore.segments(run);
            if (segs.isEmpty()) return;
            statusText.setText("");
            playing = true;
            playSegment(segs.size() - 1, 0);
        }, 3000);
    }

    private void showMarks() {
        List<BufferStore.Mark> marks = BufferStore.marks(run);
        if (marks.isEmpty()) {
            Toast.makeText(this, "No marks in this recording. \"Step away\" and the Rec menu add them.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[marks.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = DateFormat.getTimeFormat(this).format(marks.get(i).wallMs) + "   " + marks.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle("Marks")
                .setItems(items, (d, which) -> seekTo(BufferStore.offsetOfWall(BufferStore.segments(run), marks.get(which).wallMs), true))
                .show();
    }

    private void fitStage(int vw, int vh) {
        if (vw <= 0 || vh <= 0 || stage.getWidth() == 0 || stage.getHeight() == 0) return;
        float k = Math.min(stage.getWidth() / (float) vw, stage.getHeight() / (float) vh);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(vw * k), Math.round(vh * k), Gravity.CENTER);
        surfaceView.setLayoutParams(lp);
    }

    /** Where playback is on the run's whole timeline. */
    private long timelineMs() {
        if (mp == null || !prepared || segs == null) return Math.max(0, resumeAtMs);
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i).startWallMs == currentStartWall) {
                try {
                    return BufferStore.startOfSegment(segs, i) + mp.getCurrentPosition();
                } catch (IllegalStateException e) {
                    return BufferStore.startOfSegment(segs, i);
                }
            }
        }
        return 0;
    }

    private void updateProgress() {
        if (player == null || segs == null) return;
        long total = BufferStore.totalMs(segs);
        if (!userSeeking && mp != null) {
            long at = timelineMs();
            seek.setProgress((int) Math.min(at, seek.getMax()));
            timeText.setText(clock(at) + " / " + clock(total));
            showCaption();
        } else if (!userSeeking) {
            timeText.setText(clock(0) + " / " + clock(total));
        }
    }

    /** The caption for the playing moment: the segment's wall-clock start plus the position in it, from the run's start. */
    private void showCaption() {
        if (cues.isEmpty() || mp == null || !prepared) {
            captionText.setText("");
            return;
        }
        try {
            long rel = currentStartWall + mp.getCurrentPosition() - run.startedAt;
            Vtt.Cue c = Vtt.at(cues, rel);
            captionText.setText(c == null ? "" : c.text);
        } catch (IllegalStateException e) {
            captionText.setText("");
        }
    }

    private static List<Vtt.Cue> loadCues(BufferStore.Run r) {
        File f = new File(r.dir, "transcript.vtt");
        if (!f.exists()) return new java.util.ArrayList<>();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            for (int n; off < b.length && (n = in.read(b, off, b.length - off)) > 0; ) off += n;
            return Vtt.parse(new String(b, 0, off, "UTF-8"));
        } catch (IOException e) {
            return new java.util.ArrayList<>();
        }
    }

    private void releasePlayer() {
        MediaPlayer m = mp;
        mp = null;
        prepared = false;
        if (m != null) {
            try {
                m.setOnCompletionListener(null);
                m.setOnErrorListener(null);
                m.release();
            } catch (RuntimeException ignored) {
                // Already released.
            }
        }
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
