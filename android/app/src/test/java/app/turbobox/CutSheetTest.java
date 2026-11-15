package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class CutSheetTest {
    @Test
    public void clockFormats() {
        assertEquals("1:15", CutSheet.clock(75_000));
        assertEquals("1:02:03", CutSheet.clock(3_723_000));
    }

    @Test
    public void parsesVariedRanges() {
        List<long[]> r = CutSheet.parseRanges("Sure!\n- 0:00 - 4:35\n* 10:10 to 12:00\n1:02:03 – 1:05:00\nnot a range");
        assertEquals(3, r.size());
        assertEquals(4 * 60_000 + 35_000, r.get(0)[1]);
        assertEquals(10 * 60_000 + 10_000, r.get(1)[0]);
        assertEquals(3_723_000, r.get(2)[0]);
    }

    @Test
    public void joinsOverlapsAndDropsBackwards() {
        List<long[]> r = CutSheet.parseRanges("0:00-1:00\n0:50-2:00\n5:00-4:00");
        assertEquals(1, r.size());
        assertEquals(120_000, r.get(0)[1]);
    }

    @Test
    public void sheetMarksWatching() {
        List<Vtt.Cue> cues = new ArrayList<>();
        cues.add(new Vtt.Cue(1000, 3000, "hello"));
        cues.add(new Vtt.Cue(70_000, 72_000, "later"));
        List<long[]> w = new ArrayList<>();
        w.add(new long[]{30_000, 50_000});
        String s = CutSheet.build("Talk", 100_000, cues, w);
        assertTrue(s.contains("[0:01] hello"));
        assertTrue(s.indexOf("WATCHING from 0:30 to 0:50") > s.indexOf("hello"));
        assertTrue(s.indexOf("WATCHING from 0:30") < s.indexOf("[1:10] later"));
    }

    @Test
    public void longRecordingIsSplitIntoSelfContainedParts() {
        List<Vtt.Cue> cues = new ArrayList<>();
        for (int i = 0; i < 1440; i++) cues.add(new Vtt.Cue(i * 10_000L, i * 10_000L + 9000, "one two three four five six seven eight nine ten eleven twelve"));
        long total = 1440 * 10_000L;   // 4 hours
        List<CutSheet.Part> parts = CutSheet.parts("Recording", total, cues, new ArrayList<long[]>(), 4500);
        assertTrue(parts.size() >= 3);
        assertEquals(0, parts.get(0).startMs);
        assertEquals(total, parts.get(parts.size() - 1).endMs);
        for (int i = 1; i < parts.size(); i++) assertEquals(parts.get(i - 1).endMs, parts.get(i).startMs);
        for (CutSheet.Part p : parts) assertTrue(p.text.split("\\s+").length < 6500);
        assertTrue(parts.get(1).text.contains("part 2 of " + parts.size()));
    }

    @Test
    public void clampAndNone() {
        List<long[]> in = new ArrayList<>();
        in.add(new long[]{0, 100_000});
        in.add(new long[]{300_000, 400_000});
        List<long[]> c = CutSheet.clamp(in, 50_000, 200_000);
        assertEquals(1, c.size());
        assertEquals(50_000, c.get(0)[0]);
        assertTrue(CutSheet.saysNone("NONE"));
        assertTrue(!CutSheet.saysNone("0:10 - 0:20"));
    }
}
