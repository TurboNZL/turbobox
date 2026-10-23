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
        Pin p = Pin.create("Stream", "https://video.example.com/live");
        p.zoom = 1.5f;
        p.volume = 0.8f;
        p.recordMode = 2;
        Pin q = Pin.fromJson(p.toJson());
        assertEquals(p.id, q.id);
        assertEquals("Stream", q.name);
        assertEquals(1.5f, q.zoom, 0.001f);
        assertEquals(0.8f, q.volume, 0.001f);
        assertEquals(2, q.recordMode);
        assertEquals("video.example.com", q.host());
        assertEquals("S", q.initial());
    }

    @Test
    public void oldPinsWithoutNewFieldsStillLoad() throws Exception {
        Pin q = Pin.fromJson(new org.json.JSONObject("{\"id\":\"a\",\"name\":\"x\",\"url\":\"https://x.y\"}"));
        assertEquals(1f, q.zoom, 0.001f);
        assertEquals(1f, q.volume, 0.001f);
    }

    @Test
    public void recordingDefaultsAndOldPins() throws Exception {
        Pin p = Pin.create("x", "https://x.y");
        assertTrue(p.recordsOnOpen(true));          // follows the default
        assertTrue(!p.recordsOnOpen(false));
        p.recordMode = 1;
        assertTrue(p.recordsOnOpen(false));         // always
        p.recordMode = 2;
        assertTrue(!p.recordsOnOpen(true));         // never
        // saved before the default existed: a ticked box meant "always", an unticked one meant "follow"
        assertEquals(1, Pin.fromJson(new org.json.JSONObject("{\"id\":\"a\",\"autoRecord\":true}")).recordMode);
        assertEquals(0, Pin.fromJson(new org.json.JSONObject("{\"id\":\"a\",\"autoRecord\":false}")).recordMode);
    }

    @Test
    public void aspectSurvivesJsonAndIsClamped() throws Exception {
        Pin p = Pin.create("x", "https://x.y");
        p.aspect = 2;
        p.mobile = true;
        assertEquals(2, Pin.fromJson(p.toJson()).aspect);
        assertTrue(Pin.fromJson(p.toJson()).mobile);
        assertTrue(!Pin.fromJson(new org.json.JSONObject("{\"id\":\"a\"}")).mobile);   // desktop unless asked
        assertEquals(0, Pin.fromJson(new org.json.JSONObject("{\"id\":\"a\"}")).aspect);
        assertEquals(Pin.ASPECTS.length - 1, Pin.fromJson(new org.json.JSONObject("{\"id\":\"a\",\"aspect\":99}")).aspect);
        assertEquals(Pin.ASPECTS.length, Pin.ASPECT_LABELS.length);
    }

    @Test
    public void pinsCanBeReordered() {
        java.util.List<Pin> l = new java.util.ArrayList<>();
        for (String id : new String[]{"a", "b", "c", "d"}) l.add(new Pin(id, id, "https://x.y"));
        assertTrue(PinStore.move(l, "c", -1));
        assertEquals("a,c,b,d", ids(l));
        assertTrue(PinStore.move(l, "a", 1));
        assertEquals("c,a,b,d", ids(l));
        assertTrue(!PinStore.move(l, "c", -1));   // already first
        assertTrue(!PinStore.move(l, "d", 1));    // already last
        assertTrue(!PinStore.move(l, "zzz", 1));
        assertTrue(PinStore.move(l, "c", 99));    // runs to the end
        assertEquals("a,b,d,c", ids(l));
    }

    private static String ids(java.util.List<Pin> l) {
        StringBuilder sb = new StringBuilder();
        for (Pin p : l) sb.append(sb.length() == 0 ? "" : ",").append(p.id);
        return sb.toString();
    }

    @Test
    public void profilesKeepTheirOwnPinsAndOrder() throws Exception {
        java.util.List<Pin> l = new java.util.ArrayList<>();
        for (String id : new String[]{"a", "b", "c", "d"}) l.add(new Pin(id, id, "https://x.y"));
        l.get(1).profileId = "work";
        l.get(3).profileId = "work";
        assertTrue(PinStore.moveAmong(l, "d", -1, "work"));   // swaps with b, skipping the default profile's c
        assertEquals("a,d,b,c", ids(l));
        assertTrue(PinStore.moveAmong(l, "d", 1, "work"));   // and back: after b
        assertEquals("a,b,d,c", ids(l));
        assertTrue(!PinStore.moveAmong(l, "d", 1, "work"));   // last of its profile
        assertEquals("default", Pin.fromJson(new org.json.JSONObject("{\"id\":\"a\"}")).profileId);
        l.get(0).profileId = "work";
        assertEquals("work", Pin.fromJson(l.get(0).toJson()).profileId);
    }
}
