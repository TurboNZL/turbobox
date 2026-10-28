package app.turbobox;

import android.app.Activity;
import android.app.Dialog;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.function.Consumer;

/**
 * A page's own settings: the same sheet from the home screen (a pinned page) and from the page screen (the running one). Grouped in
 * coloured sections with an icon each. If the page is running a change reaches it at once. Volumes live in {@link VolumeSheet}.
 */
final class PinSheet {
    static final int PURPLE = 0xFFB48EF7, BLUE = 0xFF66D9EF, ORANGE = 0xFFFD971F, PINK = 0xFFF92672;

    private PinSheet() {
    }

    static void show(Activity act, Pin pin) {
        LinearLayout col = new LinearLayout(act);
        Dialog d = Ui.sheet(act, col);
        col.addView(Ui.text(act, "Page settings - " + pin.name, 20f, Ui.TEXT, "semibold"), Ui.lp(act, 0, 4));
        Session running = Sessions.forPin(pin.id);
        col.addView(Ui.hint(act, running == null ? "Used the next time this page opens." : "This page is running: changes apply now."), Ui.lp(act, 0, 4));
        build(act, col, pin, d::dismiss);
        android.widget.TextView done = Ui.pill(act, "Done", Ui.GREEN);
        done.setOnClickListener(v -> d.dismiss());
        col.addView(done, Ui.lp(act, 16, 0));
        d.show();
    }

    /** Adds every section for this page to {@code col}. {@code close} dismisses the sheet (for actions that need the page in view). */
    static void build(Activity act, LinearLayout col, Pin pin, Runnable close) {
        // ---- Privacy
        LinearLayout priv = Ui.section(act, col, IconButton.Icon.LOCK, "Privacy", PINK);
        priv.addView(Ui.switchRow(act, "Microphone", "Off refuses the page, like pressing Block", pin.allowMic, PINK, (b, on) -> {
            change(act, pin, p -> p.allowMic = on);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setMicAllowed(on);
        }), Ui.lp(act, 0, 4));
        priv.addView(Ui.switchRow(act, "Camera", "Off until you allow it here and in Android", pin.allowCamera, PINK, (b, on) -> {
            change(act, pin, p -> p.allowCamera = on);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setCameraAllowed(on);
            if (on && act.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 3);
            }
        }), Ui.lp(act, 8, 4));

        // ---- Look
        LinearLayout look = Ui.section(act, col, IconButton.Icon.LAYOUT, "Size and shape", BLUE);
        look.addView(Ui.sliderRow(act, "Page size", pin.pageWidth + " px", 3.84f, pin.pageWidth / 1000f, BLUE, (v, fromUser) -> {
            int px = Math.max(800, Math.round(v * 100f) * 10);
            if (!fromUser) return;
            change(act, pin, p -> p.pageWidth = px);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setPageWidth(px);
        }, v -> Math.max(800, Math.round(v * 100f) * 10) + " px"), Ui.lp(act, 0, 0));
        look.addView(Ui.sliderRow(act, "Zoom", Ui.pct(Math.max(0.5f, pin.zoom)), 3f, Math.max(0.5f, pin.zoom), BLUE, (v, fromUser) -> {
            if (!fromUser) return;
            float z = Math.max(0.5f, v);
            change(act, pin, p -> p.zoom = z);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setZoom(z);
        }, v -> Ui.pct(Math.max(0.5f, v))), Ui.lp(act, 8, 0));
        android.widget.TextView reset = Ui.pill(act, "Reset page size and zoom", 0);
        reset.setOnClickListener(v -> {
            change(act, pin, p -> {
                p.pageWidth = 1280;
                p.zoom = 1f;
            });
            Session s = Sessions.forPin(pin.id);
            if (s != null) {
                s.setPageWidth(1280);
                s.setZoom(1f);
            }
            close.run();
            Toast.makeText(act, "Page size reset to 1280 px, zoom to 100%.", Toast.LENGTH_SHORT).show();
        });
        look.addView(reset, Ui.lp(act, 6, 0));
        look.addView(Ui.hint(act, "Page size is how wide a screen the page thinks it has (at least 800 px: desktop sites cannot lay out narrower). "
                + "Zoom divides it, so the page gets bigger text, but never below that 800 px."), Ui.lp(act, 0, 8));
        String[] shortAspects = {"Fill", "16:9", "4:3", "21:9", "1:1", "9:16", "3:2"};
        look.addView(Ui.segmented(act, shortAspects, pin.aspect, BLUE, which -> {
            change(act, pin, p -> p.aspect = which);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setAspect(which);
        }), Ui.lp(act, 4, 8));
        look.addView(Ui.switchRow(act, "Mobile site", "Off asks for the desktop layout; changing it reloads the page", pin.mobile, BLUE, (b, on) -> {
            change(act, pin, p -> p.mobile = on);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setMobile(on);
        }), Ui.lp(act, 0, 4));
        look.addView(Ui.switchRow(act, "Cursor mode", "One finger moves a pointer, a tap clicks, two fingers scroll", pin.cursorMode, BLUE, (b, on) -> {
            change(act, pin, p -> p.cursorMode = on);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.setCursorMode(on);
        }), Ui.lp(act, 8, 4));
        look.addView(Ui.switchRow(act, "Shrink for the keyboard", "Typing makes the page smaller so the keyboard does not cover it", pin.shrinkOnType, BLUE,
                (b, on) -> change(act, pin, p -> p.shrinkOnType = on)), Ui.lp(act, 8, 4));

