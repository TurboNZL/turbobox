package app.turbobox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SplitMathTest {
    @Test
    public void sharesSplitTheSpace() {
        assertArrayEquals(new int[]{300, 700}, SplitMath.allocate(new float[]{0.3f, 0.7f}, null, 1000));
        assertArrayEquals(new int[]{500, 500}, SplitMath.allocate(new float[]{1f, 1f}, new int[]{0, 0}, 1000));
    }

    @Test
    public void sizesAlwaysAddUp() {
        int[] s = SplitMath.allocate(new float[]{1f, 1f, 1f}, null, 1000);
        assertEquals(1000, s[0] + s[1] + s[2]);
    }

    @Test
    public void aCappedPaneLeavesItsEmptySpaceToTheOthers() {
        int[] s = SplitMath.allocate(new float[]{0.5f, 0.5f}, new int[]{300, 0}, 1000);
        assertArrayEquals(new int[]{300, 700}, s);
    }

    @Test
    public void leftoverIsSharedByShare() {
        int[] s = SplitMath.allocate(new float[]{0.5f, 0.25f, 0.25f}, new int[]{200, 0, 0}, 1000);
        assertEquals(200, s[0]);
        assertEquals(400, s[1]);
        assertEquals(400, s[2]);
    }

    @Test
    public void aCapBiggerThanTheShareChangesNothing() {
        assertArrayEquals(new int[]{500, 500}, SplitMath.allocate(new float[]{0.5f, 0.5f}, new int[]{900, 0}, 1000));
    }

    @Test
    public void whenEveryPaneIsCappedTheSharesStand() {
        assertArrayEquals(new int[]{500, 500}, SplitMath.allocate(new float[]{0.5f, 0.5f}, new int[]{100, 100}, 1000));
    }

    @Test
    public void capsCascade() {
        // The first leaves room, the second would then overflow its own cap, the third takes the rest.
        int[] s = SplitMath.allocate(new float[]{1f, 1f, 1f}, new int[]{100, 400, 0}, 1000);
        assertArrayEquals(new int[]{100, 400, 500}, s);
    }

    @Test
    public void dragMovesTheBoundaryAndKeepsMinimums() {
        float[] r = SplitMath.drag(new float[]{0.5f, 0.5f}, 0, 0.2f);
        assertEquals(0.7f, r[0], 1e-4);
        assertEquals(0.3f, r[1], 1e-4);
        r = SplitMath.drag(new float[]{0.5f, 0.5f}, 0, 0.9f);
        assertEquals(1f - SplitMath.MIN_SHARE, r[0], 1e-4);
        assertEquals(SplitMath.MIN_SHARE, r[1], 1e-4);
        r = SplitMath.drag(new float[]{0.2f, 0.3f, 0.5f}, 1, -0.5f);
        assertEquals(0.2f, r[0], 1e-4);
        assertEquals(SplitMath.MIN_SHARE, r[1], 1e-4);
        assertEquals(0.65f, r[2], 1e-4);
    }

    @Test
    public void savedSharesRoundTripAndBadOnesAreRefused() {
        float[] r = SplitMath.decode(SplitMath.encode(new float[]{0.3f, 0.7f}), 2);
        assertEquals(0.3f, r[0], 1e-3);
        assertEquals(0.7f, r[1], 1e-3);
        assertNull(SplitMath.decode("0.3,0.7", 3));
        assertNull(SplitMath.decode("a,b", 2));
        assertNull(SplitMath.decode("0,1", 2));
        assertNull(SplitMath.decode("", 2));
    }

    @Test
    public void miniLayoutKeepsRectanglesInsideAndRoundTrips() {
        float[] c = MiniLayout.clamp(new float[]{0.9f, -0.2f, 0.5f, 0.01f});
        assertTrue(c[0] + c[2] <= 1.0001f && c[0] >= 0f && c[1] >= 0f);
        assertEquals(MiniLayout.MIN, c[3], 1e-4);
        float[][] in = MiniLayout.defaults();
        in[1] = new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        float[][] out = MiniLayout.decode(MiniLayout.encode(in));
        assertEquals(0.3f, out[1][2], 1e-3);
        assertEquals(1f, out[0][2], 1e-3);
        assertEquals(MiniLayout.defaults()[3][0], MiniLayout.decode("junk")[3][0], 1e-4);
        assertEquals(MiniLayout.defaults()[2][1], MiniLayout.decode("")[2][1], 1e-4);
    }

    @Test
    public void miniShapesStayInsideWhatAndroidAccepts() {
        for (int i = 0; i < MiniLayout.SHAPES.length; i++) {
            int[] s = MiniLayout.shape(i);
            double ratio = s[0] / (double) s[1];
            assertTrue(ratio >= 1 / 2.39 && ratio <= 2.39);
        }
        int[] wide = SplitLayout.shapeOfRatio(5f);
        assertEquals(239, wide[0]);
        int[] tall = SplitLayout.shapeOfRatio(0.1f);
        assertEquals(239, tall[1]);
    }

    @Test
    public void siteDataHelpers() {
        assertEquals(java.util.Arrays.asList("m.youtube.com", "youtube.com"), SiteData.hostAndParents("m.youtube.com"));
        assertEquals(java.util.Arrays.asList("a.b.example.com", "b.example.com", "example.com"), SiteData.hostAndParents("a.b.example.com"));
        assertEquals(java.util.Arrays.asList("192.168.0.1"), SiteData.hostAndParents("192.168.0.1"));
        assertEquals(java.util.Arrays.asList("a", "b"), SiteData.cookieNames("a=1; b=2; a=3"));
        assertEquals(0, SiteData.cookieNames(null).size());
        assertEquals("example.com", SiteData.hostOf("https://Example.com/x"));
        assertNull(SiteData.hostOf("about:blank"));
    }
}
