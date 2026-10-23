package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FaviconsTest {
    private static final String BASE = "https://video.example.com/shows/live";

    @Test
    public void biggestDeclaredIconWinsAndAddressesAreResolved() {
        String html = "<head><link rel=\"icon\" href=\"/favicon-16.png\" sizes=\"16x16\">"
                + "<link rel='icon' href='icons/big.png' sizes='96x96'>"
                + "<link rel=\"apple-touch-icon\" href=\"/touch.png\"></head>";
        // apple-touch without sizes counts as 180
        assertEquals("https://video.example.com/touch.png", Favicons.pickIconUrl(html, BASE));
        String noTouch = "<link rel=\"icon\" href=\"/favicon-16.png\" sizes=\"16x16\"><link rel='shortcut icon' href='icons/big.png' sizes='96x96'>";
        assertEquals("https://video.example.com/shows/icons/big.png", Favicons.pickIconUrl(noTouch, BASE));
    }

    @Test
    public void svgAndMaskIconsAreSkipped() {
        assertNull(Favicons.pickIconUrl("<link rel=\"icon\" href=\"/a.svg\"><link rel=\"mask-icon\" href=\"/m.png\">", BASE));
        assertEquals("https://video.example.com/b.png", Favicons.pickIconUrl("<link rel=\"icon\" href=\"/a.svg\"><link rel=\"icon\" href=\"/b.png\">", BASE));
    }

    @Test
    public void attributeOrderAndCaseDoNotMatter() {
        assertEquals("https://x.y/i.ico", Favicons.pickIconUrl("<LINK HREF=\"https://x.y/i.ico\" REL=\"Shortcut Icon\" />", BASE));
        assertNull(Favicons.pickIconUrl("<link rel=\"stylesheet\" href=\"/s.css\">", BASE));
    }

    @Test
    public void sizes() {
        assertEquals(32, Favicons.sizeOf("16x16 32x32"));
        assertEquals(0, Favicons.sizeOf("any"));
        assertEquals(0, Favicons.sizeOf(null));
    }
}
