package app.turbobox;

import java.util.Locale;

/**
 * Your own arrangement for the Mini window: one rectangle per page, as fractions of the window (left, top, width, height).
 * Page 1 is at the back and page 4 at the front. No Android classes, so it is tested on the JVM.
 */
final class MiniLayout {
    static final int PAGES = 4;
    static final float MIN = 0.12f;

    /** The shapes the Mini window can have: label, width, height. All inside the 1:2.39 to 2.39:1 Android allows. */
    static final String[] SHAPE_LABELS = {"16:9 (wide)", "4:3", "1:1 (square)", "3:4", "9:16 (tall)", "21:9 (ultrawide)"};
    static final int[][] SHAPES = {{16, 9}, {4, 3}, {1, 1}, {3, 4}, {9, 16}, {21, 9}};

    /** Page 1 fills the window, the others are small and sit in the corners. */
    private static final float[][] DEFAULTS = {
            {0f, 0f, 1f, 1f}, {0.62f, 0.62f, 0.36f, 0.36f}, {0.02f, 0.62f, 0.36f, 0.36f}, {0.62f, 0.02f, 0.36f, 0.36f}};

    private MiniLayout() {
    }

    /** The in-app split's own layout starts as four equal quarters. */
    static float[][] splitDefaults() {
        return new float[][]{{0f, 0f, 0.5f, 0.5f}, {0.5f, 0f, 0.5f, 0.5f}, {0f, 0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f, 0.5f}};
    }

    /** Equal rectangles for rows of panes (rows {1,1} = two stacked), for a split nobody has arranged yet. */
    static float[][] fromRows(int[] rows) {
        int n = 0;
        for (int r : rows) n += r;
        float[][] out = new float[n][];
        int k = 0;
        for (int i = 0; i < rows.length; i++) {
            for (int j = 0; j < rows[i]; j++) {
                out[k++] = new float[]{j / (float) rows[i], i / (float) rows.length, 1f / rows[i], 1f / rows.length};
            }
        }
        return out;
    }

    /** Rows for "stacked" (one page per row) and for "grid" (two across, then down: 2 = side by side, 3 = 2 + 1, 4 = 2 x 2). */
    static int[] stackedRows(int pages) {
        int[] r = new int[Math.max(1, Math.min(PAGES, pages))];
        java.util.Arrays.fill(r, 1);
        return r;
    }

    static int[] gridRows(int pages) {
        switch (Math.max(1, Math.min(PAGES, pages))) {
            case 1: return new int[]{1};
            case 2: return new int[]{2};
            case 3: return new int[]{2, 1};
            default: return new int[]{2, 2};
        }
    }

