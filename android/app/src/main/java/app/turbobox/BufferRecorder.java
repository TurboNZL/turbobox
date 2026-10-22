package app.turbobox;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.View;
import android.view.Window;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * The recording buffer of one session: what the page shows (a picture of the page's area of the window a few
 * times a second) and the sound it plays (PCM from the page script), encoded to H.264 + AAC and cut into 30
 * second MP4 segments in the app's cache folder. Only the newest N minutes are kept ({@link BufferStore#trim}),
 * so it is a rolling window you can play back later (see {@link MissedActivity}).
 *
 * The picture comes from {@link PixelCopy} of the visible activity window, so it needs the screen on and the app in
 * front. While it is not (screen off, another app), the last picture is repeated and the sound keeps recording,
 * so the buffer has no hole. The recorder never touches the microphone: it records what the page plays, not you.
 * Segment files are cut at key frames, so each one plays on its own.
 */
final class BufferRecorder {
    interface Listener {
        void onError(String message);
    }

    /** Picture widths and frame rates the settings offer. They are chosen separately. */
    static final int[] WIDTHS = {480, 640, 960, 1280, 1920};
    static final int[] FPS = {1, 2, 4, 6, 10, 15, 24, 30};
    private static final int[] LEGACY_WIDTH = {640, 960, 1280};   // the old single "quality" setting
    private static final int[] LEGACY_FPS = {4, 6, 10};

    static int width(Context c) {
        int w = Prefs.bufferWidth(c);
        return w > 0 ? w : LEGACY_WIDTH[Math.max(0, Math.min(2, Prefs.bufferQuality(c)))];
    }

    static int fps(Context c) {
        int f = Prefs.bufferFps(c);
        return f > 0 ? f : LEGACY_FPS[Math.max(0, Math.min(2, Prefs.bufferQuality(c)))];
    }

    /** About 0.2 bits per pixel per picture suits screens (slides, text); clamped to what encoders accept. */
    static int bitrate(int width, int height, int fps) {
        return (int) Math.max(150_000, Math.min(8_000_000, width * (double) height * fps * 0.2));
    }

    static String widthLabel(int w) {
        return w + " px wide";
    }

    static String fpsLabel(int f) {
        return f == 1 ? "1 picture a second" : f + " pictures a second";
    }

    static File root(Context c) {
        return new File(c.getCacheDir(), "buffer");
    }

    private static final int AUDIO_RATE = 24000;
    private static final long SEGMENT_US = 30_000_000L;
    private static final int AUDIO_CHUNK_BYTES = 4096;
    private static final long SILENCE_AFTER_US = 100_000;
    private static final long MAX_AHEAD_US = 1_000_000;

    private final Context app;
    private final int slot;
    private final String name;
    private final Listener listener;
    private final int fps;
    private final int bitrate;
    private final boolean stopAtLimit;
    private final long maxMs;
    private final int width;
    private final int height;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<byte[]> audioQ = new ArrayBlockingQueue<>(600);

    private BufferStore.Run run;
    private volatile boolean running;
    private volatile boolean flushRequested;
    private volatile boolean limitReached;
    private volatile String lastError = "";
    private Transcriber transcriber;
    private Thread worker;
    private HandlerThread frameThread;
    private Handler frameHandler;

    private MediaCodec venc;
    private MediaCodec aenc;
    private Surface surface;

    private volatile Window window;
    private volatile View view;
    private final Bitmap[] bitmaps = new Bitmap[2];
    private int nextBitmap;
    private boolean copying;
    private Bitmap lastGood;           // only touched on the frame thread
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // encode loop state (worker thread only)
    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private MediaMuxer mux;
    private int videoTrack;
    private int audioTrack;
    private long segBaseUs;
    private long segWallMs;
    private long lastVideoUs;
    private File segFile;
    private long audioOriginUs;
    private long audioSamples;
    private byte[] audioLeft;
    private int audioLeftPos;
    private long lastSyncRequest;

    BufferRecorder(Context app, int slot, String name, int sourceWidth, int sourceHeight, Listener listener) {
        this.app = app.getApplicationContext();
        this.slot = slot;
        this.name = name;
        this.listener = listener;
        this.fps = fps(app);
        this.stopAtLimit = Prefs.bufferStopAtLimit(app);
        this.maxMs = Prefs.bufferMinutes(app) * 60_000L;
        double scale = Math.min(1.0, width(app) / (double) Math.max(1, sourceWidth));
        this.width = round16(sourceWidth * scale);
        this.height = round16(sourceHeight * scale);
        this.bitrate = bitrate(this.width, this.height, this.fps);
    }

