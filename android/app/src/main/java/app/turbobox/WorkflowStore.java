package app.turbobox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** The saved workflows, kept as JSON in the app's preferences. */
final class WorkflowStore {
    private static final String KEY = "workflows";

    private WorkflowStore() {
    }

    static List<Workflow> load(Context c) {
        List<Workflow> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.sp(c).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Workflow.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
            // A damaged list reads as empty.
        }
        return out;
    }

    private static void save(Context c, List<Workflow> list) {
        JSONArray a = new JSONArray();
        for (Workflow w : list) a.put(w.toJson());
        Prefs.sp(c).edit().putString(KEY, a.toString()).apply();
    }

    static Workflow find(Context c, String id) {
        if (id == null) return null;
        for (Workflow w : load(c)) if (w.id.equals(id)) return w;
        return null;
    }

    /** Adds or replaces (by id), then re-plans the alarms. */
    static void put(Context c, Workflow wf) {
        List<Workflow> list = load(c);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(wf.id)) {
                list.set(i, wf);
                found = true;
            }
        }
        if (!found) list.add(wf);
        save(c, list);
        WorkflowScheduler.schedule(c, wf);
    }

    /** Records how the last run went without touching anything else the user may be editing. */
    static void recordResult(Context c, String id, boolean ok, String message) {
        Workflow w = find(c, id);
        if (w == null) return;
        w.lastRunAt = System.currentTimeMillis();
        w.lastOk = ok;
        w.lastMessage = message;
        List<Workflow> list = load(c);
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) list.set(i, w);
        save(c, list);
    }

    static void remove(Context c, String id) {
        Workflow old = find(c, id);
        if (old != null) WorkflowScheduler.cancelAll(c, old); else WorkflowScheduler.cancel(c, id);
        List<Workflow> list = load(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        save(c, list);
    }
}
