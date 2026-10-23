package app.turbobox;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class SplitLayoutTest {
    @Test
    public void rowsFollowTheNumberOfPagesAndTheScreen() {
        assertArrayEquals(new int[]{1}, SplitLayout.rows(1, false));
        assertArrayEquals(new int[]{1, 1}, SplitLayout.rows(2, false));
        assertArrayEquals(new int[]{2}, SplitLayout.rows(2, true));
        assertArrayEquals(new int[]{1, 1, 1}, SplitLayout.rows(3, false));
        assertArrayEquals(new int[]{3}, SplitLayout.rows(3, true));
        assertArrayEquals(new int[]{2, 2}, SplitLayout.rows(4, false));
        assertArrayEquals(new int[]{2, 2}, SplitLayout.rows(4, true));
        assertArrayEquals(new int[]{2, 2}, SplitLayout.rows(9, true));   // four is the most
    }

    @Test
    public void miniWindowLayouts() {
        assertArrayEquals(new int[]{3}, SplitLayout.pipRows(3, SplitLayout.SIDE_BY_SIDE, false));
        assertArrayEquals(new int[]{1, 1}, SplitLayout.pipRows(2, SplitLayout.STACKED, true));
        assertArrayEquals(new int[]{1}, SplitLayout.pipRows(2, SplitLayout.OVERLAY, true));
        assertArrayEquals(new int[]{2, 2}, SplitLayout.pipRows(4, SplitLayout.LIKE_SPLIT, false));
    }

    @Test
    public void windowShapesStayInsideWhatAndroidAccepts() {
        assertArrayEquals(new int[]{16, 9}, SplitLayout.shape(new int[]{1}));
        assertArrayEquals(new int[]{32, 18}, SplitLayout.shape(new int[]{2, 2}));
        assertArrayEquals(new int[]{16, 18}, SplitLayout.shape(new int[]{1, 1}));
        assertArrayEquals(new int[]{239, 100}, SplitLayout.shape(new int[]{2}));      // 32:9 is too wide
        assertArrayEquals(new int[]{100, 239}, SplitLayout.shape(new int[]{1, 1, 1, 1, 1}));
    }
}
