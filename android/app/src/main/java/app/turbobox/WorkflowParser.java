package app.turbobox;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a workflow's steps, one per line:
 * <pre>
 * # a comment
 * open                       load the pinned page again
 * wait 30s                   30s, 5m, 2h, 1h30m (a bare number is seconds)
 * waitfor Leave [within 2m]  until the target shows on the page (default 60 s)
 * click Join now [within 30s]  click the target (waits up to 20 s for it to appear)
 * type css:#name = My Name   put text in a field
 * key Enter                  press a key (Enter, Escape, Tab, ArrowDown ...)
 * scroll down [600]          up, down, top, bottom
 * mute | unmute              the call's outgoing audio
 * volume 80                  0-200 (%), the page's audio
 * hangup 1h30m | close 1h30m  close the page after that long (hangup off takes it back)
 * record start | record stop the screen buffer
 * step away | step back      freeze and silence the page here while it records / let go (no replay)
 * transcribe on | off        live captions (speech to text) saved as WebVTT with the recording
 * mark Chapter 2 starts     a bookmark in the buffer
 * pause | resume            freeze and silence the page on this phone / let go (nothing is recorded because of it)
 * close others | close all   close every other running page / every running page, without asking
 * media play|pause|toggle|next|previous   press that media key (the music app that last played gets it)
 * app spotify                open another app (spotify, youtube music, or its package name)
 * js document.title          run a script on the page
 * hangup | close            close the page right now
 * </pre>
 * A target is {@code css:SELECTOR}, {@code text:WORDS}, or just the words (matched against visible text, labels,
 * placeholders and titles). A leading {@code ?} makes a step optional: if it fails the workflow carries on.
 * No Android classes, so it is tested on the JVM.
 */
final class WorkflowParser {
    private WorkflowParser() {
    }

    enum Kind { OPEN, WAIT, WAITFOR, CLICK, TYPE, KEY, SCROLL, MUTE, UNMUTE, VOLUME, HANGUP, RECORD_START, RECORD_STOP, TRANSCRIBE_ON, TRANSCRIBE_OFF, STEP_AWAY, STEP_BACK, PAUSE, RESUME, MARK, JS, CLOSE, CLOSE_OTHERS, CLOSE_ALL, MEDIA, APP }

    static final class Step {
        Kind kind;
        String target = "";   // "css:..." or "text:..."
        String text = "";     // the text to type, the key, the scroll direction, the label, the script
        long seconds;         // wait / hangup length
        int number;           // volume percent, scroll pixels
        long withinMs;        // how long to keep trying before the step fails
        boolean optional;
        int line;             // 1-based, for messages
        String raw = "";

