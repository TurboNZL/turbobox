package app.turbobox;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A saved routine: open a pinned page, follow the steps (see {@link WorkflowParser}), optionally at a set
 * time on chosen days. The steps are kept as the text the user wrote.
 */
final class Workflow {
    static final int ALL_DAYS = 0x7F;   // bit 0 = Monday ... bit 6 = Sunday

    final String id;
    String name = "";
    final List<String> pinIds = new ArrayList<>();   // the pinned pages it runs on; every one gets its own run of the steps
    String steps = "";
    boolean timed;                       // run by itself at hour:minute on the chosen days
    boolean hotkey;                      // shown as a one-tap button on the page screen
    boolean enabled = true;              // only matters when timed
    int hour = 8;
    int minute = 0;
    int days = ALL_DAYS;
    long lastRunAt;
    boolean lastOk;
    String lastMessage = "";

    Workflow(String id) {
        this.id = id;
    }

    /** The first page, or "" when none is chosen. */
    String firstPinId() {
        return pinIds.isEmpty() ? "" : pinIds.get(0);
    }

    static Workflow create() {
        return new Workflow(UUID.randomUUID().toString().substring(0, 8));
    }

    JSONObject toJson() {
        try {
            return new JSONObject().put("id", id).put("name", name).put("pinId", firstPinId())
                    .put("pinIds", new JSONArray(pinIds)).put("steps", steps)
                    .put("timed", timed).put("enabled", enabled).put("hotkey", hotkey).put("hour", hour).put("minute", minute)
                    .put("days", days).put("lastRunAt", lastRunAt).put("lastOk", lastOk)
                    .put("lastMessage", lastMessage);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static Workflow fromJson(JSONObject o) {
        Workflow w = new Workflow(o.optString("id"));
        w.name = o.optString("name");
        JSONArray ids = o.optJSONArray("pinIds");
        if (ids != null) {
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.optString(i);
                if (!id.isEmpty() && !w.pinIds.contains(id)) w.pinIds.add(id);
            }
        } else if (!o.optString("pinId").isEmpty()) {
            w.pinIds.add(o.optString("pinId"));   // saved before a workflow could have several pages
        }
        w.steps = o.optString("steps");
        w.timed = o.optBoolean("timed");
        w.enabled = o.optBoolean("enabled", true);
        w.hotkey = o.optBoolean("hotkey", false);
        w.hour = o.optInt("hour", 8);
        w.minute = o.optInt("minute", 0);
        w.days = o.optInt("days", ALL_DAYS);
        w.lastRunAt = o.optLong("lastRunAt");
        w.lastOk = o.optBoolean("lastOk");
        w.lastMessage = o.optString("lastMessage");
        return w;
    }

    /** "Every day 07:30", "Mon, Wed 07:30" or "Manual". */
    String scheduleText(android.content.Context c) {
        if (!timed) return "Manual";
        return daysText(days) + " " + Ui.formatTime(c, hour, minute) + (enabled ? "" : " (off)");
    }

    static String daysText(int mask) {
        String[] names = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        if ((mask & ALL_DAYS) == ALL_DAYS) return "Every day";
        if ((mask & ALL_DAYS) == 0x1F) return "Weekdays";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if ((mask & (1 << i)) != 0) sb.append(sb.length() == 0 ? "" : ", ").append(names[i]);
        }
        return sb.length() == 0 ? "No days" : sb.toString();
    }
}
