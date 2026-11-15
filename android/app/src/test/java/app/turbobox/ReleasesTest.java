package app.turbobox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;

public class ReleasesTest {
    @Test
    public void versionsCompareNumberByNumber() {
        assertTrue(Releases.isNewer("v2026.10.10", "2026.10.9"));
        assertFalse(Releases.isNewer("v2026.10.2", "2026.10.2"));
        assertFalse(Releases.isNewer("2026.9.30", "2026.10.0"));
        assertTrue(Releases.isNewer("2027.1.0", "2026.12.40"));
        assertTrue(Releases.isNewer("v2026.10.1", "0.0.0-dev"));
    }

    @Test
    public void newestReleaseWithTheAppWinsAndDraftsAreSkipped() throws Exception {
        JSONArray list = new JSONArray("[{\"tag_name\":\"v3\",\"draft\":true,\"assets\":[{\"name\":\"turbobox-3-android.apk\",\"url\":\"u3\"}]},"
                + "{\"tag_name\":\"v2\",\"assets\":[]},"
                + "{\"tag_name\":\"v1\",\"prerelease\":true,\"assets\":[{\"name\":\"x.txt\",\"url\":\"ux\"},{\"name\":\"turbobox-1-android.apk\",\"url\":\"u1\"}]}]");
        assertArrayEquals(new String[]{"1", "u1"}, Releases.newestWithApk(list));
        assertNull(Releases.newestWithApk(new JSONArray("[]")));
    }

    @Test
    public void aTestReleaseCarryingTheOldAppOffersNothingNewer() throws Exception {
        // v2026.11.14 is a feature test: it holds the test apps and the previous real app, still named 2026.11.13.
        JSONArray list = new JSONArray("[{\"tag_name\":\"v2026.11.14\",\"prerelease\":true,\"assets\":["
                + "{\"name\":\"turbobox-2026.11.13-android.apk\",\"url\":\"uo\"},{\"name\":\"sandbox-test.apk\",\"url\":\"us\"}]},"
                + "{\"tag_name\":\"v2026.11.13\",\"assets\":[{\"name\":\"turbobox-2026.11.13-android.apk\",\"url\":\"uo2\"}]}]");
        String[] found = Releases.newestWithApk(list);
        assertArrayEquals(new String[]{"2026.11.13", "uo"}, found);
        assertFalse(Releases.isNewer(found[0], "2026.11.13"));
        assertTrue(Releases.isNewer(found[0], "2026.11.12"));
    }
}
