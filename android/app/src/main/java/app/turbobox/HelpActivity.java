package app.turbobox;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The guides (docs/guides/*.md, bundled into the app): a list, then one guide as lightly formatted text. */
public class HelpActivity extends Activity {
    private static final String DIR = "guides";

    private LinearLayout body;
    private boolean showingOne;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        showList();
    }

    @Override
    public void onBackPressed() {
        if (showingOne) showList(); else super.onBackPressed();
    }

    private void showList() {
        showingOne = false;
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        setContentView(Ui.screen(this, "Guides", body));
        try {
            String[] files = getAssets().list(DIR);
            if (files == null) files = new String[0];
            Arrays.sort(files);
            for (String f : files) {
                if (!f.endsWith(".md")) continue;
                String text = read(f);
                LinearLayout row = Ui.card(this);
                row.addView(Ui.text(this, titleOf(text, f), 17f, Ui.TEXT, "sans-serif-medium"));
                row.setClickable(true);
                row.setOnClickListener(v -> showOne(titleOf(text, f), text));
                body.addView(row, Ui.lp(this, 0, 10));
            }
        } catch (IOException e) {
            body.addView(Ui.hint(this, "The guides could not be read."));
        }
    }

    private void showOne(String title, String markdown) {
        showingOne = true;
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        setContentView(Ui.screen(this, title, body));
        TextView t = Ui.text(this, render(markdown), 15f, Ui.TEXT, "sans-serif");
        t.setLineSpacing(0f, 1.18f);
        t.setTextIsSelectable(true);
        body.addView(t);
    }

    private String read(String file) throws IOException {
        try (InputStream in = getAssets().open(DIR + "/" + file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    static String titleOf(String markdown, String fallback) {
        for (String line : markdown.split("\n")) {
            if (line.startsWith("# ")) return line.substring(2).trim();
        }
        return fallback;
    }

    /** Headings, bold, inline code and code blocks; links show their text. Enough for guides, no more. */
    static CharSequence render(String md) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean code = false;
        List<String> lines = new ArrayList<>(Arrays.asList(md.split("\n")));
        for (String raw : lines) {
            if (raw.startsWith("```")) {
                code = !code;
                continue;
            }
            int start = out.length();
            if (code) {
                out.append(raw).append('\n');
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(Ui.DIM), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            String line = raw.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
            int level = 0;
            while (level < line.length() && line.charAt(level) == '#') level++;
            if (level > 0 && level < line.length() && line.charAt(level) == ' ') {
                out.append(line.substring(level + 1)).append('\n');
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(level == 1 ? 1.35f : level == 2 ? 1.2f : 1.05f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(Ui.GREEN), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) line = "•  " + line.substring(2);
            out.append(line).append('\n');
            spanPairs(out, start, "**");
            spanPairs(out, start, "`");
        }
        return out;
    }

    /** Removes paired markers in the text added since {@code from} and styles what was between them. */
    private static void spanPairs(SpannableStringBuilder sb, int from, String marker) {
        int a = sb.toString().indexOf(marker, from);
        while (a >= 0) {
            int b = sb.toString().indexOf(marker, a + marker.length());
            if (b < 0) return;
            sb.delete(b, b + marker.length());
            sb.delete(a, a + marker.length());
            Object span = marker.equals("**") ? new StyleSpan(Typeface.BOLD) : new TypefaceSpan("monospace");
            sb.setSpan(span, a, b - marker.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            a = sb.toString().indexOf(marker, b - marker.length());
        }
    }
}
