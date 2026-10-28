package app.turbobox;

import java.util.ArrayDeque;
import java.util.Locale;

/** The last things that opened or closed a microphone (or the speech recogniser) in this app, with the time, to match against Android's privacy log. */
final class MicLog {
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private MicLog() {
    }

    static synchronized void add(String what) {
        LINES.addLast(String.format(Locale.US, "%tT  %s", System.currentTimeMillis(), what));
        while (LINES.size() > 150) LINES.removeFirst();
    }

    static synchronized String text() {
        if (LINES.isEmpty()) return "Nothing has opened a microphone since the app started.";
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }
}
