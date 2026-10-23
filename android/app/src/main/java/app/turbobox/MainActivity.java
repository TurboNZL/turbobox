package app.turbobox;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The home screen: the pinned pages (tap one to open it), the pages that are running (at most two), and the way
 * to workflows, recordings and settings.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1;

    private LinearLayout body;
    private boolean reordering;   // the pinned pages show arrows to move them

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);
        askPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        Shortcuts.refresh(this);
        WorkflowScheduler.scheduleAll(this);   // keeps the alarms right even after an update
        SessionService.ensure(this);           // standby, if it is on
    }

    private void askPermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Without the microphone permission pages cannot hear you (allow it in Android "
                    + "settings > Apps > TurboBox). Pages that only play still work.", Toast.LENGTH_LONG).show();
        }
    }

    // ---- screen ------------------------------------------------------------------------------

    private void render() {
        body.removeAllViews();
        int pad = Ui.dp(this, 16);
        body.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 28));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "TurboBox", 28f, Ui.TEXT, "semibold"));
        titles.addView(Ui.text(this, "Pinned pages, running in the background", 13f, Ui.DIM, "sans-serif"), Ui.lp(this, 2, 0));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView settings = Ui.pill(this, "Settings", 0);
        settings.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        head.addView(settings);
        body.addView(head);

        renderRunning();
        renderPins();
        renderShortcuts();
    }

    private void renderRunning() {
        List<Session> active = Sessions.active();
        if (active.isEmpty()) return;
        body.addView(Ui.label(this, "Running"), Ui.lp(this, 22, 8));
        LinearLayout card = Ui.card(this);
        for (Session s : active) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Ui.text(this, s.statusLine(), 16f, Ui.TEXT, "sans-serif-medium");
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView show = Ui.pill(this, "Open", Ui.GREEN);
            show.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            show.setOnClickListener(v -> startActivity(new Intent(this, SessionActivity.class).putExtra(SessionActivity.EXTRA_SLOT, s.slot)));
            row.addView(show);
            TextView close = Ui.pill(this, "Close", Ui.RED);
            close.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            close.setOnClickListener(v -> {
                s.close("closed from the home screen");
                render();
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.leftMargin = Ui.dp(this, 8);
            row.addView(close, cp);
            card.addView(row, Ui.lp(this, 4, 4));
        }
        body.addView(card);
    }

    private void renderPins() {
        List<Pin> pins = PinStore.load(this);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.label(this, "Pinned pages"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (pins.size() > 1) {
            TextView reorder = Ui.pill(this, reordering ? "Done" : "Reorder", reordering ? Ui.GREEN : 0);
            reorder.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
            reorder.setOnClickListener(v -> {
                reordering = !reordering;
                render();
            });
            head.addView(reorder);
        }
        body.addView(head, Ui.lp(this, 22, 8));
        if (reordering && pins.size() <= 1) reordering = false;

        List<View> tiles = new ArrayList<>();
        for (int i = 0; i < pins.size(); i++) tiles.add(pinTile(pins.get(i), i, pins.size()));
        tiles.add(addTile());
        body.addView(PinGrid.grid(this, tiles));
        if (pins.isEmpty()) {
            body.addView(Ui.hint(this, "Pin the pages you use, like a video, a stream or a document. One tap opens "
                    + "them here, and two can run at once."), Ui.lp(this, 2, 0));
        }
    }

    private View pinTile(Pin pin, int index, int count) {
        Session running = Sessions.forPin(pin.id);
        String sub = running == null ? pin.host() : Sessions.active().size() > 1 ? "Running in " + Sessions.letter(running) : "Running";
        LinearLayout t = PinGrid.tile(this, pin, sub, running == null ? Ui.DIM : Ui.GREEN, false, () -> {
            Shortcuts.refresh(this);
            render();
        });
        if (reordering) {
            // Move earlier / later in the list (the list reads left to right, then down).
            LinearLayout arrows = new LinearLayout(this);
            arrows.setGravity(Gravity.CENTER);
            TextView earlier = Ui.pill(this, "‹", 0);
            TextView later = Ui.pill(this, "›", 0);
            earlier.setTextSize(20f);
            later.setTextSize(20f);
            earlier.setAlpha(index == 0 ? 0.3f : 1f);
            later.setAlpha(index == count - 1 ? 0.3f : 1f);
            earlier.setOnClickListener(v -> move(pin, -1));
            later.setOnClickListener(v -> move(pin, 1));
            arrows.addView(earlier, Ui.weight(this, 0));
            arrows.addView(later, Ui.weight(this, 8));
            t.addView(arrows, Ui.lp(this, 10, 0));
            return t;
        }
        t.setClickable(true);
        t.setOnClickListener(v -> open(pin));
        t.setOnLongClickListener(v -> {
            pinMenu(pin);
            return true;
        });
        return t;
    }

    private void move(Pin pin, int delta) {
        PinStore.moveSaved(this, pin.id, delta);
        Shortcuts.refresh(this);   // the first pins are the launcher's long-press menu, in this order
        render();
    }

    private View addTile() {
        LinearLayout t = Ui.card(this);
        t.setGravity(Gravity.CENTER);
        TextView plus = Ui.text(this, "+", 30f, Ui.GREEN, "semibold");
        plus.setGravity(Gravity.CENTER);
        t.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        t.addView(Ui.text(this, "Pin a page", 15f, Ui.DIM, "sans-serif-medium"), Ui.lp(this, 10, 0));
        t.setMinimumHeight(Ui.dp(this, 118));
        t.setClickable(true);
        t.setOnClickListener(v -> editPin(null));
        return t;
    }

    private void renderShortcuts() {
        body.addView(Ui.label(this, "More"), Ui.lp(this, 18, 8));
        LinearLayout card = Ui.card(this);
        card.addView(navRow("Workflows", "Open a page and follow the buttons for you, even with the screen off, at a set time.",
                WorkflowsActivity.class));
        card.addView(navRow("Recordings", "What you missed: the buffer of what a page showed and played.", MissedActivity.class));
        card.addView(navRow("Guides", "How it works, with examples.", HelpActivity.class));
        body.addView(card);
    }

    private View navRow(String title, String text, Class<?> target) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        r.addView(Ui.text(this, title + "  ›", 17f, Ui.TEXT, "sans-serif-medium"));
        r.addView(Ui.hint(this, text), Ui.lp(this, 2, 0));
        r.setClickable(true);
        r.setOnClickListener(v -> startActivity(new Intent(this, target)));
        return r;
    }

    // ---- actions -----------------------------------------------------------------------------

    private void open(Pin pin) {
        startActivity(new Intent(this, SessionActivity.class)
                .setAction(SessionActivity.ACTION_OPEN_PIN)
                .putExtra(SessionActivity.EXTRA_PIN, pin.id));
    }

    private void pinMenu(Pin pin) {
        List<String> items = new ArrayList<>();
        items.add("Edit");
        items.add("Page settings");
        items.add("Add an icon to the home screen");
        items.add("Remove");
        new AlertDialog.Builder(this)
                .setTitle(pin.name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        editPin(pin);
                    } else if (which == 1) {
                        PinSheet.show(this, pin);
                    } else if (which == 2) {
                        if (!Shortcuts.requestHomeIcon(this, pin)) {
                            Toast.makeText(this, "This launcher cannot add icons. Long-press the TurboBox icon instead: your first pages are there.", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Remove " + pin.name + "?")
                                .setMessage("Workflows that use it will stop working until you pick another page.")
                                .setPositiveButton("Remove", (dd, w) -> {
                                    Session s = Sessions.forPin(pin.id);
                                    if (s != null) s.close("pin removed");
                                    PinStore.remove(this, pin.id);
                                    Favicons.delete(this, pin.id);
                                    Shortcuts.refresh(this);
                                    render();
                                })
                                .setNegativeButton("Keep", null)
                                .show();
                    }
                })
                .show();
    }

    /** Adds a page, or edits one. The address may be typed without "https://". */
    private void editPin(Pin existing) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        form.setPadding(p, Ui.dp(this, 8), p, 0);
        EditText name = new EditText(this);
        name.setHint("Name (Music, News, Work chat, ...)");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText url = new EditText(this);
        url.setHint("Address (example.com/page)");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final int[] mode = {existing == null ? 0 : existing.recordMode};
        final boolean defaultOn = Prefs.recordByDefault(this);
        TextView recording = Ui.pill(this, "Recording: " + Pin.recordModeText(mode[0], defaultOn), 0);
        recording.setOnClickListener(v -> {
            mode[0] = (mode[0] + 1) % 3;
            recording.setText("Recording: " + Pin.recordModeText(mode[0], defaultOn));
        });
        if (existing != null) {
            name.setText(existing.name);
            url.setText(existing.url);
        }
        form.addView(name);
        form.addView(url);
        form.addView(recording, Ui.lp(this, 12, 0));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Pin a page" : "Edit " + existing.name)
                .setView(form)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = Pin.normalizeUrl(url.getText().toString());
            String label = name.getText().toString().trim();
            if (address == null) {
                url.setError("Type a web address, like example.com/page");
                return;
            }
            if (label.isEmpty()) label = Pin.create("", address).host();
            boolean addressChanged = existing == null || !existing.url.equals(address);
            Pin pin = existing != null ? existing : Pin.create(label, address);
            pin.name = label;
            pin.url = address;
            pin.recordMode = mode[0];
            if (addressChanged) {
                Favicons.delete(this, pin.id);
                PinGrid.forget(pin.id);
            }
            PinStore.put(this, pin);
            Shortcuts.refresh(this);
            d.dismiss();
            render();
        }));
        d.show();
    }
}
