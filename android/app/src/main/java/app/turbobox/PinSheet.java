package app.turbobox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * A pinned page's own settings from the home screen: the ones that belong to the pin (volume, zoom, aspect ratio, desktop or
 * mobile site, microphone and camera, recording). If the page is running the change reaches it at once; the microphone
 * device, its volume and brightness are shared by every page and stay in the page's settings on the session screen.
 */
final class PinSheet {
    private PinSheet() {
    }

    static void show(Activity act, Pin pin) {
        LinearLayout col = new LinearLayout(act);
        Dialog d = Ui.sheet(act, col);
        col.addView(Ui.text(act, "Page settings - " + pin.name, 20f, Ui.TEXT, "semibold"), Ui.lp(act, 0, 4));
        Session running = Sessions.forPin(pin.id);
        col.addView(Ui.hint(act, running == null ? "Used the next time this page opens." : "This page is running: changes apply now."), Ui.lp(act, 0, 10));

        Switch mic = new Switch(act);
        mic.setText("Page may use the microphone");
        mic.setTextColor(Ui.TEXT);
        mic.setChecked(pin.allowMic);
        mic.setOnCheckedChangeListener((b, on) -> {
            change(act, pin, p -> p.allowMic = on);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setMicAllowed(on);
        });
        col.addView(mic, Ui.lp(act, 4, 4));
        Switch cam = new Switch(act);
        cam.setText("Page may use the camera");
        cam.setTextColor(Ui.TEXT);
        cam.setChecked(pin.allowCamera);
        cam.setOnCheckedChangeListener((b, on) -> {
            change(act, pin, p -> p.allowCamera = on);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setCameraAllowed(on);
        });
        col.addView(cam, Ui.lp(act, 4, 4));
        Switch mobile = new Switch(act);
        mobile.setText("Mobile site (instead of desktop)");
        mobile.setTextColor(Ui.TEXT);
        mobile.setChecked(pin.mobile);
        mobile.setOnCheckedChangeListener((b, on) -> {
            change(act, pin, p -> p.mobile = on);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setMobile(on);
        });
        col.addView(mobile, Ui.lp(act, 4, 10));

        TextView vol = Ui.text(act, Ui.pct(pin.volume), 26f, Ui.TEXT, "sans-serif");
        col.addView(Ui.label(act, "Page volume"));
        col.addView(vol, Ui.lp(act, 2, 2));
        col.addView(Ui.slider(act, 2f, pin.volume, (value, fromUser) -> {
            vol.setText(Ui.pct(value));
            if (!fromUser) return;
            change(act, pin, p -> p.volume = value);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setVolume(value);
        }), new LinearLayout.LayoutParams(-1, -2));

        float zoom0 = Math.max(0.5f, pin.zoom);
        TextView zoom = Ui.text(act, Ui.pct(zoom0), 26f, Ui.TEXT, "sans-serif");
        col.addView(Ui.label(act, "Page zoom"), Ui.lp(act, 12, 0));
        col.addView(zoom, Ui.lp(act, 2, 2));
        col.addView(Ui.slider(act, 3f, zoom0, (value, fromUser) -> {
            float z = Math.max(0.5f, value);
            zoom.setText(Ui.pct(z));
            if (!fromUser) return;
            change(act, pin, p -> p.zoom = z);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setZoom(z);
        }), new LinearLayout.LayoutParams(-1, -2));

        TextView aspect = Ui.text(act, Pin.ASPECT_LABELS[pin.aspect] + "  ›", 17f, Ui.GREEN, "sans-serif-medium");
        aspect.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
        aspect.setOnClickListener(v -> new AlertDialog.Builder(act).setTitle("Force aspect ratio")
                .setSingleChoiceItems(Pin.ASPECT_LABELS, pin.aspect, (dd, which) -> {
                    change(act, pin, p -> p.aspect = which);
                    pin.aspect = which;
                    Session s = Sessions.forPin(pin.id);
                    if (s != null) s.setAspect(which);
                    aspect.setText(Pin.ASPECT_LABELS[which] + "  ›");
                    dd.dismiss();
                }).show());
        col.addView(Ui.label(act, "Aspect ratio"), Ui.lp(act, 12, 0));
        col.addView(aspect);

        TextView rec = Ui.text(act, Pin.recordModeText(pin.recordMode, Prefs.recordByDefault(act)) + "  ›", 16f, Ui.GREEN, "sans-serif-medium");
        rec.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
        rec.setOnClickListener(v -> {
            int next = (pin.recordMode + 1) % 3;
            pin.recordMode = next;
            change(act, pin, p -> p.recordMode = next);
            rec.setText(Pin.recordModeText(next, Prefs.recordByDefault(act)) + "  ›");
        });
        col.addView(Ui.label(act, "Recording (tap to change)"), Ui.lp(act, 12, 0));
        col.addView(rec);

        TextView done = Ui.pill(act, "Done", Ui.GREEN);
        done.setOnClickListener(v -> d.dismiss());
        col.addView(done, Ui.lp(act, 14, 0));
        d.show();
    }

    /**
     * Applies a change to the stored pin and, when the page is running, to the copy it holds (it saves that copy again
     * whenever one of its own settings moves, so the two must agree).
     */
    private static void change(Activity act, Pin pin, Consumer<Pin> edit) {
        Pin stored = PinStore.find(act, pin.id);
        if (stored != null) {
            edit.accept(stored);
            PinStore.put(act, stored);
        }
        Session s = Sessions.forPin(pin.id);
        if (s != null && s.pin() != null) edit.accept(s.pin());
    }
}
