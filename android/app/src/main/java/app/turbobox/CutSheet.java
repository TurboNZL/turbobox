package app.turbobox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helps a free-plan chat AI cut a recording down: builds one plain-text sheet (instructions, the captions with their times, and the stretches
 * when the person was watching the live recording) to paste into the chat, and reads the AI's answer (the parts to keep, as time ranges) back.
 * No Android classes, so it is tested on the JVM.
 */
final class CutSheet {
    private CutSheet() {
    }

    /** 75000 -> "1:15"; 3723000 -> "1:02:03". */
    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /**
     * @param watching {from, to} in ms from the start of the recording; to <= from means "until the end"
     */
    static String build(String name, long totalMs, List<Vtt.Cue> cues, List<long[]> watching) {
        StringBuilder sb = new StringBuilder();
        sb.append("I have a recording called \"").append(name.isEmpty() ? "Recording" : name).append("\" that is ").append(clock(totalMs))
                .append(" long. Below is its transcript with times (m:ss or h:mm:ss from the start).\n");
        sb.append("Please decide which parts are worth keeping and which can be cut: small talk, silence, repetition, tangents, anything unrelated to the explanation.\n");
        sb.append("Lines marked >>> WATCHING <<< are stretches when I was only looking at the live recording myself. Cut them unless the words around them show they matter.\n");
        sb.append("Reply with ONLY the parts to KEEP, one per line, as `start - end` (for example `0:00 - 4:35`), in order, and nothing else.\n\n");
        List<Object[]> items = new ArrayList<>();
        for (Vtt.Cue c : cues) items.add(new Object[]{c.startMs, "[" + clock(c.startMs) + "] " + c.text});
        for (long[] w : watching) {
            long to = w[1] > w[0] ? w[1] : totalMs;
            items.add(new Object[]{w[0], "[" + clock(w[0]) + "] >>> WATCHING from " + clock(w[0]) + " to " + clock(to) + " <<<"});
        }
        Collections.sort(items, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Long.compare((Long) a[0], (Long) b[0]);
            }
        });
        if (items.isEmpty()) sb.append("(No captions were made for this recording.)\n");
        for (Object[] it : items) sb.append(it[1]).append('\n');
        return sb.toString();
    }

    private static final String T = "(\\d+(?::\\d{1,2}){1,2})(?:\\.\\d+)?";
    private static final Pattern RANGE = Pattern.compile(T + "\\s*(?:-->|-|\u2013|\u2014|\u2192|to|until)\\s*" + T);

    static long parseClock(String s) {
        String[] p = s.split(":");
        long v = 0;
        for (String x : p) v = v * 60 + Long.parseLong(x);
        return v * 1000;
    }

    /** The ranges (ms) in an AI's reply, sorted and with overlaps joined; lines that are not ranges are ignored. */
    static List<long[]> parseRanges(String reply) {
        List<long[]> found = new ArrayList<>();
        Matcher m = RANGE.matcher(reply == null ? "" : reply);
        while (m.find()) {
            long a = parseClock(m.group(1)), b = parseClock(m.group(2));
            if (b > a) found.add(new long[]{a, b});
        }
        Collections.sort(found, new Comparator<long[]>() {
            @Override
            public int compare(long[] x, long[] y) {
                return Long.compare(x[0], y[0]);
            }
        });
        List<long[]> out = new ArrayList<>();
        for (long[] r : found) {
            long[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && r[0] <= last[1]) last[1] = Math.max(last[1], r[1]);
            else out.add(new long[]{r[0], r[1]});
        }
        return out;
    }
}
