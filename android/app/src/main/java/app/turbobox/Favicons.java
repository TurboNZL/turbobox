package app.turbobox;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pinned pages' icons. They are looked up when a page is pinned (the page's own {@code <link rel="icon">}, the
 * biggest one, else /favicon.ico) and refreshed whenever the browser reports one for an open page. Kept as small PNGs in
 * the app's files folder; a pin without one shows its first letter.
 */
final class Favicons {
    private static final int MAX = 128;
    private static final Map<String, Bitmap> MEMORY = new HashMap<>();
    private static final Pattern LINK = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private Favicons() {
    }

    private static File file(Context c, String pinId) {
        File dir = new File(c.getFilesDir(), "favicons");
        dir.mkdirs();
        return new File(dir, pinId + ".png");
    }

    /** The saved icon, or null. */
    static synchronized Bitmap load(Context c, String pinId) {
        Bitmap b = MEMORY.get(pinId);
        if (b != null) return b;
        File f = file(c, pinId);
        if (!f.exists()) return null;
        b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) MEMORY.put(pinId, b);
        return b;
    }

    static synchronized void delete(Context c, String pinId) {
        MEMORY.remove(pinId);
        file(c, pinId).delete();
    }

    /** Keeps the icon unless a bigger one is already saved. */
    static synchronized void saveIfBetter(Context c, String pinId, Bitmap icon) {
        if (icon == null || icon.getWidth() < 16) return;
        Bitmap have = load(c, pinId);
        if (have != null && have.getWidth() >= icon.getWidth()) return;
        Bitmap small = icon;
        int side = Math.max(icon.getWidth(), icon.getHeight());
        if (side > MAX) {
            float k = MAX / (float) side;
            small = Bitmap.createScaledBitmap(icon, Math.max(1, Math.round(icon.getWidth() * k)), Math.max(1, Math.round(icon.getHeight() * k)), true);
        }
        try (FileOutputStream out = new FileOutputStream(file(c, pinId))) {
            small.compress(Bitmap.CompressFormat.PNG, 100, out);
            MEMORY.put(pinId, small);
        } catch (IOException ignored) {
            // No icon is not a failure; the tile shows a letter.
        }
    }

    /** Finds and saves the icon of a pinned page in the background; {@code done} runs on the main thread when it is saved. */
    static void fetch(Context context, Pin pin, Runnable done) {
        final Context c = context.getApplicationContext();
        final String pinId = pin.id;
        final String url = pin.url;
        new Thread(() -> {
            try {
                String html = new String(download(url, 300_000), "UTF-8");
                String icon = pickIconUrl(html, url);
                Bitmap b = null;
                if (icon != null) b = decode(icon);
                if (b == null) b = decode(new URI(url).resolve("/favicon.ico").toString());
                if (b != null) {
                    saveIfBetter(c, pinId, b);
                    if (done != null) new Handler(Looper.getMainLooper()).post(done);
                }
            } catch (IOException | URISyntaxException | RuntimeException ignored) {
                // The page may be behind a login or offline; the browser reports its icon once it is opened.
            }
        }, "favicon").start();
    }

    private static Bitmap decode(String url) throws IOException {
        byte[] data = download(url, 1_000_000);
        return BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    @SuppressWarnings("deprecation")
    private static byte[] download(String url, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0 && out.size() < limit; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /**
     * The address of the best icon a page names: the largest declared size, apple-touch icons a little ahead of plain
     * ones, SVG skipped (it cannot be decoded here). Null when the page names none. No Android classes, so it is tested on the JVM.
     */
    static String pickIconUrl(String html, String baseUrl) {
        String best = null;
        int bestScore = -1;
        Matcher m = LINK.matcher(html);
        while (m.find()) {
            String tag = m.group();
            String rel = attr(tag, "rel");
            String href = attr(tag, "href");
            if (rel == null || href == null || href.isEmpty()) continue;
            rel = rel.toLowerCase(Locale.ROOT);
            if (!rel.contains("icon") || rel.contains("mask-icon")) continue;
            if (href.toLowerCase(Locale.ROOT).split("[?#]")[0].endsWith(".svg")) continue;
            int size = sizeOf(attr(tag, "sizes"));
            if (size == 0) size = rel.contains("apple-touch") ? 180 : 32;
            int score = size * 2 + (rel.contains("apple-touch") ? 1 : 0);
            String resolved;
            try {
                resolved = new URI(baseUrl).resolve(href.trim()).toString();
            } catch (URISyntaxException | IllegalArgumentException e) {
                continue;
            }
            if (score > bestScore) {
                best = resolved;
                bestScore = score;
            }
        }
        return best;
    }

    /** The largest edge in a sizes="16x16 32x32" attribute; 0 when absent or "any". */
    static int sizeOf(String sizes) {
        if (sizes == null) return 0;
        int best = 0;
        Matcher m = Pattern.compile("(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE).matcher(sizes);
        while (m.find()) best = Math.max(best, Math.max(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        return best;
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE).matcher(tag);
        if (!m.find()) return null;
        return m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
    }
}
