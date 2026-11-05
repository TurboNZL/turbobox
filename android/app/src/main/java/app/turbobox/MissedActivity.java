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
    // the recap view inside the player
    private android.widget.ScrollView recapScroll;
    private LinearLayout recapBox;
    private LinearLayout moreRow;
    private boolean recapMode;
    private boolean recapBusy;
    private String recapProgress = "";
    private TextView recapStatus;

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
            listBody.addView(Ui.text(this, "Nothing recorded yet. Pages record when they open unless you turned that off "
                    + "(Settings > Recording buffer); Rec on a page starts it by hand.", 15f, Ui.DIM, "sans-serif"), Ui.lp(this, 18, 0));
            return;
        }
        for (BufferStore.Run r : runs) listBody.addView(runCard(r), Ui.lp(this, 10, 0));
        long bytes = BufferStore.totalBytes(BufferRecorder.root(this));
        listBody.addView(Ui.hint(this, String.format(Locale.US, "Using %.0f MB.", bytes / 1_048_576.0)), Ui.lp(this, 14, 0));
        TextView wipe = Ui.pill(this, "Delete all recordings", Ui.RED);
        wipe.setOnClickListener(v -> new DimBuilder(this)
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
        {
            TextView caps = Ui.pill(this, "Captions", 0);
            caps.setOnClickListener(v -> showCaptions(r, vtt));
            row.addView(caps, Ui.weight(this, 8));
        }
        c.addView(row, Ui.lp(this, 10, 0));
        LinearLayout row2 = new LinearLayout(this);
        TextView tr = Ui.pill(this, "Transcribe", 0);
        tr.setOnClickListener(v -> transcribeRun(r, vtt));
        row2.addView(tr, Ui.weight(this, 0));
        if (vtt.exists()) {
            TextView share = Ui.pill(this, "Share captions", 0);
            share.setOnClickListener(v -> shareCaptions(r, vtt));
            row2.addView(share, Ui.weight(this, 8));
        }
        TextView export = Ui.pill(this, "Export", 0);
        export.setOnClickListener(v -> exportRun(r));
        row2.addView(export, Ui.weight(this, 8));
        c.addView(row2, Ui.lp(this, 8, 0));
        LinearLayout row3 = new LinearLayout(this);
        TextView recap = Ui.pill(this, DigestMaker.file(r).exists() ? "Recap" : "Make recap", Ui.GREEN);
        recap.setOnClickListener(v -> {
            if (BufferStore.segments(r).isEmpty()) {
                Toast.makeText(this, "Nothing is recorded yet.", Toast.LENGTH_SHORT).show();
                return;
            }
            recapMode = true;
            showPlayer(r);
        });
        row3.addView(recap, Ui.weight(this, 0));
        TextView expCaps = Ui.pill(this, "Export captions", 0);
        expCaps.setOnClickListener(v -> exportCaptions(r, vtt));
        row3.addView(expCaps, Ui.weight(this, 8));
        TextView cut = Ui.pill(this, "Cut with AI", 0);
        cut.setOnClickListener(v -> showCutDialog(r));
        row3.addView(cut, Ui.weight(this, 8));
        c.addView(row3, Ui.lp(this, 8, 0));
        if (isLive(r)) {
            LinearLayout row4 = new LinearLayout(this);
            TextView watch = Ui.pill(this, "Watch live", Ui.RED);
            watch.setOnClickListener(v -> startActivity(new android.content.Intent(this, LiveActivity.class)));
            row4.addView(watch, Ui.weight(this, 0));
            c.addView(row4, Ui.lp(this, 8, 0));
        }
        return c;
    }

    /** Makes captions for a recording that is already made (Android 13+): the speed is set in Settings > Live transcription. */
    private void transcribeRun(BufferStore.Run r, File vtt) {
        if (isLive(r)) {
            Toast.makeText(this, "It is still recording. Stop it first, or use live captions.", Toast.LENGTH_LONG).show();
            return;
        }
        String why = Transcriber.unavailableReason(this);
        if (why != null) {
            Toast.makeText(this, why, Toast.LENGTH_LONG).show();
            return;
        }
        final RecordingTranscribe[] job = new RecordingTranscribe[1];
        TextView status = Ui.text(this, "Starting...", 15f, Ui.TEXT, "sans-serif");
        status.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 6));
        AlertDialog dlg = new DimBuilder(this).setTitle("Transcribing").setView(status)
                .setNegativeButton("Stop", (d, w) -> {
                    if (job[0] != null) job[0].stop();
                })
                .setCancelable(false).create();
        dlg.show(); Dimmer.apply(this, dlg.getWindow());
        job[0] = new RecordingTranscribe(this, r, (text, finished) -> {
            status.setText(text + (finished ? "" : "\n\nThis keeps going in the background, so you can leave. The captions are saved with the recording when it finishes."));
            if (finished) {
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Close");
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(x -> {
                    dlg.dismiss();
                    showList();
                });
            }
        });
        job[0].start();
    }

    /** Export: one video, or the 30-second pieces numbered in order; either way with the captions and marks. */
    private void exportRun(BufferStore.Run r) {
        if (BufferStore.segments(r).isEmpty()) {
            Toast.makeText(this, "Nothing to export yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        new DimBuilder(this).setTitle("Export")
                .setItems(new String[]{"One video (joins the pieces in order)", "Numbered pieces (30 seconds each)"}, (d, which) -> {
                    if (which == 0) exportMerged(r); else exportPieces(r);
                }).setNegativeButton("Cancel", null).show();
    }

    private String exportFolder(BufferStore.Run r) {
        return (r.name.isEmpty() ? "Recording" : r.name.replaceAll("[^A-Za-z0-9 _-]", "")) + " "
                + new java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(r.startedAt);
    }

    /** An output stream into Downloads/TurboBox/folder (Android 10+: the shared Downloads; before: the app's own Downloads folder). */
    private java.io.OutputStream exportStream(String folder, String name, String mime) throws java.io.IOException {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/TurboBox/" + folder);
            android.net.Uri uri = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new java.io.IOException("Downloads would not make the file");
            return getContentResolver().openOutputStream(uri);
        }
        File dir = new File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "TurboBox/" + folder);
        dir.mkdirs();
        return new java.io.FileOutputStream(new File(dir, name));
    }

    private void copyTo(File f, java.io.OutputStream out) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream o = out) {
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
        }
    }

    /** The pieces named in order: "001 - 19-30-05.mp4", the number first, then the time of day it starts. */
    private void exportPieces(BufferStore.Run r) {
        final String folder = exportFolder(r);
        final List<BufferStore.Segment> segs = BufferStore.segments(r);
        Toast.makeText(this, "Exporting " + segs.size() + " pieces...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int n = 0;
            String error = null;
            try {
                int width = Math.max(3, String.valueOf(segs.size()).length());
                java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("HH-mm-ss", Locale.US);
                for (int i = 0; i < segs.size(); i++) {
                    BufferStore.Segment sg = segs.get(i);
                    String name = String.format(Locale.US, "%0" + width + "d - %s.mp4", i + 1, clock.format(sg.startWallMs));
                    copyTo(sg.file, exportStream(folder, name, "video/mp4"));
                    n++;
                }
                exportExtras(r, folder);
            } catch (java.io.IOException | RuntimeException e) {
                error = e.getMessage();
            }
            final int count = n;
            final String err = error;
            runOnUiThread(() -> exportDone(err, count + " pieces, numbered in order, were copied to Downloads/TurboBox/" + folder + "."));
        }, "export-pieces").start();
    }

    /** The captions and marks that go with either kind of export. */
    private void exportExtras(BufferStore.Run r, String folder) throws java.io.IOException {
        File[] files = r.dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".vtt") || name.endsWith(".json") || name.endsWith(".txt")) {
                copyTo(f, exportStream(folder, name, name.endsWith(".vtt") ? "text/vtt" : "text/plain"));
            }
        }
    }

    /** Joins the pieces, in order, into one MP4 without re-encoding them (they share one format). */
    private void exportMerged(BufferStore.Run r) {
        final String folder = exportFolder(r);
        final List<BufferStore.Segment> segs = BufferStore.segments(r);
        final TextView status = Ui.text(this, "Joining " + segs.size() + " pieces...", 15f, Ui.TEXT, "sans-serif");
        status.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 8));
        final AlertDialog dlg = new DimBuilder(this).setTitle("Exporting").setView(status).setCancelable(false).create();
        dlg.show();
        Dimmer.apply(this, dlg.getWindow());
        new Thread(() -> {
            String error = null;
            java.io.File temp = null;
            try {
                // MediaMuxer needs a seekable file: join into the cache, then copy that out.
                temp = new File(getCacheDir(), "export_" + System.currentTimeMillis() + ".mp4");
                SegmentJoin.join(segs, temp, (i, total) -> runOnUiThread(() -> status.setText("Joining piece " + i + " of " + total + "...")));
                runOnUiThread(() -> status.setText("Copying to Downloads..."));
                String name = (r.name.isEmpty() ? "Recording" : r.name.replaceAll("[^A-Za-z0-9 _-]", "")) + ".mp4";
                copyTo(temp, exportStream(folder, name, "video/mp4"));
                exportExtras(r, folder);
            } catch (java.io.IOException | RuntimeException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (temp != null) temp.delete();
            }
            final String err = error;
            runOnUiThread(() -> {
                dlg.dismiss();
                exportDone(err, "One video, with the captions, was copied to Downloads/TurboBox/" + folder + ".");
            });
        }, "export-merge").start();
    }

    private void exportDone(String error, String message) {
        if (error != null) {
            Toast.makeText(this, "Export stopped: " + error, Toast.LENGTH_LONG).show();
            return;
        }
        new DimBuilder(this).setTitle("Exported")
                .setMessage(message)
                .setPositiveButton("Open Downloads", (d, w) -> startActivity(new android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)))
                .setNegativeButton("OK", null).show();
    }

    private static String safeName(BufferStore.Run r) {
        return r.name.isEmpty() ? "Recording" : r.name.replaceAll("[^A-Za-z0-9 _-]", "");
    }

    private static String readable(List<Vtt.Cue> cues) {
        StringBuilder sb = new StringBuilder();
        for (Vtt.Cue c : cues) sb.append('[').append(CutSheet.clock(c.startMs)).append("] ").append(c.text).append('\n');
        return sb.toString();
    }

    private static long runLengthMs(BufferStore.Run r) {
        long last = 0;
        for (BufferStore.Segment s : BufferStore.segments(r)) last = Math.max(last, s.startWallMs + s.durationMs);
        return last > r.startedAt ? last - r.startedAt : 0;
    }

    /** The stretches somebody watched the live recording, in ms from the run's start. */
    private static List<long[]> watchedSpans(BufferStore.Run r) {
        List<long[]> out = new java.util.ArrayList<>();
        for (long[] w : BufferStore.watching(r)) out.add(new long[]{Math.max(0, w[0] - r.startedAt), w[1] > w[0] ? w[1] - r.startedAt : 0});
        return out;
    }

    /** Saves the captions to Downloads/TurboBox: the .vtt, a plain text copy with times, and the sheet to paste into a chat AI. */
    private void exportCaptions(BufferStore.Run r, File vtt) {
        final List<Vtt.Cue> cues = loadCues(r);
        if (cues.isEmpty()) {
            Toast.makeText(this, "This recording has no captions yet. Use Transcribe to make them.", Toast.LENGTH_LONG).show();
            return;
        }
        final String folder = exportFolder(r);
        final String base = safeName(r);
        new Thread(() -> {
            String error = null;
            try {
                copyTo(vtt, exportStream(folder, base + " captions.vtt", "text/vtt"));
                try (java.io.OutputStream o = exportStream(folder, base + " captions.txt", "text/plain")) {
                    o.write(readable(cues).getBytes("UTF-8"));
                }
                List<CutSheet.Part> parts = CutSheet.parts(r.name, runLengthMs(r), cues, watchedSpans(r), CutSheet.DEFAULT_PART_WORDS);
                for (CutSheet.Part part : parts) {
                    String nm = parts.size() == 1 ? base + " for AI.txt" : base + " for AI part " + (part.index + 1) + " of " + parts.size() + ".txt";
                    try (java.io.OutputStream o = exportStream(folder, nm, "text/plain")) {
                        o.write(part.text.getBytes("UTF-8"));
                    }
                }
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String err = error;
            runOnUiThread(() -> exportDone(err, "The captions (.vtt, plain text, and a sheet to paste into an AI) were copied to Downloads/TurboBox/" + folder + "."));
        }, "export-captions").start();
    }

    // ---- the recap: a document above or below a video that always keeps its size ----

    private boolean videoAtBottom() {
        return Prefs.sp(this).getBoolean("recapVideoBottom", false);
    }

    /** Shows or hides the recap. The video stage keeps one fixed size while it is shown, so tapping around never resizes it. */
    private void applyRecapLayout() {
        if (player == null || stage == null) return;
        if (recapScroll != null && recapScroll.getParent() == player) player.removeView(recapScroll);
        if (!recapMode) {
            stage.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            captionText.setVisibility(View.VISIBLE);
            statusText.setVisibility(View.VISIBLE);
            if (moreRow != null) moreRow.setVisibility(View.VISIBLE);
            return;
        }
        int h = Math.round(getResources().getDisplayMetrics().heightPixels * 0.27f);
        stage.setLayoutParams(new LinearLayout.LayoutParams(-1, h));
        captionText.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        if (moreRow != null) moreRow.setVisibility(View.GONE);
        if (recapScroll == null) {
            recapScroll = new android.widget.ScrollView(this);
            recapBox = new LinearLayout(this);
            recapBox.setOrientation(LinearLayout.VERTICAL);
            int pad = Ui.dp(this, 16);
            recapBox.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 20));
            recapScroll.addView(recapBox);
        }
        // Inserting before the stage moves it down without taking it out of the window (its picture would be lost).
        player.addView(recapScroll, videoAtBottom() ? 1 : 2, new LinearLayout.LayoutParams(-1, 0, 1f));
        renderRecap();
    }

    private void renderRecap() {
        if (recapBox == null || run == null) return;
        recapBox.removeAllViews();
        recapStatus = null;
        final String md = DigestMaker.load(run);
        LinearLayout bar = new LinearLayout(this);
        TextView make = Ui.pill(this, md.isEmpty() ? "Make the recap" : "Remake", md.isEmpty() ? Ui.GREEN : 0);
        make.setOnClickListener(v -> makeRecap());
        bar.addView(make, Ui.weight(this, 0));
        TextView pos = Ui.pill(this, videoAtBottom() ? "Video: bottom" : "Video: top", 0);
        pos.setOnClickListener(v -> {
            Prefs.sp(this).edit().putBoolean("recapVideoBottom", !videoAtBottom()).apply();
            applyRecapLayout();
        });
        bar.addView(pos, Ui.weight(this, 6));
        TextView plain = Ui.pill(this, "Player", 0);
        plain.setOnClickListener(v -> {
            recapMode = false;
            applyRecapLayout();
        });
        bar.addView(plain, Ui.weight(this, 6));
        recapBox.addView(bar);
        if (recapBusy) {
            recapStatus = Ui.text(this, recapProgress, 14f, Ui.DIM, "sans-serif");
            recapBox.addView(recapStatus, Ui.lp(this, 10, 0));
        }
        if (md.isEmpty()) {
            AiKeys.Key key = AiKeys.current(this);
            recapBox.addView(Ui.hint(this, key != null && AiKeys.allowSend(this)
                    ? "The recap is made from this recording's captions with " + key.describe() + ": a title, the important points to the top, chapters you can tap to jump, and the stretches worth watching as a short video. It keeps going if you leave."
                    : "To make a recap, add a key and allow sending captions in Settings > AI for cutting and recaps (Groq, OpenRouter and Gemini all have free tiers)."), Ui.lp(this, 12, 0));
            return;
        }
        String title = RecapText.title(md);
        if (!title.isEmpty()) recapBox.addView(Ui.text(this, title, 22f, Ui.TEXT, "semibold"), Ui.lp(this, 12, 4));
        TextView doc = Ui.text(this, "", 16f, Ui.TEXT, "sans-serif");
        doc.setLineSpacing(0f, 1.15f);
        doc.setText(recapSpannable(md));
        doc.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        doc.setHighlightColor(0x33A6E22E);
        recapBox.addView(doc, Ui.lp(this, 6, 0));
        LinearLayout tail = new LinearLayout(this);
        TextView export = Ui.pill(this, "Export recap", 0);
        export.setOnClickListener(v -> exportRecap(md));
        tail.addView(export, Ui.weight(this, 0));
        final List<long[]> worth = RecapText.worthWatching(md);
        TextView shortVideo = Ui.pill(this, "Export short video", worth.isEmpty() ? 0 : Ui.GREEN);
        shortVideo.setOnClickListener(v -> {
            if (worth.isEmpty()) Toast.makeText(this, "The recap lists no stretches worth watching. Try Remake.", Toast.LENGTH_LONG).show();
            else cutRun(run, worth);
        });
        tail.addView(shortVideo, Ui.weight(this, 8));
        recapBox.addView(tail, Ui.lp(this, 16, 0));
    }

    private static final java.util.regex.Pattern STAMP = java.util.regex.Pattern.compile("\\[(\\d+(?::\\d{2}){1,2})\\]");
    private static final java.util.regex.Pattern RANGE_LINE = java.util.regex.Pattern.compile("(\\d+(?::\\d{2}){1,2})\\s*(?:-->|-|\u2013|\u2014|to)\\s*(\\d+(?::\\d{2}){1,2})");

    /** The recap as text with headings, and every time a tap that plays the recording from there. */
    private CharSequence recapSpannable(String md) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        boolean worth = false;
        for (String raw : md.split("\n")) {
            String line = raw.replace("**", "");
            String t = line.trim();
            if (t.regionMatches(true, 0, "TITLE:", 0, 6)) continue;
            if (t.startsWith("#")) {
                String h = t.replaceFirst("^#+\\s*", "");
                worth = h.toLowerCase(Locale.US).startsWith("worth watching");
                int s = sb.length();
                sb.append('\n').append(h).append('\n');
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, sb.length(), 0);
                sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.GREEN), s, sb.length(), 0);
                sb.setSpan(new android.text.style.RelativeSizeSpan(1.15f), s, sb.length(), 0);
                continue;
            }
            java.util.regex.Matcher m = (worth ? RANGE_LINE : STAMP).matcher(line);
            int at = 0;
            while (m.find()) {
                sb.append(line, at, m.start());
                int s = sb.length();
                final String stamp = m.group(1);
                sb.append(worth ? m.group(0) : stamp);
                sb.setSpan(new android.text.style.ClickableSpan() {
                    @Override
                    public void onClick(View v) {
                        seekRun(CutSheet.parseClock(stamp));
                    }

                    @Override
                    public void updateDrawState(android.text.TextPaint ds) {
                        ds.setColor(Ui.GREEN);
                        ds.setUnderlineText(false);
                        ds.setFakeBoldText(true);
                    }
                }, s, sb.length(), 0);
                at = m.end();
            }
            sb.append(line.substring(at)).append('\n');
        }
        return sb;
    }

    /** Plays the recording from a moment given in ms from its start; the document stays where it is. */
    private void seekRun(long msFromStart) {
        if (run == null) return;
        segs = BufferStore.segments(run);
        if (segs.isEmpty()) return;
        started = true;
        seekTo(BufferStore.offsetOfWall(segs, run.startedAt + msFromStart), true);
        updatePlayButton();
    }

    private void makeRecap() {
        if (recapBusy || run == null) return;
        if (AiKeys.current(this) == null || !AiKeys.allowSend(this)) {
            Toast.makeText(this, "Add a key and allow sending captions in Settings > AI for cutting and recaps.", Toast.LENGTH_LONG).show();
            return;
        }
        final BufferStore.Run r = run;
        recapBusy = true;
        recapProgress = "Starting...";
        renderRecap();
        DigestMaker.run(this, r, runLengthMs(r), loadCues(r), watchedSpans(r), new DigestMaker.Listener() {
            @Override
            public void progress(String text) {
                runOnUiThread(() -> {
                    recapProgress = text;
                    if (recapStatus != null) recapStatus.setText(text);
                });
            }

            @Override
            public void done(String problem) {
                runOnUiThread(() -> {
                    recapBusy = false;
                    if (problem != null) Toast.makeText(MissedActivity.this, problem, Toast.LENGTH_LONG).show();
                    if (recapMode && run != null && run.dir.equals(r.dir)) renderRecap();
                });
            }
        });
    }

    private void exportRecap(final String md) {
        final BufferStore.Run r = run;
        final String folder = exportFolder(r);
        new Thread(() -> {
            String error = null;
            try (java.io.OutputStream o = exportStream(folder, safeName(r) + " recap.md", "text/markdown")) {
                o.write(md.getBytes("UTF-8"));
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String err = error;
            runOnUiThread(() -> exportDone(err, "The recap was copied to Downloads/TurboBox/" + folder + "."));
        }, "export-recap").start();
    }

    // ---- cutting with an AI ----

    /**
     * Cut with AI. "Cut automatically" asks Gemini (with the key and account chosen in Settings) and saves its answers; otherwise, or as a
     * fallback, the captions are split into parts small enough for a free chat: Copy, paste into any AI chat, copy its reply, Paste answer.
     * Answers are remembered by time, so both ways mix. A stretch with no answer is kept whole. Cut writes one video from everything kept.
     */
    private void showCutDialog(BufferStore.Run r) {
        final List<Vtt.Cue> cues = loadCues(r);
        final long total = runLengthMs(r);
        final List<long[]> watched = watchedSpans(r);
        final List<CutSheet.Part> parts = CutSheet.parts(r.name, total, cues, watched, CutSheet.DEFAULT_PART_WORDS);
        final List<CutAnswers.Window> answers = CutAnswersStore.load(r);
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 8), pad, 0);
        final TextView[] statusView = new TextView[1];
        final boolean[] busy = new boolean[1];
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            col.removeAllViews();
            AiKeys.Key key = AiKeys.current(this);
            if (key != null && AiKeys.allowSend(this)) {
                TextView auto = Ui.pill(this, busy[0] ? "Gemini is working..." : "Cut automatically with Gemini (" + key.label + ")", Ui.GREEN);
                auto.setOnClickListener(v -> {
                    if (busy[0]) return;
                    if (cues.isEmpty()) {
                        Toast.makeText(this, "This recording has no captions to send.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    busy[0] = true;
                    render[0].run();
                    List<CutSheet.Part> big = CutSheet.parts(r.name, total, cues, watched, Ai.partWords(this));
                    AiCutter.run(this, r, big, answers, new AiCutter.Listener() {
                        @Override
                        public void progress(String text) {
                            runOnUiThread(() -> {
                                if (statusView[0] != null) statusView[0].setText(text);
                            });
                        }

                        @Override
                        public void done(String problem) {
                            runOnUiThread(() -> {
                                busy[0] = false;
                                if (problem != null) Toast.makeText(MissedActivity.this, problem, Toast.LENGTH_LONG).show();
                                render[0].run();
                            });
                        }
                    });
                });
                col.addView(auto, Ui.lp(this, 0, 4));
                TextView st = Ui.hint(this, busy[0] ? "Sending the captions..." : "Only the caption text is sent, never the video. Check the summary below, then tap Cut.");
                statusView[0] = st;
                col.addView(st, Ui.lp(this, 0, 8));
            } else {
                col.addView(Ui.hint(this, "To cut automatically, add a Gemini key and allow sending captions in Settings > AI for cutting. Or do it by hand below."), Ui.lp(this, 0, 8));
            }
            List<long[]> keep = CutAnswers.finalKeep(answers, total);
            col.addView(Ui.text(this, "Keeps " + CutSheet.clock(CutAnswers.totalMs(keep)) + " of " + CutSheet.clock(total)
                    + " (cuts " + CutSheet.clock(Math.max(0, total - CutAnswers.totalMs(keep))) + ")", 16f, Ui.GREEN, "sans-serif-medium"), Ui.lp(this, 0, 8));
            col.addView(Ui.hint(this, (parts.size() > 1 ? "By hand, this recording is split into " + parts.size() + " parts that each fit a free AI chat. " : "By hand: ")
                    + "Copy a part, paste it into an AI chat, copy the AI's reply, then Paste answer. A stretch with no answer is kept whole. Answers are remembered."), Ui.lp(this, 0, 6));
            for (CutSheet.Part part : parts) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                CutAnswers.Window got = CutAnswers.covering(answers, part.startMs, part.endMs);
                String state = got == null ? "not answered (kept whole)" : got.keep.isEmpty() ? "answered: cut all" : "answered: " + got.keep.size() + " parts kept";
                TextView label = Ui.text(this, (parts.size() > 1 ? "Part " + (part.index + 1) + "  " : "Sheet  ") + CutSheet.clock(part.startMs) + " - "
                        + CutSheet.clock(part.endMs) + "\n" + state, 14f, got == null ? Ui.TEXT : Ui.GREEN, "sans-serif");
                row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView copy = Ui.pill(this, "Copy", 0);
                copy.setOnClickListener(v -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("TurboBox cut sheet", part.text));
                    Toast.makeText(this, "Copied (" + part.text.length() / 1000 + "k characters). Paste it into the AI chat.", Toast.LENGTH_LONG).show();
                });
                row.addView(copy, Ui.weight(this, 6));
                TextView paste = Ui.pill(this, "Paste answer", Ui.GREEN);
                paste.setOnClickListener(v -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    String text = "";
                    if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                        text = cs == null ? "" : cs.toString();
                    }
                    List<long[]> ranges = CutSheet.clamp(CutSheet.parseRanges(text), part.startMs, part.endMs);
                    if (ranges.isEmpty() && !CutSheet.saysNone(text)) {
                        Toast.makeText(this, "No time ranges for this part on the clipboard. Copy the AI's whole reply, then tap Paste answer.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    synchronized (answers) {
                        CutAnswers.add(answers, new CutAnswers.Window(part.startMs, part.endMs, ranges));
                        CutAnswersStore.save(r, answers);
                    }
                    render[0].run();
                });
                row.addView(paste, Ui.weight(this, 6));
                col.addView(row, Ui.lp(this, 8, 0));
            }
            TextView clear = Ui.pill(this, "Forget the answers", 0);
            clear.setOnClickListener(v -> {
                synchronized (answers) {
                    answers.clear();
                    CutAnswersStore.save(r, answers);
                }
                render[0].run();
            });
            col.addView(clear, Ui.lp(this, 12, 0));
        };
        render[0].run();
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(col);
        new DimBuilder(this).setTitle("Cut with AI").setView(sv)
                .setPositiveButton("Cut", (d, w) -> cutRun(r, CutAnswers.finalKeep(answers, total)))
                .setNegativeButton("Close", null).show();
    }

    private void cutRun(BufferStore.Run r, List<long[]> ranges) {
        if (ranges.isEmpty()) {
            Toast.makeText(this, "No time ranges found in that answer. They look like 0:00 - 4:35, one per line.", Toast.LENGTH_LONG).show();
            return;
        }
        final List<BufferStore.Segment> segs = BufferStore.segments(r);
        if (segs.isEmpty()) {
            Toast.makeText(this, "Nothing is recorded yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        // The AI's times count from the start of the run; the pieces' joined timeline skips gaps, so go through the wall clock.
        final List<long[]> keep = new java.util.ArrayList<>();
        StringBuilder list = new StringBuilder();
        for (long[] k : ranges) {
            keep.add(new long[]{BufferStore.offsetOfWall(segs, r.startedAt + k[0]), BufferStore.offsetOfWall(segs, r.startedAt + k[1])});
            list.append(CutSheet.clock(k[0])).append(" - ").append(CutSheet.clock(k[1])).append('\n');
        }
        final String keptText = list.toString();
        final String folder = exportFolder(r);
        final TextView status = Ui.text(this, "Cutting...", 15f, Ui.TEXT, "sans-serif");
        status.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 8));
        final AlertDialog dlg = new DimBuilder(this).setTitle("Cutting").setView(status).setCancelable(false).create();
        dlg.show();
        Dimmer.apply(this, dlg.getWindow());
        new Thread(() -> {
            String error = null;
            File temp = null;
            try {
                temp = new File(getCacheDir(), "cut_" + System.currentTimeMillis() + ".mp4");
                SegmentJoin.cut(segs, keep, temp, (i, total) -> runOnUiThread(() -> status.setText("Piece " + i + " of " + total + "...")));
                runOnUiThread(() -> status.setText("Copying to Downloads..."));
                copyTo(temp, exportStream(folder, safeName(r) + " (cut).mp4", "video/mp4"));
                try (java.io.OutputStream o = exportStream(folder, safeName(r) + " (cut) kept parts.txt", "text/plain")) {
                    o.write(keptText.getBytes("UTF-8"));
                }
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (temp != null) temp.delete();
            }
            final String err = error;
            runOnUiThread(() -> {
                dlg.dismiss();
                exportDone(err, "The cut video was copied to Downloads/TurboBox/" + folder + ". Cuts start at the nearest key frame, up to 2 seconds early.");
            });
        }, "export-cut").start();
    }

    /** The captions of a recording as readable text, with a button that sends the .vtt file. */
    private void showCaptions(BufferStore.Run r, File vtt) {
        List<Vtt.Cue> list = loadCues(r);
        boolean none = list.isEmpty();
        StringBuilder sb = new StringBuilder();
        for (Vtt.Cue c : list) sb.append(Vtt.timestamp(c.startMs), 0, 8).append("   ").append(c.text).append("\n\n");
        TextView t = Ui.text(this, none
                ? "No captions were recorded for this recording.\n\nCaptions are made while a recording runs, unless Settings > Live transcription > "
                + "Turn off captions while recording is on. They need Android 13 or newer. Use Transcribe to make them now."
                : sb.toString(), 15f, Ui.TEXT, "sans-serif");
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        new DimBuilder(this)
                .setTitle("Captions - " + (r.name.isEmpty() ? "recording" : r.name))
                .setView(sv)
                .setPositiveButton(none ? "OK" : "Share .vtt file", (d, w) -> {
                    if (!none) shareCaptions(r, vtt);
                })
                .setNegativeButton(none ? null : "Close", null)
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
        recapScroll = null;
        recapBox = null;
        recapStatus = null;

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
        TextView recapTab = Ui.pill(this, "Recap", Ui.GREEN);
        recapTab.setOnClickListener(v -> {
            recapMode = true;
            applyRecapLayout();
        });
        more.addView(recapTab, Ui.weight(this, 6));
        moreRow = more;
        player.addView(more, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(player);
        applyRecapLayout();
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
        new DimBuilder(this)
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
