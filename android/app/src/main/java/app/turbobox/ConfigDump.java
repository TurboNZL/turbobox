package app.turbobox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** The app's global settings and environment as plain text, for pasting into a bug report. Secrets are left out. */
final class ConfigDump {
    private ConfigDump() {
    }

    // Names that hold secrets (keys, tokens, PIN hashes) are shown as "(hidden)".
    private static final Pattern SECRET = Pattern.compile("(?i).*(key|token|hash|salt|code|pass|secret).*");
    // Names whose value is a list or data, not a setting (pins, workflows, layouts, filter lists): only the size is shown.
    private static final int LONG = 160;

    static String build(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("TurboBox ").append(BuildConfig.VERSION_NAME).append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        try {
            android.content.pm.PackageInfo wv = WebView.getCurrentWebViewPackage();
            if (wv != null) sb.append("WebView ").append(wv.packageName).append(' ').append(wv.versionName).append('\n');
        } catch (Throwable ignored) {
            // Older phones.
        }
        try {
            String def = WebSettings.getDefaultUserAgent(c);
            sb.append("WebView's own user agent: ").append(def).append('\n');
            sb.append("Desktop pages send:       ").append(Session.desktopUserAgent(def)).append('\n');
            sb.append("Mobile pages send:        ").append(Session.mobileUserAgent(def)).append('\n');
        } catch (Throwable t) {
            sb.append("User agent: not available (").append(t.getMessage()).append(")\n");
        }
        sb.append("\nGlobal settings:\n");
        SharedPreferences sp = Prefs.sp(c);
        Map<String, ?> all = new TreeMap<>(sp.getAll());
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            String shown;
            if (SECRET.matcher(k).matches()) shown = "(hidden)";
            else {
                shown = String.valueOf(v);
                if (shown.length() > LONG) shown = "(" + shown.length() + " characters of data)";
            }
            sb.append("  ").append(k).append(" = ").append(shown).append('\n');
        }
        sb.append("\nPinned pages, sign-ins and workflows are not included.\n");
        audioRoute(c, sb);
        return sb.toString();
    }

    /** Where the phone sends sound right now and what the volumes are, for a volume that does nothing. */
    private static void audioRoute(Context c, StringBuilder sb) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            sb.append("\nAudio route\n");
            sb.append("  mode ").append(am.getMode()).append(" (0 normal, 3 in communication), volume fixed: ").append(am.isVolumeFixed()).append('\n');
            sb.append("  media volume ").append(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)).append('/')
                    .append(am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)).append(", call volume ")
                    .append(am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)).append('/')
                    .append(am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)).append('\n');
            if (Build.VERSION.SDK_INT >= 31) {
                android.media.AudioDeviceInfo cd = am.getCommunicationDevice();
                sb.append("  communication device: ").append(cd == null ? "none" : "type " + cd.getType()).append('\n');
            }
            StringBuilder outs = new StringBuilder();
            for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) outs.append(' ').append(d.getType());
            sb.append("  output device types:").append(outs).append(" (7 Bluetooth call, 8 Bluetooth media, 26 Bluetooth LE, 2 speaker)\n");
        } catch (RuntimeException e) {
            sb.append("Audio route: not available (").append(e.getMessage()).append(")\n");
        }
    }
}
