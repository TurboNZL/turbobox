package app.turbobox;

import android.content.Context;
import android.media.AudioManager;

/**
 * The phone's own volumes, set from inside the app: media and calls are separate. A Bluetooth headset in call mode
 * (HFP) only exposes the call volume to the hardware buttons, so both can be reached from here.
 */
final class NativeVolume {
    private NativeVolume() {
    }

    private static AudioManager am(Context c) {
        return (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    /** 0..1: the media stream's level. */
    static float getMedia(Context c) {
        AudioManager a = am(c);
        int max = Math.max(1, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    /** 0..1: the call stream's level (what a Bluetooth headset in call mode follows). */
    static float getCall(Context c) {
        AudioManager a = am(c);
        int min = callMin(a);
        int max = Math.max(min + 1, a.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL));
        return Math.max(0f, (a.getStreamVolume(AudioManager.STREAM_VOICE_CALL) - min) / (float) (max - min));
    }

    /** Sets the media volume only. */
    static void setMedia(Context c, float level) {
        set(am(c), AudioManager.STREAM_MUSIC, 0, level);
    }

    /** Sets the call volume only. */
    static void setCall(Context c, float level) {
        AudioManager a = am(c);
        set(a, AudioManager.STREAM_VOICE_CALL, callMin(a), level);
    }

    private static int callMin(AudioManager a) {
        return Math.max(1, android.os.Build.VERSION.SDK_INT >= 28 ? a.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL) : 1);
    }

    private static void set(AudioManager a, int stream, int min, float level) {
        float f = Math.max(0f, Math.min(1f, level));
        int max = a.getStreamMaxVolume(stream);
        try {
            a.setStreamVolume(stream, Math.max(min, min + Math.round(f * (max - min))), 0);
        } catch (SecurityException ignored) {
            // Do Not Disturb can refuse a volume change.
        }
    }
}