    private static int round16(double v) {
        return Math.max(16, ((int) Math.round(v) + 8) / 16 * 16);
    }

    boolean isRecording() {
        return running;
    }

    String lastError() {
        return lastError;
    }

    File runDir() {
        return run == null ? null : run.dir;
    }

    long startedAt() {
        return run == null ? 0 : run.startedAt;
    }

    /** The picture source: the visible activity's window and the page view in it. Either may be null. */
    void setSource(Window w, View v) {
        window = w;
        view = v;
    }

    /** Starts recording. False (see {@link #lastError}) when the phone could not open its encoders. */
    boolean start() {
        File root = root(app);
        root.mkdirs();
        run = BufferStore.newRun(root, slot, name, System.currentTimeMillis());
        BufferStore.prune(root, Prefs.keepRuns(app), run.dir);
        BufferStore.removeStrays(root, 5 * 60_000L, System.currentTimeMillis());
        try {
            MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            vf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = venc.createInputSurface();
            venc.start();

            MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, 48_000);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            aenc.start();
        } catch (IOException | RuntimeException e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            releaseCodecs();
            BufferStore.deleteRun(run);
            run = null;
            return false;
        }
        running = true;
        frameThread = new HandlerThread("buffer-frames");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
        audioOriginUs = System.nanoTime() / 1000;
        worker = new Thread(this::loop, "buffer-encode");
        worker.start();
        main.postDelayed(tick, 300); // the audio side gets going first, so the first segment can start at once
        return true;
    }

    /** Stops recording; the last segment is finished in the background. The recorded part stays for playback. */
    void stop() {
        running = false;
        main.removeCallbacks(tick);
        stopTranscribing();
    }

    // ---- captions ----

    boolean isTranscribing() {
        return transcriber != null && transcriber.isRunning();
    }

    /** Starts live captions into {@code transcript.vtt} in the run's folder. False (with a notice) when it cannot. */
    boolean startTranscribing(String language, Transcriber.Listener l) {
        if (!running || run == null) return false;
        if (isTranscribing()) return true;
        transcriber = new Transcriber(app, new File(run.dir, "transcript.vtt"), run.startedAt, language, l);
        if (transcriber.start()) return true;
        transcriber = null;
        return false;
    }

    void stopTranscribing() {
        Transcriber t = transcriber;
        transcriber = null;
        if (t != null) t.stop();
    }

    /** A bookmark at this moment. */
    void mark(String label) {
        if (run != null) BufferStore.addMark(run, System.currentTimeMillis(), label);
    }

    /** Finish the current segment at the next key frame instead of waiting for it to fill (so "latest" is playable). */
    void requestFlush() {
        flushRequested = true;
        requestSyncFrame();
    }

    /** 16-bit mono PCM at 24 kHz from the page; called on the main thread. */
    void feedAudio(byte[] pcm) {
        if (!running || pcm.length == 0) return;
        audioQ.offer(pcm);
        Transcriber t = transcriber;
        if (t != null) t.feed(pcm);
    }

    // ---- pictures ------------------------------------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            grab();
            main.postDelayed(this, 1000L / fps);
        }
    };

    private void grab() {
        if (copying) return;
        Window w = window;
        View v = view;
        boolean sized = v != null && v.getWidth() >= 8 && v.getHeight() >= 8;
        if (w == null || !sized || !v.isShown()) {
            // No screen shows the page (a workflow started it, the app is in the background): draw the view itself.
            if (sized) softFrame(v);
            else frameHandler.post(() -> draw(lastGood));
            return;
        }
        int[] at = new int[2];
        v.getLocationInWindow(at);
        Rect src = new Rect(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight());
        View decor = w.getDecorView();
        if (!src.intersect(0, 0, decor.getWidth(), decor.getHeight()) || src.width() < 8 || src.height() < 8) {
            softFrame(v);
            return;
        }
        Bitmap target = bitmapFor(src.width(), src.height());
        final Bitmap copy = target;
        final int used = nextBitmap;
        copying = true;
        try {
            PixelCopy.request(w, src, copy, result -> {
                // On the frame thread. The other bitmap is the one the next copy fills, so lastGood is never
                // overwritten while it is drawn.
                if (result == PixelCopy.SUCCESS) {
                    lastGood = copy;
                    nextBitmap = used ^ 1;
                }
                draw(lastGood);
                main.post(() -> copying = false);
            }, frameHandler);
        } catch (IllegalArgumentException e) {
            // No surface behind the window right now (screen off, window going away): draw the view itself.
            copying = false;
            softFrame(v);
        }
    }

    /** The bitmap the next picture goes into, sized to the encoder's width (never larger than the source). */
    private Bitmap bitmapFor(int srcW, int srcH) {
        double scale = Math.min(1.0, width / (double) srcW);
        int bw = Math.max(16, (int) Math.round(srcW * scale));
        int bh = Math.max(16, (int) Math.round(srcH * scale));
        Bitmap target = bitmaps[nextBitmap];
        if (target == null || target.getWidth() != bw || target.getHeight() != bh) {
            target = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            bitmaps[nextBitmap] = target;
        }
        return target;
    }

    /**
     * A picture made by drawing the view onto a bitmap, for when no window can be copied. It cannot show video
     * the browser puts on its own layer (that part stays black), but slides, pages and the call's controls come out.
     */
    private void softFrame(View v) {
        Bitmap target = bitmapFor(v.getWidth(), v.getHeight());
        try {
            Canvas c = new Canvas(target);
            c.drawColor(Color.BLACK);
            c.scale(target.getWidth() / (float) v.getWidth(), target.getHeight() / (float) v.getHeight());
            v.draw(c);
        } catch (RuntimeException e) {
            frameHandler.post(() -> draw(lastGood));
            return;
        }
        final Bitmap done = target;
        nextBitmap ^= 1;
        frameHandler.post(() -> {
            lastGood = done;
            draw(done);
        });
    }

    /** Draws a picture (or black) onto the encoder's input, fitted into its fixed size. Frame thread only. */
    private void draw(Bitmap bmp) {
        Surface s = surface;
        if (s == null || !running) return;
        Canvas c = null;
        try {
            c = s.lockCanvas(null);
            c.drawColor(Color.BLACK);
            if (bmp != null) {
                float k = Math.min(width / (float) bmp.getWidth(), height / (float) bmp.getHeight());
                float dw = bmp.getWidth() * k, dh = bmp.getHeight() * k;
                float l = (width - dw) / 2f, t = (height - dh) / 2f;
                c.drawBitmap(bmp, null, new RectF(l, t, l + dw, t + dh), paint);
            }
        } catch (IllegalStateException | IllegalArgumentException | Surface.OutOfResourcesException e) {
            return;
        } finally {
            if (c != null) {
                try {
                    s.unlockCanvasAndPost(c);
                } catch (IllegalStateException | IllegalArgumentException ignored) {
                    // The encoder is going away.
                }
            }
        }
    }

    // ---- the encode loop -----------------------------------------------------------------------

    private void loop() {
        MediaCodec.BufferInfo vi = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
        try {
            while (running) {
                feedAudio();
                boolean busy = drainVideo(vi);
                busy |= drainAudio(ai);
                if (videoFormat != null && audioFormat != null && mux == null) requestSyncFrame();
                if (!busy) {
                    try {
                        Thread.sleep(8);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            running = false;
            listener.onError("Recording stopped: " + lastError);
        } finally {
            finishSegment();
            if (limitReached) {
                main.post(() -> {
                    main.removeCallbacks(tick);
                    stopTranscribing();
                    listener.onError("The recording reached its " + (maxMs / 60_000) + " minute limit and stopped.");
                });
            }
            frameThread.quitSafely();
            try {
                frameThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            releaseCodecs();
        }
    }

    private long audioPositionUs() {
        return audioOriginUs + audioSamples * 1_000_000L / AUDIO_RATE;
    }

    /**
     * Feeds the AAC encoder from the page's PCM. When the page sends nothing (a silent lecture, a throttled
     * page) silence is fed instead, by the clock, so the audio never falls behind the pictures; when the page's
     * sound arrives in a burst far ahead of the clock it is dropped, so the two never drift apart.
     */
    private void feedAudio() {
        for (int guard = 0; guard < 8; guard++) {
            long nowUs = System.nanoTime() / 1000;
            boolean data = (audioLeft != null && audioLeftPos < audioLeft.length) || !audioQ.isEmpty();
            long lagUs = nowUs - audioPositionUs();
            if (data && -lagUs > MAX_AHEAD_US) {
                audioQ.clear();
                audioLeft = null;
                data = false;
            }
            if (!data && lagUs < SILENCE_AFTER_US) return;
            int idx = aenc.dequeueInputBuffer(0);
            if (idx < 0) return;
            ByteBuffer in = aenc.getInputBuffer(idx);
            in.clear();
            int want = Math.min(in.capacity(), AUDIO_CHUNK_BYTES);
            int filled = 0;
            while (filled < want) {
                if (audioLeft == null || audioLeftPos >= audioLeft.length) {
                    audioLeft = audioQ.poll();
                    audioLeftPos = 0;
                    if (audioLeft == null) break;
                }
                int n = Math.min(want - filled, audioLeft.length - audioLeftPos);
                in.put(audioLeft, audioLeftPos, n);
                audioLeftPos += n;
                filled += n;
            }
            if (filled == 0) {
                int silent = (int) Math.min(want, Math.max(2, lagUs * AUDIO_RATE / 1_000_000L * 2));
                filled = silent & ~1;
                for (int i = 0; i < filled; i++) in.put((byte) 0);
            }
            filled &= ~1; // whole samples only
            aenc.queueInputBuffer(idx, 0, filled, audioPositionUs(), 0);
            audioSamples += filled / 2;
        }
    }

    private boolean drainVideo(MediaCodec.BufferInfo info) throws IOException {
        int oi = venc.dequeueOutputBuffer(info, 0);
        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            videoFormat = venc.getOutputFormat();
            return true;
        }
        if (oi < 0) return false;
        try {
            ByteBuffer buf = venc.getOutputBuffer(oi);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (!config && info.size > 0 && buf != null) {
                boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                if (key && shouldCut(info.presentationTimeUs)) {
                    finishSegment();
                    if (videoFormat != null && audioFormat != null) startSegment(info.presentationTimeUs);
                }
                if (mux != null) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    MediaCodec.BufferInfo out = new MediaCodec.BufferInfo();
                    out.set(info.offset, info.size, info.presentationTimeUs - segBaseUs, info.flags);
                    mux.writeSampleData(videoTrack, buf, out);
                    lastVideoUs = info.presentationTimeUs;
                }
            }
        } finally {
            venc.releaseOutputBuffer(oi, false);
        }
        return true;
    }

    private boolean drainAudio(MediaCodec.BufferInfo info) {
        int oa = aenc.dequeueOutputBuffer(info, 0);
        if (oa == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            audioFormat = aenc.getOutputFormat();
            return true;
        }
        if (oa < 0) return false;
        try {
            ByteBuffer buf = aenc.getOutputBuffer(oa);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            long pts = info.presentationTimeUs - segBaseUs;
            if (!config && info.size > 0 && buf != null && mux != null && pts >= 0) {
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                MediaCodec.BufferInfo out = new MediaCodec.BufferInfo();
                out.set(info.offset, info.size, pts, info.flags);
                mux.writeSampleData(audioTrack, buf, out);
            }
        } finally {
            aenc.releaseOutputBuffer(oa, false);
        }
        return true;
    }

    /** A key frame starts a new segment when none is open, when the open one is full, or when a flush was asked for. */
    private boolean shouldCut(long keyUs) {
        if (mux == null) return true;
        long len = keyUs - segBaseUs;
        return len >= SEGMENT_US || (flushRequested && len >= 2_000_000L);
    }

    private void startSegment(long baseUs) throws IOException {
        segBaseUs = baseUs;
        lastVideoUs = baseUs;
        segWallMs = System.currentTimeMillis();
        segFile = BufferStore.partFile(run, segWallMs);
        mux = new MediaMuxer(segFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        videoTrack = mux.addTrack(videoFormat);
        audioTrack = mux.addTrack(audioFormat);
        mux.start();
        flushRequested = false;
    }

    private void finishSegment() {
        if (mux == null) return;
        MediaMuxer m = mux;
        mux = null;
        boolean ok = true;
        try {
            m.stop();
        } catch (RuntimeException e) {
            ok = false; // nothing was written
        }
        try {
            m.release();
        } catch (RuntimeException ignored) {
            // Already released.
        }
        long durMs = Math.max(1, (lastVideoUs - segBaseUs) / 1000 + 1000L / fps);
        if (ok && BufferStore.finishSegment(segFile, run, segWallMs, durMs) != null) {
            if (stopAtLimit) {
                if (running && BufferStore.totalMs(BufferStore.segments(run)) >= maxMs) {
                    running = false;
                    limitReached = true;
                }
            } else {
                BufferStore.trim(run, maxMs);
            }
        } else {
            segFile.delete();
        }
    }

    private void requestSyncFrame() {
        long now = System.nanoTime() / 1_000_000;
        if (now - lastSyncRequest < 500 || venc == null) return;
        lastSyncRequest = now;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            venc.setParameters(b);
        } catch (RuntimeException ignored) {
            // The encoder is stopping.
        }
    }

    private void releaseCodecs() {
        MediaCodec v = venc, a = aenc;
        venc = null;
        aenc = null;
        if (v != null) {
            try {
                v.stop();
            } catch (RuntimeException ignored) {
                // Never started.
            }
            v.release();
        }
        if (a != null) {
            try {
                a.stop();
            } catch (RuntimeException ignored) {
                // Never started.
            }
            a.release();
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }
}
