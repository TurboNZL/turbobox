package app.turbobox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The prompts that turn a recording's captions into a recap (a title, "important stuff" at the top, chapters, notes, and the stretches worth
 * watching as a short video), and the reading of the finished recap. No Android classes, so it is tested on the JVM.
 */
final class RecapText {
    private RecapText() {
    }

    static String notesPrompt(String name, long totalMs, long fromMs, long toMs, String body) {
        return "You are taking notes on one part of a recording called \"" + (name.isEmpty() ? "Recording" : name) + "\" (a talk, video or meeting), "
                + "from its transcript. Times are m:ss or h:mm:ss from the start of the whole recording, which is " + CutSheet.clock(totalMs) + " long. "
                + "This part covers " + CutSheet.clock(fromMs) + " to " + CutSheet.clock(toMs) + ".\n"
                + "Write short notes as lines like `- [12:30] the point`. Put `!` right after the time on anything that really matters: deadlines, assignments, "
                + "tests or exam hints, announcements, instructions, things the speaker says to remember or do, questions put to the listeners. "
                + "Skip small talk, filler, jokes and repetition. Lines marked >>> WATCHING <<< are when the listener was awake and watching: ignore them. "
                + "Use only what the transcript says. End with one line `TOPIC: <3 to 8 words>`. Output only the notes.\n\n" + body;
    }

    static String condensePrompt(String notes) {
        return "These are notes from parts of a recording. Merge and shorten them to about a third of the length. Keep every time in square brackets, "
                + "every `!` mark, and everything that matters (deadlines, assignments, exam hints, announcements). Drop repetition and detail. "
                + "Keep the same line format `- [12:30] ...` and keep any `TOPIC:` lines. Output only the notes.\n\n" + notes;
    }

    static String finalPrompt(String name, long totalMs, String notes) {
        return "Below are notes taken from a recording called \"" + (name.isEmpty() ? "Recording" : name) + "\", " + CutSheet.clock(totalMs) + " long. "
                + "Times are m:ss or h:mm:ss from the start. From the notes write a recap for someone who slept through it and wants to miss as little as possible, "
                + "in exactly this layout and nothing else:\n"
                + "TITLE: <a clear title, at most 10 words>\n"
                + "## Important stuff (TL;DR)\n"
                + "- [time] the things they must not miss, most important first; deadlines, assignments, exam or test hints and announcements come first. 3 to 8 lines.\n"
                + "## Chapters\n"
                + "- [time] Chapter title: one line on what it covers\n"
                + "## Worth watching\n"
                + "- start - end | why this stretch is worth watching (the stretches to put in a short video, in order, together at most a fifth of the recording; "
                + "times as m:ss or h:mm:ss)\n"
                + "## Notes\n"
                + "- [time] fuller notes, grouped under the chapters\n"
                + "Use only what is in the notes; invent nothing. If something is unclear, leave it out.\n\n" + notes;
    }

    /** The recap's title (the TITLE: line), or "". */
    static String title(String md) {
        for (String line : md.split("\n")) {
            String t = line.trim();
            if (t.regionMatches(true, 0, "TITLE:", 0, 6)) return t.substring(6).replace("*", "").trim();
        }
        return "";
    }

    /** The text under a "## " heading that starts with {@code heading}, or "". */
    static String section(String md, String heading) {
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String line : md.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#")) {
                in = t.replaceFirst("^#+\\s*", "").toLowerCase(Locale.US).startsWith(heading.toLowerCase(Locale.US));
                continue;
            }
            if (in) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** The stretches in "Worth watching", in ms from the start of the recording. */
    static List<long[]> worthWatching(String md) {
        List<long[]> out = new ArrayList<>();
        for (String line : section(md, "Worth watching").split("\n")) {
            int bar = line.indexOf('|');
            out.addAll(CutSheet.parseRanges(bar >= 0 ? line.substring(0, bar) : line));
        }
        return CutSheet.merge(out);
    }
}
