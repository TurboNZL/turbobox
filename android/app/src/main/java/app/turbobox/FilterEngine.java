package app.turbobox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A request blocker that reads the filter lists uBlock Origin and Adblock Plus use (EasyList, EasyPrivacy, uBlock filters):
 * network rules (||domain^, wildcards, $third-party, $script, $domain=..., @@ exceptions, $important) and element hiding
 * (##selector, domain##selector, #@# exceptions). Rules that need a full content blocker (scriptlets, procedural selectors,
 * header and CSP rewriting) are skipped. Pure Java, no Android classes, so it is tested on the JVM.
 *
 * Network rules are indexed by the longest whole word in their pattern, so a request is checked against a handful of
 * rules instead of all of them. Thread safe once built (a new engine replaces the old one when lists change).
 */
final class FilterEngine {
    static final int SCRIPT = 1, IMAGE = 2, STYLESHEET = 4, MEDIA = 8, FONT = 16, XHR = 32, SUBDOCUMENT = 64, DOCUMENT = 128,
            PING = 256, WEBSOCKET = 512, OTHER = 1024, UNKNOWN = 0;
    private static final int ANY = SCRIPT | IMAGE | STYLESHEET | MEDIA | FONT | XHR | SUBDOCUMENT | PING | WEBSOCKET | OTHER;

    private static final class Rule {
        String pattern = "";      // lower case, anchors removed
        boolean domainAnchor;     // ||
        boolean startAnchor;      // |
        boolean endAnchor;        // trailing |
        boolean exception;
        boolean important;
        boolean matchCase;
        int types = ANY;          // resource types it applies to
        int party;                // 0 = both, 1 = third-party only, 2 = first-party only
        String[] includeDomains;  // page domains it applies on (null = everywhere)
        String[] excludeDomains;
        boolean document;         // exception for a whole page: turns blocking off there
    }

    private static final class Cosmetic {
        String selector;
        String[] include;
        String[] exclude;
    }

    private final Map<String, List<Rule>> index = new HashMap<>();
    private final List<Rule> loose = new ArrayList<>();
    private final Map<String, List<Rule>> pageExceptions = new HashMap<>();
    private final List<Cosmetic> generic = new ArrayList<>();
    private final Map<String, List<Cosmetic>> hideByDomain = new HashMap<>();
    private final Map<String, List<Cosmetic>> unhideByDomain = new HashMap<>();
    private final Set<String> genericUnhide = new HashSet<>();
    private int networkRules;
    private int cosmeticRules;

    int networkRules() {
        return networkRules;
    }

    int cosmeticRules() {
        return cosmeticRules;
    }

    // ---- reading lists ------------------------------------------------------------------------------------------

    void addList(String text) {
        int from = 0;
        int n = text.length();
        while (from < n) {
            int nl = text.indexOf('\n', from);
            if (nl < 0) nl = n;
            addLine(text.substring(from, nl));
            from = nl + 1;
        }
    }

