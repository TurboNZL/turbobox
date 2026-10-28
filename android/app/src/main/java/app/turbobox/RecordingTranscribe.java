package app.turbobox;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Captions for a recording that has been made already: its sound is decoded piece by piece and fed to the speech recogniser at the
 * speed set in Settings (real time is the most exact; faster takes a third of the time and can miss a word at the start of a phrase), and the words are saved as
 * {@code transcript.vtt} next to it. Android 13 or newer.
 */
final class RecordingTranscribe {
    interface Progress {
        void update(String text, boolean finished);
    }

    private static final int RATE = 24000;

    private final Context app;
    private final BufferStore.Run run;
    private final Progress progress;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private Transcriber transcriber;

    RecordingTranscribe(Context app, BufferStore.Run run, Progress progress) {
        this.app = app.getApplicationContext();
        this.run = run;
        this.progress = progress;
    }

    void stop() {
        stopped = true;
    }

    void start() {
        String why = Transcriber.unavailableReason(app);
        if (why != null) {
            progress.update(why, true);
            return;
        }
        final List<BufferStore.Segment> segs = BufferStore.segments(run);
        if (segs.isEmpty()) {
            progress.update("Nothing has been written for this recording yet.", true);
            return;
        }
        final File vtt = new File(run.dir, "transcript.vtt");
        vtt.delete();
        long first = segs.get(0).startWallMs - run.startedAt;
        // The recogniser reads from the main thread's looper, so it is created here; the audio is fed from a thread of its own.
        transcriber = new Transcriber(app, vtt, System.currentTimeMillis() - Math.max(0, first), "" + Prefs.transcribeLang(app),
                text -> main.post(() -> progress.update(text, false)));
        transcriber.useMediaClock(Math.max(0, first));   // the sound is fed faster than it was spoken, so times come from how much was fed
        if (!transcriber.start()) {
            progress.update("Could not start the speech recogniser.", true);
            return;
        }
        new Thread(() -> {
            long total = BufferStore.totalMs(segs), done = 0;
            for (BufferStore.Segment s : segs) {
                if (stopped) break;
                final long base = done;
                decode(s.file, ms -> {
                    final int pct = total == 0 ? 0 : (int) Math.min(99, (base + ms) * 100 / total);
                    main.post(() -> progress.update("Transcribing... " + pct + "%", false));
                });
                done += s.durationMs;
            }
            try {
                Thread.sleep(4000);   // the last phrase is still being recognised
            } catch (InterruptedException ignored) {
                // Stopping anyway.
            }
            main.post(() -> {
                transcriber.stop();
                progress.update(stopped ? "Stopped. What was heard so far is saved." : "Done. The captions are saved with the recording.", true);
            });
        }, "transcribe-recording").start();
    }

    private interface Tick {
        void at(long ms);
    }

    /** Decodes the audio of one segment to 24 kHz mono PCM and feeds it at the chosen speed. */
    private void decode(File file, Tick tick) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) return;
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : RATE;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            long fed = 0;
            final int speed = Math.max(1, Prefs.transcribeSpeed(app));
            long t0 = System.nanoTime();
            while (!outputDone && !stopped) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer b = codec.getInputBuffer(in);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(out);
                    byte[] pcm = new byte[info.size];
                    ob.get(pcm);
                    ob.clear();
                    byte[] conv = convert(pcm, rate, channels);
                    fed += conv.length / 2;
                    transcriber.feedBlocking(conv);
                    tick.at(fed * 1000L / RATE);
                    // Not ahead of the chosen speed (the recogniser's pipe also holds the feed back when it falls behind).
                    long due = t0 + fed * 1_000_000_000L / RATE / speed;
                    long wait = (due - System.nanoTime()) / 1_000_000L;
                    if (wait > 0) Thread.sleep(wait);
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
        } catch (java.io.IOException | RuntimeException | InterruptedException e) {
            main.post(() -> progress.update("Could not read part of the recording: " + e.getMessage(), false));
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                    // Never started.
                }
                codec.release();
            }
            ex.release();
        }
    }

    /** 16-bit PCM at any rate and channel count to 24 kHz mono (channels averaged, rate by linear steps). */
    static byte[] convert(byte[] pcm, int rate, int channels) {
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int frames = pcm.length / 2 / Math.max(1, channels);
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += in.getShort((i * channels + c) * 2);
            mono[i] = (short) (sum / Math.max(1, channels));
        }
        int outFrames = (int) ((long) frames * RATE / Math.max(1, rate));
        ByteBuffer out = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < outFrames; i++) {
            double src = i * (double) rate / RATE;
            int a = (int) src;
            int b = Math.min(frames - 1, a + 1);
            double f = src - a;
            out.putShort((short) Math.round(mono[Math.min(frames - 1, a)] * (1 - f) + mono[b] * f));
        }
        return out.array();
    }
}
