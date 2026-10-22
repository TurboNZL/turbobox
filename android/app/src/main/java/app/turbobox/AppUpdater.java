package app.turbobox;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Updates the app from the newest GitHub release that carries an .apk, so nothing has to be done on a computer. The
 * APK is signed with the same key every time, which is what lets Android install it over the running app. Android
 * always asks the user to confirm the install.
 *
 * A Java port of the engine's {@code updaters} module (Kotlin in TurboMusic); same rules: the release list rather than
 * /releases/latest (which skips pre-releases), the token never follows a redirect to the storage host.
 */
final class AppUpdater {
    static final class State {
        final String status;   // idle, checking, downloading, ready, none, error
        final String version;
        final int percent;
        final String message;

        State(String status, String version, int percent, String message) {
            this.status = status;
            this.version = version;
            this.percent = percent;
            this.message = message;
        }
    }

    interface Listener {
        void onState(State s);
    }

    private static volatile State state = new State("idle", null, -1, null);
    private static Listener listener;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppUpdater() {
    }

    static State state() {
        return state;
    }

    static void setListener(Listener l) {
        listener = l;
    }

    private static void set(State s) {
        state = s;
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onState(s);
        });
    }

    static void check(Context context) {
        final Context ctx = context.getApplicationContext();
        if (state.status.equals("checking") || state.status.equals("downloading")) return;
        set(new State("checking", null, -1, null));
        IO.execute(() -> {
            String token = Prefs.updateToken(ctx);
            try {
                String url = "https://api.github.com/repos/" + BuildConfig.REPO_OWNER + "/" + BuildConfig.REPO_NAME + "/releases?per_page=20";
                JSONArray releases = new JSONArray(new String(get(url, token, "application/vnd.github+json"), "UTF-8"));
                Prefs.setLastUpdateCheck(ctx, System.currentTimeMillis());
                String[] found = Releases.newestWithApk(releases);
                if (found == null) {
                    set(new State("none", null, -1, "No release with an Android app (.apk) has been published yet."));
                    return;
                }
                if (!Releases.isNewer(found[0], BuildConfig.VERSION_NAME)) {
                    set(new State("none", null, -1, null));
                    return;
                }
                String version = found[0].startsWith("v") ? found[0].substring(1) : found[0];
                set(new State("downloading", version, 0, null));
                File dir = new File(ctx.getCacheDir(), "updates");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File out = new File(dir, "update.apk");
                final int[] last = {-1};
                download(found[1], token, out, pct -> {
                    if (pct != last[0]) {
                        last[0] = pct;
                        set(new State("downloading", version, pct, null));
                    }
                });
                set(new State("ready", version, 100, null));
            } catch (UnknownHostException e) {
                set(new State("error", null, -1, "No connection to GitHub. Check the phone's internet."));
            } catch (IOException | JSONException | IllegalStateException e) {
                set(new State("error", null, -1, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        });
    }

    /** Opens Android's installer for the downloaded file (the first time: the page where "install unknown apps" is allowed for this app). */
    static void install(Context context) {
        File file = new File(context.getCacheDir(), "updates/update.apk");
        if (!file.exists()) return;
        if (!context.getPackageManager().canRequestPackageInstalls()) {
            context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
        context.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static HttpURLConnection open(String url, String token, String accept) throws IOException {
        String u = url;
        // GitHub answers asset downloads with a redirect to its storage host; that host must NOT get the GitHub
        // token, so follow by hand.
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("Accept", accept);
            c.setRequestProperty("User-Agent", "turbobox-android");
            if (!token.isEmpty() && new URL(u).getHost().endsWith("github.com")) c.setRequestProperty("Authorization", "Bearer " + token);
            int code = c.getResponseCode();
            if (code >= 300 && code <= 399) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) throw new IllegalStateException("Redirect without a target");
                u = next;
                continue;
            }
            if (code == 404 || code == 401) {
                throw new IllegalStateException(token.isEmpty()
                        ? "GitHub cannot find the repository. If it is private, add a GitHub token in Settings > App updates."
                        : "GitHub cannot find the repository with this token. It needs read access to the repository's contents.");
            }
            if (code != 200) throw new IllegalStateException("GitHub answered " + code);
            return c;
        }
        throw new IllegalStateException("Too many redirects");
    }

    private static byte[] get(String url, String token, String accept) throws IOException {
        HttpURLConnection c = open(url, token, accept);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private interface Progress {
        void percent(int pct);
    }

    private static void download(String url, String token, File out, Progress progress) throws IOException {
        HttpURLConnection c = open(url, token, "application/octet-stream");
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            long total = c.getContentLengthLong();
            long done = 0;
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                o.write(buf, 0, n);
                done += n;
                if (total > 0) progress.percent((int) (done * 100 / total));
            }
        } finally {
            c.disconnect();
        }
    }
}
