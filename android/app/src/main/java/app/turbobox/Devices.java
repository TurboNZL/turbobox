package app.turbobox;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The phone's audio inputs, named the way the settings sheet lists them. */
final class Devices {
    private Devices() {
    }

    /** The system lists plumbing as inputs too (telephony, remote submix, buses); only real microphones are offered. */
    static boolean isMicrophone(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return true;
            default:
                return Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET;
        }
    }

    /** The microphones a person can pick: the phone's own, a wired or USB one, a Bluetooth headset's. One entry each. */
    static List<AudioDeviceInfo> inputs(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        List<AudioDeviceInfo> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (isMicrophone(d.getType()) && seen.add(key(d))) list.add(d);
        }
        // The phone's own microphone first, so the list reads the same way every time.
        Collections.sort(list, (a, b) -> Integer.compare(rank(a.getType()), rank(b.getType())));
        return list;
    }

    private static int rank(int type) {
        return type == AudioDeviceInfo.TYPE_BUILTIN_MIC ? 0 : 1;
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
        String saved = Prefs.micKey(c);
        if (!saved.isEmpty()) {
            // A choice saved by an older version may name plumbing (telephony, a submix): move it to the phone's microphone.
            int bar = saved.indexOf('|');
            int type = -1;
            try {
                type = Integer.parseInt(bar < 0 ? saved : saved.substring(0, bar));
            } catch (NumberFormatException ignored) {
                // Unreadable: replaced below.
            }
            if (isMicrophone(type)) return;
        }
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
            case 26: return "Bluetooth headset"; // TYPE_BLE_HEADSET
            default: return "Type " + type;
        }
    }
}
