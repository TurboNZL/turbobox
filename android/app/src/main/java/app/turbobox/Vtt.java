package app.turbobox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WebVTT subtitle files: writing cues as they are recognised, and reading them back to show captions in the player. No Android classes, so it is tested on the JVM. */
final class Vtt {
    private Vtt() {
    }

    static final class Cue {
        final long startMs;
        final long endMs;
        final String text;

        Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    static final String HEADER = "WEBVTT\n\n";

    /** 3723500 -> "01:02:03.500". */
    static String timestamp(long ms) {
        long t = Math.max(0, ms);
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000);
    }

    /** One cue as text. The words are made safe for the format: no "-->", no blank lines, no markup. */
    static String format(Cue c) {
        String text = c.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("-->", "- >").replaceAll("\\s*\\R\\s*", " ").trim();
        long end = Math.max(c.endMs, c.startMs + 500);
        return timestamp(c.startMs) + " --> " + timestamp(end) + "\n" + text + "\n\n";
    }

    private static final Pattern TIME = Pattern.compile("(?:(\\d+):)?(\\d{2}):(\\d{2})\\.(\\d{3})");

    static long parseTime(String s) {
        Matcher m = TIME.matcher(s.trim());
        if (!m.matches()) return -1;
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        return h * 3_600_000 + Long.parseLong(m.group(2)) * 60_000 + Long.parseLong(m.group(3)) * 1000 + Long.parseLong(m.group(4));
    }

    static List<Cue> parse(String vtt) {
        List<Cue> out = new ArrayList<>();
        String[] blocks = vtt.replace("\r\n", "\n").split("\n\n+");
        for (String block : blocks) {
            String[] lines = block.split("\n");
            for (int i = 0; i < lines.length; i++) {
                int arrow = lines[i].indexOf("-->");
                if (arrow < 0) continue;
                long start = parseTime(lines[i].substring(0, arrow));
                String rest = lines[i].substring(arrow + 3).trim();
                int sp = rest.indexOf(' ');
                long end = parseTime(sp < 0 ? rest : rest.substring(0, sp));
                if (start < 0 || end < 0) break;
                StringBuilder text = new StringBuilder();
                for (int j = i + 1; j < lines.length; j++) text.append(text.length() == 0 ? "" : " ").append(lines[j]);
                out.add(new Cue(start, end, text.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")));
                break;
            }
        }
        return out;
    }

    /** The cue showing at {@code ms}, or null. */
    static Cue at(List<Cue> cues, long ms) {
        for (Cue c : cues) if (ms >= c.startMs && ms < c.endMs) return c;
        return null;
    }
}