    void addLine(String raw) {
        String line = raw.trim();
        if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[') return;
        int hash = line.indexOf('#');
        if (hash >= 0 && cosmeticMarker(line, hash)) {
            addCosmetic(line, hash);
            return;
        }
        addNetwork(line);
    }

    /** Is the '#' at {@code at} the start of ##, #@#, #?#, #$#, #%#? (A '#' inside a URL pattern is not.) */
    private static boolean cosmeticMarker(String line, int at) {
        if (line.startsWith("##", at)) return true;
        if (line.startsWith("#@#", at) || line.startsWith("#?#", at) || line.startsWith("#$#", at) || line.startsWith("#%#", at)) return true;
        return line.startsWith("#@$#", at) || line.startsWith("#@?#", at);
    }

    private void addCosmetic(String line, int hash) {
        String marker;
        if (line.startsWith("#@#", hash)) marker = "#@#";
        else if (line.startsWith("##", hash)) marker = "##";
        else return;   // scripts, style injection and procedural filters are not supported
        String selector = line.substring(hash + marker.length()).trim();
        if (selector.isEmpty() || selector.startsWith("+js(") || unsupportedSelector(selector)) return;
        Cosmetic c = new Cosmetic();
        c.selector = selector;
        String domains = line.substring(0, hash).trim();
        if (!domains.isEmpty()) {
            List<String> in = new ArrayList<>();
            List<String> out = new ArrayList<>();
            for (String d : domains.split(",")) {
                d = d.trim().toLowerCase(Locale.ROOT);
                if (d.isEmpty() || d.contains("*") || d.contains("/")) continue;   // regex and wildcard domains are not supported
                if (d.startsWith("~")) out.add(d.substring(1)); else in.add(d);
            }
            if (in.isEmpty() && out.isEmpty()) return;
            c.include = in.isEmpty() ? null : in.toArray(new String[0]);
            c.exclude = out.isEmpty() ? null : out.toArray(new String[0]);
        }
        boolean unhide = marker.equals("#@#");
        cosmeticRules++;
        if (unhide) {
            if (c.include == null) genericUnhide.add(selector);
            else for (String d : c.include) unhideByDomain.computeIfAbsent(d, k -> new ArrayList<>()).add(c);
        } else if (c.include == null) {
            generic.add(c);
        } else {
            for (String d : c.include) hideByDomain.computeIfAbsent(d, k -> new ArrayList<>()).add(c);
        }
    }

    private static boolean unsupportedSelector(String s) {
        return s.contains(":has-text(") || s.contains(":xpath(") || s.contains(":matches-") || s.contains(":upward(")
                || s.contains(":min-text-length(") || s.contains(":watch-attr(") || s.contains(":remove(") || s.contains(":style(")
                || s.contains(":others(") || s.contains(":not(:has-text") || s.contains(":-abp-") || s.contains(":contains(")
                || s.contains(":nth-ancestor(") || s.contains(":remove-attr(") || s.contains(":remove-class(");
    }

    private void addNetwork(String line) {
        Rule r = new Rule();
        String body = line;
        if (body.startsWith("@@")) {
            r.exception = true;
            body = body.substring(2);
        }
        int dollar = body.lastIndexOf('$');
        if (dollar >= 0 && !(body.startsWith("/") && body.endsWith("/"))) {
            String opts = body.substring(dollar + 1);
            if (looksLikeOptions(opts)) {
                body = body.substring(0, dollar);
                if (!applyOptions(r, opts)) return;
            }
        }
        if (body.startsWith("/") && body.length() > 1 && body.endsWith("/")) return;   // regular expression rules are skipped
        if (body.startsWith("||")) {
            r.domainAnchor = true;
            body = body.substring(2);
        } else if (body.startsWith("|")) {
            r.startAnchor = true;
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            r.endAnchor = true;
            body = body.substring(0, body.length() - 1);
        }
        if (body.isEmpty() || body.equals("*")) {
            if (!r.exception && r.includeDomains == null) return;   // would block everything
        }
        r.pattern = r.matchCase ? body : body.toLowerCase(Locale.ROOT);
        networkRules++;
        if (r.exception && r.document) {
            if (r.includeDomains != null) for (String d : r.includeDomains) pageExceptions.computeIfAbsent(d, k -> new ArrayList<>()).add(r);
            else if (r.domainAnchor) {
                String host = hostPart(r.pattern);
                if (!host.isEmpty()) pageExceptions.computeIfAbsent(host, k -> new ArrayList<>()).add(r);
            }
            return;
        }
        String token = bestToken(r);
        if (token == null) loose.add(r);
        else index.computeIfAbsent(token, k -> new ArrayList<>(2)).add(r);
    }

    private static String hostPart(String pattern) {
        int end = pattern.length();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '^' || c == '/' || c == '*' || c == ':' || c == '?') {
                end = i;
                break;
            }
        }
        return pattern.substring(0, end);
    }

    private static boolean looksLikeOptions(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '~' || c == ',' || c == '=' || c == '|' || c == '.' || c == '*' || c == ':' || c == '/')) return false;
        }
        return true;
    }

    /** False when the rule needs something this engine cannot do (it is then dropped rather than applied too widely). */
    private boolean applyOptions(Rule r, String opts) {
        int positive = 0;
        int negative = 0;
        for (String o : opts.split(",")) {
            String name = o.trim();
            if (name.isEmpty()) continue;
            boolean not = name.startsWith("~");
            if (not) name = name.substring(1);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            switch (name) {
                case "third-party": case "3p": r.party = not ? 2 : 1; break;
                case "first-party": case "1p": r.party = not ? 1 : 2; break;
                case "important": r.important = true; break;
                case "match-case": r.matchCase = true; break;
                case "all": break;
                case "document": case "doc":
                    if (r.exception) r.document = true;
                    else return false;   // blocking whole pages is not done
                    break;
                case "script": if (not) negative |= SCRIPT; else positive |= SCRIPT; break;
                case "image": if (not) negative |= IMAGE; else positive |= IMAGE; break;
                case "stylesheet": case "css": if (not) negative |= STYLESHEET; else positive |= STYLESHEET; break;
                case "media": if (not) negative |= MEDIA; else positive |= MEDIA; break;
                case "font": if (not) negative |= FONT; else positive |= FONT; break;
                case "xmlhttprequest": case "xhr": if (not) negative |= XHR; else positive |= XHR; break;
                case "subdocument": case "frame": if (not) negative |= SUBDOCUMENT; else positive |= SUBDOCUMENT; break;
                case "ping": if (not) negative |= PING; else positive |= PING; break;
                case "websocket": if (not) negative |= WEBSOCKET; else positive |= WEBSOCKET; break;
                case "other": if (not) negative |= OTHER; else positive |= OTHER; break;
                case "object": case "object-subrequest": case "popup": case "inline-script": case "inline-font":
                    if (!r.exception) return false;
                    break;
                case "domain": case "from":
                    if (value == null) return false;
                    List<String> in = new ArrayList<>();
                    List<String> out = new ArrayList<>();
                    boolean skipped = false;
                    for (String d : value.split("\\|")) {
                        d = d.trim().toLowerCase(Locale.ROOT);
                        if (d.isEmpty()) continue;
                        if (d.contains("*") || d.contains("/")) {
                            skipped = true;   // wildcard and regex domains are not supported
                            continue;
                        }
                        if (d.startsWith("~")) out.add(d.substring(1)); else in.add(d);
                    }
                    // Only unsupported domains were named: the rule cannot be applied as written, so it is dropped (never widened).
                    if (in.isEmpty() && skipped && out.isEmpty()) return false;
                    if (!in.isEmpty()) r.includeDomains = in.toArray(new String[0]);
                    if (!out.isEmpty()) r.excludeDomains = out.toArray(new String[0]);
                    break;
                case "badfilter": return false;
                case "redirect": case "redirect-rule":
                    if (r.exception || name.equals("redirect-rule")) return false;
                    break;   // a redirect rule blocks the request; the resource is simply empty
                case "elemhide": case "ehide": case "generichide": case "ghide": case "specifichide": case "shide": case "genericblock":
                    return false;
                default:
                    return false;   // csp, removeparam, replace, header, denyallow, permissions...
            }
        }
        if (positive != 0) r.types = positive & ~negative;
        else if (negative != 0) r.types = ANY & ~negative;
        return r.types != 0 || r.document;
    }

    /** The longest whole word of the pattern, or null when it has none that is safe to index on. */
    private static String bestToken(Rule r) {
        String p = r.pattern;
        String best = null;
        int n = p.length();
        int i = 0;
        while (i < n) {
            if (!isWordChar(p.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && isWordChar(p.charAt(j))) j++;
            boolean leftOk = i > 0 ? p.charAt(i - 1) != '*' : r.domainAnchor || r.startAnchor;
            boolean rightOk = j < n ? p.charAt(j) != '*' : r.endAnchor;
            // A pattern that ends in the middle of a word ("ads") also matches "loads": only a '^' or a '/' after it is a safe edge.
            if (j == n && !r.endAnchor) rightOk = false;
            if (i > 0 && p.charAt(i - 1) != '*' && !leftOk) leftOk = true;
            if (leftOk && rightOk && j - i >= 2 && (best == null || j - i > best.length())) best = p.substring(i, j);
            i = j;
        }
        return best;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '%';
    }

    // ---- asking ------------------------------------------------------------------------------------------------

    /**
     * Should this request be blocked? {@code pageHost} is the host of the page that made it (empty for the page itself),
     * {@code type} one of the resource constants (or {@link #UNKNOWN}).
     */
    boolean shouldBlock(String url, String pageHost, int type) {
        if (url == null || type == DOCUMENT) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        String host = hostOf(lower);
        if (host.isEmpty()) return false;
        String page = pageHost == null ? "" : pageHost.toLowerCase(Locale.ROOT);
        if (!page.isEmpty() && pageDisabled(page)) return false;
        boolean third = !page.isEmpty() && !sameParty(host, page);
        boolean blocked = false;
        boolean important = false;
        boolean excepted = false;
        for (String token : tokens(lower)) {
            List<Rule> rules = index.get(token);
            if (rules == null) continue;
            for (int k = 0; k < rules.size(); k++) {
                Rule r = rules.get(k);
                if (!applies(r, lower, url, host, page, third, type)) continue;
                if (r.exception) excepted = true;
                else {
                    blocked = true;
                    if (r.important) important = true;
                }
            }
        }
        for (int k = 0; k < loose.size(); k++) {
            Rule r = loose.get(k);
            if (!applies(r, lower, url, host, page, third, type)) continue;
            if (r.exception) excepted = true;
            else {
                blocked = true;
                if (r.important) important = true;
            }
        }
        return blocked && (important || !excepted);
    }

    private boolean pageDisabled(String pageHost) {
        for (String h = pageHost; h != null; h = parent(h)) {
            List<Rule> list = pageExceptions.get(h);
            if (list != null) return true;
        }
        return false;
    }

    private boolean applies(Rule r, String lower, String original, String host, String page, boolean third, int type) {
        if (type != UNKNOWN && (r.types & type) == 0) return false;
        if (r.party == 1 && !third) return false;
        if (r.party == 2 && third) return false;
        if (r.includeDomains != null && !domainIn(page, r.includeDomains)) return false;
        if (r.excludeDomains != null && domainIn(page, r.excludeDomains)) return false;
        return matches(r, r.matchCase ? original : lower, host);
    }

    private static boolean domainIn(String host, String[] domains) {
        if (host.isEmpty()) return false;
        for (String d : domains) if (host.equals(d) || host.endsWith("." + d)) return true;
        return false;
    }

    private static boolean matches(Rule r, String url, String host) {
        String p = r.pattern;
        if (p.isEmpty()) return true;
        int schemeEnd = url.indexOf("://");
        int start = schemeEnd < 0 ? 0 : schemeEnd + 3;
        if (r.domainAnchor) {
            int hostEnd = url.indexOf('/', start);
            if (hostEnd < 0) hostEnd = url.length();
            for (int at = start; at < hostEnd; at++) {
                if (at != start && url.charAt(at - 1) != '.') continue;
                if (matchFrom(p, 0, url, at, r.endAnchor)) return true;
            }
            return false;
        }
        if (r.startAnchor) return matchFrom(p, 0, url, 0, r.endAnchor);
        for (int at = 0; at <= url.length(); at++) {
            if (matchFrom(p, 0, url, at, r.endAnchor)) return true;
        }
        return false;
    }

    /** Matches pattern[pi..] against the URL from {@code ui}: '*' is anything, '^' is a separator (or the end). */
    private static boolean matchFrom(String p, int pi, String u, int ui, boolean endAnchor) {
        int plen = p.length();
        int ulen = u.length();
        while (pi < plen) {
            char c = p.charAt(pi);
            if (c == '*') {
                while (pi < plen && p.charAt(pi) == '*') pi++;
                if (pi == plen) return true;
                for (int k = ui; k <= ulen; k++) if (matchFrom(p, pi, u, k, endAnchor)) return true;
                return false;
            }
            if (c == '^') {
                if (ui == ulen) {
                    pi++;
                    continue;   // the end of the address counts as a separator
                }
                if (!isSeparator(u.charAt(ui))) return false;
                ui++;
                pi++;
                continue;
            }
            if (ui >= ulen || u.charAt(ui) != c) return false;
            ui++;
            pi++;
        }
        return !endAnchor || ui == ulen;
    }

    private static boolean isSeparator(char c) {
        return !(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '%');
    }

    private static List<String> tokens(String url) {
        List<String> out = new ArrayList<>();
        int n = url.length();
        int i = 0;
        while (i < n) {
            if (!isWordChar(url.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && isWordChar(url.charAt(j))) j++;
            if (j - i >= 2) out.add(url.substring(i, j));
            i = j;
        }
        return out;
    }

    static String hostOf(String url) {
        int s = url.indexOf("://");
        if (s < 0) return "";
        s += 3;
        int e = s;
        while (e < url.length()) {
            char c = url.charAt(e);
            if (c == '/' || c == ':' || c == '?' || c == '#') break;
            e++;
        }
        int at = url.lastIndexOf('@', e);
        if (at >= s) s = at + 1;
        return url.substring(s, e);
    }

    private static String parent(String host) {
        int dot = host.indexOf('.');
        return dot < 0 ? null : host.substring(dot + 1);
    }

    /** Same registrable domain? (Uses the app's own "example.com / example.co.nz" rule.) */
    static boolean sameParty(String a, String b) {
        return Hosts.rootDomain(a).equals(Hosts.rootDomain(b));
    }

    // ---- hiding elements ----------------------------------------------------------------------------------------

    /**
     * The selectors to hide on a page of this host: the ones for the site, plus the generic ones that apply anywhere
     * (up to {@code maxGeneric}), minus the exceptions.
     */
    List<String> hideSelectors(String pageHost, int maxGeneric) {
        String host = pageHost == null ? "" : pageHost.toLowerCase(Locale.ROOT);
        Set<String> unhide = new HashSet<>(genericUnhide);
        for (String h = host; h != null; h = parent(h)) {
            List<Cosmetic> u = unhideByDomain.get(h);
            if (u != null) for (Cosmetic c : u) if (appliesHere(c, host)) unhide.add(c.selector);
        }
        List<String> out = new ArrayList<>();
        for (String h = host; h != null && !h.isEmpty(); h = parent(h)) {
            List<Cosmetic> list = hideByDomain.get(h);
            if (list == null) continue;
            for (Cosmetic c : list) if (appliesHere(c, host) && !unhide.contains(c.selector)) out.add(c.selector);
        }
        int taken = 0;
        for (Cosmetic c : generic) {
            if (taken >= maxGeneric) break;
            if (unhide.contains(c.selector)) continue;
            out.add(c.selector);
            taken++;
        }
        return out;
    }

    private static boolean appliesHere(Cosmetic c, String host) {
        if (c.exclude != null && domainIn(host, c.exclude)) return false;
        return c.include == null || domainIn(host, c.include);
    }
}
