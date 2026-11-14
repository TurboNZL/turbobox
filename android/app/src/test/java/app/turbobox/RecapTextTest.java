package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class RecapTextTest {
    private static final String MD = "TITLE: **Cells and energy**\n## Important stuff (TL;DR)\n- [12:30] Essay due Friday\n"
            + "## Chapters\n- [0:00] Intro\n## Worth watching\n- 12:00 - 13:30 | the assignment\n- 1:02:00 - 1:05:00 | the exam hints\n## Notes\n- [5:00] x\n";

    @Test
    public void readsTitleAndSections() {
        assertEquals("Cells and energy", RecapText.title(MD));
        assertTrue(RecapText.section(MD, "Important stuff").contains("Essay due Friday"));
        assertTrue(!RecapText.section(MD, "Important stuff").contains("Intro"));
    }

    @Test
    public void worthWatchingRanges() {
        List<long[]> r = RecapText.worthWatching(MD);
        assertEquals(2, r.size());
        assertEquals(12 * 60_000L, r.get(0)[0]);
        assertEquals(3_900_000L, r.get(1)[1]);
    }

    @Test
    public void promptsCarryTheirInputs() {
        String p = RecapText.notesPrompt("Recording", 7_200_000, 0, 1_800_000, "[0:05] hello");
        assertTrue(p.contains("0:00 to 30:00"));
        assertTrue(p.contains("[0:05] hello"));
        assertTrue(RecapText.finalPrompt("Recording", 7_200_000, "- [1:00] a").contains("## Important stuff (TL;DR)"));
        assertTrue(p.contains("\n"));
    }

    @Test
    public void displayShapes() {
        int[] wide = DisplayShape.sizeFor(1080, 2340, 16f / 9f);
        assertEquals(2340, wide[0]);
        assertEquals(1316, wide[1]);
        int[] tall = DisplayShape.sizeFor(1080, 2340, 9f / 16f);
        assertEquals(1080, tall[0]);
        assertEquals(1920, tall[1]);
        int[] sq = DisplayShape.sizeFor(1080, 2340, 1f);
        assertEquals(1080, sq[0]);
        assertEquals(1080, sq[1]);
        int[] phone = DisplayShape.sizeFor(1080, 2340, 0f);
        assertEquals(2340, phone[1]);
    }
}
