package app.turbobox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Where the recording buffer lives and how to read it back. A recording ("run") is a folder of short MP4 segments
 * named {@code seg_<startWallMs>_<durationMs>.mp4} (a segment still being written is {@code .part}), plus
 * {@code meta.json} and {@code marks.json}. The newest segments are kept, the oldest deleted, so a run is a
 * rolling window. No Android classes, so it is tested on the JVM.
 */
final class BufferStore {
    private BufferStore() {
    }

    static final class Segment {
        final File file;
        final long startWallMs;
        final long durationMs;

        Segment(File file, long startWallMs, long durationMs) {
            this.file = file;
            this.startWallMs = startWallMs;
            this.durationMs = durationMs;
        }
    }

    static final class Mark {
        final long wallMs;
        final String label;

        Mark(long wallMs, String label) {
            this.wallMs = wallMs;
            this.label = label;
        }
    }

    static final class Run {
        final File dir;
        final long startedAt;
        final int slot;
        final String name;

        Run(File dir, long startedAt, int slot, String name) {
            this.dir = dir;
            this.startedAt = startedAt;
            this.slot = slot;
            this.name = name;
        }
    }

    /** A point on the run's own timeline (gaps between segments are skipped): which segment, how far in. */
    static final class Position {
        final int index;
        final long offsetMs;

        Position(int index, long offsetMs) {
            this.index = index;
            this.offsetMs = offsetMs;
        }
    }

    // ---- runs ----

    static Run newRun(File root, int slot, String name, long now) {
        File dir = new File(root, "run_" + now + "_s" + slot);
        dir.mkdirs();
        try {
            writeText(new File(dir, "meta.json"),
                    new JSONObject().put("name", name).put("slot", slot).put("startedAt", now).toString());
        } catch (JSONException | IOException ignored) {
            // The folder name still carries the time and slot.
        }
        return new Run(dir, now, slot, name);
    }