        /** What the page script ({@code __turbobox.step}) is given. */
        JSONObject toJson() {
            try {
                return new JSONObject().put("type", kind.name().toLowerCase(Locale.ROOT)).put("target", target)
                        .put("text", text).put("number", number);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final class ParseError extends Exception {
        final int line;

        ParseError(int line, String message) {
            super("Line " + line + ": " + message);
            this.line = line;
        }
    }

    private static final Pattern WITHIN = Pattern.compile("^(.*\\S)\\s+within\\s+(\\S+)$", Pattern.CASE_INSENSITIVE);

    static List<Step> parse(String source) throws ParseError {
        List<Step> steps = new ArrayList<>();
        String[] lines = source == null ? new String[0] : source.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            steps.add(parseLine(line, i + 1));
        }
        return steps;
    }

    private static Step parseLine(String line, int no) throws ParseError {
        Step s = new Step();
        s.line = no;
        s.raw = line;
        if (line.startsWith("?")) {
            s.optional = true;
            line = line.substring(1).trim();
        }
        int sp = line.indexOf(' ');
        String verb = (sp < 0 ? line : line.substring(0, sp)).toLowerCase(Locale.ROOT);
        String rest = sp < 0 ? "" : line.substring(sp + 1).trim();
        switch (verb) {
            case "open":
                s.kind = Kind.OPEN;
                break;
            case "wait":
                s.kind = Kind.WAIT;
                s.seconds = durationOrFail(rest, no);
                break;
            case "waitfor":
                s.kind = Kind.WAITFOR;
                rest = within(s, rest, 60_000, no);
                s.target = targetOrFail(rest, no);
                break;
            case "click":
                s.kind = Kind.CLICK;
                rest = within(s, rest, 20_000, no);
                s.target = targetOrFail(rest, no);
                break;
            case "type": {
                s.kind = Kind.TYPE;
                rest = within(s, rest, 20_000, no);
                int eq = rest.indexOf('=');
                if (eq < 0) throw new ParseError(no, "type needs \"target = text\"");
                s.target = targetOrFail(rest.substring(0, eq).trim(), no);
                s.text = rest.substring(eq + 1).trim();
                break;
            }
            case "key":
                s.kind = Kind.KEY;
                if (rest.isEmpty()) throw new ParseError(no, "key needs a key name, like Enter");
                s.text = rest;
                s.withinMs = 5_000;
                break;
            case "scroll": {
                s.kind = Kind.SCROLL;
                String[] p = rest.split("\\s+");
                String dir = p[0].toLowerCase(Locale.ROOT);
                if (!dir.equals("up") && !dir.equals("down") && !dir.equals("top") && !dir.equals("bottom")) {
                    throw new ParseError(no, "scroll needs up, down, top or bottom");
                }
                s.text = dir;
                s.number = 600;
                if (p.length > 1) {
                    try {
                        s.number = Integer.parseInt(p[1]);
                    } catch (NumberFormatException e) {
                        throw new ParseError(no, "\"" + p[1] + "\" is not a number of pixels");
                    }
                }
                s.withinMs = 5_000;
                break;
            }
            case "mute":
                s.kind = Kind.MUTE;
                break;
            case "unmute":
                s.kind = Kind.UNMUTE;
                break;
            case "volume":
                s.kind = Kind.VOLUME;
                try {
                    s.number = Integer.parseInt(rest.replace("%", "").trim());
                } catch (NumberFormatException e) {
                    throw new ParseError(no, "volume needs a number from 0 to 200");
                }
                if (s.number < 0 || s.number > 200) throw new ParseError(no, "volume is 0 to 200");
                break;
            case "pause":
                s.kind = Kind.PAUSE;
                break;
            case "resume":
                s.kind = Kind.RESUME;
                break;
            case "media": {
                String key = rest.toLowerCase(Locale.ROOT);
                if (!key.equals("play") && !key.equals("pause") && !key.equals("toggle") && !key.equals("next") && !key.equals("previous")) {
                    throw new ParseError(no, "media needs play, pause, toggle, next or previous");
                }
                s.kind = Kind.MEDIA;
                s.text = key;
                break;
            }
            case "app":
                if (rest.isEmpty()) throw new ParseError(no, "app needs a name, like spotify");
                s.kind = Kind.APP;
                s.text = rest;
                break;
            case "hangup":
            case "close":
                if (rest.equalsIgnoreCase("others")) {
                    s.kind = Kind.CLOSE_OTHERS;
                    break;
                }
                if (rest.equalsIgnoreCase("all")) {
                    s.kind = Kind.CLOSE_ALL;
                    break;
                }
                // Two names for one thing. With a time: close the page after that long (a timer, the page stays open until
                // then). With nothing (or 0): close it right now. "off" takes a timer back.
                if (rest.isEmpty()) {
                    s.kind = Kind.CLOSE;
                } else if (rest.equalsIgnoreCase("off")) {
                    s.kind = Kind.HANGUP;
                    s.seconds = 0;
                } else {
                    long d = durationOrFail(rest, no);
                    s.kind = d == 0 ? Kind.CLOSE : Kind.HANGUP;
                    s.seconds = d;
                }
                break;
            case "record":
                if (rest.equalsIgnoreCase("start")) s.kind = Kind.RECORD_START;
                else if (rest.equalsIgnoreCase("stop")) s.kind = Kind.RECORD_STOP;
                else throw new ParseError(no, "record start or record stop");
                break;
            case "step":
                if (rest.equalsIgnoreCase("away")) s.kind = Kind.STEP_AWAY;
                else if (rest.equalsIgnoreCase("back")) s.kind = Kind.STEP_BACK;
                else throw new ParseError(no, "step away or step back");
                break;
            case "transcribe":
                if (rest.equalsIgnoreCase("on")) s.kind = Kind.TRANSCRIBE_ON;
                else if (rest.equalsIgnoreCase("off")) s.kind = Kind.TRANSCRIBE_OFF;
                else throw new ParseError(no, "transcribe on or transcribe off");
                break;
            case "mark":
                s.kind = Kind.MARK;
                s.text = rest;
                break;
            case "js":
                s.kind = Kind.JS;
                if (rest.isEmpty()) throw new ParseError(no, "js needs some code");
                s.text = rest;
                s.withinMs = 5_000;
                break;
            default:
                throw new ParseError(no, "I do not know the step \"" + verb + "\"");
        }
        return s;
    }

    /** Does a step of this kind need a page to act on? (The others work in a workflow that has none.) */
    static boolean needsPage(Kind k) {
        switch (k) {
            case WAIT: case CLOSE_ALL: case CLOSE_OTHERS: case MEDIA: case APP:
                return false;
            default:
                return true;
        }
    }

    /** Takes a trailing "within 30s" off the text and records it; returns what is left. */
    private static String within(Step s, String rest, long defaultMs, int no) throws ParseError {
        s.withinMs = defaultMs;
        Matcher m = WITHIN.matcher(rest);
        if (m.matches()) {
            long sec = parseDuration(m.group(2));
            if (sec < 0) throw new ParseError(no, "\"" + m.group(2) + "\" is not a time (try 30s, 5m)");
            s.withinMs = sec * 1000;
            return m.group(1);
        }
        return rest;
    }

    private static String targetOrFail(String raw, int no) throws ParseError {
        String t = normalizeTarget(raw);
        if (t == null) throw new ParseError(no, "this step needs a target (css:#id, text:Join, or the words on it)");
        return t;
    }

    private static long durationOrFail(String raw, int no) throws ParseError {
        long d = parseDuration(raw);
        if (d < 0) throw new ParseError(no, "\"" + raw + "\" is not a time (try 30s, 5m, 2h, 1h30m)");
        return d;
    }

    /** "css:..." and "text:..." are kept; anything else is the visible words. Null when empty. */
    static String normalizeTarget(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("css:")) return t.substring(4).trim().isEmpty() ? null : "css:" + t.substring(4).trim();
        if (lower.startsWith("text:")) t = t.substring(5).trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            t = t.substring(1, t.length() - 1);
        }
        return t.isEmpty() ? null : "text:" + t;
    }

    private static final Pattern DURATION = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$", Pattern.CASE_INSENSITIVE);

    /** Seconds in "90", "45s", "5m", "2h", "1h30m", "1h30m15s"; -1 when it is not a time. */
    static long parseDuration(String raw) {
        if (raw == null) return -1;
        String s = raw.trim();
        if (s.isEmpty()) return -1;
        if (s.matches("\\d+")) return Long.parseLong(s);
        Matcher m = DURATION.matcher(s);
        if (!m.matches()) return -1;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long min = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long sec = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        return h * 3600 + min * 60 + sec;
    }
}
