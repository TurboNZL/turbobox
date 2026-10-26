package app.turbobox;

/** Host name helpers with no Android classes, so the JVM tests can use them. */
final class Hosts {
    private Hosts() {
    }

    /** "a.b.example.com" -> "example.com"; "a.example.co.nz" -> "example.co.nz". */
    static String rootDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        String last = parts[parts.length - 1], second = parts[parts.length - 2];
        int keep = last.length() == 2 && second.matches("co|com|org|net|gov|ac|edu|ne|or|go") ? 3 : 2;
        keep = Math.min(keep, parts.length);
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - keep; i < parts.length; i++) sb.append(sb.length() == 0 ? "" : ".").append(parts[i]);
        return sb.toString();
    }
}
