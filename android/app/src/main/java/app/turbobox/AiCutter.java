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

    interface Listener {
        void progress(String text);   // any thread

        void done(String problem);    // any thread; null when every part was answered
    }

    static void run(Context c, BufferStore.Run r, List<CutSheet.Part> parts, List<CutAnswers.Window> answers, Listener l) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String problem = null;
            for (CutSheet.Part part : parts) {
                l.progress("Asking the AI" + (parts.size() > 1 ? " about part " + (part.index + 1) + " of " + parts.size() : "") + "...");
                String reply;
                try {
                    reply = Ai.any(app, part.text, l::progress);
                } catch (IOException e) {
                    problem = e.getMessage();
                    break;   // stop at the first problem; what was answered stays saved
                }
                List<long[]> keep = CutSheet.clamp(CutSheet.parseRanges(reply), part.startMs, part.endMs);
                if (keep.isEmpty() && !CutSheet.saysNone(reply)) {
                    problem = "The AI's answer for part " + (part.index + 1) + " had no time ranges, so that part is kept whole.";
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
}
