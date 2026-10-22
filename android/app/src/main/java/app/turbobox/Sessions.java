package app.turbobox;

import java.util.ArrayList;
import java.util.List;

/** The two session slots. Slot A is used first. */
final class Sessions {
    static final int COUNT = 2;
    private static final Session[] SLOTS = new Session[COUNT];

    static {
        for (int i = 0; i < COUNT; i++) SLOTS[i] = new Session(i);
    }

    private Sessions() {
    }

    static Session get(int slot) {
        return SLOTS[slot];
    }

    static List<Session> all() {
        List<Session> l = new ArrayList<>();
        for (Session s : SLOTS) l.add(s);
        return l;
    }

    static List<Session> active() {
        List<Session> l = new ArrayList<>();
        for (Session s : SLOTS) if (s.isActive()) l.add(s);
        return l;
    }

    static boolean anyActive() {
        for (Session s : SLOTS) if (s.isActive()) return true;
        return false;
    }

    /** The running session showing this pin, or null. */
    static Session forPin(String pinId) {
        for (Session s : SLOTS) if (s.isActive() && s.pin() != null && s.pin().id.equals(pinId)) return s;
        return null;
    }

    /** The lowest slot with nothing running, or -1 when both are in use. */
    static int firstFreeSlot() {
        for (int i = 0; i < COUNT; i++) if (!SLOTS[i].isActive()) return i;
        return -1;
    }
}
