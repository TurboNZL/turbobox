package app.turbobox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** The saved split layouts of two to four pages: apply one (it opens its pages and arranges them), edit it, delete it. */
public class LayoutsActivity extends Activity {
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        setContentView(Ui.screen(this, "Layouts", body));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        body.removeAllViews();
        body.addView(Ui.hint(this, "A layout puts two, three or four of your pinned pages on the screen together, each where you drew it. "
                + "Apply opens the pages that are not running yet and splits the screen the way the layout says."));
        TextView add = Ui.pill(this, "New layout", Ui.GREEN);
        add.setOnClickListener(v -> edit(SavedLayout.create(), true));
        body.addView(add, Ui.lp(this, 12, 8));
        List<SavedLayout> list = LayoutStore.load(this);
        if (list.isEmpty()) body.addView(Ui.hint(this, "No layouts yet."), Ui.lp(this, 12, 0));
        for (SavedLayout l : list) body.addView(card(l), Ui.lp(this, 10, 0));
    }

    private String pageText(SavedLayout l) {
        List<String> names = new ArrayList<>();
        for (String id : l.pinIds) {
            Pin p = PinStore.find(this, id);
            if (p != null) names.add(p.name);
        }
        return names.isEmpty() ? l.count + " pages, whichever are open" : String.join(", ", names);
    }

    private LinearLayout card(SavedLayout l) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, l.name.isEmpty() ? "(no name)" : l.name, 17f, Ui.TEXT, "sans-serif-medium"));
        c.addView(Ui.text(this, pageText(l), 13f, Ui.DIM, "sans-serif"), Ui.lp(this, 3, 0));
        LinearLayout row = new LinearLayout(this);
        TextView apply = Ui.pill(this, "Apply", Ui.GREEN);
        apply.setOnClickListener(v -> startActivity(new Intent(this, SessionActivity.class)
                .setAction(SessionActivity.ACTION_APPLY_LAYOUT).putExtra(SessionActivity.EXTRA_LAYOUT, l.id)));
        row.addView(apply, Ui.weight(this, 0));
        TextView edit = Ui.pill(this, "Edit", 0);
        edit.setOnClickListener(v -> edit(l, false));
        row.addView(edit, Ui.weight(this, 8));
        TextView del = Ui.pill(this, "Delete", Ui.RED);
        del.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Delete " + (l.name.isEmpty() ? "this layout" : l.name) + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    LayoutStore.remove(this, l.id);
                    render();
                }).setNegativeButton("Keep", null).show());
        row.addView(del, Ui.weight(this, 8));
        c.addView(row, Ui.lp(this, 10, 0));
        return c;
    }

    /** Full-screen editor: name, how many pages, which pages, and where each goes. */
    private void edit(SavedLayout l, boolean isNew) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 16));
        col.addView(Ui.text(this, isNew ? "New layout" : "Edit layout", 20f, Ui.TEXT, "semibold"), Ui.lp(this, 0, 8));
        EditText name = new EditText(this);
        name.setHint("Name");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(l.name);
        name.setTextColor(Ui.TEXT);
        col.addView(name);

        // Rectangles for all four slots; only the first l.count are used.
        float[][] all = MiniLayout.splitDefaults();
        float[][] mine = l.rectangles();
        for (int i = 0; i < mine.length; i++) all[i] = mine[i];
        float[][][] current = {all};

        LinearLayout counts = new LinearLayout(this);
        TextView[] countButtons = new TextView[SavedLayout.MAX_PAGES + 1];
        FrameLayout holder = new FrameLayout(this);
        TextView pages = Ui.pill(this, "", 0);
        Runnable[] place = new Runnable[1];

        place[0] = () -> {
            for (int n = SavedLayout.MIN_PAGES; n <= SavedLayout.MAX_PAGES; n++) Ui.tint(this, countButtons[n], n == l.count ? Ui.GREEN : 0);
            pages.setText(pageButtonText(l));
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxW = dm.widthPixels - Ui.dp(this, 32);
            int maxH = Math.round(dm.heightPixels * 0.5f);
            int w = Math.min(maxW, Math.round(maxH * dm.widthPixels / (dm.heightPixels * 0.8f)));
            int h = Math.round(w * dm.heightPixels * 0.8f / dm.widthPixels);
            holder.removeAllViews();
            MiniLayoutEditor ed = new MiniLayoutEditor(this, rects -> current[0] = rects);
            ed.set(current[0], l.count);
            holder.addView(ed, new FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER));
        };

        col.addView(Ui.label(this, "Pages"), Ui.lp(this, 12, 4));
        for (int n = SavedLayout.MIN_PAGES; n <= SavedLayout.MAX_PAGES; n++) {
            final int count = n;
            TextView b = Ui.pill(this, n + " pages", 0);
            b.setOnClickListener(v -> {
                if (count == l.count) return;
                float[][] preset = SavedLayout.presets(count);
                float[][] next = MiniLayout.splitDefaults();
                for (int i = 0; i < preset.length; i++) next[i] = preset[i];
                current[0] = next;   // a different number of pages starts from that number's arrangement
                l.count = count;
                while (l.pinIds.size() > count) l.pinIds.remove(l.pinIds.size() - 1);
                place[0].run();
            });
            countButtons[n] = b;
            counts.addView(b, Ui.weight(this, n == SavedLayout.MIN_PAGES ? 0 : 8));
        }
        col.addView(counts);

        pages.setOnClickListener(v -> {
            List<Pin> pins = new ArrayList<>();
            for (Pin p : PinStore.load(this)) if (!Profiles.isLocked(this, p.profileId)) pins.add(p);
            if (pins.isEmpty()) {
                Toast.makeText(this, "Pin a page on the home screen first.", Toast.LENGTH_LONG).show();
                return;
            }
            PinGrid.pick(this, "Pages for this layout", pins, new LinkedHashSet<>(l.pinIds), true, chosen -> {
                l.pinIds.clear();
                for (Pin p : pins) if (chosen.contains(p.id) && l.pinIds.size() < l.count) l.pinIds.add(p.id);   // the home screen's order
                place[0].run();
            });
        });
        col.addView(pages, Ui.lp(this, 10, 0));
        col.addView(Ui.hint(this, "Page 1 is the first one chosen (home screen order). With none chosen the layout uses whichever pages are open. "
                + "Drag a page to move it, drag its corner to resize it; page 4 is in front."), Ui.lp(this, 8, 8));
        col.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView save = Ui.pill(this, "Save", Ui.GREEN);
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) {
                name.setError("Give it a name");
                return;
            }
            l.name = n;
            float[][] used = new float[l.count][];
            System.arraycopy(current[0], 0, used, 0, l.count);
            l.rects = MiniLayout.encode(used);
            LayoutStore.put(this, l);
            d.dismiss();
            render();
        });
        col.addView(save, Ui.lp(this, 8, 0));
        TextView cancel = Ui.pill(this, "Cancel", 0);
        cancel.setOnClickListener(v -> d.dismiss());
        col.addView(cancel, Ui.lp(this, 8, 0));
        d.setContentView(col, new ViewGroup.LayoutParams(-1, -1));
        d.show();
        place[0].run();
    }

    private String pageButtonText(SavedLayout l) {
        return pageText(l) + "  ›";
    }
}
