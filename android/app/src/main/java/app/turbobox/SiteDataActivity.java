package app.turbobox;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Every site that holds cookies or storage in TurboBox's browser, each with a button to clear it. */
public class SiteDataActivity extends Activity {
    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        setContentView(Ui.screen(this, "Site data", body));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private String storeName(String store) {
        if (store.isEmpty()) return "Default profile";
        if (store.startsWith("tb_p_")) {
            for (Profiles.Profile p : Profiles.load(this)) if (Profiles.webProfileName(p.id) != null && store.equals(Profiles.webProfileName(p.id))) return p.name;
            return "A profile";
        }
        if (store.startsWith("tb_site_")) return "Kept from an unpinned page";
        return store;
    }

    private void render() {
        body.removeAllViews();
        body.addView(Ui.hint(this, "Sign-ins and what sites keep in this app's browser. Android cannot list them itself, so this is every "
                + "site a page has been on since this list began, plus your pinned pages. Clearing a site signs you out of it; "
                + "sign-ins shared with a parent domain (like youtube.com) sign out its other pages too. Reload a page that is open."),
                Ui.lp(this, 6, 8));
        List<SiteData.Site> sites = SiteData.list(this);
        if (sites.isEmpty()) {
            body.addView(Ui.hint(this, "Nothing yet. Sites appear here once a page has opened them."), Ui.lp(this, 8, 0));
            return;
        }
        for (SiteData.Site s : sites) {
            LinearLayout row = Ui.card(this);
            row.addView(Ui.text(this, s.host, 16f, Ui.TEXT, "sans-serif-medium"));
            row.addView(Ui.hint(this, storeName(s.store) + " - " + (s.cookies == 0 ? "no cookies" : s.cookies == 1 ? "1 cookie" : s.cookies + " cookies")
                    + ", plus any stored page data"), Ui.lp(this, 2, 6));
            TextView clear = Ui.pill(this, "Clear this site's data", Ui.RED);
            clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Clear " + s.host + "?")
                    .setMessage("Its cookies and stored data are removed from this app, so you will be signed out of it.")
                    .setPositiveButton("Clear", (d, w) -> {
                        SiteData.delete(this, s);
                        Toast.makeText(this, s.host + " cleared", Toast.LENGTH_SHORT).show();
                        render();
                    }).setNegativeButton("Cancel", null).show());
            row.addView(clear);
            body.addView(row, Ui.lp(this, 8, 0));
        }
        TextView all = Ui.pill(this, "Clear everything", Ui.RED);
        all.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Clear all site data?")
                .setMessage("Every cookie and all stored page data in every profile are removed. You will be signed out of everything.")
                .setPositiveButton("Clear all", (d, w) -> {
                    SiteData.deleteAll(this);
                    Toast.makeText(this, "All site data cleared", Toast.LENGTH_SHORT).show();
                    render();
                }).setNegativeButton("Cancel", null).show());
        body.addView(all, Ui.lp(this, 16, 8));
    }
}
