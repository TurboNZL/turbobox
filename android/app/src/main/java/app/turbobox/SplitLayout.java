package app.turbobox;

/** How pages are arranged when more than one is on screen. No Android classes, so it is tested on the JVM. */
final class SplitLayout {
    static final int LIKE_SPLIT = 0;     // the same arrangement the split view has
    static final int SIDE_BY_SIDE = 1;
    static final int STACKED = 2;
    static final int OVERLAY = 3;        // one page fills the window, the other floats over a corner of it

    private SplitLayout() {
    }

    /**
     * How many pages go in each row, top to bottom. One page fills everything. On a wide screen two or three go side by
     * side; on a tall one they stack. Four are always two by two.
     */
    static int[] rows(int pages, boolean wide) {
        switch (Math.max(1, Math.min(4, pages))) {
            case 1: return new int[]{1};
            case 2: return wide ? new int[]{2} : new int[]{1, 1};
            case 3: return wide ? new int[]{3} : new int[]{1, 1, 1};
            default: return new int[]{2, 2};
        }
    }

    /** The rows for a Mini window's chosen layout. */
    static int[] pipRows(int pages, int layout, boolean wide) {
        int n = Math.max(1, Math.min(4, pages));
        if (layout == SIDE_BY_SIDE) return new int[]{n};
        if (layout == STACKED) {
            int[] r = new int[n];
            java.util.Arrays.fill(r, 1);
            return r;
        }
        if (layout == OVERLAY) return new int[]{1};
        return rows(n, wide);
    }

    /**
     * Width over height for a window holding these rows of 16:9 pages, kept inside the 1:2.39 to 2.39:1 that Android's
     * picture in picture accepts. Returned as {numerator, denominator}.
     */
    static int[] shape(int[] rows) {
        int cols = 1;
        for (int r : rows) cols = Math.max(cols, r);
        int num = cols * 16, den = rows.length * 9;
        if (num * 100 > den * 239) return new int[]{239, 100};
        if (num * 239 < den * 100) return new int[]{100, 239};
        return new int[]{num, den};
    }
}