    /** All runs, newest first. */
    static List<Run> listRuns(File root) {
        List<Run> runs = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) return runs;
        for (File d : dirs) {
            if (!d.isDirectory() || !d.getName().startsWith("run_")) continue;
            long started = 0;
            int slot = 0;
            String name = "";
            try {
                JSONObject m = new JSONObject(readText(new File(d, "meta.json")));
                started = m.optLong("startedAt");
                slot = m.optInt("slot");
                name = m.optString("name");
            } catch (JSONException | IOException e) {
                started = d.lastModified();
            }
            runs.add(new Run(d, started, slot, name));
        }
        Collections.sort(runs, new Comparator<Run>() {
            @Override
            public int compare(Run a, Run b) {
                return Long.compare(b.startedAt, a.startedAt);
            }
        });
        return runs;
    }

    /** Deletes the oldest runs beyond {@code keep}, never the one that is being recorded. */
    static void prune(File root, int keep, File activeDir) {
        List<Run> runs = listRuns(root);
        int kept = 0;
        for (Run r : runs) {
            boolean active = activeDir != null && r.dir.equals(activeDir);
            if (active || kept < keep) {
                kept++;
                continue;
            }
            deleteRun(r);
        }
    }

    static void deleteRun(Run r) {
        File[] files = r.dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        r.dir.delete();
    }

    // ---- segments ----

    static File partFile(Run run, long startWallMs) {
        return new File(run.dir, "seg_" + startWallMs + ".part");
    }

    /** Finishes a segment: gives the written file its final name, which carries its duration. */
    static Segment finishSegment(File part, Run run, long startWallMs, long durationMs) {
        File done = new File(run.dir, "seg_" + startWallMs + "_" + durationMs + ".mp4");
        if (!part.renameTo(done)) return null;
        return new Segment(done, startWallMs, durationMs);
    }

    /** The finished segments, oldest first. */
    static List<Segment> segments(Run run) {
        List<Segment> out = new ArrayList<>();
        File[] files = run.dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith("seg_") || !n.endsWith(".mp4")) continue;
            String[] p = n.substring(4, n.length() - 4).split("_");
            if (p.length != 2) continue;
            try {
                out.add(new Segment(f, Long.parseLong(p[0]), Long.parseLong(p[1])));
            } catch (NumberFormatException ignored) {
                // Not one of ours.
            }
        }
        Collections.sort(out, new Comparator<Segment>() {
            @Override
            public int compare(Segment a, Segment b) {
                return Long.compare(a.startWallMs, b.startWallMs);
            }
        });
        return out;
    }

    static long totalMs(List<Segment> segs) {
        long t = 0;
        for (Segment s : segs) t += s.durationMs;
        return t;
    }

    static long totalBytes(File root) {
        long t = 0;
        File[] dirs = root.listFiles();
        if (dirs == null) return 0;
        for (File d : dirs) {
            File[] files = d.listFiles();
            if (files != null) for (File f : files) t += f.length();
        }
        return t;
    }

    /** Deletes the oldest segments while the run holds more than {@code maxMs}. */
    static void trim(Run run, long maxMs) {
        List<Segment> segs = segments(run);
        long total = totalMs(segs);
        for (int i = 0; i < segs.size() - 1 && total > maxMs; i++) {
            total -= segs.get(i).durationMs;
            segs.get(i).file.delete();
        }
    }

    /**
     * Removes unfinished segments left by a recorder that was killed mid-write. A segment still being written is
     * touched every few seconds and is at most about 35 s long, so one untouched for {@code olderThanMs} is a stray.
     */
    static void removeStrays(File root, long olderThanMs, long now) {
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.getName().endsWith(".part") && now - f.lastModified() > olderThanMs) f.delete();
            }
        }
    }

    // ---- the run's own timeline ----

    /** The segment and offset {@code offsetMs} into the run (clamped to its ends); null when it is empty. */
    static Position locate(List<Segment> segs, long offsetMs) {
        if (segs.isEmpty()) return null;
        long at = Math.max(0, offsetMs);
        for (int i = 0; i < segs.size(); i++) {
            long d = segs.get(i).durationMs;
            if (at < d || i == segs.size() - 1) return new Position(i, Math.min(at, Math.max(0, d - 1)));
            at -= d;
        }
        return new Position(segs.size() - 1, 0);
    }

    /** Where a wall-clock moment falls on the run's timeline; a moment in a gap maps to the next segment's start. */
    static long offsetOfWall(List<Segment> segs, long wallMs) {
        long before = 0;
        for (Segment s : segs) {
            if (wallMs < s.startWallMs) return before;
            if (wallMs < s.startWallMs + s.durationMs) return before + (wallMs - s.startWallMs);
            before += s.durationMs;
        }
        return before;
    }

    static long startOfSegment(List<Segment> segs, int index) {
        long t = 0;
        for (int i = 0; i < index && i < segs.size(); i++) t += segs.get(i).durationMs;
        return t;
    }

    // ---- marks ----

    static void addMark(Run run, long wallMs, String label) {
        List<Mark> marks = marks(run);
        marks.add(new Mark(wallMs, label));
        JSONArray a = new JSONArray();
        try {
            for (Mark m : marks) a.put(new JSONObject().put("t", m.wallMs).put("label", m.label));
            writeText(new File(run.dir, "marks.json"), a.toString());
        } catch (JSONException | IOException ignored) {
            // A bookmark that cannot be saved is not worth stopping the recording for.
        }
    }

    static List<Mark> marks(Run run) {
        List<Mark> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(readText(new File(run.dir, "marks.json")));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Mark(o.optLong("t"), o.optString("label")));
            }
        } catch (JSONException | IOException ignored) {
            // No marks yet.
        }
        return out;
    }

    // ---- small file helpers ----

    private static void writeText(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    private static String readText(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(b, 0, off, "UTF-8");
        }
    }
}
