package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorkflowPageScheduleTest {
    @Test
    public void aPageCanHaveItsOwnTimeAndDays() {
        Workflow w = Workflow.create();
        w.pinIds.add("a1");
        w.pinIds.add("b2");
        Workflow.PageSchedule ps = new Workflow.PageSchedule();
        ps.hour = 22;
        ps.minute = 15;
        ps.days = 1 << 2;   // Wednesday
        w.pageSchedules.put("b2", ps);
        Workflow back = Workflow.fromJson(w.toJson());
        assertTrue(back.hasOwnSchedule("b2"));
        assertFalse(back.hasOwnSchedule("a1"));
        assertEquals(22, back.pageSchedules.get("b2").hour);
        assertEquals(15, back.pageSchedules.get("b2").minute);
        assertEquals(1 << 2, back.pageSchedules.get("b2").days);
    }

    @Test
    public void aSchedulForAPageThatWasRemovedIsNotKept() {
        Workflow w = Workflow.create();
        w.pinIds.add("a1");
        w.pageSchedules.put("gone", new Workflow.PageSchedule());
        assertFalse(Workflow.fromJson(w.toJson()).hasOwnSchedule("gone"));
    }
}
