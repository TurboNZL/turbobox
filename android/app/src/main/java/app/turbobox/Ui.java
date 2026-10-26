package app.turbobox;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Shared sizes, colours and small view builders. The app is built from plain Views, like Tether. */
final class Ui {
    static final int BG = 0xFF0B0B0E;          // engine neutrals
    static final int CARD = 0xFF16161C;
    static final int STROKE = 0xFF26262F;
    static final int TEXT = 0xFFECECF1;
    static final int DIM = 0xFF9A9AA8;
    static final int FAINT = 0xFF6C6C7A;

    static final int GREEN = 0xFFA6E22E;       // TurboBox's own colour (the emblem): a lime, so it never reads as another app
    static final int AMBER = 0xFFFBBF24;
    static final int RED = 0xFFE10600;         // the engine red

    private Ui() {
    }

    /** A switch that can be seen on the dark background: lime when on, a light grey track when off. */
    static android.widget.Switch toggle(Context c) {
        android.widget.Switch sw = new android.widget.Switch(c);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setTrackTintList(new ColorStateList(states, new int[]{withAlpha(GREEN, 0.55f), 0xFF6A6A78}));
        sw.setThumbTintList(new ColorStateList(states, new int[]{GREEN, 0xFFD6D6DE}));
        sw.setTextColor(TEXT);
        return sw;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static float dpf(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** The system UI font at a given weight (400 regular, 500 medium, 600 semibold). */
    static Typeface font(int weight) {
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.DEFAULT, weight, false);
        return weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }

    /** {@code family}: "monospace", "sans-serif-medium", "semibold"; anything else is regular. */
    static TextView text(Context c, CharSequence s, float sp, int color, String family) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if ("monospace".equals(family)) {
            t.setTypeface(Typeface.MONOSPACE);
        } else if ("semibold".equals(family)) {
            t.setTypeface(font(600));
        } else if ("sans-serif-medium".equals(family)) {
            t.setTypeface(font(500));
        } else {
            t.setTypeface(font(400));
        }
        t.setIncludeFontPadding(false);
        return t;
    }

    static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    static GradientDrawable rounded(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dpf(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    /** A rounded panel with padding; children are added vertically. */
    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rounded(c, CARD, STROKE, 16));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** A tappable pill: filled with the tint at low strength. */
    static TextView pill(Context c, String label, int tint) {
        TextView t = text(c, label, 14f, TEXT, "sans-serif-medium");
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 6), dp(c, 11), dp(c, 6), dp(c, 11));
        t.setClickable(true);
        tint(c, t, tint);
        return t;
    }

    static void tint(Context c, TextView t, int tint) {
        boolean plain = tint == 0;
        t.setBackground(rounded(c, plain ? withAlpha(Color.WHITE, 0.10f) : withAlpha(tint, 0.30f),
                plain ? STROKE : withAlpha(tint, 0.55f), 100));
    }

    static LinearLayout.LayoutParams match(int heightPx) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
    }

    static LinearLayout.LayoutParams wrapWidth(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topDp);
        return p;
    }

    static LinearLayout.LayoutParams weight(Context c, int leftDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.leftMargin = dp(c, leftDp);
        return p;
    }

    /** Page title bar with a Back pill: used by every screen but the session. */
    static LinearLayout screen(Activity a, String title, LinearLayout body) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int pad = dp(a, 16);
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(pad, dp(a, 14), pad, dp(a, 6));
        TextView back = text(a, "‹ Back", 15f, GREEN, "sans-serif-medium");
        back.setPadding(0, dp(a, 6), dp(a, 14), dp(a, 6));
        back.setOnClickListener(v -> a.finish());
        head.addView(back);
        head.addView(text(a, title, 20f, TEXT, "semibold"));
        root.addView(head, match(ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        body.setPadding(pad, dp(a, 8), pad, dp(a, 28));
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /**
     * A dark sheet that rises from the bottom and scrolls: {@code col} is its content (the caller fills it, then calls
     * {@code show()}). Same look as the session's settings.
     */
    static android.app.Dialog sheet(Activity act, LinearLayout col) {
        android.app.Dialog d = new android.app.Dialog(act);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        int pad = dp(act, 18);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(act, 18), pad, dp(act, 22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF121217);
        float r = dpf(act, 24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(dp(act, 1), STROKE);
        col.setBackground(bg);
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(scroll);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.45f);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return d;
    }

    static View spacer(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dp)));
        return v;
    }

    interface OnValue {
        void onValue(float value, boolean fromUser);
    }

    /** A slider from 0 to {@code max} (1.0 = 100%), in 1% steps, tinted with the app colour. */
    static SeekBar slider(Context c, float max, float value, OnValue l) {
        SeekBar bar = new SeekBar(c);
        final int steps = Math.round(max * 100f);
        bar.setMax(steps);
        bar.setProgress(Math.round(Math.max(0f, Math.min(max, value)) * 100f));
        bar.setProgressTintList(ColorStateList.valueOf(GREEN));
        bar.setThumbTintList(ColorStateList.valueOf(GREEN));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                l.onValue(progress / 100f, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
        return bar;
    }

    /** "7:30 AM" or "07:30", the way the phone is set to show the time. */
    static String formatTime(Context c, int hour, int minute) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
        cal.set(java.util.Calendar.MINUTE, minute);
        return android.text.format.DateFormat.getTimeFormat(c).format(cal.getTime());
    }

    static String pct(float value) {
        return String.format(java.util.Locale.US, "%d%%", Math.round(value * 100f));
    }

    /** A small dim explanatory line. */
    static TextView hint(Context c, String s) {
        TextView t = text(c, s, 13f, FAINT, "sans-serif");
        t.setLineSpacing(0f, 1.1f);
        return t;
    }

    static TextView label(Context c, String s) {
        return text(c, s, 14f, DIM, "sans-serif-medium");
    }

    static LinearLayout.LayoutParams lp(Context c, int topDp, int bottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topDp);
        p.bottomMargin = dp(c, bottomDp);
        return p;
    }
}
