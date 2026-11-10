package app.turbobox;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class SiteDataTest {
    @Test
    public void foldersAlongAnAddress() {
        assertEquals(Arrays.asList("/"), SiteData.foldersOf("https://a.example.com/"));
        assertEquals(Arrays.asList("/", "/v2", "/v2/", "/v2/x", "/v2/x/"), SiteData.foldersOf("https://a.example.com/v2/x?q=1#/frag"));
    }
}
