package app.turbobox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What the AI (or a person pasting its reply) said about stretches of a recording: for each window of the recording, the parts to keep.
 * A stretch nobody answered for is kept whole, so a failed or skipped request never loses footage. Windows can have any size (a chat part, or
 * a whole 4 hour recording in one request), so everything is by time, not by part number.
 */
final class CutAnswers {
    private CutAnswers() {
    }

    static final class Window {
        final long from;
        final long to;
        final List<long[]> keep;

        Window(long from, long to, List<long[]> keep) {
            this.from = from;
            this.to = to;
            this.keep = keep;
        }
    }

    /** Adds an answer, replacing any earlier answers that overlap its window. */
    static void add(List<Window> list, Window w) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Window o = list.get(i);
            if (o.from < w.to && o.to > w.from) list.remove(i);
        }
        list.add(w);
    }

    /** The answer covering the whole of [from, to), or null. */
    static Window covering(List<Window> list, long from, long to) {
        for (Window w : list) if (w.from <= from && w.to >= to) return w;
        return null;
    }

    /** Everything to keep: all kept parts of the answers, plus every stretch of [0, total) that no answer covers. */
    static List<long[]> finalKeep(List<Window> list, long total) {
        List<long[]> keep = new ArrayList<>();
        List<long[]> windows = new ArrayList<>();
        for (Window w : list) {
            keep.addAll(CutSheet.clamp(w.keep, w.from, w.to));
            windows.add(new long[]{w.from, w.to});
        }
        long at = 0;
        for (long[] w : CutSheet.merge(windows)) {
            if (w[0] > at) keep.add(new long[]{at, w[0]});
            at = Math.max(at, w[1]);
        }
        if (at < total) keep.add(new long[]{at, total});
        return CutSheet.merge(keep);
    }

    static long totalMs(List<long[]> ranges) {
        long t = 0;
        for (long[] r : ranges) t += r[1] - r[0];
        return t;
    }

    static String toJson(List<Window> list) throws JSONException {
        JSONArray a = new JSONArray();
        for (Window w : list) {
            JSONArray keep = new JSONArray();
            for (long[] k : w.keep) keep.put(new JSONArray().put(k[0]).put(k[1]));
            a.put(new JSONObject().put("from", w.from).put("to", w.to).put("keep", keep));
        }
        return a.toString();
    }

    static List<Window> fromJson(String text) {
        List<Window> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(text);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                List<long[]> keep = new ArrayList<>();
                JSONArray k = o.getJSONArray("keep");
                for (int j = 0; j < k.length(); j++) keep.add(new long[]{k.getJSONArray(j).getLong(0), k.getJSONArray(j).getLong(1)});
                out.add(new Window(o.getLong("from"), o.getLong("to"), keep));
            }
        } catch (JSONException e) {
            // Start over (an older file, or none).
        }
        return out;
    }
}
