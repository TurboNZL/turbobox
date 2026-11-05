package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class CutAnswersTest {
    private static List<long[]> r(long... v) {
        List<long[]> l = new ArrayList<>();
        for (int i = 0; i < v.length; i += 2) l.add(new long[]{v[i], v[i + 1]});
        return l;
    }

    @Test
    public void unansweredStretchesAreKept() {
        List<CutAnswers.Window> a = new ArrayList<>();
        CutAnswers.add(a, new CutAnswers.Window(1000, 2000, r(1200, 1500)));
        List<long[]> keep = CutAnswers.finalKeep(a, 3000);
        assertEquals(3, keep.size());
        assertEquals(0, keep.get(0)[0]);
        assertEquals(1000, keep.get(0)[1]);
        assertEquals(1200, keep.get(1)[0]);
        assertEquals(2000, keep.get(2)[0]);
        assertEquals(3000, keep.get(2)[1]);
    }

    @Test
    public void noneAnswerCutsTheWholeWindow() {
        List<CutAnswers.Window> a = new ArrayList<>();
        CutAnswers.add(a, new CutAnswers.Window(0, 1000, new ArrayList<long[]>()));
        List<long[]> keep = CutAnswers.finalKeep(a, 1000);
        assertEquals(0, keep.size());
    }

    @Test
    public void newerAnswerReplacesOverlappingOnes() {
        List<CutAnswers.Window> a = new ArrayList<>();
        CutAnswers.add(a, new CutAnswers.Window(0, 1000, r(0, 500)));
        CutAnswers.add(a, new CutAnswers.Window(0, 5000, r(0, 100)));
        assertEquals(1, a.size());
        assertNotNull(CutAnswers.covering(a, 100, 900));
        assertNull(CutAnswers.covering(a, 100, 9000));
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        List<CutAnswers.Window> a = new ArrayList<>();
        CutAnswers.add(a, new CutAnswers.Window(10, 20, r(12, 15)));
        List<CutAnswers.Window> b = CutAnswers.fromJson(CutAnswers.toJson(a));
        assertEquals(1, b.size());
        assertEquals(15, b.get(0).keep.get(0)[1]);
    }
}
