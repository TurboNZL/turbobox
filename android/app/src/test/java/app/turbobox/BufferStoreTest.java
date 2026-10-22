package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class BufferStoreTest {
    private File root;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("buffer").toFile();
    }

    private BufferStore.Segment segment(BufferStore.Run run, long start, long dur) throws IOException {
        File part = BufferStore.partFile(run, start);
        assertTrue(part.createNewFile());
        return BufferStore.finishSegment(part, run, start, dur);
    }

    @Test
    public void segmentsComeBackOldestFirstWithTheirDurations() throws IOException {
        BufferStore.Run run = BufferStore.newRun(root, 0, "Lecture", 1000);
        segment(run, 40_000, 30_000);
        segment(run, 10_000, 30_000);
        BufferStore.partFile(run, 70_000).createNewFile(); // still being written: not listed
        List<BufferStore.Segment> s = BufferStore.segments(run);
        assertEquals(2, s.size());
        assertEquals(10_000, s.get(0).startWallMs);
        assertEquals(30_000, s.get(1).durationMs);
        assertEquals(60_000, BufferStore.totalMs(s));
    }

    @Test
    public void trimKeepsTheNewestAndAlwaysTheLast() throws IOException {
        BufferStore.Run run = BufferStore.newRun(root, 0, "Lecture", 1000);
        for (int i = 0; i < 5; i++) segment(run, i * 30_000L, 30_000);
        BufferStore.trim(run, 70_000); // room for two whole segments
        List<BufferStore.Segment> s = BufferStore.segments(run);
        assertEquals(2, s.size());
        assertEquals(3 * 30_000L, s.get(0).startWallMs);
        BufferStore.trim(run, 0); // never deletes the newest
        assertEquals(1, BufferStore.segments(run).size());
    }

    @Test
    public void timelineSkipsGapsBetweenSegments() throws IOException {
        BufferStore.Run run = BufferStore.newRun(root, 0, "Lecture", 1000);
        segment(run, 0, 30_000);
        segment(run, 100_000, 30_000); // recording was off for 70 s
        List<BufferStore.Segment> s = BufferStore.segments(run);
        BufferStore.Position p = BufferStore.locate(s, 45_000);
        assertEquals(1, p.index);
        assertEquals(15_000, p.offsetMs);
        assertEquals(15_000, BufferStore.offsetOfWall(s, 15_000));
        assertEquals(30_000, BufferStore.offsetOfWall(s, 60_000));   // in the gap: the next segment's start
        assertEquals(45_000, BufferStore.offsetOfWall(s, 115_000));
        assertEquals(60_000, BufferStore.offsetOfWall(s, 999_000));  // after the end
        assertEquals(30_000, BufferStore.startOfSegment(s, 1));
    }

    @Test
    public void locateClampsAtBothEnds() throws IOException {
        BufferStore.Run run = BufferStore.newRun(root, 0, "Lecture", 1000);
        segment(run, 0, 30_000);
        List<BufferStore.Segment> s = BufferStore.segments(run);
        assertEquals(0, BufferStore.locate(s, -5).offsetMs);
        assertEquals(29_999, BufferStore.locate(s, 999_999).offsetMs);
        assertNull(BufferStore.locate(new java.util.ArrayList<BufferStore.Segment>(), 0));
    }

    @Test
    public void runsListNewestFirstAndPruneKeepsTheActiveOne() throws IOException {
        BufferStore.Run a = BufferStore.newRun(root, 0, "A", 1000);
        BufferStore.Run b = BufferStore.newRun(root, 1, "B", 2000);
        BufferStore.Run c = BufferStore.newRun(root, 0, "C", 3000);
        segment(a, 0, 1000);
        List<BufferStore.Run> runs = BufferStore.listRuns(root);
        assertEquals("C", runs.get(0).name);
        assertEquals(1, runs.get(1).slot);
        BufferStore.prune(root, 1, a.dir); // keeps the newest (C) and the active one (A)
        runs = BufferStore.listRuns(root);
        assertEquals(2, runs.size());
        assertFalse(b.dir.exists());
        assertTrue(a.dir.exists());
        assertTrue(c.dir.exists());
    }

    @Test
    public void marksSurviveInOrder() {
        BufferStore.Run run = BufferStore.newRun(root, 0, "Lecture", 1000);
        BufferStore.addMark(run, 5000, "Stepped away");
        BufferStore.addMark(run, 9000, "Back");
        List<BufferStore.Mark> m = BufferStore.marks(run);
        assertEquals(2, m.size());
        assertEquals("Stepped away", m.get(0).label);
        assertEquals(9000, m.get(1).wallMs);
    }

    @Test
    public void onlyOldUnfinishedSegmentsAreStrays() throws IOException {
        BufferStore.Run run = BufferStore.newRun(root, 0, "Lecture", 1000);
        File stray = BufferStore.partFile(run, 1);
        File writing = BufferStore.partFile(run, 2);
        assertTrue(stray.createNewFile());
        assertTrue(writing.createNewFile());
        long now = System.currentTimeMillis();
        assertTrue(stray.setLastModified(now - 10 * 60_000L)); // a recorder that died ten minutes ago
        assertTrue(writing.setLastModified(now - 5_000L));     // being written right now
        BufferStore.removeStrays(root, 5 * 60_000L, now);
        assertFalse(stray.exists());
        assertTrue(writing.exists());
    }
}
