package app.turbobox;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes a recording's recap with an online AI: notes on each chunk of the captions (small enough for a free tier's per-minute limit), merged
 * if they are still long, then one final pass that writes the layout in {@link RecapText}. Saved as {@code recap.md} in the run. Keeps the
 * service up while it works, so it carries on with the screen off.
 */
final class DigestMaker {
    private DigestMaker() {
    }

    interface Listener {
        void progress(String text);   // any thread

        void done(String problem);    // any thread; null when the recap was saved
    }

    private static final AtomicInteger RUNNING = new AtomicInteger();

    static boolean busy() {
        return RUNNING.get() > 0;
    }

    static File file(BufferStore.Run r) {
        return new File(r.dir, "recap.md");
    }

    static String load(BufferStore.Run r) {
        File f = file(r);
        if (!f.exists()) return "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            for (int n; off < b.length && (n = in.read(b, off, b.length - off)) > 0; ) off += n;
            return new String(b, 0, off, "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static int words(String s) {
        String t = s.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    static void run(Context c, BufferStore.Run r, long total, List<Vtt.Cue> cues, List<long[]> watched, Listener l) {
        final Context app = c.getApplicationContext();
        if (cues.isEmpty()) {
            l.done("This recording has no captions to make a recap from.");
            return;
        }
        RUNNING.incrementAndGet();
        SessionService.ensure(app);
        new Thread(() -> {
            String problem = null;
            try {
                int words = Ai.partWords(app);
                List<CutSheet.Part> chunks = CutSheet.parts(r.name, total, cues, watched, words);
                List<String> notes = new ArrayList<>();
                for (CutSheet.Part part : chunks) {
                    l.progress("Reading part " + (part.index + 1) + " of " + chunks.size() + "...");
                    notes.add(Ai.any(app, RecapText.notesPrompt(r.name, total, part.startMs, part.endMs, part.body), l::progress).trim());
                }
                String joined = String.join("\n", notes);
                for (int round = 0; round < 3 && words(joined) > words * 1.3; round++) {
                    l.progress("Shortening the notes...");
                    List<String> merged = new ArrayList<>();
                    StringBuilder group = new StringBuilder();
                    for (String n : notes) {
                        if (group.length() > 0 && words(group.toString()) + words(n) > words) {
                            merged.add(Ai.any(app, RecapText.condensePrompt(group.toString()), l::progress).trim());
                            group.setLength(0);
                        }
                        group.append(n).append('\n');
                    }
                    if (group.length() > 0) merged.add(Ai.any(app, RecapText.condensePrompt(group.toString()), l::progress).trim());
                    notes = merged;
                    joined = String.join("\n", notes);
                }
                l.progress("Writing the recap...");
                String recap = Ai.any(app, RecapText.finalPrompt(r.name, total, joined), l::progress).trim();
                if (RecapText.title(recap).isEmpty() && !recap.contains("##")) throw new IOException("The AI's reply was not a recap.");
                try (FileOutputStream out = new FileOutputStream(file(r))) {
                    out.write(recap.getBytes("UTF-8"));
                }
            } catch (IOException e) {
                problem = e.getMessage() == null ? "The recap could not be made." : e.getMessage();
            } finally {
                RUNNING.decrementAndGet();
                SessionService.ensure(app);
            }
            l.done(problem);
        }, "ai-recap").start();
    }
}
