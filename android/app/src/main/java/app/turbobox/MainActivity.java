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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
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
    private final java.util.Set<String> iconTried = new java.util.HashSet<>();   // looked up once per start, not on every redraw

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
            Toast.makeText(this, "Without the microphone permission calls cannot send your voice (allow it in Android "
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
            TextView name = Ui.text(this, (char) ('A' + s.slot) + "   " + s.title()
                    + (s.isRecording() ? "   ● rec" : "") + (s.isMuted() ? "   muted" : ""), 16f, Ui.TEXT, "sans-serif-medium");
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
        body.addView(Ui.label(this, "Pinned pages"), Ui.lp(this, 22, 8));
        List<Pin> pins = PinStore.load(this);
        LinearLayout row = null;
        for (int i = 0; i <= pins.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                body.addView(row, Ui.lp(this, 0, 10));
            }
            View tile = i < pins.size() ? pinTile(pins.get(i)) : addTile(pins.size());
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.leftMargin = i % 2 == 0 ? 0 : Ui.dp(this, 10);
            row.addView(tile, p);
        }
        if (pins.size() % 2 == 0) {
            // The "+" tile started a new row on its own: keep it half-width like the others.
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        if (pins.isEmpty()) {
            body.addView(Ui.hint(this, "Pin the pages you use, like a meeting room or a lecture stream. One tap opens "
                    + "them here, and two can run at once."), Ui.lp(this, 2, 0));
        }
    }

    private View pinTile(Pin pin) {
        Session running = Sessions.forPin(pin.id);
        LinearLayout t = Ui.card(this);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        android.graphics.Bitmap icon = Favicons.load(this, pin.id);
        if (icon != null) {
            // The page's own icon, on a light disc so dark icons stay visible on the dark card.
            ImageView img = new ImageView(this);
            img.setImageBitmap(icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Ui.dp(this, 8);
            img.setPadding(pad, pad, pad, pad);
            img.setBackground(Ui.rounded(this, 0xFFF2F2F5, 0, 100));
            t.addView(img, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        } else {
            TextView badge = Ui.text(this, pin.initial(), 22f, Ui.BG, "semibold");
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Ui.rounded(this, Ui.GREEN, 0, 100));
            t.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
            if (iconTried.add(pin.id)) Favicons.fetch(this, pin, () -> {
                Shortcuts.refresh(this);
                render();
            });
        }
        TextView name = Ui.text(this, pin.name, 16f, Ui.TEXT, "sans-serif-medium");
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(name, Ui.lp(this, 10, 0));
        TextView host = Ui.text(this, running == null ? pin.host() : "Running in " + (char) ('A' + running.slot), 12f,
                running == null ? Ui.DIM : Ui.GREEN, "sans-serif");
        host.setSingleLine(true);
        host.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(host, Ui.lp(this, 2, 0));
        t.setClickable(true);
        t.setOnClickListener(v -> open(pin));
        t.setOnLongClickListener(v -> {
            pinMenu(pin);
            return true;
        });
        return t;
    }

    private View addTile(int count) {
        LinearLayout t = Ui.card(this);
        t.setGravity(Gravity.CENTER);
        TextView plus = Ui.text(this, "+", 30f, Ui.GREEN, "semibold");
        plus.setGravity(Gravity.CENTER);
        t.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        t.addView(Ui.text(this, count >= PinStore.MAX ? "Full" : "Pin a page", 15f, Ui.DIM, "sans-serif-medium"), Ui.lp(this, 10, 0));
        t.setMinimumHeight(Ui.dp(this, 118));
        t.setClickable(true);
        t.setOnClickListener(v -> {
            if (count >= PinStore.MAX) {
                Toast.makeText(this, "You can pin up to " + PinStore.MAX + " pages. Long-press one to remove it.", Toast.LENGTH_LONG).show();
            } else {
                editPin(null);
            }
        });
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
        items.add("Add an icon to the home screen");
        items.add("Remove");
        new AlertDialog.Builder(this)
                .setTitle(pin.name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 0) {
                        editPin(pin);
                    } else if (which == 1) {
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
        name.setHint("Name (Lecture, Standup, ...)");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText url = new EditText(this);
        url.setHint("Address (meet.example.com/room)");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        CheckBox auto = new CheckBox(this);
        auto.setText("Start the recording buffer when it opens");
        if (existing != null) {
            name.setText(existing.name);
            url.setText(existing.url);
            auto.setChecked(existing.autoRecord);
        }
        form.addView(name);
        form.addView(url);
        form.addView(auto);
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
                url.setError("Type a web address, like meet.example.com/room");
                return;
            }
            if (label.isEmpty()) label = Pin.create("", address).host();
            Pin pin = existing != null ? existing : Pin.create(label, address);
            pin.name = label;
            pin.url = address;
            pin.autoRecord = auto.isChecked();
            if (!PinStore.put(this, pin)) {
                Toast.makeText(this, "You can pin up to " + PinStore.MAX + " pages.", Toast.LENGTH_LONG).show();
                return;
            }
            if (existing == null || !existing.url.equals(address)) {
                Favicons.delete(this, pin.id);
                iconTried.remove(pin.id);
            }
            Shortcuts.refresh(this);
            d.dismiss();
            render();
        }));
        d.show();
    }
}
