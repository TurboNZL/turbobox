package app.turbobox;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A light look at what a page is recording right now: the newest picture the recorder has and the words being captioned. It does not touch the
 * page, the recording or the page screen (nothing is resized, frozen or moved), and while it is open the run notes that you were watching, so a
 * cut of the recording can drop that stretch. Open it from the home screen's Running list, the Recordings list or the notification.
 */
public class LiveActivity extends Activity {
    static final String EXTRA_SLOT = "slot";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ImageView picture;
    private TextView caption;
    private TextView status;
    private BufferRecorder watched;
    private int slot = -1;
    private Bitmap shown;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        slot = getIntent().getIntExtra(EXTRA_SLOT, -1);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        status = Ui.text(this, "", 14f, Ui.DIM, "sans-serif");
        body.addView(status, Ui.lp(this, 0, 8));
        picture = new ImageView(this);
        picture.setAdjustViewBounds(true);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.setBackgroundColor(0xFF000000);
        body.addView(picture, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 360)));
        caption = Ui.text(this, "", 16f, Ui.TEXT, "sans-serif");
        caption.setGravity(Gravity.START);
        body.addView(caption, Ui.lp(this, 12, 0));
        body.addView(Ui.hint(this, "This is the recording's own picture, a moment behind. While this screen is open the recording notes that you were "
                + "watching, so that stretch can be cut out later."), Ui.lp(this, 12, 0));
        setContentView(Ui.screen(this, "Watching live", body));
    }

    private Session target() {
        if (slot >= 0) {
            Session s = Sessions.get(slot);
            if (s.isActive() && s.isRecording()) return s;
        }
        for (Session s : Sessions.active()) if (s.isRecording()) return s;
        return null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(tick);
        endWatching();
    }

    private void endWatching() {
        BufferRecorder r = watched;
        watched = null;
        if (r != null) r.watchEnded();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Session s = target();
            BufferRecorder r = s == null ? null : s.recorder();
            if (r != watched) {
                endWatching();
                if (r != null) {
                    watched = r;
                    r.watchStarted();
                }
            }
            if (r == null) {
                status.setText("Nothing is recording right now.");
            } else {
                status.setText(s.statusLine());
                Bitmap snap = r.snapshot();
                if (snap != null) {
                    picture.setImageBitmap(snap);
                    Bitmap old = shown;
                    shown = snap;
                    if (old != null) old.recycle();
                }
                String c = r.liveCaption();
                caption.setText(c.isEmpty() ? (r.isTranscribing() ? "(listening...)" : "(captions are off)") : c);
            }
            ui.postDelayed(this, 600);
        }
    };
}
