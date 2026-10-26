package app.turbobox;

import android.content.Context;
import android.media.AudioManager;

/**
 * The phone's own volume, set from inside the app. A Bluetooth headset in call mode (HFP) only exposes the call
 * volume to the hardware buttons, and the buttons follow whichever stream the system believes is active, so
 * this sets the media and the call stream together, as one level.
 */
final class NativeVolume {
    private NativeVolume() {
    }

    private static AudioManager am(Context c) {
        return (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    /** 0..1: the media stream's level. */
    static float get(Context c) {
        AudioManager a = am(c);
        int max = Math.max(1, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    /** Sets the media and call volume to the same fraction of each one's range. */
    static void set(Context c, float level) {
        AudioManager a = am(c);
        float f = Math.max(0f, Math.min(1f, level));
        int[] streams = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL};
        for (int stream : streams) {
            int max = a.getStreamMaxVolume(stream);
            int min = stream == AudioManager.STREAM_VOICE_CALL ? Math.max(1, android.os.Build.VERSION.SDK_INT >= 28 ? a.getStreamMinVolume(stream) : 1) : 0;
            try {
                a.setStreamVolume(stream, Math.max(min, Math.round(f * max)), 0);
            } catch (SecurityException ignored) {
                // Do Not Disturb can refuse a volume change; the other stream may still take it.
            }
        }
    }
}
