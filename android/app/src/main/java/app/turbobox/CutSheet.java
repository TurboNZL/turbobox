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
        return merge(found);
    }

    /** Sorted, with overlapping and touching ranges joined. */
    static List<long[]> merge(List<long[]> found) {
        List<long[]> sorted = new ArrayList<>();
        for (long[] r : found) sorted.add(new long[]{r[0], r[1]});
        Collections.sort(sorted, new Comparator<long[]>() {
            @Override
            public int compare(long[] x, long[] y) {
                return Long.compare(x[0], y[0]);
            }
        });
        List<long[]> out = new ArrayList<>();
        for (long[] r : sorted) {
            long[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && r[0] <= last[1]) last[1] = Math.max(last[1], r[1]);
            else out.add(r);
        }
        return out;
    }

    /** Only the part of each range inside [from, to). */
    static List<long[]> clamp(List<long[]> ranges, long from, long to) {
        List<long[]> out = new ArrayList<>();
        for (long[] r : ranges) {
            long a = Math.max(r[0], from), b = Math.min(r[1], to);
            if (b > a) out.add(new long[]{a, b});
        }
        return out;
    }

    /** Is the reply an explicit "nothing here is worth keeping"? */
    static boolean saysNone(String reply) {
        return reply != null && reply.trim().matches("(?is).*\\bNONE\\b.*") && parseRanges(reply).isEmpty();
    }

    /** One chunk of a long recording, small enough for a free chat: its time window and the text to paste. */
    static final class Part {
        final int index;
        final int count;
        final long startMs;
        final long endMs;
        final String text;   // instructions for cutting + the transcript
        final String body;   // the transcript lines alone

        Part(int index, int count, long startMs, long endMs, String text, String body) {
            this.body = body;
            this.index = index;
            this.count = count;
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    static final int DEFAULT_PART_WORDS = 4500;

    private static int words(String s) {
        String t = s.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    /**
     * Splits a long recording (2 or 4 hours is tens of thousands of words) into parts of about {@code maxWords} words, each a sheet that stands
     * on its own: the AI needs no memory of the other parts, and only answers for the window its part covers. Captions are merged into lines
     * of about 20 seconds with one time each, which is much shorter than one line per phrase.
     */
    static List<Part> parts(String name, long totalMs, List<Vtt.Cue> cues, List<long[]> watching, int maxWords) {
        List<long[]> lineStart = new ArrayList<>();   // {start}
        List<String> lineText = new ArrayList<>();
        StringBuilder cur = null;
        long curStart = 0;
        for (Vtt.Cue c : cues) {
            if (cur == null || c.startMs - curStart >= 20_000) {
                if (cur != null) {
                    lineStart.add(new long[]{curStart});
                    lineText.add(cur.toString());
                }
                cur = new StringBuilder();
                curStart = c.startMs;
            } else {
                cur.append(' ');
            }
            cur.append(c.text.trim());
        }
        if (cur != null) {
            lineStart.add(new long[]{curStart});
            lineText.add(cur.toString());
        }
        // Cut the lines into parts by word count.
        List<int[]> spans = new ArrayList<>();   // {firstLine, endLineExclusive}
        int first = 0, w = 0;
        for (int i = 0; i < lineText.size(); i++) {
            w += words(lineText.get(i));
            if (w >= maxWords) {
                spans.add(new int[]{first, i + 1});
                first = i + 1;
                w = 0;
            }
        }
        if (first < lineText.size() || spans.isEmpty()) spans.add(new int[]{first, lineText.size()});
        List<Part> out = new ArrayList<>();
        int n = spans.size();
        for (int p = 0; p < n; p++) {
            long from = p == 0 ? 0 : lineStart.get(spans.get(p)[0])[0];
            long to = p == n - 1 ? Math.max(totalMs, from + 1) : lineStart.get(spans.get(p + 1)[0])[0];
            StringBuilder sb = new StringBuilder();
            sb.append("I have a recording called \"").append(name.isEmpty() ? "Recording" : name).append("\" that is ").append(clock(totalMs)).append(" long. ");
            if (n > 1) sb.append("This is part ").append(p + 1).append(" of ").append(n).append(", covering ").append(clock(from)).append(" to ").append(clock(to)).append(". ");
            sb.append("Below is its transcript with times (m:ss or h:mm:ss from the start of the whole recording).\n");
            sb.append("Decide which stretches of THIS part are worth keeping and which can be cut: small talk, silence, repetition, tangents, anything unrelated to the explanation. When unsure, keep it.\n");
            sb.append("Lines marked >>> WATCHING <<< are stretches when I was only looking at the live recording myself. Cut them unless the words around them show they matter.\n");
            sb.append("Reply with ONLY the stretches to KEEP, one per line, as `start - end` (for example `").append(clock(from)).append(" - ").append(clock(Math.min(to, from + 270_000)))
                    .append("`), in order, all inside ").append(clock(from)).append(" to ").append(clock(to)).append(". If nothing in this part is worth keeping reply NONE. No other words.\n\n");
            List<Object[]> items = new ArrayList<>();
            for (int i = spans.get(p)[0]; i < spans.get(p)[1]; i++) items.add(new Object[]{lineStart.get(i)[0], "[" + clock(lineStart.get(i)[0]) + "] " + lineText.get(i)});
            for (long[] wch : watching) {
                long wt = wch[1] > wch[0] ? wch[1] : totalMs;
                long a = Math.max(wch[0], from), b = Math.min(wt, to);
                if (b > a) items.add(new Object[]{a, "[" + clock(a) + "] >>> WATCHING from " + clock(a) + " to " + clock(b) + " <<<"});
            }
            Collections.sort(items, new Comparator<Object[]>() {
                @Override
                public int compare(Object[] x, Object[] y) {
                    return Long.compare((Long) x[0], (Long) y[0]);
                }
            });
            StringBuilder bd = new StringBuilder();
            if (items.isEmpty()) bd.append("(No captions were made for this recording.)\n");
            for (Object[] it : items) bd.append(it[1]).append('\n');
            sb.append(bd);
            out.add(new Part(p, n, from, to, sb.toString(), bd.toString()));
        }
        return out;
    }
}
