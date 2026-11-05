package app.turbobox;

import android.media.AudioRecord;

/**
 * The one place the app opens a microphone. With "Never use the microphone" on, it refuses, whatever the Android permission says, so the
 * permission can be granted just for the speech recogniser (which is fed from the recording's own sound, not from a microphone) without
 * any page, test or recording ever getting microphone audio. Every {@code AudioRecord} in the app is made through {@link #open}.
 */
final class MicGuard {
    private static volatile boolean blocked;

    private MicGuard() {
    }

    static void set(boolean v) {
        blocked = v;
    }

    static boolean blocked() {
        return blocked;
    }

    static AudioRecord open(int source, int rate, int channel, int encoding, int bytes) {
        if (blocked) {
            MicLog.add("microphone open REFUSED (never use the microphone is on)");
            throw new SecurityException("the microphone is turned off for the whole app (Settings > Privacy)");
        }
        return new AudioRecord(source, rate, channel, encoding, bytes);
    }
}
