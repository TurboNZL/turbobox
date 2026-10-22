package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class VttTest {
    @Test
    public void timestamps() {
        assertEquals("00:00:00.000", Vtt.timestamp(0));
        assertEquals("01:02:03.500", Vtt.timestamp(3_723_500));
        assertEquals("00:00:00.000", Vtt.timestamp(-5));
        assertEquals(3_723_500, Vtt.parseTime("01:02:03.500"));
        assertEquals(62_500, Vtt.parseTime("01:02.500"));
        assertEquals(-1, Vtt.parseTime("soon"));
    }

    @Test
    public void cuesRoundTrip() {
        String file = Vtt.HEADER + Vtt.format(new Vtt.Cue(1000, 4000, "Hello everyone")) + Vtt.format(new Vtt.Cue(65_000, 70_000, "Today: A & B"));
        assertTrue(file.startsWith("WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHello everyone\n\n"));
        List<Vtt.Cue> cues = Vtt.parse(file);
        assertEquals(2, cues.size());
        assertEquals("Hello everyone", cues.get(0).text);
        assertEquals(65_000, cues.get(1).startMs);
        assertEquals("Today: A & B", cues.get(1).text);
    }

    @Test
    public void unsafeTextIsMadeSafe() {
        String out = Vtt.format(new Vtt.Cue(0, 1000, "a --> b\n\nc <i>d</i>"));
        assertTrue(out, !out.substring(out.indexOf('\n') + 1).contains("-->"));
        assertTrue(out, !out.contains("\n\nc"));
        assertTrue(out, out.contains("&lt;i&gt;"));
    }

    @Test
    public void aCueNeverEndsBeforeItStarts() {
        String out = Vtt.format(new Vtt.Cue(5000, 5000, "x"));
        assertTrue(out, out.startsWith("00:00:05.000 --> 00:00:05.500"));
    }

    @Test
    public void findsTheCueAtATime() {
        List<Vtt.Cue> cues = Vtt.parse(Vtt.HEADER + Vtt.format(new Vtt.Cue(1000, 4000, "one")) + Vtt.format(new Vtt.Cue(6000, 8000, "two")));
        assertEquals("one", Vtt.at(cues, 1000).text);
        assertNull(Vtt.at(cues, 4000));
        assertNotNull(Vtt.at(cues, 7000));
        assertNull(Vtt.at(cues, 500));
    }
}
