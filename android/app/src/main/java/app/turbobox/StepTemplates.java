package app.turbobox;

import java.util.ArrayList;
import java.util.List;

/**
 * Every workflow step as a form: what to ask the user for, and how the answers become the line of text the parser
 * reads. The editor shows one button per template; tapping it asks for the fields and puts the finished line in the
 * steps box, so nobody has to remember the syntax. No Android classes, so the JVM tests parse every line it can
 * produce.
 */
final class StepTemplates {
    private StepTemplates() {
    }

    enum FieldType { TIME, TEXT, TARGET, NUMBER, CHOICE }

    static final class Field {
        final FieldType type;
        final String label;
        final String hint;
        final String[] options;   // CHOICE only
        final String def;         // starting value; seconds for TIME

        Field(FieldType type, String label, String hint, String def, String... options) {
            this.type = type;
            this.label = label;
            this.hint = hint;
            this.def = def;
            this.options = options;
        }
    }

    interface Formatter {
        String format(String[] values);
    }

    static final class Template {
        final String group;
        final String title;
        final String help;
        final Field[] fields;
        final boolean pageStep;      // looks for something on the page: can be optional and can have its own time limit
        final Formatter formatter;

        Template(String group, String title, String help, boolean pageStep, Formatter formatter, Field... fields) {
            this.group = group;
            this.title = title;
            this.help = help;
            this.pageStep = pageStep;
            this.formatter = formatter;
            this.fields = fields;
        }

        /** The line for these answers, one per field (TIME as seconds); null when a required answer is empty. */
        String line(String[] values) {
            for (int i = 0; i < fields.length; i++) {
                boolean required = fields[i].type == FieldType.TARGET || (fields[i].type == FieldType.TEXT && fields[i].def != null && !fields[i].def.isEmpty());
                if (required && (values[i] == null || values[i].trim().isEmpty())) return null;
            }
            return formatter.format(values);
        }

        /** The answers a form starts with. */
        String[] defaults() {
            String[] v = new String[fields.length];
            for (int i = 0; i < v.length; i++) v[i] = fields[i].def == null ? "" : fields[i].def;
            return v;
        }
    }

    private static final String TARGET_HINT = "The words on it, or css:#selector";

    private static final List<Template> ALL = new ArrayList<>();

