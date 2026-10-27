package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

public class WorkflowParserTest {
    @Test
    public void durations() {
        assertEquals(90, WorkflowParser.parseDuration("90"));
        assertEquals(45, WorkflowParser.parseDuration("45s"));
        assertEquals(300, WorkflowParser.parseDuration("5m"));
        assertEquals(7200, WorkflowParser.parseDuration("2h"));
        assertEquals(5400, WorkflowParser.parseDuration("1h30m"));
        assertEquals(5415, WorkflowParser.parseDuration("1h30m15s"));
        assertEquals(-1, WorkflowParser.parseDuration("soon"));
        assertEquals(-1, WorkflowParser.parseDuration(""));
        assertEquals(-1, WorkflowParser.parseDuration("5x"));
    }

    @Test
    public void targets() {
        assertEquals("css:#join", WorkflowParser.normalizeTarget("css:#join"));
        assertEquals("css:#join", WorkflowParser.normalizeTarget("CSS: #join "));
        assertEquals("text:Join now", WorkflowParser.normalizeTarget("Join now"));
        assertEquals("text:Join now", WorkflowParser.normalizeTarget("text:Join now"));
        assertEquals("text:Join now", WorkflowParser.normalizeTarget("\"Join now\""));
        assertNull(WorkflowParser.normalizeTarget("  "));
        assertNull(WorkflowParser.normalizeTarget("css:"));
    }

    @Test
    public void aWholeWorkflow() throws Exception {
        String src = "# morning routine\n"
                + "open\n"
                + "wait 5s\n"
                + "? click Accept cookies within 5s\n"
                + "click css:button.join within 2m\n"
                + "type css:#name = My Name = Test\n"
                + "key Enter\n"
                + "scroll down 300\n"
                + "mute\n"
                + "volume 80%\n"
                + "hangup 1h30m\n"
                + "record start\n"
                + "transcribe on\n"
                + "step away\n"
                + "step back\n"
                + "mark Chapter 2 starts\n"
                + "waitfor Leave\n"
                + "js document.title\n"
                + "record stop\n"
                + "close\n";
        List<WorkflowParser.Step> s = WorkflowParser.parse(src);
        assertEquals(19, s.size());
        assertEquals(WorkflowParser.Kind.OPEN, s.get(0).kind);
        assertEquals(5, s.get(1).seconds);
        assertTrue(s.get(2).optional);
        assertEquals("text:Accept cookies", s.get(2).target);
        assertEquals(5000, s.get(2).withinMs);
        assertFalse(s.get(3).optional);
        assertEquals("css:button.join", s.get(3).target);
        assertEquals(120_000, s.get(3).withinMs);
        assertEquals("My Name = Test", s.get(4).text);
        assertEquals("Enter", s.get(5).text);
        assertEquals("down", s.get(6).text);
        assertEquals(300, s.get(6).number);
        assertEquals(80, s.get(8).number);
        assertEquals(5400, s.get(9).seconds);
        assertEquals(WorkflowParser.Kind.TRANSCRIBE_ON, s.get(11).kind);
        assertEquals(WorkflowParser.Kind.STEP_AWAY, s.get(12).kind);
        assertEquals(WorkflowParser.Kind.STEP_BACK, s.get(13).kind);
        assertEquals("Chapter 2 starts", s.get(14).text);
        assertEquals(60_000, s.get(15).withinMs);
        assertEquals("document.title", s.get(16).text);
        assertEquals(WorkflowParser.Kind.CLOSE, s.get(18).kind);
        assertEquals(5, s.get(3).line); // the source's line number, comments included
    }

    @Test
    public void hangupAndCloseAreTheSameThing() throws Exception {
        List<WorkflowParser.Step> s = WorkflowParser.parse("hangup\nclose\nhangup 0\nclose 0s\nhangup 30m\nclose 1h\nhangup off\nclose off");
        assertEquals(WorkflowParser.Kind.CLOSE, s.get(0).kind);
        assertEquals(WorkflowParser.Kind.CLOSE, s.get(1).kind);
        assertEquals(WorkflowParser.Kind.CLOSE, s.get(2).kind);
        assertEquals(WorkflowParser.Kind.CLOSE, s.get(3).kind);
        assertEquals(WorkflowParser.Kind.HANGUP, s.get(4).kind);
        assertEquals(1800, s.get(4).seconds);
        assertEquals(WorkflowParser.Kind.HANGUP, s.get(5).kind);
        assertEquals(3600, s.get(5).seconds);
        assertEquals(WorkflowParser.Kind.HANGUP, s.get(6).kind);
        assertEquals(0, s.get(6).seconds);
        assertEquals(WorkflowParser.Kind.HANGUP, s.get(7).kind);
        assertEquals(0, s.get(7).seconds);
    }

