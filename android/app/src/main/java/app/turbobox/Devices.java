package app.turbobox;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The phone's audio inputs, named the way the settings sheet lists them. */
final class Devices {
    private Devices() {
    }

    /** The phone's input devices, in the order the system lists them. */
    static List<AudioDeviceInfo> inputs(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        List<AudioDeviceInfo> list = new ArrayList<>();
        Collections.addAll(list, am.getDevices(AudioManager.GET_DEVICES_INPUTS));
        return list;
    }

    /** A key that identifies an input across runs (device ids are not stable). */
    static String key(AudioDeviceInfo d) {
        return d.getType() + "|" + d.getProductName();
    }

    static AudioDeviceInfo find(Context context, String key) {
        if (context == null || key == null || key.isEmpty()) return null;
        for (AudioDeviceInfo d : inputs(context)) if (key(d).equals(key)) return d;
        return null;
    }

    /** There is no "automatic" input: until one is picked, the phone's own microphone is used (and shown). */
    static void ensureMicChoice(Context c) {
        if (!Prefs.micKey(c).isEmpty()) return;
        AudioDeviceInfo pick = null;
        for (AudioDeviceInfo d : inputs(c)) {
            if (pick == null) pick = d;
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                pick = d;
                break;
            }
        }
        if (pick != null) Prefs.setMicKey(c, key(pick));
    }

    static String describe(AudioDeviceInfo d) {
        return typeName(d.getType()) + " - " + d.getProductName();
    }

    static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Phone mic";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "Earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Speaker";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth call (SCO)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired headphones";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB device";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB headset";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "Telephony";
            default: return "Type " + type;
        }
    }
}
