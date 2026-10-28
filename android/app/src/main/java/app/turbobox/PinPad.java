package app.turbobox;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A number pad drawn by the app, in the middle of the screen, so a code is entered without the phone's keyboard (which
 * pushes everything up) and without reaching to the bottom edge. The code is 4 to 8 digits; OK confirms it.
 */
final class PinPad {
    static final int MIN = 4;
    static final int MAX = 8;

    private PinPad() {
    }

    /** Asks for the code; {@code check} says whether it is right, and {@code onOk} runs when it is. */
    static void ask(Activity act, String title, Predicate<String> check, Runnable onOk) {
        build(act, title, "Enter the code", code -> {
            if (check.test(code)) return null;
            return "Wrong code";
        }, onOk == null ? null : code -> onOk.run());
    }

    /** Asks for a new code twice and hands it over; the caller stores it. */
    static void create(Activity act, String title, Consumer<String> onNew) {
        build(act, title, "Choose a code of " + MIN + " to " + MAX + " digits", first -> {
            // Second pass, in a new pad: the first code is carried in the closure.
            build(act, title, "Enter it again", second -> first.equals(second) ? null : "They do not match", code -> onNew.accept(code));
            return null;
        }, null);
    }

    private interface Check {
        /** Null when the code is accepted, otherwise what to tell the person. */
        String accept(String code);
    }

    private static void build(Activity act, String title, String hint, Check check, Consumer<String> after) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(act, 20);
        col.setPadding(pad, pad, pad, pad);
        col.setBackground(Ui.rounded(act, 0xFF121217, Ui.STROKE, 24));
        Dialog d = new Dialog(act);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        col.addView(Ui.text(act, title, 19f, Ui.TEXT, "semibold"));
        TextView sub = Ui.hint(act, hint);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, Ui.lp(act, 4, 12));
        TextView dots = Ui.text(act, "", 30f, Ui.GREEN, "sans-serif-medium");
        dots.setGravity(Gravity.CENTER);
        dots.setMinHeight(Ui.dp(act, 44));
        col.addView(dots, Ui.lp(act, 0, 8));

        StringBuilder code = new StringBuilder();
        Runnable paint = () -> {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < code.length(); i++) s.append("● ");
            dots.setText(s.toString().trim());
        };

        GridLayout grid = new GridLayout(act);
        grid.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        for (String k : keys) {
            TextView b = Ui.text(act, k, 22f, Ui.TEXT, "sans-serif-medium");
            b.setGravity(Gravity.CENTER);
            boolean action = k.length() > 1 || k.equals("⌫");
            b.setBackground(Ui.rounded(act, k.equals("OK") ? Ui.withAlpha(Ui.GREEN, 0.30f) : Ui.withAlpha(Color.WHITE, action ? 0.05f : 0.10f), 0, 100));
            b.setClickable(true);
            // The key you press lights up (lime while the finger is down) and then eases back, so you can see what registered.
            final int idle = k.equals("OK") ? Ui.withAlpha(Ui.GREEN, 0.30f) : Ui.withAlpha(Color.WHITE, action ? 0.05f : 0.10f);
            b.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    b.setBackground(Ui.rounded(act, Ui.GREEN, 0, 100));
                    b.setTextColor(Ui.BG);
                } else if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP || ev.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                    b.postDelayed(() -> {
                        b.setBackground(Ui.rounded(act, idle, 0, 100));
                        b.setTextColor(Ui.TEXT);
                    }, 120);
                }
                return false;
            });
            b.setOnClickListener(v -> {
                if (k.equals("⌫")) {
                    if (code.length() > 0) code.setLength(code.length() - 1);
                } else if (k.equals("OK")) {
                    if (code.length() < MIN) {
                        sub.setText("At least " + MIN + " digits");
                        sub.setTextColor(Ui.AMBER);
                        return;
                    }
                    String err = check.accept(code.toString());
                    if (err != null) {
                        code.setLength(0);
                        sub.setText(err);
                        sub.setTextColor(Ui.RED);
                    } else {
                        d.dismiss();
                        if (after != null) after.accept(code.toString());
                    }
                } else if (code.length() < MAX) {
                    code.append(k);
                }
                paint.run();
            });
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = Ui.dp(act, 72);
            p.height = Ui.dp(act, 58);
            p.setMargins(Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 6), Ui.dp(act, 6));
            grid.addView(b, p);
        }
        col.addView(grid);
        TextView cancel = Ui.text(act, "Cancel", 15f, Ui.DIM, "sans-serif-medium");
        cancel.setPadding(Ui.dp(act, 20), Ui.dp(act, 12), Ui.dp(act, 20), Ui.dp(act, 4));
        cancel.setOnClickListener(v -> d.dismiss());
        col.addView(cancel);

        d.setContentView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.CENTER);
            w.setDimAmount(0.6f);
        }
        d.show(); Dimmer.apply(act, d.getWindow());
    }
}
