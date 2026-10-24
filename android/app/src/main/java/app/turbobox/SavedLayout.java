package app.turbobox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named arrangement of two to four pages for the split view, applied from the home screen like a workflow is run:
 * the pages it names are opened (if they are not already) and share the screen the way the layout places them.
 * No Android classes besides org.json, which the unit tests have.
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
    final List<String> pinIds = new ArrayList<>();   // page k of the layout; empty = whichever pages are open

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
            o.put("id", id).put("name", name).put("count", count).put("mini", mini).put("shape", shape).put("rects", rects).put("pins", new JSONArray(pinIds));
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
        JSONArray a = o.optJSONArray("pins");
        if (a != null) for (int i = 0; i < a.length() && l.pinIds.size() < l.count; i++) l.pinIds.add(a.optString(i));
        return l;
    }
}
