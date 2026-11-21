package app.turbobox;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tiny web server that only this phone can reach (loopback), for Settings > Debugging > "Check what a page sees". The page it serves
 * (assets/requestcheck.html) lists what a site could read in the page (navigator, screen, touch, media queries, connection type...), and
 * the server lists the request headers it actually received (user agent, client hints) exactly as a real site would get them, with no
 * outside service involved. It closes itself a few minutes after the last start.
 */
final class RequestCheck {
    private static final long LIFETIME_MS = 5 * 60 * 1000L;
    private static final String ACCEPT_CH = "Sec-CH-UA-Arch, Sec-CH-UA-Bitness, Sec-CH-UA-Full-Version-List, Sec-CH-UA-Model, "
            + "Sec-CH-UA-Platform-Version, Sec-CH-UA-WoW64, Sec-CH-UA-Form-Factors, Sec-CH-Prefers-Color-Scheme, Sec-CH-Viewport-Width, "
            + "Sec-CH-DPR, Sec-CH-Device-Memory, Sec-CH-Save-Data, Device-Memory, DPR, Viewport-Width, Save-Data, Downlink, ECT, RTT";

    private static ServerSocket server;
    private static long closeAt;

    private RequestCheck() {
    }

    /** Starts (or extends) the server and returns the address of its page. */
    static synchronized String start(Context c) throws IOException {
        final String html = readAsset(c, "requestcheck.html");
        closeAt = System.currentTimeMillis() + LIFETIME_MS;
        if (server == null || server.isClosed()) {
            final ServerSocket s = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            server = s;
            Thread t = new Thread(() -> serve(s, html), "request-check");
            t.setDaemon(true);
            t.start();
        }
        return "http://127.0.0.1:" + server.getLocalPort() + "/";
    }

    private static void serve(ServerSocket s, String html) {
        try {
            s.setSoTimeout(15000);
            while (true) {
                if (System.currentTimeMillis() > closeAt) break;
                try {
                    handle(s.accept(), html);
                } catch (java.net.SocketTimeoutException e) {
                    // Wake up to check the lifetime.
                } catch (IOException e) {
                    if (s.isClosed()) break;
                }
            }
        } catch (IOException ignored) {
            // Closing.
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    /** The headers of one request, in the order they arrived. */
    static Map<String, String> readHeaders(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
        Map<String, String> out = new LinkedHashMap<>();
        String line = r.readLine();
        if (line == null) return out;
        out.put(":request", line);
        for (int n = 0; n < 100 && (line = r.readLine()) != null && !line.isEmpty(); n++) {
            int colon = line.indexOf(':');
            if (colon > 0) out.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).trim());
        }
        return out;
    }

    private static void handle(Socket sock, String html) throws IOException {
        try (Socket s = sock) {
            s.setSoTimeout(5000);
            Map<String, String> h = readHeaders(s.getInputStream());
            String request = h.getOrDefault(":request", "");
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> e : h.entrySet()) {
                try {
                    json.put(e.getKey(), e.getValue());
                } catch (org.json.JSONException ignored) {
                    // A name that cannot be a key is skipped.
                }
            }
            boolean again = request.startsWith("GET /again");
            String type = again ? "application/json" : "text/html";
            String body = again ? json.toString() : html.replace("/*HEADERS*/null", json.toString());
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + data.length
                    + "\r\nCache-Control: no-store\r\nAccept-CH: " + ACCEPT_CH + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(data);
            out.flush();
        }
    }

    private static String readAsset(Context c, String name) throws IOException {
        try (InputStream in = c.getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
