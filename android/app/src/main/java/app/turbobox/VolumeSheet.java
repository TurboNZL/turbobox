package app.turbobox;

import android.app.Activity;
import android.app.Dialog;
import android.widget.LinearLayout;

import java.util.List;

/** Every volume and level in one place: the phone, your microphone, brightness, and each page. From the home screen and the page screen. */
final class VolumeSheet {
    private VolumeSheet() {
    }

    static void show(Activity act) {
        LinearLayout col = new LinearLayout(act);
        Dialog d = Ui.sheet(act, col);
        col.addView(Ui.text(act, "Volumes", 20f, Ui.TEXT, "semibold"), Ui.lp(act, 0, 4));

        LinearLayout phone = Ui.section(act, col, IconButton.Icon.SPEAKER, "Phone", PinSheet.BLUE);
        phone.addView(Ui.sliderRow(act, "Media volume", Ui.pct(NativeVolume.getMedia(act)), 1f, NativeVolume.getMedia(act), PinSheet.BLUE,
                (v, fromUser) -> {
                    if (fromUser) NativeVolume.setMedia(act, v);
                }, Ui::pct));
        phone.addView(Ui.sliderRow(act, "Call volume", Ui.pct(NativeVolume.getCall(act)), 1f, NativeVolume.getCall(act), PinSheet.BLUE,
                (v, fromUser) -> {
                    if (fromUser) NativeVolume.setCall(act, v);
                }, Ui::pct), Ui.lp(act, 12, 0));
        phone.addView(Ui.sliderRow(act, "Screen brightness of this app", Ui.pct(Prefs.brightness(act)), 1f, Prefs.brightness(act), PinSheet.ORANGE,
                (v, fromUser) -> {
                    if (!fromUser) return;
                    Prefs.setBrightness(act, v);
                    if (act instanceof SessionActivity) ((SessionActivity) act).refreshBrightness();
                    Dimmer.refresh(act);
                }, Ui::pct), Ui.lp(act, 12, 0));

        LinearLayout mic = Ui.section(act, col, IconButton.Icon.MIC, "Your microphone", PinSheet.PINK);
        mic.addView(Ui.sliderRow(act, "What others hear", Ui.pct(Prefs.micGain(act)), 3f, Prefs.micGain(act), PinSheet.PINK, (v, fromUser) -> {
            if (!fromUser) return;
            Prefs.setMicGain(act, v);
            for (Session s : Sessions.active()) s.setMicGain(v);
        }, Ui::pct));

        LinearLayout pages = Ui.section(act, col, IconButton.Icon.PLAY, "Pages", PinSheet.PURPLE);
        List<Session> running = Sessions.active();
        if (running.isEmpty()) {
            pages.addView(Ui.hint(act, "Open a page to set its volume here. A page's volume is kept with the pinned page."));
            for (Pin p : PinStore.load(act)) {
                if (Profiles.isLocked(act, p.profileId)) continue;
                pages.addView(row(act, p.name, p.volume, v -> {
                    Pin stored = PinStore.find(act, p.id);
                    if (stored != null) {
                        stored.volume = v;
                        PinStore.put(act, stored);
                    }
                }), Ui.lp(act, 10, 0));
            }
        } else {
            for (Session s : running) {
                pages.addView(row(act, s.title(), s.volume(), v -> {
                    s.setVolume(v);
                    if (s.pin() != null) PinStore.put(act, s.pin());
                }), Ui.lp(act, 10, 0));
            }
        }
        android.widget.TextView done = Ui.pill(act, "Done", Ui.GREEN);
        done.setOnClickListener(v -> d.dismiss());
        col.addView(done, Ui.lp(act, 16, 0));
        d.show();
    }

    private static LinearLayout row(Activity act, String name, float vol, java.util.function.Consumer<Float> apply) {
        return Ui.sliderRow(act, name, Ui.pct(vol), 2f, vol, PinSheet.PURPLE, (v, fromUser) -> {
            if (fromUser) apply.accept(v);
        }, Ui::pct);
    }
}
