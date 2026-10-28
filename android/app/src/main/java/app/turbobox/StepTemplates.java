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

    enum FieldType { TIME, TEXT, TARGET, NUMBER, CHOICE, CLOCK, WORKFLOW, PAGES, PAGE }

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
                boolean required = fields[i].type == FieldType.TARGET || fields[i].type == FieldType.PAGES || fields[i].type == FieldType.PAGE || (fields[i].type == FieldType.TEXT && fields[i].def != null && !fields[i].def.isEmpty());
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

        ALL.add(new Template(wait, "Wait until a time", "Waits for that time of day (today, or tomorrow if it has passed).", false, v -> "wait until " + v[0].trim(),
                new Field(FieldType.CLOCK, "At what time?", null, "22:00")));
        ALL.add(new Template(wait, "Run another workflow", "Starts it and carries on; it runs by itself from there.", false, v -> "run " + v[0].trim(),
                new Field(FieldType.WORKFLOW, "Which workflow?", null, "Other workflow")));

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

        String pageGroup = "The page and other pages";
        ALL.add(new Template(pageGroup, "Pause the page", "Freeze and silence it on this phone. It keeps running.", false, v -> "pause"));
        ALL.add(new Template(pageGroup, "Resume the page", "Show and hear it again.", false, v -> "resume"));
        ALL.add(new Template(pageGroup, "Close the other pages", "Closes every running page except this workflow's own pages, without asking.", false,
                v -> v[0].equals("every page") ? "close all" : "close others",
                new Field(FieldType.CHOICE, "Which pages?", null, "all but this workflow's pages", "all but this workflow's pages", "every page")));
        ALL.add(new Template(pageGroup, "Close all but chosen pages", "Closes every running page except the ones you pick (and, if you like, this workflow's own pages).", false,
                v -> "close keep " + v[0].trim() + (v[1].startsWith("Yes") ? " and workflow" : ""),
                new Field(FieldType.PAGES, "Pages that stay open", null, "page"),
                new Field(FieldType.CHOICE, "Also keep this workflow's pages?", null, "No", "No", "Yes, keep them too")));
        ALL.add(new Template(pageGroup, "Focus this workflow's page", "Bring the page running this workflow to the front. If several workflows or pages run at once, the first to ask keeps it until it finishes.", false,
                v -> "focus this"));
        ALL.add(new Template(pageGroup, "Focus a page", "Bring one page to the front, if it is running. Skipped when it is not.", false,
                v -> "focus " + v[0].trim(),
                new Field(FieldType.PAGE, "Page to bring to the front", null, "page")));
        ALL.add(new Template(pageGroup, "Press a media key", "Play, pause or skip in whichever music app played last.", false, v -> "media " + v[0],
                new Field(FieldType.CHOICE, "Which key?", null, "play", "play", "pause", "toggle", "next", "previous")));
        ALL.add(new Template(pageGroup, "Open another app", "Spotify, YouTube Music, or any app by its package name.", false, v -> "app " + v[0].trim(),
                new Field(FieldType.TEXT, "App", "spotify, youtube music, or a package name", "spotify")));

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

        String end = "Finishing";
        ALL.add(new Template(end, "Hang up (close the page)", "Close the page now, or after a wait.", false,
                v -> secs(v[0]) == 0 ? "hangup" : "hangup " + duration(secs(v[0])),
                new Field(FieldType.TIME, "Wait how long before hanging up?", "0 means right away", "0")));
        ALL.add(new Template(end, "Cancel the hang-up timer", "Take back an earlier hang-up.", false, v -> "hangup off"));
    }

    static List<Template> all() {
        return ALL;
    }

    // ---- icons and colours (the editor maps the key to its drawn icon) ----------------------------------------------------

    static final int C_WAIT = 0xFFFD971F, C_PAGE = 0xFF66D9EF, C_SOUND = 0xFFF92672, C_REC = 0xFFE10600, C_CLOSE = 0xFFFBBF24, C_FLOW = 0xFFB48EF7;

    /** The name of the icon (an IconButton.Icon constant) for a kind of step. */
    static String iconKey(WorkflowParser.Kind k) {
        switch (k) {
            case OPEN: return "ROTATE";
            case WAIT: case WAIT_UNTIL: return "TIMER";
            case WAITFOR: return "EYE";
            case CLICK: case MARK: return "TARGET";
            case TYPE: case KEY: return "EDIT";
            case SCROLL: return "DOWN";
            case MUTE: return "MIC_OFF";
            case UNMUTE: return "MIC";
            case VOLUME: return "SPEAKER";
            case FOCUS: return "LAYOUT";
            case HANGUP: case CLOSE: case CLOSE_OTHERS: case CLOSE_ALL: case CLOSE_KEEP: return "CLOSE";
            case RECORD_START: return "RECORD";
            case RECORD_STOP: return "STOP";
            case STEP_AWAY: return "BOTH";
            case STEP_BACK: case RESUME: case MEDIA: return "PLAY";
            case PAUSE: return "PAUSE";
            case TRANSCRIBE_ON: case TRANSCRIBE_OFF: return "CAPTIONS";
            case JS: return "GEAR";
            case APP: return "MINI";
            case RUN: return "SHARE";
            default: return "GEAR";
        }
    }

    static int colorFor(WorkflowParser.Kind k) {
        switch (k) {
            case WAIT: case WAIT_UNTIL: return C_WAIT;
            case OPEN: case WAITFOR: case CLICK: case TYPE: case KEY: case SCROLL: case JS: return C_PAGE;
            case MUTE: case UNMUTE: case VOLUME: case MEDIA: return C_SOUND;
            case RECORD_START: case RECORD_STOP: case STEP_AWAY: case STEP_BACK: case PAUSE: case RESUME: case MARK: case TRANSCRIBE_ON: case TRANSCRIBE_OFF: return C_REC;
            case HANGUP: case CLOSE: case CLOSE_OTHERS: case CLOSE_ALL: case CLOSE_KEEP: return C_CLOSE;
            default: return C_FLOW;
        }
    }

    /** The kind of step a line is, or null when it does not parse. */
    static WorkflowParser.Kind kindOf(String line) {
        try {
            List<WorkflowParser.Step> l = WorkflowParser.parse(line);
            return l.isEmpty() ? null : l.get(0).kind;
        } catch (WorkflowParser.ParseError e) {
            return null;
        }
    }

    /** The line with the condition ("if mobile ..." / "if desktop ...") the person chose: 0 always, 1 mobile site, 2 desktop site. */
    static String withCondition(String line, int condition) {
        return condition == 1 ? "if mobile " + line : condition == 2 ? "if desktop " + line : line;
    }

    /** What the editor shows for a step line: plain words instead of the syntax. Falls back to the line itself. */
    static String describe(String line) {
        WorkflowParser.Step s;
        try {
            List<WorkflowParser.Step> list = WorkflowParser.parse(line);
            if (list.isEmpty()) return line;
            s = list.get(0);
        } catch (WorkflowParser.ParseError e) {
            return line;
        }
        String within = s.withinMs > 0 && (s.kind == WorkflowParser.Kind.CLICK || s.kind == WorkflowParser.Kind.TYPE || s.kind == WorkflowParser.Kind.WAITFOR)
                && s.withinMs != defaultWithin(s.kind) ? ", for up to " + duration(s.withinMs / 1000) : "";
        String text;
        switch (s.kind) {
            case OPEN: text = "Load the page again"; break;
            case WAIT: text = "Wait " + duration(s.seconds); break;
            case WAIT_UNTIL: text = String.format(java.util.Locale.US, "Wait until %02d:%02d", s.number / 60, s.number % 60); break;
            case RUN: text = "Run the workflow \"" + s.text + "\""; break;
            case WAITFOR: text = "Wait until " + quote(s.target) + " shows" + within; break;
            case CLICK: text = "Click " + quote(s.target) + within; break;
            case TYPE: text = "Type \"" + s.text + "\" into " + quote(s.target) + within; break;
            case KEY: text = "Press " + s.text; break;
            case SCROLL: text = s.text.equals("top") || s.text.equals("bottom") ? "Scroll to the " + s.text : "Scroll " + s.text + " " + s.number + " px"; break;
            case MUTE: text = "Mute the microphone"; break;
            case UNMUTE: text = "Unmute the microphone"; break;
            case VOLUME: text = "Page volume " + s.number + "%"; break;
            case HANGUP: text = s.seconds == 0 ? "Cancel the hang-up timer" : "Hang up in " + duration(s.seconds); break;
            case CLOSE: text = "Close the page"; break;
            case CLOSE_OTHERS: text = "Close the other pages"; break;
            case CLOSE_ALL: text = "Close every page"; break;
            case FOCUS: text = s.text.equals("this") ? "Focus this workflow's page" : "Focus a page, if it is running"; break;
            case CLOSE_KEEP: text = "Close all but " + (s.text.isEmpty() ? 0 : s.text.split(",").length) + " chosen page(s)" + (s.number == 1 ? " and this workflow's pages" : ""); break;
            case RECORD_START: text = "Start recording"; break;
            case RECORD_STOP: text = "Stop recording"; break;
            case STEP_AWAY: text = "Pause and record (step away)"; break;
            case STEP_BACK: text = "Step back"; break;
            case PAUSE: text = "Pause the page"; break;
            case RESUME: text = "Resume the page"; break;
            case TRANSCRIBE_ON: text = "Live captions on"; break;
            case TRANSCRIBE_OFF: text = "Live captions off"; break;
            case MARK: text = s.text.isEmpty() ? "Add a mark" : "Add a mark: " + s.text; break;
            case JS: text = "Run a script: " + s.text; break;
            case MEDIA: text = "Media key: " + s.text; break;
            case APP: text = "Open app: " + s.text; break;
            default: text = line;
        }
        if (s.condition == 1) text += " (only on the mobile site)";
        if (s.condition == 2) text += " (only on the desktop site)";
        return s.optional ? text + " (skip if it fails)" : text;
    }

    private static long defaultWithin(WorkflowParser.Kind k) {
        return k == WorkflowParser.Kind.WAITFOR ? 60_000 : 20_000;
    }

    private static String quote(String target) {
        if (target.startsWith("text:")) return "\"" + target.substring(5) + "\"";
        return target;
    }

    /** A step read back into the form that made it, so it can be edited. */
    static final class Edit {
        Template template;
        String[] values;
        boolean optional;
        long withinSeconds;   // 0 = the usual time
        int condition;        // 0 always, 1 mobile site only, 2 desktop site only
    }

    /** Null when the line is not one a template can rebuild (it is then edited as plain text). */
    static Edit toEdit(String line) {
        WorkflowParser.Step s;
        try {
            List<WorkflowParser.Step> list = WorkflowParser.parse(line);
            if (list.isEmpty()) return null;
            s = list.get(0);
        } catch (WorkflowParser.ParseError e) {
            return null;
        }
        String title;
        String[] v;
        String target = s.target.startsWith("text:") ? s.target.substring(5) : s.target;
        switch (s.kind) {
            case WAIT: title = "Wait"; v = new String[]{String.valueOf(s.seconds)}; break;
            case WAIT_UNTIL: title = "Wait until a time"; v = new String[]{String.format(java.util.Locale.US, "%02d:%02d", s.number / 60, s.number % 60)}; break;
            case RUN: title = "Run another workflow"; v = new String[]{s.text}; break;
            case WAITFOR: title = "Wait until something shows"; v = new String[]{target}; break;
            case CLICK: title = "Click"; v = new String[]{target}; break;
            case TYPE: title = "Type into a field"; v = new String[]{target, s.text}; break;
            case KEY: title = "Press a key"; v = new String[]{s.text}; break;
            case SCROLL: title = "Scroll"; v = new String[]{s.text, String.valueOf(s.number)}; break;
            case OPEN: title = "Load the page again"; v = new String[0]; break;
            case JS: title = "Run a script"; v = new String[]{s.text}; break;
            case MUTE: title = "Mute"; v = new String[0]; break;
            case UNMUTE: title = "Unmute"; v = new String[0]; break;
            case VOLUME: title = "Page volume"; v = new String[]{String.valueOf(s.number)}; break;
            case RECORD_START: title = "Start recording"; v = new String[0]; break;
            case RECORD_STOP: title = "Stop recording"; v = new String[0]; break;
            case MARK: title = "Add a mark"; v = new String[]{s.text}; break;
            case STEP_AWAY: title = "Step away"; v = new String[0]; break;
            case STEP_BACK: title = "Step back"; v = new String[0]; break;
            case TRANSCRIBE_ON: title = "Live captions on"; v = new String[0]; break;
            case TRANSCRIBE_OFF: title = "Live captions off"; v = new String[0]; break;
            case HANGUP:
                if (s.seconds == 0) { title = "Cancel the hang-up timer"; v = new String[0]; }
                else { title = "Hang up (close the page)"; v = new String[]{String.valueOf(s.seconds)}; }
                break;
            case CLOSE: title = "Hang up (close the page)"; v = new String[]{"0"}; break;
            case PAUSE: title = "Pause the page"; v = new String[0]; break;
            case RESUME: title = "Resume the page"; v = new String[0]; break;
            case CLOSE_OTHERS: title = "Close the other pages"; v = new String[]{"all but this workflow's pages"}; break;
            case CLOSE_ALL: title = "Close the other pages"; v = new String[]{"every page"}; break;
            case CLOSE_KEEP: title = "Close all but chosen pages"; v = new String[]{s.text.isEmpty() ? "none" : s.text, s.number == 1 ? "Yes, keep them too" : "No"}; break;
            case FOCUS:
                if (s.text.equals("this")) { title = "Focus this workflow's page"; v = new String[0]; }
                else { title = "Focus a page"; v = new String[]{s.text}; }
                break;
            case MEDIA: title = "Press a media key"; v = new String[]{s.text}; break;
            case APP: title = "Open another app"; v = new String[]{s.text}; break;
            default: return null;
        }
        for (Template t : ALL) {
            if (!t.title.equals(title)) continue;
            Edit e = new Edit();
            e.template = t;
            e.values = v;
            e.optional = s.optional;
            e.condition = s.condition;
            long def = defaultWithin(s.kind);
            e.withinSeconds = t.pageStep && s.withinMs != def ? s.withinMs / 1000 : 0;
            return e;
        }
        return null;
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
