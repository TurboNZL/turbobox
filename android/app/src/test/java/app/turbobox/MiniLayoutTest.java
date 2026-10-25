package app.turbobox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
