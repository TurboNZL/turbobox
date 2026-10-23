package app.turbobox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.PixelCopy;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Shows the running pages: one at a time (tabs A and B) or both on screen at once (Split). The pages, the timers
 * and the recording buffers live in {@link Session}, so leaving this screen (or locking the phone) does not stop
 * them; only Close, the timer, a workflow or the notification end a page. The bar acts on the page that is in front.
 */
public class SessionActivity extends Activity implements Session.Listener {
    static final String ACTION_OPEN_PIN = "app.turbobox.OPEN_PIN";
    static final String EXTRA_PIN = "pin";
    static final String EXTRA_SLOT = "slot";
    private static final int[] TIMER_MINUTES = {0, 15, 30, 45, 60, 90, 120, 180, 240, 360, 480};

    /** Set while the pocket lock is on. Static so it outlives this screen (home, screen off, a new instance of the activity). */
    private static boolean pocketLocked;
    private static final String ACTION_PIP_MUTE = "app.turbobox.PIP_MUTE";

    private final FrameLayout[] panes = new FrameLayout[Sessions.COUNT];
    private final AspectFrame[] boxes = new AspectFrame[Sessions.COUNT];
    private final TextView[] tabs = new TextView[Sessions.COUNT];
    private LinearLayout root;
    private LinearLayout chromeBox;
    private Session fullscreenSession;
    private Dialog lockDialog;
    private PopupWindow floatButton;
    private boolean pipReceiverOn;
    private LinearLayout paneHolder;
    private TextView splitButton;
    private TextView muteButton;
    private TextView recButton;
    private TextView ccButton;
    private TextView awayButton;
    private TextView countdown;
    private LevelMeterView strip;
    private View dim;
    private int focus;
    private boolean split;
    private final java.util.Set<Integer> splitSet = new java.util.TreeSet<>();   // the slots shown together while Split is on (up to four)
    private boolean inPip;
    private SessionSheet sheet;
    private final PopupWindow[] freezePopups = new PopupWindow[Sessions.COUNT];
    private final Bitmap[] frozenPictures = new Bitmap[Sessions.COUNT];
    private final View[] fullscreenViews = new View[Sessions.COUNT];
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        buildUi();
        for (Session s : Sessions.all()) s.addListener(this);
        applyBrightness();
        if (!handleIntent(getIntent())) finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleIntent(intent)) finish();
    }

    /** Opens the pin or focuses the slot the intent names. False when there is nothing to show. */
    private boolean handleIntent(Intent intent) {
        String pinId = intent.getStringExtra(EXTRA_PIN);
        if (ACTION_OPEN_PIN.equals(intent.getAction()) && pinId != null) {
            Pin pin = PinStore.find(this, pinId);
            if (pin != null) {
                openPin(pin);
                return !Sessions.active().isEmpty();
            }
        }
        if (intent.hasExtra(EXTRA_SLOT)) focus = Math.max(0, Math.min(Sessions.COUNT - 1, intent.getIntExtra(EXTRA_SLOT, 0)));
        if (Sessions.active().isEmpty()) return false;
        if (!Sessions.get(focus).isActive()) focus = Sessions.active().get(0).slot;
        rebuild();
        return true;
    }

    /** The pin's own slot if it is already running, else a free one; when both are busy, ask which to replace. */
    private void openPin(Pin pin) {
        Session running = Sessions.forPin(pin.id);
        if (running != null) {
            focus = running.slot;
            rebuild();
            return;
        }
        int free = Sessions.firstFreeSlot();
        if (free >= 0) {
            start(free, pin);
            return;
        }
        String[] names = new String[Sessions.PERMANENT];
        for (int i = 0; i < names.length; i++) names[i] = "Replace " + Sessions.tag(Sessions.get(i)) + Sessions.get(i).title();
        new AlertDialog.Builder(this)
                .setTitle("Both pages are in use")
                .setItems(names, (d, which) -> {
                    Sessions.get(which).close("replaced by " + pin.name);
                    start(which, pin);
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    if (Sessions.active().isEmpty()) finish();
                })
                .setOnCancelListener(d -> {
                    if (Sessions.active().isEmpty()) finish();
                })
                .show();
    }

    private void start(int slot, Pin pin) {
        Sessions.get(slot).open(this, pin);
        focus = slot;
        rebuild();
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        // A page that was hosted off screen (recording with the screen off) comes back into its pane here.
        for (Session s : Sessions.active()) s.setFrameWindow(getWindow());
        rebuild();
        Session f = front();
        if (f != null && !inPip) f.setCallMeter(true);
        updateControls();
        registerPipReceiver();
        if (Sessions.active().isEmpty()) pocketLocked = false;
        if (pocketLocked) showLock();
        getWindow().getDecorView().post(this::applyBarMode);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // After the screen was off or locked a page recorded off screen is put back into its pane.
        if (!Sessions.active().isEmpty()) rebuild();
        // Back from the replay of what was missed: the live page is shown and heard again.
        for (Session s : Sessions.active()) {
            if (s.catchingUp) {
                s.catchingUp = false;
                s.setFrozen(false);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        keepPagesRunning();
    }

    @Override
    protected void onStop() {
        super.onStop();
        for (Session s : Sessions.all()) {
            s.setFrameWindow(null);
            s.setCallMeter(false); // the level strip is only useful while it can be seen
        }
        if (sheet != null) sheet.dismiss();
        dismissFloat();
        unregisterPipReceiver();
        keepPagesRunning();
    }

    /** Leaving the screen (home, another app, screen off) must never pause a page. */
    private void keepPagesRunning() {
        for (Session s : Sessions.active()) s.keepRunning();
    }

    @Override
    protected void onDestroy() {
        // The pages and the timers outlive this screen; only the view of them goes away.
        for (int i = 0; i < freezePopups.length; i++) dismissFreeze(i);
        dismissFloat();
        dismissLock();
        for (Session s : Sessions.all()) {
            s.removeListener(this);
            s.exitFullscreen();
            WebView wv = s.peekWebView();
            // Only take the page out of one of our own panes: it may now be on the private display that keeps it alive.
            if (wv != null && wv.getParent() == boxes[s.slot]) boxes[s.slot].removeView(wv);
            s.detach();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (pocketLocked) return;   // locked: nothing gets through
        if (fullscreenSession != null) {
            fullscreenSession.exitFullscreen();
            return;
        }
        // Never navigate the page back mid-call (that could unload it); just leave. The pages keep running and
        // the notification brings you back.
        moveTaskToBack(true);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        rebuild();
        getWindow().getDecorView().post(this::applyBarMode);   // the floating button belongs in the new shape of the screen
    }

    // ---- picture in picture ------------------------------------------------------------------

    private boolean canPip() {
        return Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** The pages the Mini window shows: only while Split is on is it more than the page in front. */
    private List<Session> pipPages() {
        if (!split) {
            List<Session> one = new ArrayList<>();
            if (front() != null) one.add(front());
            return one;
        }
        return members();
    }

    private PictureInPictureParams.Builder pipParams() {
        List<Session> pages = pipPages();
        boolean wide = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int[] shape = SplitLayout.shape(SplitLayout.pipRows(pages.size(), Prefs.pipLayout(this), wide));
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder().setAspectRatio(new Rational(shape[0], shape[1]));
        // The mic button works for any page: it is the same mute as the notification's (for a page that is not using the
        // microphone it simply keeps it from being sent when one is asked for).
        if (!Sessions.active().isEmpty()) {
            boolean muted = Sessions.allMuted();
            Icon icon = Icon.createWithResource(this, muted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            PendingIntent pi = PendingIntent.getBroadcast(this, 7, new Intent(ACTION_PIP_MUTE).setPackage(getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            List<RemoteAction> actions = new ArrayList<>();
            actions.add(new RemoteAction(icon, muted ? "Unmute" : "Mute", muted ? "Turn the microphone back on" : "Mute the microphone", pi));
            b.setActions(actions);
        }
        // From Android 12 the window shrinks by itself when you go Home with a page open.
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(!Sessions.active().isEmpty() && !pocketLocked);
        return b;
    }

    private final BroadcastReceiver pipMute = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Sessions.toggleAllMuted();
        }
    };

    private void registerPipReceiver() {
        if (pipReceiverOn || !canPip()) return;
        IntentFilter f = new IntentFilter(ACTION_PIP_MUTE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipMute, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(pipMute, f);
        pipReceiverOn = true;
    }

    private void unregisterPipReceiver() {
        if (!pipReceiverOn) return;
        pipReceiverOn = false;
        try {
            unregisterReceiver(pipMute);
        } catch (IllegalArgumentException ignored) {
            // Already gone.
        }
    }

    private void updatePip() {
        if (!canPip()) return;
        try {
            setPictureInPictureParams(pipParams().build());
        } catch (RuntimeException ignored) {
            // Not allowed right now; the Home gesture still works through onUserLeaveHint.
        }
    }

    /** Shrinks the page in front into a small floating window, which also keeps Android from unloading the app. */
    private void enterPip() {
        if (!canPip() || isInPictureInPictureMode() || Sessions.active().isEmpty() || pocketLocked) return;
        if (sheet != null) sheet.dismiss();
        try {
            enterPictureInPictureMode(pipParams().build());
        } catch (RuntimeException e) {
            Toast.makeText(this, "Picture in picture is not available here.", Toast.LENGTH_SHORT).show();
        }
    }

    /** Which layout the Mini window should have, asked from the Mini button's long press. */
    private void choosePipLayout() {
        String[] items = {"Like the split view", "Side by side", "Stacked", "One page floating over the other"};
        new AlertDialog.Builder(this).setTitle("Mini window while Split is on")
                .setSingleChoiceItems(items, Prefs.pipLayout(this), (d, which) -> {
                    Prefs.setPipLayout(this, which);
                    d.dismiss();
                    updatePip();
                    Toast.makeText(this, "Used when the Mini window opens while Split is on.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < 31) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean in, Configuration config) {
        super.onPictureInPictureModeChanged(in, config);
        inPip = in;
        chromeBox.setVisibility(in ? View.GONE : View.VISIBLE);
        rebuild();
        getWindow().getDecorView().post(this::applyBarMode);
    }

    // ---- UI ----------------------------------------------------------------------------------

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        chromeBox = new LinearLayout(this);
        chromeBox.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 10);
        int gap = 6;

        // Tabs for the open pages, then Split
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        tabRow.setPadding(side, Ui.dp(this, 8), side, 0);
        for (int i = 0; i < Sessions.COUNT; i++) {
            final int slot = i;
            TextView t = Ui.pill(this, "", 0);
            t.setOnClickListener(v -> tabTapped(slot));
            tabs[i] = t;
            tabRow.addView(t, Ui.weight(this, i == 0 ? 0 : gap));
        }
        splitButton = Ui.pill(this, "Split", 0);
        splitButton.setOnClickListener(v -> toggleSplit());
        tabRow.addView(splitButton, Ui.weight(this, gap));
        chromeBox.addView(tabRow, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        // Close, Mute, Timer, Settings
        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setPadding(side, Ui.dp(this, 6), side, 0);
        TextView close = Ui.pill(this, "Close", Ui.RED);
        close.setOnClickListener(v -> {
            Session s = front();
            if (s != null) s.close("you closed the page");
        });
        row1.addView(close, Ui.weight(this, 0));
        muteButton = Ui.pill(this, "Mute", 0);
        muteButton.setOnClickListener(v -> {
            Session s = front();
            if (s != null) s.setMuted(!s.isMuted());
        });
        row1.addView(muteButton, Ui.weight(this, gap));
        TextView timer = Ui.pill(this, "Timer", 0);
        timer.setOnClickListener(v -> showTimerDialog());
        row1.addView(timer, Ui.weight(this, gap));
        TextView settings = Ui.pill(this, "Settings", 0);
        settings.setOnClickListener(v -> showSettings());
        row1.addView(settings, Ui.weight(this, gap));
        chromeBox.addView(row1, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        // Step away / Back, Rec, CC
        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(side, Ui.dp(this, 6), side, 0);
        awayButton = Ui.pill(this, "Step away", Ui.GREEN);
        awayButton.setOnClickListener(v -> stepAwayOrBack());
        row2.addView(awayButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        recButton = Ui.pill(this, "Rec", 0);
        recButton.setOnClickListener(v -> recordingMenu());
        row2.addView(recButton, Ui.weight(this, gap));
        ccButton = Ui.pill(this, "CC", 0);
        ccButton.setOnClickListener(v -> toggleCaptions());
        row2.addView(ccButton, Ui.weight(this, gap));
        chromeBox.addView(row2, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        // The countdown, with Mini (picture in picture) and Lock (pocket protection) beside it
        LinearLayout row3 = new LinearLayout(this);
        row3.setGravity(Gravity.CENTER_VERTICAL);
        row3.setPadding(side, Ui.dp(this, 6), side, Ui.dp(this, 4));
        countdown = Ui.text(this, "No timer", 13f, Ui.DIM, "sans-serif-medium");
        countdown.setGravity(Gravity.CENTER_VERTICAL);
        row3.addView(countdown, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (canPip()) {
            TextView pip = Ui.pill(this, "Mini", 0);
            pip.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
            pip.setOnClickListener(v -> enterPip());
            pip.setOnLongClickListener(v -> {
                choosePipLayout();
                return true;
            });
            row3.addView(pip);
        }
        TextView hide = Ui.pill(this, "Hide bar", 0);
        hide.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        hide.setOnClickListener(v -> {
            Prefs.setBarHidden(this, true);
            applyBarMode();
        });
        LinearLayout.LayoutParams hideParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hideParams.leftMargin = Ui.dp(this, gap);
        row3.addView(hide, hideParams);
        TextView lock = Ui.pill(this, "Lock", 0);
        lock.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        lock.setOnClickListener(v -> startPocketLock());
        LinearLayout.LayoutParams lockParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lockParams.leftMargin = Ui.dp(this, gap);
        row3.addView(lock, lockParams);
        chromeBox.addView(row3, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        strip = new LevelMeterView(this);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, Ui.dp(this, 5));
        ml.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 6));
        chromeBox.addView(strip, ml);

        root.addView(chromeBox, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        paneHolder = new LinearLayout(this);
        for (int i = 0; i < Sessions.COUNT; i++) {
            panes[i] = new FrameLayout(this);
            boxes[i] = new AspectFrame(this);
            panes[i].addView(boxes[i], new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(paneHolder, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Brightness veil over everything, the same trick as Tether's main screen.
        FrameLayout frame = new FrameLayout(this);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setClickable(false);
        dim.setFocusable(false);
        frame.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
    }

    private void applyBrightness() {
        dim.setAlpha((1f - Prefs.brightness(this)) * 0.9f);
    }

    /** The page the bar acts on, or null when none is open. */
    private Session front() {
        Session s = Sessions.get(focus);
        return s.isActive() ? s : null;
    }

    /** The pages in the split view, in slot order (at least the page in front). */
    private List<Session> members() {
        List<Session> out = new ArrayList<>();
        for (Session s : Sessions.active()) if (splitSet.contains(s.slot)) out.add(s);
        if (out.isEmpty() && front() != null) out.add(front());
        return out;
    }

    private void toggleSplit() {
        split = !split;
        if (split) {
            splitSet.clear();
            for (Session s : Sessions.active()) if (splitSet.size() < 4) splitSet.add(s.slot);
            Toast.makeText(this, "Tap tabs to choose which pages share the screen. Tap the highlighted tab again to take it out.", Toast.LENGTH_LONG).show();
        }
        rebuild();
    }

    /** A tab was tapped: the page in front, or (with Split on) in or out of the split. */
    private void tabTapped(int slot) {
        if (!split) {
            focus = slot;
        } else if (!splitSet.contains(slot)) {
            if (splitSet.size() >= 4) {
                Toast.makeText(this, "Four pages is the most that share the screen.", Toast.LENGTH_SHORT).show();
                return;
            }
            splitSet.add(slot);
            focus = slot;
        } else if (focus != slot) {
            focus = slot;
        } else if (splitSet.size() > 1) {
            splitSet.remove(slot);
            focus = splitSet.iterator().next();
        }
        rebuild();
    }

    /** Puts the pages in their panes and brings every control up to date. */
    private void rebuild() {
        List<Session> active = Sessions.active();
        if (active.isEmpty()) return;
        if (!Sessions.get(focus).isActive()) focus = active.get(0).slot;
        if (active.size() < 2) split = false;
        if (split) {
            java.util.Iterator<Integer> it = splitSet.iterator();
            while (it.hasNext()) if (!Sessions.get(it.next()).isActive()) it.remove();
            if (!splitSet.contains(focus)) {
                if (splitSet.size() >= 4) splitSet.remove(splitSet.iterator().next());
                splitSet.add(focus);
            }
        }

        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        List<Session> shown = new ArrayList<>(inPip ? pipPages() : split ? members() : new ArrayList<Session>());
        if (shown.isEmpty() && front() != null) shown.add(front());
        boolean together = split && shown.size() >= 2;
        boolean overlay = inPip && together && Prefs.pipLayout(this) == SplitLayout.OVERLAY;
        int[] rows = !together || overlay ? new int[]{1}
                : inPip ? SplitLayout.pipRows(shown.size(), Prefs.pipLayout(this), landscape)
                : SplitLayout.rows(shown.size(), landscape);

        Session base = null, inset = null;
        List<Session> placed = shown;
        if (overlay) {
            base = front() != null && shown.contains(front()) ? front() : shown.get(0);
            inset = shown.get(0) == base ? shown.get(1) : shown.get(0);
            placed = new ArrayList<>();
            placed.add(base);
            placed.add(inset);
        }
        for (int i = 0; i < Sessions.COUNT; i++) {
            Session s = Sessions.get(i);
            WebView wv = s.peekWebView();
            if (s.isActive() && !placed.contains(s) && s.isHosted()) {
                s.attach(this);   // already on its private display; leave it there
            } else if (s.isActive()) {
                wv = s.webView(this);
                s.attach(this);
                if (wv.getParent() != boxes[i]) {
                    if (wv.getParent() instanceof ViewGroup) ((ViewGroup) wv.getParent()).removeView(wv);
                    boxes[i].addView(wv, new FrameLayout.LayoutParams(-1, -1));
                }
                boxes[i].setRatio(Pin.ASPECTS[s.aspect()]);
            } else if (wv != null && wv.getParent() == boxes[i]) {
                boxes[i].removeView(wv);
            }
            if (panes[i].getParent() != null) ((ViewGroup) panes[i].getParent()).removeView(panes[i]);
        }
        paneHolder.removeAllViews();
        paneHolder.setOrientation(LinearLayout.VERTICAL);
        if (overlay) {
            FrameLayout stack = new FrameLayout(this);
            stack.addView(panes[base.slot], new FrameLayout.LayoutParams(-1, -1));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 64), Gravity.BOTTOM | Gravity.END);
            lp.setMargins(0, 0, Ui.dp(this, 6), Ui.dp(this, 6));
            stack.addView(panes[inset.slot], lp);
            paneHolder.addView(stack, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            int next = 0;
            for (int rowSize : rows) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < rowSize && next < shown.size(); c++, next++) {
                    row.addView(panes[shown.get(next).slot], new LinearLayout.LayoutParams(0, -1, 1f));
                }
                paneHolder.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
            }
        }
        // A page that is open but not on screen (another tab is in front) keeps running on a private display; one that
        // simply left the screen would be treated as hidden, and a video would pause.
        for (int i = 0; i < Sessions.COUNT; i++) {
            Session s = Sessions.get(i);
            if (!s.isActive()) continue;
            if (panes[i].getParent() != null) {
                s.setFrameWindow(getWindow());                      // leaves the private display if it was on one
            } else {
                s.setFrameWindowQuietly(getWindow());
                s.hostWithoutScreen();
            }
            if (i != focus) s.setCallMeter(false);
        }
        Session f = front();
        if (f != null && !inPip) f.setCallMeter(true);
        updatePip();
        updateControls();
        updateFreeze();
    }

    private void updateControls() {
        Session f = front();
        for (int i = 0; i < Sessions.COUNT; i++) {
            Session s = Sessions.get(i);
            tabs[i].setVisibility(s.isActive() ? View.VISIBLE : View.GONE);
            if (!s.isActive()) continue;
            tabs[i].setText(s.label());
            tabs[i].setSingleLine(true);
            tabs[i].setEllipsize(android.text.TextUtils.TruncateAt.END);
            Ui.tint(this, tabs[i], i == focus ? Ui.GREEN : split && splitSet.contains(i) ? 0xFF6B8F1F : 0);
        }
        splitButton.setVisibility(Sessions.active().size() > 1 ? View.VISIBLE : View.GONE);
        Ui.tint(this, splitButton, split ? Ui.GREEN : 0);
        if (f == null) return;
        muteButton.setText(f.isMuted() ? "Unmute" : "Mute");
        Ui.tint(this, muteButton, f.isMuted() ? Ui.RED : 0); // red while muted, so the state is obvious at a glance
        onTimer(f, f.getRemainingMs());
        recButton.setText(f.isRecording() ? (f.isTranscribing() ? "● Rec CC" : "● Rec") : "Rec");
        Ui.tint(this, recButton, f.isRecording() ? Ui.RED : 0);
        ccButton.setText(f.isTranscribing() ? "CC on" : "CC");
        Ui.tint(this, ccButton, f.isTranscribing() ? Ui.GREEN : 0);
        awayButton.setText(f.awayAtWall != 0 ? "Back - play what I missed" : "Step away");
    }

    // ---- the options bar, retracted into a button ---------------------------------------------

    /**
     * The bar across the top can be put away with its Hide bar button: the page then gets the whole screen and a small round
     * button you can drag stays over it; tapping that brings the bar back. The button is a window of its own, like the freeze
     * cover, so a recording of the page never shows it.
     */
    private void applyBarMode() {
        dismissFloat();
        boolean hidden = Prefs.barHidden(this) && !inPip && !isFinishing();
        chromeBox.setVisibility(inPip || hidden ? View.GONE : View.VISIBLE);
        if (!hidden) return;
        View decor = getWindow().getDecorView();
        if (decor.getWidth() < 8) {
            decor.post(this::applyBarMode);
            return;
        }
        int size = Ui.dp(this, 44);
        TextView button = Ui.text(this, "▼", 18f, Ui.BG, "semibold");
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("Show the options bar");
        button.setBackground(Ui.rounded(this, Ui.withAlpha(Ui.GREEN, 0.92f), 0, 100));
        floatButton = new PopupWindow(button, size, size, false);
        floatButton.setClippingEnabled(false);
        final int maxX = Math.max(0, decor.getWidth() - size), maxY = Math.max(0, decor.getHeight() - size);
        final int[] pos = {Math.round(Prefs.floatX(this) * maxX), Math.round(Prefs.floatY(this) * maxY)};
        final float[] down = new float[4];
        final boolean[] dragged = {false};
        button.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    down[2] = pos[0];
                    down[3] = pos[1];
                    dragged[0] = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!dragged[0] && Math.hypot(dx, dy) < Ui.dp(this, 8)) return true;
                    dragged[0] = true;
                    pos[0] = Math.max(0, Math.min(maxX, Math.round(down[2] + dx)));
                    pos[1] = Math.max(0, Math.min(maxY, Math.round(down[3] + dy)));
                    floatButton.update(pos[0], pos[1], -1, -1);
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                    if (dragged[0]) {
                        Prefs.setFloatPos(this, maxX == 0 ? 0f : pos[0] / (float) maxX, maxY == 0 ? 0f : pos[1] / (float) maxY);
                    } else {
                        Prefs.setBarHidden(this, false);
                        applyBarMode();
                    }
                    return true;
                default:
                    return true;
            }
        });
        try {
            floatButton.showAtLocation(decor, Gravity.TOP | Gravity.START, pos[0], pos[1]);
        } catch (RuntimeException e) {
            floatButton = null;   // the window is going away
        }
    }

    private void dismissFloat() {
        PopupWindow b = floatButton;
        floatButton = null;
        if (b != null) {
            try {
                b.dismiss();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
    }

    // ---- pocket lock -------------------------------------------------------------------------------

    private void startPocketLock() {
        if (Sessions.active().isEmpty()) return;
        pocketLocked = true;
        if (sheet != null) sheet.dismiss();
        showLock();
        updatePip();
    }

    /**
     * The lock is a window of its own above everything (so the recording, which copies this activity's window, never shows
     * it, and Step away's cover underneath is covered too). Touches stop there; Back does nothing. To unlock, hold both
     * circles at once for a moment.
     */
    private void showLock() {
        if (lockDialog != null) return;
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);
        d.setContentView(new PocketLockView(this, () -> {
            pocketLocked = false;
            dismissLock();
            updatePip();
            Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show();
        }));
        d.setOnKeyListener((dd, code, ev) -> code == android.view.KeyEvent.KEYCODE_BACK
                || code == android.view.KeyEvent.KEYCODE_APP_SWITCH || code == android.view.KeyEvent.KEYCODE_MENU);
        lockDialog = d;
        try {
            d.show();
        } catch (RuntimeException e) {
            lockDialog = null;
        }
    }

    private void dismissLock() {
        Dialog d = lockDialog;
        lockDialog = null;
        if (d != null) {
            try {
                d.dismiss();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
    }

    // ---- actions -----------------------------------------------------------------------------

    private void showSettings() {
        Session s = front();
        if (s == null || (sheet != null && sheet.isShowing())) return;
        sheet = new SessionSheet(this, s, this::applyBrightness);
        sheet.show();
    }

    /**
     * "Step away": start recording if it is not yet, and remember the moment. "Back": open the recording from that
     * moment, so what was missed can be watched at a faster speed until it catches up with live.
     */
    private void stepAwayOrBack() {
        Session s = front();
        if (s == null) return;
        if (s.awayAtWall == 0) {
            if (!s.stepAway()) return;   // this phone stops showing and playing the page; the recording goes on
            Toast.makeText(this, "The page is frozen and silent here, and still being recorded. Tap Back when you return.", Toast.LENGTH_LONG).show();
        } else {
            long from = s.awayAtWall;
            s.awayAtWall = 0;
            BufferRecorder r = s.recorder();
            if (r == null || r.runDir() == null) {
                Toast.makeText(this, "The recording is not running, so nothing was kept.", Toast.LENGTH_LONG).show();
                s.setFrozen(false);
            } else {
                s.catchingUp = true;   // stays frozen and silent behind the player; let go when you come back
                r.requestFlush(); // so the most recent part becomes playable
                startActivity(new Intent(this, MissedActivity.class)
                        .putExtra(MissedActivity.EXTRA_RUN, r.runDir().getAbsolutePath())
                        .putExtra(MissedActivity.EXTRA_FROM_WALL, from));
            }
        }
        updateControls();
    }

    /** Live captions on or off for the page in front; turning them on starts the recording they are saved with. */
    private void toggleCaptions() {
        Session s = front();
        if (s == null) return;
        if (s.isTranscribing()) {
            s.setTranscribing(false);
            Toast.makeText(this, "Live captions off. What was captured is saved with the recording.", Toast.LENGTH_SHORT).show();
            return;
        }
        String why = Transcriber.unavailableReason(this);
        if (why != null) {
            Toast.makeText(this, why, Toast.LENGTH_LONG).show();
            return;
        }
        if (!s.isRecording()) s.startRecording();
        if (!s.isRecording()) return;
        s.setTranscribing(true);
        Toast.makeText(this, "Live captions on. They are saved with the recording (Recordings > Captions).", Toast.LENGTH_LONG).show();
    }

    private void recordingMenu() {
        Session s = front();
        if (s == null) return;
        if (!s.isRecording()) {
            s.startRecording();
            if (s.isRecording()) {
                Toast.makeText(this, String.format(Locale.US, "Recording. The last %d minutes are kept.", Prefs.bufferMinutes(this)),
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        boolean cc = s.isTranscribing();
        new AlertDialog.Builder(this)
                .setTitle("Recording " + s.title())
                .setItems(new String[]{"Add a mark", "Play the recording", cc ? "Stop live captions" : "Start live captions (speech to text)", "Stop recording"},
                        (d, which) -> {
                            if (which == 0) markDialog(s);
                            else if (which == 1) playRecording(s);
                            else if (which == 2) s.setTranscribing(!cc);
                            else s.stopRecording();
                        })
                .show();
    }

    private void markDialog(Session s) {
        EditText input = new EditText(this);
        input.setHint("What is starting?");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Add a mark")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> s.mark(input.getText().toString().trim().isEmpty() ? "Mark" : input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void playRecording(Session s) {
        BufferRecorder r = s.recorder();
        if (r == null || r.runDir() == null) return;
        r.requestFlush();
        startActivity(new Intent(this, MissedActivity.class).putExtra(MissedActivity.EXTRA_RUN, r.runDir().getAbsolutePath()));
    }

    // ---- timer -------------------------------------------------------------------------------

    private void showTimerDialog() {
        Session s = front();
        if (s == null) return;
        List<String> labels = new ArrayList<>();
        for (int minutes : TIMER_MINUTES) labels.add(timerLabel(minutes));
        labels.add("At a set time...");
        new AlertDialog.Builder(this)
                .setTitle("Hang up timer - " + s.title())
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < TIMER_MINUTES.length) s.arm(TIMER_MINUTES[which] * 60_000L);
                    else pickClockTime(s);
                })
                .show();
    }

    private static String timerLabel(int minutes) {
        if (minutes == 0) return "Off";
        if (minutes < 60) return "In " + minutes + " minutes";
        if (minutes == 60) return "In 1 hour";
        if (minutes % 60 == 0) return "In " + minutes / 60 + " hours";
        return "In " + minutes / 60 + "½ hours";
    }

    /** Hang up at the next occurrence of a clock time (tonight's 03:30, say). */
    private void pickClockTime(Session s) {
        Calendar now = Calendar.getInstance();
        new TimePickerDialog(this, (view, hour, minute) -> {
            Calendar at = Calendar.getInstance();
            at.set(Calendar.HOUR_OF_DAY, hour);
            at.set(Calendar.MINUTE, minute);
            at.set(Calendar.SECOND, 0);
            at.set(Calendar.MILLISECOND, 0);
            if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_MONTH, 1);
            s.arm(at.getTimeInMillis() - System.currentTimeMillis());
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    // ---- freeze: the picture that stays on screen while you are away ------------------------------

    /**
     * Covers a frozen page with its last picture and a banner. The cover is a window of its own (a PopupWindow), not a
     * view inside this one, so the recorder, which copies this window, keeps seeing the live page underneath.
     */
    private void showFreeze(int slot) {
        dismissFreeze(slot);
        Session s = Sessions.get(slot);
        FrameLayout pane = panes[slot];
        if (!s.isFrozen() || !s.isActive() || pane.getParent() == null || pane.getWidth() < 8 || pane.getHeight() < 8) return;
        FrameLayout cover = new FrameLayout(this);
        cover.setBackgroundColor(0xFF000000);
        if (frozenPictures[slot] != null) {
            ImageView img = new ImageView(this);
            img.setImageBitmap(frozenPictures[slot]);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cover.addView(img, new FrameLayout.LayoutParams(-1, -1));
        }
        TextView banner = Ui.text(this, s.catchingUp ? "Watching what you missed" : "Stepped away - frozen here, still recording", 13f, Ui.TEXT, "sans-serif-medium");
        banner.setGravity(Gravity.CENTER);
        banner.setBackgroundColor(0xB3000000);
        banner.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        cover.addView(banner, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        PopupWindow popup = new PopupWindow(cover, pane.getWidth(), pane.getHeight(), false);
        popup.setClippingEnabled(false);
        int[] at = new int[2];
        pane.getLocationOnScreen(at);
        try {
            popup.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, at[0], at[1]);
            freezePopups[slot] = popup;
        } catch (RuntimeException ignored) {
            // The window is going away.
        }
    }

    private void dismissFreeze(int slot) {
        PopupWindow p = freezePopups[slot];
        freezePopups[slot] = null;
        if (p != null) {
            try {
                p.dismiss();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
    }

    /** Takes the picture of the page as it is now, then covers it. */
    private void freezeNow(int slot) {
        FrameLayout pane = panes[slot];
        if (pane.getWidth() < 8 || pane.getHeight() < 8 || pane.getParent() == null) {
            frozenPictures[slot] = null;
            showFreeze(slot);
            return;
        }
        int[] at = new int[2];
        pane.getLocationInWindow(at);
        Rect src = new Rect(at[0], at[1], at[0] + pane.getWidth(), at[1] + pane.getHeight());
        float k = Math.min(1f, 1280f / src.width());
        Bitmap bmp = Bitmap.createBitmap(Math.max(16, Math.round(src.width() * k)), Math.max(16, Math.round(src.height() * k)), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(getWindow(), src, bmp, result -> {
                frozenPictures[slot] = result == PixelCopy.SUCCESS ? bmp : null;
                showFreeze(slot);
            }, mainHandler);
        } catch (IllegalArgumentException e) {
            frozenPictures[slot] = null;
            showFreeze(slot);
        }
    }

    /** Re-places the covers after the panes moved (rotation, split, window size). */
    private void updateFreeze() {
        for (int i = 0; i < Sessions.COUNT; i++) {
            final int slot = i;
            dismissFreeze(slot);
            if (Sessions.get(slot).isFrozen() && !inPip) paneHolder.post(() -> showFreeze(slot));
        }
    }

    // ---- Session.Listener --------------------------------------------------------------------

    @Override
    public void onTimer(Session s, long remainingMs) {
        if (s.slot != focus) return;
        if (remainingMs < 0) {
            countdown.setText("No timer");
            return;
        }
        long sec = (remainingMs + 999) / 1000;
        countdown.setText(sec >= 3600
                ? String.format(Locale.US, "Closes in %d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
                : String.format(Locale.US, "Closes in %d:%02d", sec / 60, sec % 60));
    }

    @Override
    public void onOpened(Session s) {
        if (Sessions.get(focus).isActive()) updateControls();   // a new tab (a workflow's temporary page) shows up
        else rebuild();
    }

    @Override
    public void onMuted(Session s, boolean muted) {
        updateControls();
        updatePip();   // the Mini window's microphone button shows the state
    }

    @Override
    public void onAspect(Session s) {
        boxes[s.slot].setRatio(Pin.ASPECTS[s.aspect()]);
        updateFreeze();
    }

    /** The page's own full-screen view (a video's button) fills that page's area only, not the phone's screen. */
    @Override
    public void onFullscreen(Session s, View view) {
        FrameLayout pane = panes[s.slot];
        if (view == null) {
            if (fullscreenSession == s) fullscreenSession = null;
            View old = fullscreenViews[s.slot];
            if (old != null && old.getParent() == pane) pane.removeView(old);
            fullscreenViews[s.slot] = null;
        } else {
            fullscreenSession = s;
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            view.setBackgroundColor(Color.BLACK);
            pane.addView(view, new FrameLayout.LayoutParams(-1, -1));
            fullscreenViews[s.slot] = view;
        }
    }

    @Override
    public void onNotice(Session s, String text) {
        Toast.makeText(getApplicationContext(), s.label() + ": " + text, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onFrozen(Session s, boolean frozen) {
        if (frozen) {
            freezeNow(s.slot);
        } else {
            dismissFreeze(s.slot);
            frozenPictures[s.slot] = null;
        }
        updateControls();
    }

    @Override
    public void onRecording(Session s, boolean on, String note) {
        if (!note.isEmpty()) Toast.makeText(getApplicationContext(), note, Toast.LENGTH_LONG).show();
        updateControls();
    }

    @Override
    public void onClosed(Session s, String reason) {
        Toast.makeText(getApplicationContext(), s.title() + " closed: " + reason, Toast.LENGTH_LONG).show();
        if (Sessions.active().isEmpty()) {
            pocketLocked = false;
            if (sheet != null) sheet.dismiss();
            finish();
        } else {
            rebuild();
        }
    }
}