    static {
        String wait = "Wait and watch";
        ALL.add(new Template(wait, "Wait", "Pause before the next step.", false, v -> "wait " + duration(secs(v[0])),
                new Field(FieldType.TIME, "How long?", null, "30")));
        ALL.add(new Template(wait, "Wait until something shows", "Carries on once the words appear on the page.", true,
                v -> "waitfor " + v[0].trim(),
                new Field(FieldType.TARGET, "What should show up?", TARGET_HINT, "Leave")));

        String page = "On the page";
        ALL.add(new Template(page, "Click", "Click a button or link.", true, v -> "click " + v[0].trim(),
                new Field(FieldType.TARGET, "What should it click?", TARGET_HINT, "Join")));
        ALL.add(new Template(page, "Type into a field", "Fill in a text box.", true,
                v -> "type " + v[0].trim() + " = " + v[1].trim(),
                new Field(FieldType.TARGET, "Which field?", TARGET_HINT, "Name"),
                new Field(FieldType.TEXT, "What to type", null, "Your name")));
        ALL.add(new Template(page, "Press a key", "Enter, Escape, Tab, arrows...", false, v -> "key " + v[0],
                new Field(FieldType.CHOICE, "Which key?", null, "Enter", "Enter", "Escape", "Tab", "Space", "ArrowUp", "ArrowDown",
                        "ArrowLeft", "ArrowRight")));
        ALL.add(new Template(page, "Scroll", "Move the page up or down.", false,
                v -> v[0].equals("top") || v[0].equals("bottom") ? "scroll " + v[0] : "scroll " + v[0] + " " + number(v[1], 600),
                new Field(FieldType.CHOICE, "Which way?", null, "down", "down", "up", "top", "bottom"),
                new Field(FieldType.NUMBER, "How far (pixels)?", "Ignored for top and bottom", "600")));
        ALL.add(new Template(page, "Load the page again", "Open the pinned address again.", false, v -> "open"));
        ALL.add(new Template(page, "Run a script", "JavaScript on the page. For people who know what they want.", false, v -> "js " + v[0].trim(),
                new Field(FieldType.TEXT, "Script", null, "document.title")));

        String sound = "Sound";
        ALL.add(new Template(sound, "Mute", "Switch your microphone off for this page.", false, v -> "mute"));
        ALL.add(new Template(sound, "Unmute", "Switch your microphone on again.", false, v -> "unmute"));
        ALL.add(new Template(sound, "Page volume", "How loud the page plays, 0 to 200%.", false, v -> "volume " + Math.max(0, Math.min(200, number(v[0], 100))),
                new Field(FieldType.NUMBER, "Volume (%)", "0 to 200", "100")));

        String rec = "Recording";
        ALL.add(new Template(rec, "Start recording", "Start the rolling recording.", false, v -> "record start"));
        ALL.add(new Template(rec, "Stop recording", "Stop it.", false, v -> "record stop"));
        ALL.add(new Template(rec, "Add a mark", "A bookmark in the recording.", false,
                v -> v[0].trim().isEmpty() ? "mark" : "mark " + v[0].trim(),
                new Field(FieldType.TEXT, "What is starting?", "Optional", "")));
        ALL.add(new Template(rec, "Step away", "Freeze and silence the page here; it keeps recording.", false, v -> "step away"));
        ALL.add(new Template(rec, "Step back", "Show and hear the page again.", false, v -> "step back"));
        ALL.add(new Template(rec, "Live captions on", "Speech to text while it records.", false, v -> "transcribe on"));
        ALL.add(new Template(rec, "Live captions off", "Stop the captions.", false, v -> "transcribe off"));

        String end = "Finishing";
        ALL.add(new Template(end, "Hang up (close the page)", "Close the page now, or after a wait.", false,
                v -> secs(v[0]) == 0 ? "hangup" : "hangup " + duration(secs(v[0])),
                new Field(FieldType.TIME, "Wait how long before hanging up?", "0 means right away", "0")));
        ALL.add(new Template(end, "Cancel the hang-up timer", "Take back an earlier hang-up.", false, v -> "hangup off"));
    }

    static List<Template> all() {
        return ALL;
    }

    private static long secs(String v) {
        try {
            return Math.max(0, Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int number(String v, int fallback) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Seconds as the parser reads them: 5400 becomes "1h30m", 45 "45s", 0 "0s". */
    static String duration(long seconds) {
        if (seconds <= 0) return "0s";
        long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append('h');
        if (m > 0) sb.append(m).append('m');
        if (s > 0) sb.append(s).append('s');
        return sb.toString();
    }

    /** The line with the "? " (fine if it fails) and "within" (how long to keep trying) the user asked for. */
    static String decorate(String line, boolean optional, long withinSeconds) {
        String out = line;
        if (withinSeconds > 0) out += " within " + duration(withinSeconds);
        return optional ? "? " + out : out;
    }

    static final class Insert {
        final String text;
        final int cursor;

        Insert(String text, int cursor) {
            this.text = text;
            this.cursor = cursor;
        }
    }

    /**
     * Puts {@code line} on its own line just after the line the cursor is in (or on the cursor's line when that one is
     * empty). The cursor ends up after the new line.
     */
    static Insert insertAfterCursor(String text, int cursor, String line) {
        String t = text == null ? "" : text;
        int c = Math.max(0, Math.min(t.length(), cursor));
        int lineStart = t.lastIndexOf('\n', c - 1) + 1;
        int lineEnd = t.indexOf('\n', c);
        if (lineEnd < 0) lineEnd = t.length();
        String current = t.substring(lineStart, lineEnd);
        String out;
        int at;
        if (current.trim().isEmpty()) {
            out = t.substring(0, lineStart) + line + "\n" + t.substring(Math.min(t.length(), lineEnd + (lineEnd < t.length() ? 1 : 0)));
            at = lineStart + line.length();
        } else {
            out = t.substring(0, lineEnd) + "\n" + line + (lineEnd == t.length() ? "\n" : "") + t.substring(lineEnd);
            at = lineEnd + 1 + line.length();
        }
        return new Insert(out, Math.min(at, out.length()));
    }

    /** Seconds typed as hours, minutes and seconds boxes (blank counts as 0). */
    static long toSeconds(String h, String m, String s) {
        return secs(h) * 3600 + secs(m) * 60 + secs(s);
    }
}
