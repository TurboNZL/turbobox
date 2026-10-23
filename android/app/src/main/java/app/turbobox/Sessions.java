package app.turbobox;

import java.util.ArrayList;
import java.util.List;

/**
 * The session slots: the first four are the ones you open pages into, used in order. The slots after them are temporary: a workflow that
 * runs while both are busy gets one for as long as it needs, and it goes away when the page closes.
 */
final class Sessions {
    static final int PERMANENT = 4;
    static final int COUNT = 8;
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

    /** The lowest permanent slot (A, B) with nothing running, or -1 when both are in use. */
    static int firstFreeSlot() {
        for (int i = 0; i < PERMANENT; i++) if (!SLOTS[i].isActive()) return i;
        return -1;
    }

    /** The lowest slot of any kind with nothing running (a workflow may borrow a temporary one), or -1. */
    static int firstFreeAnySlot() {
        for (int i = 0; i < COUNT; i++) if (!SLOTS[i].isActive()) return i;
        return -1;
    }

    /** Is every open page muted (and is there one)? */
    static boolean allMuted() {
        List<Session> list = active();
        for (Session s : list) if (!s.isMuted()) return false;
        return !list.isEmpty();
    }

    /** Mutes or unmutes every open page: the notification's and the Mini window's button. */
    static void toggleAllMuted() {
        boolean target = !allMuted();
        for (Session s : active()) s.setMuted(target);
    }

    /** "A | " when more than one page is open (so a single page is just its name), else "". */
    static String tag(Session s) {
        return active().size() > 1 ? letter(s) + " | " : "";
    }

    /**
     * The letter a page goes by: in the order the open pages were opened, so the first one open is always A whichever
     * slot it sits in.
     */
    static char letter(Session s) {
        int rank = 0;
        for (Session o : SLOTS) {
            if (o != s && o.isActive() && (!s.isActive() || o.openedSeq() < s.openedSeq())) rank++;
        }
        return (char) ('A' + rank);
    }
}
