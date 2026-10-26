package app.turbobox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MiniLayoutTest {
    @Test
    public void defaultRowsForEachCount() {
        assertArrayEquals(new int[]{1, 1, 1}, MiniLayout.stackedRows(3));
        assertArrayEquals(new int[]{2}, MiniLayout.gridRows(2));
        assertArrayEquals(new int[]{2, 1}, MiniLayout.gridRows(3));
        assertArrayEquals(new int[]{2, 2}, MiniLayout.gridRows(4));
    }

    @Test
    public void orderRoundTripsAndFallsBackToPageOneAtTheBack() {
        assertArrayEquals(new int[]{2, 0, 1}, MiniLayout.decodeOrder(MiniLayout.encodeOrder(new int[]{2, 0, 1}), 3));
        assertArrayEquals(new int[]{0, 1, 2}, MiniLayout.decodeOrder("", 3));
        assertArrayEquals(new int[]{0, 1, 2}, MiniLayout.decodeOrder("0,0,1", 3));   // not a permutation
        assertArrayEquals(new int[]{0, 1}, MiniLayout.decodeOrder("0,1,2", 2));      // wrong length
    }

    @Test
    public void packedArrangementOnlyUnpacksForItsPageCount() {
        float[][] r = MiniLayout.fromRows(new int[]{2, 1});
        String packed = MiniLayout.pack(r, new int[]{1, 2, 0});
        float[][] back = MiniLayout.unpackRects(packed, 3);
        assertEquals(3, back.length);
        assertEquals(0.5f, back[1][0], 0.001f);
        assertArrayEquals(new int[]{1, 2, 0}, MiniLayout.unpackOrder(packed, 3));
        assertNull(MiniLayout.unpackRects(packed, 4));
        assertNull(MiniLayout.unpackRects("", 2));
    }

    @Test
    public void floatingPagesSurvivePacking() {
        float[][] r = MiniLayout.splitDefaults();
        boolean[] f = {false, true, false, false};
        String packed = MiniLayout.pack(r, new int[]{0, 1, 2, 3}, f);
        assertTrue(java.util.Arrays.equals(f, MiniLayout.unpackFloats(packed, 4)));
        assertTrue(!MiniLayout.pack(r, new int[]{0, 1, 2, 3}).contains("|0|") && MiniLayout.unpackFloats(MiniLayout.pack(r, new int[]{0, 1, 2, 3}), 4)[1] == false);
        assertEquals(4, MiniLayout.unpackRects(packed, 4).length);
    }

    @Test
    public void snappingMakesEdgesMeetOnlyWhenClose() {
        float[][] all = {{0f, 0f, 0.5f, 0.5f}, {0.52f, 0.1f, 0.3f, 0.3f}};
        float[] moved = MiniLayout.snap(all[1], all, 1, false, 0.03f);
        assertEquals(0.5f, moved[0], 0.0001f);          // its left edge met the other page's right edge
        float[] far = MiniLayout.snap(new float[]{0.6f, 0.1f, 0.3f, 0.3f}, all, 1, false, 0.03f);
        assertEquals(0.6f, far[0], 0.0001f);             // too far: left alone
        float[] grown = MiniLayout.snap(new float[]{0.52f, 0.1f, 0.47f, 0.3f}, all, 1, true, 0.03f);
        assertEquals(1f, grown[0] + grown[2], 0.0001f);  // the right edge met the window's edge
    }
}