    @Test
    public void errorsNameTheLine() {
        String[] bad = {"dance", "wait soon", "click", "type css:#a", "scroll sideways", "volume 300", "record", "key", "js", "transcribe maybe", "step sideways", "hangup soon"};
        for (String line : bad) {
            try {
                WorkflowParser.parse("open\n" + line);
                fail("accepted: " + line);
            } catch (WorkflowParser.ParseError e) {
                assertEquals(line, 2, e.line);
            }
        }
    }

    @Test
    public void stepJsonCarriesWhatThePageNeeds() throws Exception {
        WorkflowParser.Step s = WorkflowParser.parse("type css:#a = hi").get(0);
        assertEquals("type", s.toJson().getString("type"));
        assertEquals("css:#a", s.toJson().getString("target"));
        assertEquals("hi", s.toJson().getString("text"));
    }

    @Test
    public void waitUntilAndRunSteps() throws Exception {
        WorkflowParser.Step a = WorkflowParser.parse("wait until 22:30").get(0);
        assertEquals(WorkflowParser.Kind.WAIT_UNTIL, a.kind);
        assertEquals(22 * 60 + 30, a.number);
        assertEquals(6 * 60 + 15, WorkflowParser.clockMinutes("6:15 am"));
        assertEquals(19 * 60, WorkflowParser.clockMinutes("7pm"));
        assertEquals(0, WorkflowParser.clockMinutes("12am"));
        assertEquals(-1, WorkflowParser.clockMinutes("25:00"));
        WorkflowParser.Step r = WorkflowParser.parse("run Morning check-in").get(0);
        assertEquals(WorkflowParser.Kind.RUN, r.kind);
        assertEquals("Morning check-in", r.text);
        try {
            WorkflowParser.parse("wait until soon");
            org.junit.Assert.fail("should not parse");
        } catch (WorkflowParser.ParseError e) {
            org.junit.Assert.assertTrue(e.getMessage().contains("time of day"));
        }
    }

    @Test
    public void focusStep() throws Exception {
        WorkflowParser.Step a = WorkflowParser.parse("focus a1b2").get(0);
        assertEquals(WorkflowParser.Kind.FOCUS, a.kind);
        assertEquals("a1b2", a.text);
        assertTrue(!WorkflowParser.needsPage(a.kind));
        try {
            WorkflowParser.parse("focus");
            fail("needs a page");
        } catch (WorkflowParser.ParseError e) {
            assertTrue(e.getMessage().contains("one page"));
        }
    }

    @Test
    public void closeKeepStep() throws Exception {
        WorkflowParser.Step a = WorkflowParser.parse("close keep a1b2,c3d4").get(0);
        assertEquals(WorkflowParser.Kind.CLOSE_KEEP, a.kind);
        assertEquals("a1b2,c3d4", a.text);
        assertEquals(0, a.number);
        WorkflowParser.Step b = WorkflowParser.parse("close keep a1b2 and workflow").get(0);
        assertEquals("a1b2", b.text);
        assertEquals(1, b.number);
        assertEquals(1, WorkflowParser.parse("close keep and workflow").get(0).number);
        try {
            WorkflowParser.parse("close keep");
            fail("needs pages");
        } catch (WorkflowParser.ParseError e) {
            assertTrue(e.getMessage().contains("pages to keep"));
        }
    }

    @Test
    public void conditionsOnTheSiteMode() throws Exception {
        WorkflowParser.Step a = WorkflowParser.parse("if mobile click Menu").get(0);
        assertEquals(1, a.condition);
        assertEquals(WorkflowParser.Kind.CLICK, a.kind);
        assertEquals("text:Menu", a.target);
        WorkflowParser.Step b = WorkflowParser.parse("? if desktop click Menu within 2m").get(0);
        assertEquals(2, b.condition);
        assertTrue(b.optional);
        assertEquals(120_000, b.withinMs);
        assertEquals(0, WorkflowParser.parse("click Menu").get(0).condition);
        assertEquals("Click \"Menu\" (only on the mobile site)", StepTemplates.describe("if mobile click Menu"));
        StepTemplates.Edit e = StepTemplates.toEdit("if desktop mute");
        assertEquals(2, e.condition);
        assertEquals("if mobile mute", StepTemplates.withCondition("mute", 1));
    }
}
