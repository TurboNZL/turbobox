package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SavedLayoutTest {
    @Test
    public void presetsHaveOneRectanglePerPage() {
        for (int n = SavedLayout.MIN_PAGES; n <= SavedLayout.MAX_PAGES; n++) {
            SavedLayout l = new SavedLayout();
            l.count = n;
            assertEquals(n, l.rectangles().length);
        }
    }

    @Test
    public void savedRectanglesComeBackAndOnlyCountAreUsed() {
        SavedLayout l = new SavedLayout();
        l.count = 3;
        l.rects = MiniLayout.encode(new float[][]{{0f, 0f, 0.5f, 1f}, {0.5f, 0f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f, 0.5f}});
        float[][] r = l.rectangles();
        assertEquals(3, r.length);
        assertEquals(0.5f, r[0][2], 0.001f);
        assertEquals(1f, r[0][3], 0.001f);
        assertTrue(r[2][1] > 0.4f);
    }
}
