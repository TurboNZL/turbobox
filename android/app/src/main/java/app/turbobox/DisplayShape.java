package app.turbobox;

/** The pixel size of a page's display for a forced shape. No Android classes, so it is tested on the JVM. */
final class DisplayShape {
    private DisplayShape() {
    }

    /**
     * From the phone's short and long side: landscape shapes use the long side as width, the others the short side as width;
     * 0 (no forced shape) is the phone's own portrait screen.
     */
    static int[] sizeFor(int lo, int hi, float ratio) {
        if (ratio <= 0f) return new int[]{lo, hi};
        if (ratio > 1f) return new int[]{hi, Math.round(hi / ratio)};
        return new int[]{lo, Math.round(lo / ratio)};
    }
}
