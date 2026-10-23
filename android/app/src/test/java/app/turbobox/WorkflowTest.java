package app.turbobox;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class WorkflowTest {
    @Test
    public void severalPagesSurviveJson() {
        Workflow w = Workflow.create();
        w.pinIds.add("a");
        w.pinIds.add("b");
        Workflow back = Workflow.fromJson(w.toJson());
        assertEquals("a,b", String.join(",", back.pinIds));
        assertEquals("a", back.firstPinId());
    }

    @Test
    public void aWorkflowSavedWithOnePageStillLoads() throws Exception {
        Workflow w = Workflow.fromJson(new JSONObject("{\"id\":\"x\",\"pinId\":\"p1\",\"steps\":\"open\"}"));
        assertEquals("p1", String.join(",", w.pinIds));
        assertEquals("", Workflow.fromJson(new JSONObject("{\"id\":\"y\"}")).firstPinId());
    }
}