        // ---- Workflow recording size
        LinearLayout wfs = Ui.section(act, col, IconButton.Icon.RECORD, "Size while a workflow records", PINK);
        wfs.addView(Ui.switchRow(act, "Lock a size for workflows", "While a workflow uses this page (and until its recording ends) the page has the size below, "
                + "whatever the screen does. Off: the page keeps its normal size.", pin.wfSize, PINK, (b, on) -> {
            change(act, pin, p -> p.wfSize = on);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.refreshLock();
        }), Ui.lp(act, 0, 4));
        wfs.addView(Ui.sliderRow(act, "Page size", pin.wfPageWidth + " px", 3.84f, pin.wfPageWidth / 1000f, PINK, (v, fromUser) -> {
            int px = Math.max(800, Math.round(v * 100f) * 10);
            if (!fromUser) return;
            change(act, pin, p -> p.wfPageWidth = px);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.refreshLock();
        }, v -> Math.max(800, Math.round(v * 100f) * 10) + " px"), Ui.lp(act, 8, 0));
        wfs.addView(Ui.sliderRow(act, "Zoom", Ui.pct(Math.max(0.5f, pin.wfZoom)), 3f, Math.max(0.5f, pin.wfZoom), PINK, (v, fromUser) -> {
            if (!fromUser) return;
            float z = Math.max(0.5f, v);
            change(act, pin, p -> p.wfZoom = z);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.refreshLock();
        }, v -> Ui.pct(Math.max(0.5f, v))), Ui.lp(act, 8, 0));
        String[] lockShapes = {"Phone", "16:9", "4:3", "21:9", "1:1", "9:16", "3:2"};
        wfs.addView(Ui.label(act, "Shape of the recording"), Ui.lp(act, 8, 4));
        wfs.addView(Ui.segmented(act, lockShapes, pin.wfAspect, PINK, which -> {
            change(act, pin, p -> p.wfAspect = which);
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.refreshLock();
        }));

        // ---- Workflows this page takes part in
        PageWorkflows.section(act, col, pin, ORANGE);

        // ---- Behaviour
        LinearLayout beh = Ui.section(act, col, IconButton.Icon.GEAR, "Behaviour", ORANGE);
        beh.addView(Ui.switchRow(act, "Top bar floats over the page", "The bar sits on top of this page instead of pushing it down, so the page keeps its full size", pin.barOverlay, ORANGE,
                (b, on) -> {
                    change(act, pin, p -> p.barOverlay = on);
                    if (act instanceof SessionActivity) ((SessionActivity) act).applyBarOverlay();
                }), Ui.lp(act, 0, 4));
        beh.addView(Ui.switchRow(act, "Ask before closing", "A confirmation for pages where closing could cost you something", pin.confirmClose, ORANGE,
                (b, on) -> change(act, pin, p -> p.confirmClose = on)), Ui.lp(act, 0, 4));
        beh.addView(Ui.switchRow(act, "Block ads and trackers", null, !pin.noAdblock, ORANGE,
                (b, on) -> change(act, pin, p -> p.noAdblock = !on)), Ui.lp(act, 8, 4));
        beh.addView(Ui.switchRow(act, "Protected video (Widevine)", "Needed by streaming sites. It cannot be recorded (comes out black). Applies when the page is opened again.",
                pin.allowDrm, ORANGE, (b, on) -> change(act, pin, p -> p.allowDrm = on)), Ui.lp(act, 8, 4));
        String[] rec = {"Follow default", "Always record", "Never record"};
        beh.addView(Ui.label(act, "Recording when it opens"), Ui.lp(act, 12, 4));
        beh.addView(Ui.segmented(act, rec, pin.recordMode, ORANGE, which -> {
            pin.recordMode = which;
            change(act, pin, p -> p.recordMode = which);
        }));

        // ---- Tools
        LinearLayout tools = Ui.section(act, col, IconButton.Icon.TARGET, "Tools", PURPLE);
        android.widget.TextView pick = Ui.pill(act, "Pick an element (copy its selector)", 0);
        pick.setOnClickListener(v -> {
            Session s = Sessions.forPin(pin.id);
            if (s == null) {
                Toast.makeText(act, "Open the page first, then pick from its settings.", Toast.LENGTH_LONG).show();
                return;
            }
            s.startPick();
            close.run();
            Toast.makeText(act, "Tap the element on the page. Its selector is copied for a workflow step.", Toast.LENGTH_LONG).show();
        });
        tools.addView(pick);
        android.widget.TextView sound = Ui.pill(act, "Turn the page's own sound on", 0);
        sound.setOnClickListener(v -> {
            Session s = Sessions.forPin(pin.id);
            if (s != null) s.unmutePageMedia();
        });
        tools.addView(sound, Ui.lp(act, 8, 0));
        android.widget.TextView vols = Ui.pill(act, "Volumes", 0);
        vols.setOnClickListener(v -> VolumeSheet.show(act));
        tools.addView(vols, Ui.lp(act, 8, 0));
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
        if (s != null && s.pin() != pin) edit.accept(pin);
    }
}
