package app.turbobox;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Live speech to text for a recording: the page's sound (the same 24 kHz mono PCM the recorder gets) is fed to Android's
 * speech recognizer through a pipe, and every recognised phrase is appended to a WebVTT file with its time from the
 * start of the recording. Needs Android 13 (the recognizer's audio-source extras). The recognizer is restarted after each
 * phrase and after silence; sound that arrives while it restarts waits in a queue, so nothing is skipped.
 *
 * Times are approximate: a cue starts when the recognizer reports speech starting and ends when it delivers the phrase.
 * Main thread, except the writer thread that moves PCM into the pipe.
 */
final class Transcriber {
    interface Listener {
        void onNotice(String text);
    }

    private static final int RATE = 24000;
    private static final int MAX_FAILURES = 6;

    private final Context app;
    private final File vttFile;
    private final long originWallMs;
    private final String language;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(2000);
    private SpeechRecognizer recognizer;
    private volatile boolean running;
    private volatile OutputStream pipeOut;
    private ParcelFileDescriptor pipeIn;
    private Thread writer;
    private long speechStartMs = -1;
    private int failures;
    private long lastFailureAt;
    private int cues;

    Transcriber(Context app, File vttFile, long originWallMs, String language, Listener listener) {
        this.app = app.getApplicationContext();
        this.vttFile = vttFile;
        this.originWallMs = originWallMs;
        this.language = language;
        this.listener = listener;
    }

    boolean isRunning() {
        return running;
    }

    /** Why transcription cannot work here, or null when it can. */
    static String unavailableReason(Context c) {
        if (Build.VERSION.SDK_INT < 33) return "Live transcription needs Android 13 or newer.";
        if (!SpeechRecognizer.isRecognitionAvailable(c)) return "This phone has no speech recognition service (install or enable Google's speech services).";
        return null;
    }

    boolean start() {
        String why = unavailableReason(app);
        if (why != null) {
            listener.onNotice(why);
            return false;
        }
        try {
            if (!vttFile.exists()) {
                try (FileOutputStream o = new FileOutputStream(vttFile)) {
                    o.write(Vtt.HEADER.getBytes("UTF-8"));
                }
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(app);
            recognizer.setRecognitionListener(new Callbacks());
        } catch (IOException | RuntimeException e) {
            listener.onNotice("Could not start transcription: " + e.getMessage());
            return false;
        }
        running = true;
        writer = new Thread(this::pump, "transcribe-pipe");
        writer.start();
        beginSession();
        return true;
    }

    void stop() {
        if (!running) return;
        running = false;
        main.removeCallbacksAndMessages(null);
        closePipe();
        try {
            if (recognizer != null) {
                recognizer.stopListening();
                final SpeechRecognizer r = recognizer;
                main.postDelayed(r::destroy, 3000); // let the last phrase come in
            }
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        recognizer = null;
    }

    /** 24 kHz mono 16-bit PCM from the page. */
    void feed(byte[] pcm) {
        if (running) queue.offer(pcm);
    }

    // ---- one recognition session ---------------------------------------------------------------

    private void beginSession() {
        if (!running || recognizer == null) return;
        try {
            closePipe();
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            pipeIn = pipe[0];
            pipeOut = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.getPackageName())
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipeIn)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE);
            if (!language.isEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
            speechStartMs = -1;
            recognizer.startListening(i);
        } catch (IOException | RuntimeException e) {
            fail("Transcription problem: " + e.getMessage());
        }
    }

    private void closePipe() {
        OutputStream o = pipeOut;
        pipeOut = null;
        if (o != null) {
            try {
                o.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
        ParcelFileDescriptor in = pipeIn;
        pipeIn = null;
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    /** Moves queued PCM into the current pipe; when there is none (a restart is under way) it waits. */
    private void pump() {
        while (running) {
            byte[] chunk;
            try {
                chunk = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (chunk == null) continue;
            while (running) {
                OutputStream o = pipeOut;
                if (o == null) {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                try {
                    o.write(chunk);
                    break;
                } catch (IOException e) {
                    // The recognizer closed its end: a new session will open a new pipe. Keep the chunk for it.
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }
    }

    private void fail(String text) {
        long now = System.currentTimeMillis();
        failures = now - lastFailureAt < 60_000 ? failures + 1 : 1;
        lastFailureAt = now;
        if (failures >= MAX_FAILURES) {
            listener.onNotice(text + " Transcription stopped.");
            stop();
        } else {
            main.postDelayed(this::beginSession, 1000);
        }
    }

    private void addCue(String text) {
        if (text == null || text.trim().isEmpty()) return;
        long now = System.currentTimeMillis() - originWallMs;
        long start = speechStartMs >= 0 ? speechStartMs : Math.max(0, now - 2000);
        speechStartMs = -1;
        failures = 0;
        try (FileOutputStream o = new FileOutputStream(vttFile, true)) {
            o.write(Vtt.format(new Vtt.Cue(start, now, text.trim())).getBytes("UTF-8"));
            cues++;
        } catch (IOException e) {
            listener.onNotice("Could not save the captions: " + e.getMessage());
        }
    }

    private final class Callbacks implements RecognitionListener {
        private String partial = "";

        @Override
        public void onBeginningOfSpeech() {
            speechStartMs = System.currentTimeMillis() - originWallMs;
        }

        @Override
        public void onPartialResults(Bundle b) {
            ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l != null && !l.isEmpty()) {
                partial = l.get(0);
                if (speechStartMs < 0) speechStartMs = System.currentTimeMillis() - originWallMs;
            }
        }

        @Override
        public void onResults(Bundle b) {
            ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            addCue(l != null && !l.isEmpty() ? l.get(0) : partial);
            partial = "";
            main.post(Transcriber.this::beginSession);
        }

        @Override
        public void onError(int error) {
            // Silence and "heard nothing" are normal between phrases; keep what was heard, then listen again.
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                addCue(partial);
                partial = "";
                main.post(Transcriber.this::beginSession);
            } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                listener.onNotice("Transcription needs the microphone permission (Android settings > Apps > TurboBox).");
                stop();
            } else {
                partial = "";
                fail("Speech recognition error " + error + ".");
            }
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    }
}
