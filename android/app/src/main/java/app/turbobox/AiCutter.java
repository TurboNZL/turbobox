package app.turbobox;

import android.content.Context;

import java.io.IOException;
import java.util.List;

/**
 * Asks Gemini which stretches of a recording to keep, a few big parts at a time (the model takes a whole hour or more in one go), and saves the
 * answers as it goes. A part that fails or is not understood stays unanswered and so is kept whole.
 */
final class AiCutter {
    private AiCutter() {
    }

    /** About 25 thousand words, an hour and a half or more of talk, well inside Gemini's input. */
    static final int GEMINI_PART_WORDS = 25000;

    interface Listener {
        void progress(String text);   // any thread

        void done(String problem);    // any thread; null when every part was answered
    }

    static void run(Context c, BufferStore.Run r, List<CutSheet.Part> parts, List<CutAnswers.Window> answers, Listener l) {
        final Context app = c.getApplicationContext();
        final AiKeys.Key key = AiKeys.current(app);
        final String model = AiKeys.model(app);
        new Thread(() -> {
            String problem = null;
            if (key == null) {
                l.done("No Gemini key is saved (Settings > AI for cutting).");
                return;
            }
            for (CutSheet.Part part : parts) {
                l.progress("Asking Gemini" + (parts.size() > 1 ? " about part " + (part.index + 1) + " of " + parts.size() : "") + "...");
                String reply = null;
                for (int attempt = 0; attempt < 3 && reply == null; attempt++) {
                    try {
                        reply = GeminiClient.generate(key.key, model, part.text);
                    } catch (GeminiClient.ApiException e) {
                        if (e.code == 429 && attempt < 2) {
                            l.progress("Gemini's free limit was hit; waiting a minute...");
                            if (!sleep(62_000)) return;
                        } else {
                            problem = e.getMessage();
                            break;
                        }
                    } catch (IOException e) {
                        if (attempt == 2) problem = "No answer from Gemini (" + (e.getMessage() == null ? "network" : e.getMessage()) + ").";
                        else if (!sleep(3_000)) return;
                    }
                }
                if (reply == null) break;   // stop at the first problem; what was answered stays saved
                List<long[]> keep = CutSheet.clamp(CutSheet.parseRanges(reply), part.startMs, part.endMs);
                if (keep.isEmpty() && !CutSheet.saysNone(reply)) {
                    problem = "Gemini's answer for part " + (part.index + 1) + " had no time ranges, so that part is kept whole.";
                    continue;
                }
                synchronized (answers) {
                    CutAnswers.add(answers, new CutAnswers.Window(part.startMs, part.endMs, keep));
                    CutAnswersStore.save(r, answers);
                }
            }
            l.done(problem);
        }, "ai-cut").start();
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
