package app.turbobox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PinTest {
    @Test
    public void addressesAreCompletedOrRejected() {
        assertEquals("https://meet.example.com/room", Pin.normalizeUrl("meet.example.com/room"));
        assertEquals("http://192.168.1.5:8080/x", Pin.normalizeUrl(" http://192.168.1.5:8080/x "));
        assertEquals("https://a.b", Pin.normalizeUrl("HTTPS://a.b".toLowerCase()));
        assertNull(Pin.normalizeUrl(""));
        assertNull(Pin.normalizeUrl("two words.com"));
        assertNull(Pin.normalizeUrl("ftp://example.com"));
        assertNull(Pin.normalizeUrl("javascript:alert(1)"));
        assertNull(Pin.normalizeUrl("https://"));
        assertNull(Pin.normalizeUrl(null));
    }

    @Test
    public void roundTripsThroughJson() {
        Pin p = Pin.create("Lecture", "https://uni.example.com/live");
        p.zoom = 1.5f;
        p.volume = 0.8f;
        p.autoRecord = true;
        Pin q = Pin.fromJson(p.toJson());
        assertEquals(p.id, q.id);
        assertEquals("Lecture", q.name);
        assertEquals(1.5f, q.zoom, 0.001f);
        assertEquals(0.8f, q.volume, 0.001f);
        assertTrue(q.autoRecord);
        assertEquals("uni.example.com", q.host());
        assertEquals("L", q.initial());
    }

    @Test
    public void oldPinsWithoutNewFieldsStillLoad() throws Exception {
        Pin q = Pin.fromJson(new org.json.JSONObject("{\"id\":\"a\",\"name\":\"x\",\"url\":\"https://x.y\"}"));
        assertEquals(1f, q.zoom, 0.001f);
        assertEquals(1f, q.volume, 0.001f);
    }
}