    /** Front-to-back order as saved text ("0,1,2": back first). */
    static String encodeOrder(int[] z) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < z.length; i++) b.append(i == 0 ? "" : ",").append(z[i]);
        return b.toString();
    }

    /** The saved order if it is a permutation of 0..n-1, else page 1 at the back and the last page in front. */
    static int[] decodeOrder(String s, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = i;
        if (s == null || s.isEmpty()) return out;
        String[] parts = s.split(",");
        if (parts.length != n) return out;
        boolean[] seen = new boolean[n];
        int[] z = new int[n];
        try {
            for (int i = 0; i < n; i++) {
                z[i] = Integer.parseInt(parts[i].trim());
                if (z[i] < 0 || z[i] >= n || seen[z[i]]) return out;
                seen[z[i]] = true;
            }
        } catch (NumberFormatException e) {
            return out;
        }
        return z;
    }

    /** An arrangement as one string: rectangles, a bar, the order, a bar, the floating pages (0/1 each; left out when none float). */
    static String pack(float[][] rects, int[] z) {
        return pack(rects, z, null);
    }

    static String pack(float[][] rects, int[] z, boolean[] floats) {
        String base = encode(rects) + "|" + encodeOrder(z);
        return anyFloat(floats) ? base + "|" + encodeFloats(floats) : base;
    }

    static boolean anyFloat(boolean[] f) {
        if (f != null) for (boolean b : f) if (b) return true;
        return false;
    }

    static String encodeFloats(boolean[] f) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < f.length; i++) b.append(f[i] ? '1' : '0');
        return b.toString();
    }

    /** Which pages float (can be moved but not touched or resized); all false when the text is missing or the wrong length. */
    static boolean[] decodeFloats(String s, int n) {
        boolean[] out = new boolean[n];
        if (s == null || s.length() != n) return out;
        for (int i = 0; i < n; i++) out[i] = s.charAt(i) == '1';
        return out;
    }

    static boolean[] unpackFloats(String packed, int n) {
        String[] parts = packed == null ? new String[0] : packed.split("\\|", -1);
        return decodeFloats(parts.length > 2 ? parts[2] : "", n);
    }

    /**
     * Snaps a rectangle that is being moved (or, with {@code resizing}, whose right and bottom edges are being dragged) to the
     * edges of the window and of the other pages when they are within {@code threshold} (a fraction). Pages can still overlap:
     * this only makes edges meet when you bring them close.
     */
    static float[] snap(float[] r, float[][] all, int self, boolean resizing, float threshold) {
        float[] o = r.clone();
        float[] xs = new float[all.length * 2 + 2];
        float[] ys = new float[all.length * 2 + 2];
        int k = 0;
        xs[k] = 0f;
        ys[k++] = 0f;
        xs[k] = 1f;
        ys[k++] = 1f;
        for (int i = 0; i < all.length; i++) {
            if (i == self) continue;
            xs[k] = all[i][0];
            ys[k++] = all[i][1];
            xs[k] = all[i][0] + all[i][2];
            ys[k++] = all[i][1] + all[i][3];
        }
        float bestX = threshold, bestY = threshold;
        float shiftX = 0, shiftY = 0;
        for (int i = 0; i < k; i++) {
            if (!resizing) {
                float dl = xs[i] - o[0], dr = xs[i] - (o[0] + o[2]);
                if (Math.abs(dl) < bestX) { bestX = Math.abs(dl); shiftX = dl; }
                if (Math.abs(dr) < bestX) { bestX = Math.abs(dr); shiftX = dr; }
                float dt = ys[i] - o[1], db = ys[i] - (o[1] + o[3]);
                if (Math.abs(dt) < bestY) { bestY = Math.abs(dt); shiftY = dt; }
                if (Math.abs(db) < bestY) { bestY = Math.abs(db); shiftY = db; }
            } else {
                float dr = xs[i] - (o[0] + o[2]);
                if (Math.abs(dr) < bestX) { bestX = Math.abs(dr); shiftX = dr; }
                float db = ys[i] - (o[1] + o[3]);
                if (Math.abs(db) < bestY) { bestY = Math.abs(db); shiftY = db; }
            }
        }
        if (resizing) {
            o[2] += shiftX;
            o[3] += shiftY;
        } else {
            o[0] += shiftX;
            o[1] += shiftY;
        }
        return clamp(o);
    }

    /** The rectangles of a packed arrangement for exactly {@code n} pages, or null when it holds another number. */
    static float[][] unpackRects(String s, int n) {
        if (s == null || s.isEmpty()) return null;
        String[] parts = s.split("\\|", -1);
        String[] r = parts[0].split(";");
        if (r.length != n) return null;
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) {
            String[] v = r[i].split(",");
            if (v.length != 4) return null;
            try {
                float[] f = new float[4];
                for (int j = 0; j < 4; j++) f[j] = Float.parseFloat(v[j]);
                out[i] = clamp(f);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    static int[] unpackOrder(String s, int n) {
        String[] parts = s == null ? new String[0] : s.split("\\|", -1);
        return decodeOrder(parts.length > 1 ? parts[1] : "", n);
    }

    static float[][] defaults() {
        float[][] out = new float[PAGES][];
        for (int i = 0; i < PAGES; i++) out[i] = DEFAULTS[i].clone();
        return out;
    }

    /** Keeps a rectangle inside the window and at least {@link #MIN} in each direction. */
    static float[] clamp(float[] r) {
        float w = Math.max(MIN, Math.min(1f, r[2]));
        float h = Math.max(MIN, Math.min(1f, r[3]));
        float x = Math.max(0f, Math.min(1f - w, r[0]));
        float y = Math.max(0f, Math.min(1f - h, r[1]));
        return new float[]{x, y, w, h};
    }

    static String encode(float[][] rects) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rects.length; i++) {
            if (i > 0) b.append(';');
            for (int j = 0; j < 4; j++) b.append(j == 0 ? "" : ",").append(String.format(Locale.US, "%.4f", rects[i][j]));
        }
        return b.toString();
    }

    /** The saved rectangles; anything missing or unreadable is the default for that page. */
    static float[][] decode(String s) {
        return decode(s, defaults());
    }

    static float[][] decode(String s, float[][] out) {
        if (s == null || s.isEmpty()) return out;
        String[] pages = s.split(";");
        for (int i = 0; i < PAGES && i < pages.length; i++) {
            String[] v = pages[i].split(",");
            if (v.length != 4) continue;
            try {
                float[] r = new float[4];
                for (int j = 0; j < 4; j++) r[j] = Float.parseFloat(v[j]);
                out[i] = clamp(r);
            } catch (NumberFormatException ignored) {
                // Keeps the default for this page.
            }
        }
        return out;
    }

    static int[] shape(int index) {
        return SHAPES[Math.max(0, Math.min(SHAPES.length - 1, index))];
    }
}
