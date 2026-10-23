package app.turbobox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/**
 * The pocket lock's face: black, with two dots. Touch and hold both dots at the same time for a moment to unlock. A
 * knock, a bump or fabric pressing on the glass does not hit two exact spots and stay there, and two fingers that mean
 * to do it manage it at once, so nothing else on the screen can be triggered while it is in a pocket.
 */
final class PocketLockView extends View {
    static final long HOLD_MS = 1500;

    interface OnUnlock {
        void unlocked();
    }

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OnUnlock onUnlock;
    private final float radius;
    private final boolean[] held = new boolean[2];
    private long heldSince;   // when both dots were first down together, 0 = not both
    private boolean done;

    PocketLockView(Context c, OnUnlock onUnlock) {
        super(c);
        this.onUnlock = onUnlock;
        radius = Ui.dpf(c, 44);
        setBackgroundColor(0xFF000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dpf(c, 3));
        ring.setColor(0xFF3A3A44);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.withAlpha(Ui.GREEN, 0.55f));
        text.setColor(0xFF4A4A56);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Ui.dpf(c, 14));
    }

    private float cx(int dot) {
        return getWidth() * (dot == 0 ? 0.25f : 0.75f);
    }

    private float cy() {
        return getHeight() * 0.68f;
    }

    /** Which dots have a finger on them, from the fingers now down (the one just lifted, if any, excluded). */
    private void read(MotionEvent e) {
        held[0] = held[1] = false;
        int skip = e.getActionMasked() == MotionEvent.ACTION_POINTER_UP || e.getActionMasked() == MotionEvent.ACTION_UP ? e.getActionIndex() : -1;
        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) return;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            for (int d = 0; d < 2; d++) {
                float dx = e.getX(i) - cx(d), dy = e.getY(i) - cy();
                if (dx * dx + dy * dy <= radius * radius * 1.6f) held[d] = true;
            }
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (heldSince == 0 || done) return;
            if (System.currentTimeMillis() - heldSince >= HOLD_MS) {
                done = true;
                invalidate();
                onUnlock.unlocked();
                return;
            }
            invalidate();
            handler.postDelayed(this, 40);
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        read(e);
        boolean both = held[0] && held[1];
        if (both && heldSince == 0) {
            heldSince = System.currentTimeMillis();
            handler.post(tick);
        } else if (!both) {
            heldSince = 0;
            handler.removeCallbacks(tick);
        }
        invalidate();
        return true;   // everything stops here
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float progress = heldSince == 0 ? 0f : Math.min(1f, (System.currentTimeMillis() - heldSince) / (float) HOLD_MS);
        for (int d = 0; d < 2; d++) {
            c.drawCircle(cx(d), cy(), radius, ring);
            if (held[d]) {
                c.drawCircle(cx(d), cy(), radius * (0.35f + 0.65f * progress), fill);
            }
        }
        c.drawText("Locked", getWidth() / 2f, getHeight() * 0.42f, text);
        c.drawText("Touch and hold both circles to unlock", getWidth() / 2f, getHeight() * 0.42f + Ui.dpf(getContext(), 24), text);
    }
}
