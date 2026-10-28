package app.turbobox;

import android.app.Activity;
import android.app.AlertDialog;
import android.media.AudioDeviceInfo;

import java.util.ArrayList;
import java.util.List;

/** Choosing which of the phone's real microphones feeds the pages. Pages never see this list. */
final class MicPicker {
    private MicPicker() {
    }

    static AudioDeviceInfo chosen(Activity act) {
        return Devices.find(act, Prefs.micKey(act));
    }

    static String summary(Activity act) {
        Devices.ensureMicChoice(act);
        AudioDeviceInfo d = chosen(act);
        return d == null ? "Chosen input not available" : Devices.describe(d);
    }

    /** Shows the list; {@code done} runs after a choice has been saved and applied to every open page. */
    static void show(Activity act, Runnable done) {
        List<AudioDeviceInfo> inputs = Devices.inputs(act);
        List<String> items = new ArrayList<>();
        for (AudioDeviceInfo d : inputs) items.add(Devices.describe(d));
        String key = Prefs.micKey(act);
        int checked = -1;
        for (int i = 0; i < inputs.size(); i++) if (Devices.key(inputs.get(i)).equals(key)) checked = i;
        new DimBuilder(act)
                .setTitle("Microphone")
                .setSingleChoiceItems(items.toArray(new String[0]), checked, (d, which) -> {
                    String picked = Devices.key(inputs.get(which));
                    Prefs.setMicKey(act, picked);
                    for (Session s : Sessions.active()) s.setMicChoice(picked);
                    InputRoute.apply(act, chosen(act));
                    d.dismiss();
                    if (done != null) done.run();
                })
                .show();
    }
}
