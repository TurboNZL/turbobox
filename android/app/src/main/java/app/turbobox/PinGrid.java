package app.turbobox;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The pinned pages as a grid of tiles. The home screen draws it, and so does the workflow editor's page picker, so a
 * page looks the same wherever you choose it.
 */
final class PinGrid {
    private static final Set<String> TRIED = new HashSet<>();   // icons looked up once per start, not on every redraw

    private PinGrid() {
    }

    /** Lets the next draw look for this page's icon again (its address changed). */
    static void forget(String pinId) {
        TRIED.remove(pinId);
    }

    /**
     * One tile: the page's icon (or its first letter), its name and a line under it.
     *
     * @param onIcon called when an icon was fetched in the background, so the caller can redraw
     */
    static LinearLayout tile(Context c, Pin pin, String subtitle, int subtitleColor, boolean selected, Runnable onIcon) {
        LinearLayout t = Ui.card(c);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        paintSelected(c, t, selected);
        Bitmap icon = Favicons.load(c, pin.id);
        if (icon != null) {
            // The page's own icon, on a light disc so dark icons stay visible on the dark card.
            ImageView img = new ImageView(c);
            img.setImageBitmap(icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Ui.dp(c, 8);
            img.setPadding(pad, pad, pad, pad);
            img.setBackground(Ui.rounded(c, 0xFFF2F2F5, 0, 100));
            t.addView(img, new LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)));
        } else {
            TextView badge = Ui.text(c, pin.initial(), 22f, Ui.BG, "semibold");
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Ui.rounded(c, Ui.GREEN, 0, 100));
            t.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, 46)));
            if (onIcon != null && TRIED.add(pin.id)) Favicons.fetch(c, pin, onIcon);
        }
        TextView name = Ui.text(c, pin.name, 16f, Ui.TEXT, "sans-serif-medium");
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(name, Ui.lp(c, 10, 0));
        TextView sub = Ui.text(c, subtitle, 12f, subtitleColor, "sans-serif");
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(sub, Ui.lp(c, 2, 0));
        return t;
    }

    static void paintSelected(Context c, View tile, boolean selected) {
        tile.setBackground(selected ? Ui.rounded(c, Ui.withAlpha(Ui.GREEN, 0.16f), Ui.GREEN, 16) : Ui.rounded(c, Ui.CARD, Ui.STROKE, 16));
    }

    /** Lays the tiles out two to a row, the last one keeping half width when it is alone. */
    static LinearLayout grid(Context c, List<View> tiles) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(c);
                col.addView(row, Ui.lp(c, 0, 10));
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.leftMargin = i % 2 == 0 ? 0 : Ui.dp(c, 10);
            row.addView(tiles.get(i), p);
        }
        if (row != null && tiles.size() % 2 == 1) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        return col;
    }

    interface OnDone {
        void done(Set<String> chosen);
    }

    /**
     * A sheet of the pinned pages, laid out like the home screen. Tapping a tile picks it; with {@code multi} several can be
     * picked (a workflow that runs on more than one page) and Done ends it, otherwise the first tap ends it.
     */
    static Dialog pick(Activity act, String title, List<Pin> allPins, Set<String> already, boolean multi, OnDone onDone) {
        LinearLayout col = new LinearLayout(act);
        Dialog d = Ui.sheet(act, col);
        final Set<String> chosen = new LinkedHashSet<>(already);
        // The same filter as the home screen: starts on the profile it is showing, and a locked profile's pages are not listed.
        final String[] filter = {Profiles.filter(act)};
        List<Profiles.Profile> profiles = Profiles.load(act);

        col.addView(Ui.text(act, title, 20f, Ui.TEXT, "semibold"));
        col.addView(Ui.hint(act, multi ? "Tap every page this should run on." : "Tap the page."), Ui.lp(act, 4, 12));
        LinearLayout chips = new LinearLayout(act);
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(act);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        if (profiles.size() > 1) col.addView(chipScroll, Ui.lp(act, 0, 12));
        LinearLayout holder = new LinearLayout(act);
        holder.setOrientation(LinearLayout.VERTICAL);
        col.addView(holder);

        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            chips.removeAllViews();
            chips.addView(chip(act, "All", filter[0].isEmpty(), () -> {
                filter[0] = "";
                render[0].run();
            }));
            for (Profiles.Profile p : profiles) {
                boolean locked = Profiles.isLocked(act, p.id);
                chips.addView(chip(act, (p.hasPassword() ? (locked ? "\uD83D\uDD12 " : "\uD83D\uDD13 ") : "") + p.name, p.id.equals(filter[0]), () -> {
                    if (Profiles.isLocked(act, p.id)) {
                        PinPad.ask(act, "Unlock " + p.name, code -> Profiles.unlock(act, p.id, code), () -> {
                            filter[0] = p.id;
                            render[0].run();
                        });
                        return;
                    }
                    filter[0] = p.id;
                    render[0].run();
                }));
            }
            holder.removeAllViews();
            List<View> tiles = new java.util.ArrayList<>();
            int hidden = 0;
            for (Pin pin : allPins) {
                if (!(filter[0].isEmpty() || pin.profileId.equals(filter[0]))) continue;
                if (Profiles.isLocked(act, pin.profileId)) {
                    hidden++;
                    continue;
                }
                LinearLayout t = tile(act, pin, pin.host(), Ui.DIM, chosen.contains(pin.id), null);
                t.setClickable(true);
                t.setOnClickListener(v -> {
                    if (!multi) {
                        d.dismiss();
                        Set<String> one = new LinkedHashSet<>();
                        one.add(pin.id);
                        onDone.done(one);
                        return;
                    }
                    if (!chosen.remove(pin.id)) chosen.add(pin.id);
                    paintSelected(act, t, chosen.contains(pin.id));
                });
                tiles.add(t);
            }
            holder.addView(grid(act, tiles));
            if (hidden > 0) holder.addView(Ui.hint(act, hidden + (hidden == 1 ? " page is" : " pages are") + " hidden in locked profiles."), Ui.lp(act, 0, 8));
        };
        render[0].run();
        if (multi) {
            TextView done = Ui.pill(act, "Done", Ui.GREEN);
            done.setOnClickListener(v -> {
                d.dismiss();
                // A locked profile's pages are not shown, so a choice made earlier for them is kept as it was.
                onDone.done(chosen);
            });
            col.addView(done, Ui.lp(act, 6, 0));
        }
        d.show();
        return d;
    }

    private static TextView chip(Activity act, String label, boolean selected, Runnable onTap) {
        TextView c = Ui.pill(act, label, selected ? Ui.GREEN : 0);
        c.setPadding(Ui.dp(act, 14), Ui.dp(act, 7), Ui.dp(act, 14), Ui.dp(act, 7));
        c.setOnClickListener(v -> onTap.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = Ui.dp(act, 6);
        c.setLayoutParams(p);
        return c;
    }
}
