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
        String src = "# morning lecture\n"
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
                + "mark Lecture starts\n"
                + "waitfor Leave\n"
                + "js document.title\n"
                + "record stop\n"
                + "close\n";
        List<WorkflowParser.Step> s = WorkflowParser.parse(src);
        assertEquals(16, s.size());
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
        assertEquals("Lecture starts", s.get(11).text);
        assertEquals(60_000, s.get(12).withinMs);
        assertEquals("document.title", s.get(13).text);
        assertEquals(WorkflowParser.Kind.CLOSE, s.get(15).kind);
        assertEquals(5, s.get(3).line); // the source's line number, comments included
    }

    @Test
    public void errorsNameTheLine() {
        String[] bad = {"dance", "wait soon", "click", "type css:#a", "scroll sideways", "volume 300", "record", "key", "js"};
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
}
