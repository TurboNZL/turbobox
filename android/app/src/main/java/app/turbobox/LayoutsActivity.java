package app.turbobox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Saved layouts of two to four pages, and every setting about how pages are arranged. A layout is made live on the pages
 * themselves (Layout > Arrange on the session screen) and is not tied to any page; here you use, rename, arrange again or delete it.
 */
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
        body.addView(Ui.hint(this, "A layout is where two, three or four pages sit on the screen. You make it on the pages themselves: open them, "
                + "turn Split on and choose Layout > Arrange. Drag each page's frame, bring one in front of another, then Save as layout. "
                + "A layout is not tied to any page; it is used on whichever pages are in the split, and only when you choose it."));
        renderSettings();

        body.addView(Ui.label(this, "Make one"), Ui.lp(this, 18, 8));
        LinearLayout make = new LinearLayout(this);
        TextView full = Ui.pill(this, "Arrange the split", Ui.GREEN);
        full.setOnClickListener(v -> arrange(false, null, false));
        make.addView(full, Ui.weight(this, 0));
        TextView mini = Ui.pill(this, "Arrange the Mini window", 0);
        mini.setOnClickListener(v -> arrange(true, null, false));
        make.addView(mini, Ui.weight(this, 8));
        body.addView(make);

        body.addView(Ui.label(this, "Saved layouts"), Ui.lp(this, 18, 8));
        List<SavedLayout> list = LayoutStore.load(this);
        if (list.isEmpty()) body.addView(Ui.hint(this, "No layouts yet."), Ui.lp(this, 4, 0));
        for (SavedLayout l : list) body.addView(card(l), Ui.lp(this, 10, 0));
    }

    /** Opens the session screen with the pages that are running; the layout is made or changed there, on the live pages. */
    private void arrange(boolean mini, SavedLayout l, boolean edit) {
        int open = Sessions.active().size();
        if (open < (l != null ? l.count : 2)) {
            Toast.makeText(this, "Open " + (l != null ? l.count : 2) + " pages first (tap them on the home screen).", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, SessionActivity.class).setAction(SessionActivity.ACTION_APPLY_LAYOUT);
        if (l != null) i.putExtra(SessionActivity.EXTRA_LAYOUT, l.id).putExtra(SessionActivity.EXTRA_EDIT, edit);
        else i.putExtra(SessionActivity.EXTRA_ARRANGE, true).putExtra(SessionActivity.EXTRA_MINI, mini);
        startActivity(i);
    }

    /** How a new split and the Mini window start. Nothing here (or anywhere) applies a layout by itself. */
    private void renderSettings() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "How things start", 17f, Ui.TEXT, "sans-serif-medium"));
        String[] splitChoices = {"The layout last used with this many pages", "Stacked", "Grid (left to right, then down)"};
        card.addView(choiceRow("A new split starts as", splitChoices[Math.max(0, Math.min(2, Prefs.splitDefault(this)))], () ->
                new DimBuilder(this).setTitle("A new split starts as")
                        .setSingleChoiceItems(splitChoices, Prefs.splitDefault(this), (d, which) -> {
                            Prefs.setSplitDefault(this, which);
                            d.dismiss();
                            render();
                        }).show()));
        card.addView(Ui.hint(this, "\"Last used\" is the arrangement you finished with Arrange or applied last for that number of pages; "
                + "until there is one, it is the grid."), Ui.lp(this, 0, 4));
        Switch reclaim = Ui.toggle(this);
        reclaim.setText("Let other pages use the empty space");
        reclaim.setTextColor(Ui.TEXT);
        reclaim.setChecked(Prefs.reclaimEmpty(this));
        reclaim.setOnCheckedChangeListener((b, on) -> Prefs.setReclaimEmpty(this, on));
        card.addView(reclaim, Ui.lp(this, 8, 2));
        card.addView(Ui.hint(this, "In the stacked and grid starts: a page held to a shape (a forced aspect ratio, or a video in full screen) takes "
                + "only the room that shape needs and the other pages grow into the rest, instead of black bars."), Ui.lp(this, 0, 4));
        body.addView(card, Ui.lp(this, 12, 0));
    }

    private LinearLayout choiceRow(String label, String value, Runnable onTap) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        r.addView(Ui.text(this, label, 16f, Ui.TEXT, "sans-serif-medium"));
        r.addView(Ui.text(this, value + "  ›", 15f, Ui.GREEN, "sans-serif-medium"), Ui.lp(this, 2, 0));
        r.setClickable(true);
        r.setOnClickListener(v -> onTap.run());
        return r;
    }

    static String pipLayoutText(int v) {
        return v == 1 ? "Side by side" : v == 2 ? "Stacked" : v == 3 ? "The Mini layout last used with this many pages" : "Like the split view";
    }

    private LinearLayout card(SavedLayout l) {
        LinearLayout c = Ui.card(this);
        TextView name = Ui.text(this, l.name.isEmpty() ? "(no name)" : l.name, 17f, Ui.TEXT, "sans-serif-medium");
        name.setOnClickListener(v -> rename(l));
        c.addView(name);
        c.addView(Ui.text(this, l.count + " pages  -  " + (l.mini ? "Mini window" : "full screen split") + "  -  tap the name to rename",
                13f, Ui.DIM, "sans-serif"), Ui.lp(this, 3, 0));
        LinearLayout row = new LinearLayout(this);
        TextView use = Ui.pill(this, "Use", Ui.GREEN);
        use.setOnClickListener(v -> arrange(l.mini, l, false));
        row.addView(use, Ui.weight(this, 0));
        TextView edit = Ui.pill(this, "Edit live", 0);
        edit.setOnClickListener(v -> arrange(l.mini, l, true));
        row.addView(edit, Ui.weight(this, 8));
        TextView del = Ui.pill(this, "Delete", Ui.RED);
        del.setOnClickListener(v -> new DimBuilder(this).setTitle("Delete " + (l.name.isEmpty() ? "this layout" : l.name) + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    LayoutStore.remove(this, l.id);
                    render();
                }).setNegativeButton("Keep", null).show());
        row.addView(del, Ui.weight(this, 8));
        c.addView(row, Ui.lp(this, 10, 0));
        return c;
    }

    private void rename(SavedLayout l) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(l.name);
        new DimBuilder(this).setTitle("Rename layout").setView(name)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    l.name = n;
                    LayoutStore.put(this, l);
                    render();
                }).setNegativeButton("Cancel", null).show();
    }
}
