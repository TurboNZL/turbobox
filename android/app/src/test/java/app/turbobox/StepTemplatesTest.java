package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class StepTemplatesTest {
    @Test
    public void everyButtonMakesALineTheParserAccepts() throws Exception {
        for (StepTemplates.Template t : StepTemplates.all()) {
            String line = t.line(t.defaults());
            assertNotNull(t.title, line);
            for (boolean optional : new boolean[]{false, true}) {
                String decorated = t.pageStep ? StepTemplates.decorate(line, optional, 90) : line;
                List<WorkflowParser.Step> steps = WorkflowParser.parse(decorated);
                assertEquals(t.title + ": " + decorated, 1, steps.size());
                if (t.pageStep) {
                    assertEquals(optional, steps.get(0).optional);
                    assertEquals(90_000, steps.get(0).withinMs);
                }
            }
        }
    }

    @Test
    public void durationsRoundTripThroughTheParser() {
        for (long s : new long[]{1, 30, 59, 60, 90, 3600, 5400, 5415, 7200, 86400}) {
            assertEquals(s, WorkflowParser.parseDuration(StepTemplates.duration(s)));
        }
        assertEquals("1h30m", StepTemplates.duration(5400));
        assertEquals(5415, StepTemplates.toSeconds("1", "30", "15"));
        assertEquals(90, StepTemplates.toSeconds("", "1", "30"));
    }

    @Test
    public void hangUpAsksForTheWait() throws Exception {
        StepTemplates.Template hang = null;
        for (StepTemplates.Template t : StepTemplates.all()) if (t.title.startsWith("Hang up")) hang = t;
        assertNotNull(hang);
        assertEquals("hangup", hang.line(new String[]{"0"}));
        assertEquals("hangup 1h30m", hang.line(new String[]{"5400"}));
        assertEquals(WorkflowParser.Kind.CLOSE, WorkflowParser.parse("hangup").get(0).kind);
        assertEquals(WorkflowParser.Kind.HANGUP, WorkflowParser.parse("hangup 1h30m").get(0).kind);
    }

    @Test
    public void requiredAnswersAreChecked() {
        for (StepTemplates.Template t : StepTemplates.all()) {
            if (t.title.equals("Click")) {
                assertNull(t.line(new String[]{"  "}));
                assertEquals("click Join now", t.line(new String[]{" Join now "}));
            }
            if (t.title.equals("Type into a field")) {
                assertEquals("type css:#name = Sam", t.line(new String[]{"css:#name", "Sam"}));
                assertNull(t.line(new String[]{"", "Sam"}));
            }
            if (t.title.equals("Scroll")) {
                assertEquals("scroll top", t.line(new String[]{"top", "600"}));
                assertEquals("scroll down 250", t.line(new String[]{"down", "250"}));
            }
        }
    }

    @Test
    public void aStepIsInsertedAfterTheLineTheCursorIsIn() {
        StepTemplates.Insert a = StepTemplates.insertAfterCursor("wait 5s\nclick Join\nmute\n", 3, "unmute");
        assertEquals("wait 5s\nunmute\nclick Join\nmute\n", a.text);
        assertEquals("wait 5s\nunmute".length(), a.cursor);
        StepTemplates.Insert b = StepTemplates.insertAfterCursor("wait 5s", 7, "mute");
        assertEquals("wait 5s\nmute\n", b.text);
        StepTemplates.Insert c = StepTemplates.insertAfterCursor("wait 5s\n", 8, "mute");   // cursor on the empty last line
        assertEquals("wait 5s\nmute\n", c.text);
        StepTemplates.Insert d = StepTemplates.insertAfterCursor("", 0, "mute");
        assertEquals("mute\n", d.text);
        assertTrue(d.cursor <= d.text.length());
        StepTemplates.Insert e = StepTemplates.insertAfterCursor("a\n\nb", 2, "x");   // a blank line in the middle is filled
        assertEquals("a\nx\nb", e.text);
    }

    @Test
    public void everyStepFromAButtonCanBeReadBackIntoItsForm() {
        for (StepTemplates.Template t : StepTemplates.all()) {
            String line = t.line(t.defaults());
            StepTemplates.Edit e = StepTemplates.toEdit(line);
            assertNotNull(t.title + ": " + line, e);
            assertEquals(t.title, e.template.title);
            assertEquals(line, e.template.line(e.values));
        }
    }

    @Test
    public void optionalAndTimeLimitSurviveEditing() {
        StepTemplates.Edit e = StepTemplates.toEdit("? click Join within 2m");
        assertNotNull(e);
        assertTrue(e.optional);
        assertEquals(120, e.withinSeconds);
        assertEquals("Join", e.values[0]);
        assertEquals(0, StepTemplates.toEdit("click Join").withinSeconds);
    }

    @Test
    public void stepsAreDescribedInWords() {
        assertEquals("Click \"Join\"", StepTemplates.describe("click Join"));
        assertEquals("Wait 1h30m", StepTemplates.describe("wait 1h30m"));
        assertEquals("Close every page", StepTemplates.describe("close all"));
        assertEquals("Media key: play", StepTemplates.describe("media play"));
        assertEquals("Click \"Join\", for up to 2m (skip if it fails)".replace(" (skip if it fails)", "") + " (skip if it fails)", StepTemplates.describe("? click Join within 2m"));
        assertEquals("not a step", StepTemplates.describe("not a step"));
    }

    @Test
    public void workflowsWithoutAPageAreAllowedForPageLessSteps() throws Exception {
        assertTrue(!WorkflowParser.needsPage(WorkflowParser.parse("media play").get(0).kind));
        assertTrue(!WorkflowParser.needsPage(WorkflowParser.parse("close others").get(0).kind));
        assertTrue(WorkflowParser.needsPage(WorkflowParser.parse("click Join").get(0).kind));
    }
}
