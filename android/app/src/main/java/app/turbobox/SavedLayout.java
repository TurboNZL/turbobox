package app.turbobox;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * A named arrangement of two to four pages, made live on the pages themselves and kept to use again. It is not tied to any
 * page: applying it puts whichever pages are in the split into its places, page 1 first. No Android classes besides org.json.
 */
final class SavedLayout {
    static final int MIN_PAGES = 2;
    static final int MAX_PAGES = MiniLayout.PAGES;

    String id;
    String name = "";
    int count = 2;
    boolean mini;                               // for the Mini (picture in picture) window instead of the full-screen split
    int shape;                                  // a Mini layout's window shape: index into MiniLayout.SHAPES
    String rects = "";                          // MiniLayout.encode of the first {@link #count} rectangles; "" = the preset
    String floats = "";                         // MiniLayout.encodeFloats: pages that float; "" = none
    String z = "";                              // MiniLayout.encodeOrder: pages back to front; "" = page 1 at the back

    static SavedLayout create() {
        SavedLayout l = new SavedLayout();
        l.id = UUID.randomUUID().toString();
        return l;
    }

    /** The rectangles to use, always {@link #count} of them. */
    float[][] rectangles() {
        float[][] base = presets(count);
        float[][] all = MiniLayout.decode(rects, padTo(base));
        float[][] out = new float[count][];
        System.arraycopy(all, 0, out, 0, count);
        return out;
    }

    private static float[][] padTo(float[][] r) {
        float[][] out = MiniLayout.splitDefaults();
        for (int i = 0; i < r.length && i < out.length; i++) out[i] = r[i];
        return out;
    }

    boolean[] floatFlags() {
        return MiniLayout.decodeFloats(floats, count);
    }

    int[] order() {
        return MiniLayout.decodeOrder(z, count);
    }

    /** A sensible start for each number of pages. */
    static float[][] presets(int pages) {
        switch (Math.max(MIN_PAGES, Math.min(MAX_PAGES, pages))) {
            case 2: return new float[][]{{0f, 0f, 1f, 0.5f}, {0f, 0.5f, 1f, 0.5f}};
            case 3: return new float[][]{{0f, 0f, 1f, 0.5f}, {0f, 0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f, 0.5f}};
            default: return MiniLayout.splitDefaults();
        }
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("name", name).put("count", count).put("mini", mini).put("shape", shape).put("rects", rects).put("z", z).put("floats", floats);
        } catch (JSONException ignored) {
            // Plain values cannot fail.
        }
        return o;
    }

    static SavedLayout fromJson(JSONObject o) {
        SavedLayout l = new SavedLayout();
        l.id = o.optString("id", UUID.randomUUID().toString());
        l.name = o.optString("name", "");
        l.count = Math.max(MIN_PAGES, Math.min(MAX_PAGES, o.optInt("count", 2)));
        l.mini = o.optBoolean("mini", false);
        l.shape = Math.max(0, Math.min(MiniLayout.SHAPES.length - 1, o.optInt("shape", 0)));
        l.rects = o.optString("rects", "");
        l.z = o.optString("z", "");
        l.floats = o.optString("floats", "");
        return l;
    }
}
