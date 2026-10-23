package app.turbobox;

import java.util.Locale;

/**
 * The arithmetic behind the resizable split view. No Android classes, so it is tested on the JVM.
 *
 * A split is rows of panes. Each row has a share of the height and each pane a share of its row's width (the shares of a
 * list add up to 1). A pane with a fixed shape (a forced aspect ratio, or a video that is full screen) cannot use more than
 * that shape fills; with "reclaim" on, what it leaves empty goes to its neighbours instead of staying black.
 */
final class SplitMath {
    static final float MIN_SHARE = 0.15f;

    private SplitMath() {
    }

    /** Shares that add up to 1 (equal shares when the input is unusable). */
    static float[] normalize(float[] f, int n) {
        float[] out = new float[n];
        float sum = 0;
        if (f != null && f.length == n) {
            for (float v : f) sum += v > 0 ? v : 0;
        }
        for (int i = 0; i < n; i++) out[i] = sum > 0 ? Math.max(0f, f[i]) / sum : 1f / n;
        return out;
    }

    /**
     * Splits {@code total} pixels by the shares. {@code caps[i]} is the most pane i can use (0 or less = no limit); pixels a
     * capped pane leaves over go to the others in proportion to their shares. If every pane is capped nothing can be handed
     * on, so the plain shares stand.
     */
    static int[] allocate(float[] shares, int[] caps, int total) {
        int n = shares.length;
        float[] want = normalize(shares, n);
        float[] size = new float[n];
        boolean[] fixed = new boolean[n];
        float left = Math.max(0, total);
        for (int round = 0; round <= n; round++) {
            float freeShare = 0;
            for (int i = 0; i < n; i++) if (!fixed[i]) freeShare += want[i];
            if (freeShare <= 0) break;
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                if (fixed[i]) continue;
                size[i] = left * want[i] / freeShare;
            }
            for (int i = 0; i < n; i++) {
                if (fixed[i] || caps == null || caps[i] <= 0 || size[i] <= caps[i]) continue;
                fixed[i] = true;
                size[i] = caps[i];
                left -= caps[i];
                changed = true;
            }
            if (!changed) break;
        }
        int allFixed = 0;
        for (boolean b : fixed) if (b) allFixed++;
        if (allFixed == n) return plain(want, total);
        int[] out = new int[n];
        int used = 0, lastFree = -1;
        for (int i = 0; i < n; i++) {
            out[i] = Math.round(size[i]);
            used += out[i];
            if (!fixed[i]) lastFree = i;
        }
        out[lastFree] += total - used;   // rounding goes to the last pane that was free to grow
        return out;
    }

    private static int[] plain(float[] want, int total) {
        int[] out = new int[want.length];
        int used = 0;
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.round(total * want[i]);
            used += out[i];
        }
        out[out.length - 1] += total - used;
        return out;
    }

    /**
     * Moves the boundary between pane {@code i} and {@code i + 1} by a fraction of the whole, keeping both at least
     * {@link #MIN_SHARE}. {@code start} is the shares at the time the finger went down.
     */
    static float[] drag(float[] start, int i, float delta) {
        float[] out = start.clone();
        float pair = start[i] + start[i + 1];
        float a = Math.max(MIN_SHARE, Math.min(pair - MIN_SHARE, start[i] + delta));
        out[i] = a;
        out[i + 1] = pair - a;
        return out;
    }

    /** Shares as saved text: "0.5,0.5". */
    static String encode(float[] f) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < f.length; i++) b.append(i == 0 ? "" : ",").append(String.format(Locale.US, "%.4f", f[i]));
        return b.toString();
    }

    /** Saved shares back, or null when the text does not hold exactly {@code n} usable numbers. */
    static float[] decode(String s, int n) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split(",");
        if (parts.length != n) return null;
        float[] out = new float[n];
        try {
            for (int i = 0; i < n; i++) {
                out[i] = Float.parseFloat(parts[i]);
                if (!(out[i] > 0)) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return normalize(out, n);
    }
}
