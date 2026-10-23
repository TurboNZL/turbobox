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
        Profiles.returning();
        String filter = Profiles.filter(this);
        if (!filter.isEmpty() && Profiles.find(this, filter).id.equals(Profiles.DEFAULT) && !filter.equals(Profiles.DEFAULT)) filter = Profiles.DEFAULT;   // its profile was removed
        Profiles.setFilter(this, filter);
        render();
        Shortcuts.refresh(this);
        WorkflowScheduler.scheduleAll(this);   // keeps the alarms right even after an update
        SessionService.ensure(this);           // standby, if it is on
    }

    @Override
    protected void onPause() {
        super.onPause();
        Profiles.leaving();
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

        renderProfiles();
        renderRunning();
        renderPins();
        renderShortcuts();
    }

    /** A row of profile chips: tap to show that profile's pages, All to see every page. */
    private void renderProfiles() {
        List<Profiles.Profile> all = Profiles.load(this);
        String filter = Profiles.filter(this);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        if (all.size() > 1) row.addView(profileChip("All", filter.isEmpty(), null));
        for (Profiles.Profile p : all) {
            if (all.size() == 1) break;   // with only the default, there is nothing to choose between
            row.addView(profileChip(p.name, p.id.equals(filter), p));
        }
        TextView add = Ui.pill(this, all.size() == 1 ? "Profiles: add one" : "+", 0);
        add.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        add.setOnClickListener(v -> nameProfile(null));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.leftMargin = Ui.dp(this, 6);
        row.addView(add, ap);
        scroll.addView(row);
        body.addView(scroll, Ui.lp(this, 14, 0));
        if (all.size() > 1) {
            body.addView(Ui.hint(this, "Each profile has its own pinned pages and sign-ins; the app's settings are the same for all. Long-press a profile to rename or remove it."), Ui.lp(this, 6, 0));
        }
    }

    private TextView profileChip(String label, boolean selected, Profiles.Profile profile) {
        boolean locked = profile != null && Profiles.isLocked(this, profile.id);
        TextView chip = Ui.pill(this, (profile != null && profile.hasPassword() ? (locked ? "🔒 " : "🔓 ") : "") + label, selected ? Ui.GREEN : 0);
        chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        chip.setOnClickListener(v -> {
            if (profile != null && Profiles.isLocked(this, profile.id)) {
                askPassword(profile, () -> {
                    Profiles.setFilter(this, profile.id);
                    Shortcuts.refresh(this);
                    render();
                });
                return;
            }
            Profiles.setFilter(this, profile == null ? "" : profile.id);
            render();
        });
        if (profile != null) {
            chip.setOnLongClickListener(v -> {
                profileMenu(profile);
                return true;
            });
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = Ui.dp(this, 6);
        chip.setLayoutParams(p);
        return chip;
    }

    private void profileMenu(Profiles.Profile profile) {
        List<String> items = new ArrayList<>();
        items.add("Rename");
        items.add(profile.hasPassword() ? "Change or remove the password" : "Set a password");
        if (profile.hasPassword() && !Profiles.isLocked(this, profile.id)) items.add("Lock now");
        if (!profile.id.equals(Profiles.DEFAULT)) items.add("Remove, with its pages and sign-ins");
        new AlertDialog.Builder(this).setTitle(profile.name)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String chosen = items.get(which);
                    if (chosen.equals("Rename")) {
                        nameProfile(profile);
                    } else if (chosen.equals("Set a password") || chosen.startsWith("Change")) {
                        if (profile.hasPassword()) askPassword(profile, () -> newPassword(profile)); else newPassword(profile);
                    } else if (chosen.equals("Lock now")) {
                        Profiles.lock(profile.id);
                        Shortcuts.refresh(this);
                        render();
                    } else {
                        new AlertDialog.Builder(this).setTitle("Remove " + profile.name + "?")
                                .setMessage("Its pinned pages, signed-in sessions and settings are deleted.")
                                .setPositiveButton("Remove", (dd, w) -> {
                                    Profiles.remove(this, profile.id);
                                    Shortcuts.refresh(this);
                                    render();
                                })
                                .setNegativeButton("Keep", null).show();
                    }
                }).show();
    }

    /** Asks for the profile's password and runs {@code then} when it is right. */
    private void askPassword(Profiles.Profile profile, Runnable then) {
        EditText pw = new EditText(this);
        pw.setHint("Password");
        pw.setSingleLine(true);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Unlock " + profile.name).setView(pw)
                .setPositiveButton("Unlock", null).setNegativeButton("Cancel", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (Profiles.unlock(this, profile.id, pw.getText().toString())) {
                d.dismiss();
                then.run();
            } else {
                pw.setError("Wrong password");
            }
        }));
        d.show();
    }

    /** A new password for a profile, typed twice; leaving both empty removes the password. */
    private void newPassword(Profiles.Profile profile) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        form.setPadding(p, Ui.dp(this, 8), p, 0);
        EditText one = new EditText(this);
        one.setHint("New password (empty removes it)");
        EditText two = new EditText(this);
        two.setHint("Again");
        for (EditText e : new EditText[]{one, two}) {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            form.addView(e);
        }
        form.addView(Ui.hint(this, "It only hides this profile's pages here; it cannot be recovered if you forget it (removing the profile is the way out)."), Ui.lp(this, 6, 0));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Password for " + profile.name).setView(form)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!one.getText().toString().equals(two.getText().toString())) {
                two.setError("They do not match");
                return;
            }
            Profiles.setPassword(this, profile.id, one.getText().toString());
            Shortcuts.refresh(this);
            d.dismiss();
            render();
        }));
        d.show();
    }

    private void nameProfile(Profiles.Profile existing) {
        EditText name = new EditText(this);
        name.setHint("Name (Work, Family, ...)");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (existing != null) name.setText(existing.name);
        new AlertDialog.Builder(this).setTitle(existing == null ? "New profile" : "Rename profile").setView(name)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    if (existing == null) {
                        Profiles.setFilter(this, Profiles.add(this, n).id);
                    } else {
                        Profiles.rename(this, existing.id, n);
                    }
                    render();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void renderRunning() {
        List<Session> active = new ArrayList<>();
        for (Session s : Sessions.active()) if (!Profiles.isLocked(this, s.profileId())) active.add(s);
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
        String filter = Profiles.filter(this);
        List<Pin> pins = new ArrayList<>();
        int hidden = 0;
        for (Pin p : PinStore.load(this)) {
            if (!(filter.isEmpty() || p.profileId.equals(filter))) continue;
            if (Profiles.isLocked(this, p.profileId)) hidden++; else pins.add(p);
        }
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

        if (!filter.isEmpty() && Profiles.isLocked(this, filter)) {
            LinearLayout locked = Ui.card(this);
            locked.addView(Ui.text(this, "🔒 This profile is locked", 16f, Ui.TEXT, "sans-serif-medium"));
            TextView unlock = Ui.pill(this, "Unlock", Ui.GREEN);
            unlock.setOnClickListener(v -> askPassword(Profiles.find(this, filter), () -> {
                Shortcuts.refresh(this);
                render();
            }));
            locked.addView(unlock, Ui.lp(this, 10, 0));
            body.addView(locked);
            return;
        }
        List<View> tiles = new ArrayList<>();
        for (int i = 0; i < pins.size(); i++) tiles.add(pinTile(pins.get(i), i, pins.size()));
        tiles.add(addTile());
        tiles.add(quickTile());
        body.addView(PinGrid.grid(this, tiles));
        if (hidden > 0) {
            body.addView(Ui.hint(this, hidden + (hidden == 1 ? " page is" : " pages are") + " hidden in locked profiles. Tap the profile to unlock it."), Ui.lp(this, 2, 0));
        }
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
        PinStore.moveSavedAmong(this, pin.id, delta, Profiles.filter(this));
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

    private View quickTile() {
        LinearLayout t = Ui.card(this);
        t.setGravity(Gravity.CENTER);
        TextView icon = Ui.text(this, "◐", 26f, Ui.TEXT, "semibold");
        icon.setGravity(Gravity.CENTER);
        t.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        t.addView(Ui.text(this, "Open a page", 15f, Ui.DIM, "sans-serif-medium"), Ui.lp(this, 10, 0));
        t.addView(Ui.text(this, "not pinned", 12f, Ui.FAINT, "sans-serif"), Ui.lp(this, 2, 0));
        t.setMinimumHeight(Ui.dp(this, 118));
        t.setClickable(true);
        t.setOnClickListener(v -> quickPage());
        return t;
    }

    /** A page that is not pinned, by default incognito: its own cookies and storage, no recording, gone when it closes. */
    private void quickPage() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        form.setPadding(p, Ui.dp(this, 8), p, 0);
        EditText url = new EditText(this);
        url.setHint("Address (example.com/page)");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        android.widget.Switch incognito = new android.widget.Switch(this);
        incognito.setText("Incognito (own cookies, apart from your pinned pages)");
        incognito.setChecked(true);
        android.widget.Switch wipe = new android.widget.Switch(this);
        wipe.setText("Delete them when the page closes");
        wipe.setChecked(true);
        incognito.setOnCheckedChangeListener((b, on) -> wipe.setEnabled(on));
        android.widget.Switch mobile = new android.widget.Switch(this);
        mobile.setText("Mobile site");
        form.addView(url);
        form.addView(incognito, Ui.lp(this, 12, 0));
        form.addView(wipe, Ui.lp(this, 8, 0));
        form.addView(Ui.hint(this, "Incognito keeps this page's sign-ins away from your pinned pages. With deleting on it also leaves no history and is not recorded. "
                + "Turn deleting off to stay signed in to that site next time (it keeps its own cookies for it). Without Incognito the page shares cookies with everything else."), Ui.lp(this, 2, 0));
        form.addView(mobile, Ui.lp(this, 10, 0));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Open a page")
                .setView(form)
                .setPositiveButton("Open", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = Pin.normalizeUrl(url.getText().toString());
            if (address == null) {
                url.setError("Type a web address, like example.com/page");
                return;
            }
            d.dismiss();
            startActivity(new Intent(this, SessionActivity.class).setAction(SessionActivity.ACTION_OPEN_URL)
                    .putExtra(SessionActivity.EXTRA_URL, address).putExtra(SessionActivity.EXTRA_INCOGNITO, incognito.isChecked())
                    .putExtra(SessionActivity.EXTRA_WIPE, wipe.isChecked())
                    .putExtra(SessionActivity.EXTRA_PROFILE, Profiles.filter(this).isEmpty() ? Profiles.DEFAULT : Profiles.filter(this))
                    .putExtra(SessionActivity.EXTRA_MOBILE, mobile.isChecked()));
        }));
        d.show();
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
        final String[] profile = {existing != null ? existing.profileId : Profiles.filter(this).isEmpty() ? Profiles.DEFAULT : Profiles.filter(this)};
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
        List<Profiles.Profile> profiles = Profiles.load(this);
        if (profiles.size() > 1) {
            TextView which = Ui.pill(this, "Profile: " + Profiles.find(this, profile[0]).name, 0);
            which.setOnClickListener(v -> {
                String[] names = new String[profiles.size()];
                for (int i = 0; i < names.length; i++) names[i] = profiles.get(i).name;
                new AlertDialog.Builder(this).setTitle("Profile").setItems(names, (dd, i) -> {
                    profile[0] = profiles.get(i).id;
                    which.setText("Profile: " + profiles.get(i).name);
                }).show();
            });
            form.addView(which, Ui.lp(this, 8, 0));
        }
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
            pin.profileId = profile[0];
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
