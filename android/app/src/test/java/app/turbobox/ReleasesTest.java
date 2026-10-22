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
    public void newestReleaseWithAnApkWinsAndDraftsAreSkipped() throws Exception {
        JSONArray list = new JSONArray("[{\"tag_name\":\"v3\",\"draft\":true,\"assets\":[{\"name\":\"a.apk\",\"url\":\"u3\"}]},"
                + "{\"tag_name\":\"v2\",\"assets\":[]},"
                + "{\"tag_name\":\"v1\",\"prerelease\":true,\"assets\":[{\"name\":\"x.txt\",\"url\":\"ux\"},{\"name\":\"turbobox-1-android.apk\",\"url\":\"u1\"}]}]");
        assertArrayEquals(new String[]{"v1", "u1"}, Releases.newestWithApk(list));
        assertNull(Releases.newestWithApk(new JSONArray("[]")));
    }
}
