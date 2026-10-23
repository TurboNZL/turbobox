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
        float[][] out = defaults();
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
