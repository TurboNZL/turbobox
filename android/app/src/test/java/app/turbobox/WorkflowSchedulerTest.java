package app.turbobox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public class WorkflowSchedulerTest {
    private static final ZoneId Z = ZoneId.of("Europe/Berlin");

    private static long at(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, Z).toInstant().toEpochMilli();
    }

    // 2026-10-22 is a Thursday.
    @Test
    public void laterTodayWhenTheTimeIsAhead() {
        long now = at(2026, 10, 22, 6, 0);
        assertEquals(at(2026, 10, 22, 7, 30), WorkflowScheduler.nextRun(now, 7, 30, Workflow.ALL_DAYS, Z));
    }

    @Test
    public void tomorrowWhenTheTimeHasPassed() {
        long now = at(2026, 10, 22, 8, 0);
        assertEquals(at(2026, 10, 23, 7, 30), WorkflowScheduler.nextRun(now, 7, 30, Workflow.ALL_DAYS, Z));
    }

    @Test
    public void exactlyNowIsNotNext() {
        long now = at(2026, 10, 22, 7, 30);
        assertEquals(at(2026, 10, 23, 7, 30), WorkflowScheduler.nextRun(now, 7, 30, Workflow.ALL_DAYS, Z));
    }

    @Test
    public void skipsToTheNextChosenDay() {
        long now = at(2026, 10, 22, 8, 0); // Thursday
        int mondayWednesday = 1 | (1 << 2);
        assertEquals(at(2026, 10, 26, 7, 30), WorkflowScheduler.nextRun(now, 7, 30, mondayWednesday, Z));
        int weekdays = 0x1F; // Friday is next
        assertEquals(at(2026, 10, 23, 7, 30), WorkflowScheduler.nextRun(now, 7, 30, weekdays, Z));
        int sundayOnly = 1 << 6;
        assertEquals(at(2026, 10, 25, 7, 30), WorkflowScheduler.nextRun(now, 7, 30, sundayOnly, Z));
    }

    @Test
    public void thisWeekdayNextWeekWhenTodayHasPassed() {
        long now = at(2026, 10, 22, 8, 0); // Thursday, 08:00
        int thursdayOnly = 1 << 3;
        assertEquals(at(2026, 10, 29, 7, 30), WorkflowScheduler.nextRun(now, 7, 30, thursdayOnly, Z));
    }

    @Test
    public void noDaysMeansNever() {
        assertEquals(-1, WorkflowScheduler.nextRun(at(2026, 10, 22, 8, 0), 7, 30, 0, Z));
    }

    @Test
    public void daysText() {
        assertEquals("Every day", Workflow.daysText(Workflow.ALL_DAYS));
        assertEquals("Weekdays", Workflow.daysText(0x1F));
        assertEquals("Mon, Wed", Workflow.daysText(1 | 4));
    }
}
