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
    static final String ACTION_OPEN_URL = "app.turbobox.OPEN_URL";   // a page that is not pinned
    static final String EXTRA_URL = "url";
    static final String ACTION_APPLY_LAYOUT = "app.turbobox.APPLY_LAYOUT";   // a saved layout from the home screen
    static final String EXTRA_LAYOUT = "layout";
    static final String EXTRA_EDIT = "edit";      // with a layout: arrange it live afterwards
    static final String EXTRA_ARRANGE = "arrange";   // no layout: arrange the pages live (EXTRA_MINI says for which window)
    static final String EXTRA_MINI = "arrangeMini";
    static final String EXTRA_INCOGNITO = "incognito";
    static final String EXTRA_MOBILE = "mobile";
    static final String EXTRA_WIPE = "wipe";
    static final String EXTRA_PROFILE = "profile";
    private static final int[] TIMER_MINUTES = {0, 15, 30, 45, 60, 90, 120, 180, 240, 360, 480};

    private static final String ACTION_PIP_MUTE = "app.turbobox.PIP_MUTE";
    private static final String ACTION_PIP_MEDIA = "app.turbobox.PIP_MEDIA";
    private static final String EXTRA_OP = "op";

    private final FrameLayout[] panes = new FrameLayout[Sessions.COUNT];
    private final AspectFrame[] boxes = new AspectFrame[Sessions.COUNT];
    private final TextView[] tabs = new TextView[Sessions.COUNT];
    private LinearLayout root;
    private LinearLayout chromeBox;
    private Session fullscreenSession;
    private final PopupWindow[] lockPopups = new PopupWindow[Sessions.COUNT];
    private PopupWindow floatButton;
    private boolean pipReceiverOn;
    private LinearLayout paneHolder;
    private PipStage paneStage;
    private android.graphics.Rect lastCrop;   // where the pages really are inside the page area (kept current while not in the Mini window)
    private int lastStageW, lastStageH;
    private boolean preparedMini;
    private IconButton splitButton;
    private IconButton muteButton;
    private IconButton recButton;
    private IconButton ccButton;
    private IconButton awayButton;
    private TextView countdown;
    private final java.util.Map<String, IconButton> barItems = new java.util.HashMap<>();
    private LinearLayout barRow1, barRow2;
    private IconButton pauseButton;
    private android.widget.HorizontalScrollView hotkeyScroll;
    private LinearLayout hotkeyRow;
    private LevelMeterView strip;
    private View dim;
    private int focus;
    private boolean split;
    private final java.util.Set<Integer> splitSet = new java.util.TreeSet<>();   // the slots shown together while Split is on (up to four)
    private List<Integer> splitOrder;   // page order set by an applied layout; null = the order of the tabs
    private boolean inPip;
    private float[][] liveRects;        // the arrangement of the split made or applied this time (null = what the settings say)
    private int[] liveZ;
    private float[][] liveMiniRects;    // the same for the Mini window
    private int[] liveMiniZ;
    private boolean[] liveFloats;       // which pages float (see FloatLayer), full screen and Mini window
    private boolean[] liveMiniFloats;
    private boolean liveLandscape;      // the orientation the live full-screen arrangement was made in
    private FloatLayer floatLayer;
    private boolean editing;
    private boolean editMini;
    private float pipRatio;            // the Mini window's shape, kept while it is open
    private String editingLayoutId;
    private int editSelected = -1;
    private LinearLayout editBar;
    private TextView editTitle;
    private TextView editShape;
    private TextView editFloat;
    private TextView editSnap;
    private IconButton layoutButton;
    private MiniLayoutEditor overlay;
    private EditText typeBox;
    private Session typeSession;
    private boolean typeMulti, typeSilent;
    private final MirrorView[] mirrors = new MirrorView[Sessions.COUNT];
    private FractionLayout editStack;
    private float paneAspect;   // width / height of the pages' area on screen when not in the Mini window
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
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));   // never a grey flash while the window is resized
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
        if (ACTION_OPEN_URL.equals(intent.getAction())) {
            intent.setAction(null);   // handled once: a recreated activity must not open it again
            String address = Pin.normalizeUrl(intent.getStringExtra(EXTRA_URL));
            if (address != null) {
                Pin quick = Pin.create("", address);
                quick.name = quick.host();
                quick.temporary = true;
                quick.incognito = intent.getBooleanExtra(EXTRA_INCOGNITO, true);
                quick.mobile = intent.getBooleanExtra(EXTRA_MOBILE, false);
                String profile = intent.getStringExtra(EXTRA_PROFILE);
                if (profile != null && !profile.isEmpty()) quick.profileId = profile;
                quick.wipeOnClose = quick.incognito && intent.getBooleanExtra(EXTRA_WIPE, true);
                quick.recordMode = quick.wipeOnClose ? 2 : 0;   // a page that leaves nothing behind is not recorded either
                openPin(quick);
                return !Sessions.active().isEmpty();
            }
        }
        if (ACTION_APPLY_LAYOUT.equals(intent.getAction())) {
            intent.setAction(null);   // handled once, like the page above
            if (!Sessions.active().isEmpty()) {
                SavedLayout l = LayoutStore.find(this, intent.getStringExtra(EXTRA_LAYOUT));
                if (l != null) {
                    if (applyLayout(l) && intent.getBooleanExtra(EXTRA_EDIT, false)) startEdit(l.mini, l.id);
                } else if (intent.getBooleanExtra(EXTRA_ARRANGE, false)) {
                    startEdit(intent.getBooleanExtra(EXTRA_MINI, false), null);
                }
            }
            return !Sessions.active().isEmpty();
        }
        if (ACTION_OPEN_PIN.equals(intent.getAction()) && pinId != null) {
            Pin pin = PinStore.find(this, pinId);
            if (pin != null && Profiles.isLocked(this, pin.profileId) && Sessions.forPin(pin.id) == null) {
                Toast.makeText(this, "That page is in a locked profile. Unlock it in TurboBox first.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, MainActivity.class));
                return false;
            }
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

    /**
     * Puts the pages of the split (or, if the split has another number, the first ones in tab order) into the layout's places.
     * A layout is not tied to pages. It holds until the pages in the split change; what a new split starts as is a setting.
     */
    private boolean applyLayout(SavedLayout l) {
        List<Integer> slots = new ArrayList<>();
        if (split && members().size() == l.count) {
            for (Session m : members()) slots.add(m.slot);
        } else {
            for (Session a : Sessions.active()) if (slots.size() < l.count) slots.add(a.slot);
        }
        if (slots.size() < l.count) {
            Toast.makeText(this, "This layout is for " + l.count + " pages; only " + slots.size() + " are open.", Toast.LENGTH_LONG).show();
            rebuild();
            return false;
        }
        split = true;
        splitSet.clear();
        splitSet.addAll(slots);
        splitOrder = slots;
        if (!splitSet.contains(focus)) focus = slots.get(0);
        float[][] r = l.rectangles();
        int[] z = l.order();
        boolean[] fl = l.floatFlags();
        if (l.mini) {
            liveMiniRects = r;
            liveMiniZ = z;
            liveMiniFloats = fl;
            Prefs.setMiniShape(this, l.shape);
            Toast.makeText(this, "Used by the Mini window.", Toast.LENGTH_SHORT).show();
        } else {
            liveRects = r;
            liveZ = z;
            liveFloats = fl;
            liveLandscape = isLandscape();
        }
        Prefs.setLastArrangement(this, l.mini, l.count, isLandscape(), MiniLayout.pack(r, z, fl));
        rebuild();
        return true;
    }

    // ---- arranging the pages live ------------------------------------------------------------

    /** Where the pages sit: rectangles (and a front-to-back order), or rows of a grid. */
    private static final class Arr {
        float[][] rects;
        int[] z;
        int[] rows;
        boolean[] floats;
    }

    /**
     * The arrangement for this many pages: the live one (made or applied this time), else what the settings say a new split starts
     * as. Nothing is ever applied by itself.
     */
    private Arr arrangement(boolean mini, int n, boolean landscape) {
        mini = false;   // the Mini window shows the same arrangement as the screen, only smaller
        Arr a = new Arr();
        float[][] live = mini ? liveMiniRects : liveRects;
        int[] liveOrder = mini ? liveMiniZ : liveZ;
        if (live != null && live.length == n && liveOrder != null && (mini || liveLandscape == landscape)) {
            a.rects = live;
            a.z = liveOrder;
            a.floats = mini ? liveMiniFloats : liveFloats;
            return a;
        }
        if (mini) {
            int mode = Prefs.pipLayout(this);
            if (mode == SplitLayout.OVERLAY) {
                String last = Prefs.lastArrangement(this, true, n, false);
                float[][] r = MiniLayout.unpackRects(last, n);
                if (r != null) {
                    a.rects = r;
                    a.z = MiniLayout.unpackOrder(last, n);
                    a.floats = MiniLayout.unpackFloats(last, n);
                    return a;
                }
                mode = SplitLayout.LIKE_SPLIT;
            }
            a.rows = SplitLayout.pipRows(n, mode, landscape);
            return a;
        }
        int d = Prefs.splitDefault(this);
        if (d == 0) {
            String last = Prefs.lastArrangement(this, false, n, landscape);
            float[][] r = MiniLayout.unpackRects(last, n);
            if (r != null) {
                a.rects = r;
                a.z = MiniLayout.unpackOrder(last, n);
                a.floats = MiniLayout.unpackFloats(last, n);
                return a;
            }
            d = 2;
        }
        a.rows = d == 1 ? MiniLayout.stackedRows(n) : MiniLayout.gridRows(n);
        return a;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** Starts arranging the split's pages (or the Mini window's) with frames over the live pages. */
    private void startEdit(boolean mini, String layoutId) {
        if (inPip) return;
        if (Sessions.active().isEmpty()) return;
        if (!split) {
            split = true;
            splitSet.clear();
            for (Session a : Sessions.active()) if (splitSet.size() < 4) splitSet.add(a.slot);
            splitOrder = null;
        }
        int n = members().size();
        if (n < 1) return;
        Arr a = arrangement(mini, n, isLandscape());
        float[][] r = new float[n][];
        if (a.rects != null) {
            for (int i = 0; i < n; i++) r[i] = a.rects[i].clone();
        } else {
            float[][] base = MiniLayout.fromRows(a.rows);
            for (int i = 0; i < n; i++) r[i] = base[i];
        }
        int[] z = a.z != null ? a.z.clone() : MiniLayout.decodeOrder("", n);
        boolean[] fl = a.floats != null ? a.floats.clone() : new boolean[n];
        if (mini) {
            liveMiniRects = r;
            liveMiniZ = z;
            liveMiniFloats = fl;
        } else {
            liveRects = r;
            liveZ = z;
            liveFloats = fl;
            liveLandscape = isLandscape();
        }
        editing = true;
        editMini = mini;
        editingLayoutId = layoutId;
        editSelected = -1;
        rebuild();
    }

    private void stopEdit() {
        if (!editing) return;
        float[][] r = editMini ? liveMiniRects : liveRects;
        int[] z = editMini ? liveMiniZ : liveZ;
        if (r != null && z != null) Prefs.setLastArrangement(this, editMini, r.length, isLandscape(), MiniLayout.pack(r, z, editMini ? liveMiniFloats : liveFloats));
        editing = false;
        editingLayoutId = null;
        rebuild();
    }

    private void saveEdit() {
        float[][] r = editMini ? liveMiniRects : liveRects;
        int[] z = editMini ? liveMiniZ : liveZ;
        if (r == null || z == null) return;
        if (editingLayoutId != null) {
            SavedLayout l = LayoutStore.find(this, editingLayoutId);
            if (l != null) {
                fill(l, r, z);
                LayoutStore.put(this, l);
                Toast.makeText(this, "Saved changes to " + l.name + ".", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        EditText name = new EditText(this);
        name.setHint("Name (Lecture and notes, Four up, ...)");
        name.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Save this arrangement").setView(name)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(this, "Give it a name.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    SavedLayout l = SavedLayout.create();
                    l.name = n;
                    fill(l, r, z);
                    LayoutStore.put(this, l);
                    editingLayoutId = l.id;
                    Toast.makeText(this, "Saved. Find it under Layouts on the home screen.", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void fill(SavedLayout l, float[][] r, int[] z) {
        l.count = r.length;
        l.mini = editMini;
        l.shape = Prefs.miniShape(this);
        l.rects = MiniLayout.encode(r);
        l.z = MiniLayout.encodeOrder(z);
        boolean[] fl = editMini ? liveMiniFloats : liveFloats;
        l.floats = fl != null && MiniLayout.anyFloat(fl) ? MiniLayout.encodeFloats(fl) : "";
    }

    /** The row of buttons that shows while arranging: order of the pages, window shape, save, done. */
    private void buildEditBar() {
        editBar = new LinearLayout(this);
        editBar.setOrientation(LinearLayout.VERTICAL);
        editBar.setBackgroundColor(Ui.BG);
        int side = Ui.dp(this, 12);
        editBar.setPadding(side, Ui.dp(this, 6), side, Ui.dp(this, 6));
        editTitle = Ui.text(this, "", 13f, Ui.DIM, "sans-serif");
        editBar.addView(editTitle);
        LinearLayout row1 = new LinearLayout(this);
        TextView forward = Ui.pill(this, "Bring forward", 0);
        forward.setOnClickListener(v -> moveSelected(1));
        TextView back = Ui.pill(this, "Send back", 0);
        back.setOnClickListener(v -> moveSelected(-1));
        editShape = Ui.pill(this, "Shape", 0);
        editShape.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Mini window shape")
                .setSingleChoiceItems(MiniLayout.SHAPE_LABELS, Prefs.miniShape(this), (d, which) -> {
                    Prefs.setMiniShape(this, which);
                    d.dismiss();
                    rebuild();
                }).show());
        editFloat = Ui.pill(this, "Float", 0);
        editFloat.setOnClickListener(v -> {
            if (overlay == null || !overlay.toggleFloat()) Toast.makeText(this, "Tap a page's frame first.", Toast.LENGTH_SHORT).show();
            else updateEditBar();
        });
        row1.addView(forward, Ui.weight(this, 0));
        row1.addView(back, Ui.weight(this, 6));
        row1.addView(editFloat, Ui.weight(this, 6));
        row1.addView(editShape, Ui.weight(this, 6));
        editBar.addView(row1, Ui.lp(this, 6, 0));
        LinearLayout row2 = new LinearLayout(this);
        editSnap = Ui.pill(this, "Snap", 0);
        editSnap.setOnClickListener(v -> {
            Prefs.setSnapEdges(this, !Prefs.snapEdges(this));
            if (overlay != null) overlay.setSnap(Prefs.snapEdges(this));
            updateEditBar();
        });
        TextView save = Ui.pill(this, "Save", 0);
        save.setOnClickListener(v -> saveEdit());
        TextView reset = Ui.pill(this, "Reset to grid", 0);
        reset.setOnClickListener(v -> {
            int n = members().size();
            float[][] base = MiniLayout.fromRows(MiniLayout.gridRows(n));
            float[][] r = new float[n][];
            for (int i = 0; i < n; i++) r[i] = base[i];
            if (editMini) {
                liveMiniRects = r;
                liveMiniZ = MiniLayout.decodeOrder("", n);
                liveMiniFloats = new boolean[n];
            } else {
                liveRects = r;
                liveZ = MiniLayout.decodeOrder("", n);
                liveFloats = new boolean[n];
            }
            rebuild();
        });
        TextView done = Ui.pill(this, "Done", Ui.GREEN);
        done.setOnClickListener(v -> stopEdit());
        row2.addView(editSnap, Ui.weight(this, 0));
        row2.addView(save, Ui.weight(this, 6));
        row2.addView(reset, Ui.weight(this, 6));
        row2.addView(done, Ui.weight(this, 6));
        editBar.addView(row2, Ui.lp(this, 6, 0));
        editBar.setVisibility(View.GONE);
    }

    private void moveSelected(int step) {
        if (overlay == null || overlay.selected() < 0) {
            Toast.makeText(this, "Tap a page's frame first.", Toast.LENGTH_SHORT).show();
            return;
        }
        overlay.move(step);
    }

    private void updateEditBar() {
        editBar.setVisibility(editing && !inPip ? View.VISIBLE : View.GONE);
        if (!editing) return;
        Ui.tint(this, editSnap, Prefs.snapEdges(this) ? Ui.GREEN : 0);
        boolean sel = overlay != null && overlay.selected() >= 0;
        Ui.tint(this, editFloat, sel && overlay.isFloating(overlay.selected()) ? Ui.GREEN : 0);
        editShape.setVisibility(editMini ? View.VISIBLE : View.GONE);
        editShape.setText("Shape: " + MiniLayout.SHAPE_LABELS[Math.max(0, Math.min(MiniLayout.SHAPE_LABELS.length - 1, Prefs.miniShape(this)))]);
        editTitle.setText((editMini ? "Arranging the Mini window" : "Arranging the split") + (editingLayoutId != null ? " (a saved layout)" : "")
                + ". Drag a page's frame, drag its corner to resize. Float turns a page into a movable picture-in-picture that cannot be touched. "
                + "Snap makes edges meet.");
    }

    private void layoutMenu() {
        String[] items = {"Arrange the pages here (live)", "Use a saved layout..."};
        new AlertDialog.Builder(this).setTitle("Layout").setItems(items, (d, which) -> {
            if (which == 0) startEdit(false, null);
            else chooseSavedLayout();
        }).show();
    }

    private void chooseSavedLayout() {
        List<SavedLayout> all = LayoutStore.load(this);
        List<SavedLayout> usable = new ArrayList<>();
        for (SavedLayout l : all) if (l.count <= Sessions.active().size()) usable.add(l);
        if (usable.isEmpty()) {
            Toast.makeText(this, all.isEmpty() ? "No saved layouts yet: arrange the pages, then Save as layout."
                    : "Your layouts need more pages than are open.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[usable.size()];
        for (int i = 0; i < names.length; i++) names[i] = usable.get(i).name + "  (" + usable.get(i).count + " pages" + (usable.get(i).mini ? ", Mini window" : "") + ")";
        new AlertDialog.Builder(this).setTitle("Use a layout").setItems(names, (d, which) -> applyLayout(usable.get(which))).show();
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
        getWindow().getDecorView().post(this::applyBarMode);
    }

    @Override
    protected void onResume() {
        if (dim != null) applyBrightness();
        if (barRow1 != null) arrangeBar();
        if (!isInPictureInPictureMode()) unprepareMini();   // the Home gesture got things ready in case the Mini window opened; it did not
        applyRotation();
        super.onResume();
        Profiles.returning();
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
        Profiles.leaving();
        keepPagesRunning();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Stopped while still a Mini window = the person dismissed it with its X. Finish the screen so no empty window is left;
        // the pages themselves keep running (they belong to the service, not to this screen).
        if (inPip && Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode() && !isChangingConfigurations()) finish();
        unprepareMini();
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
        for (int i = 0; i < mirrors.length; i++) if (mirrors[i] != null) mirrors[i].release();
        dismissFloat();
        dismissLocks();
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
        if (allShownLocked()) return;   // locked: nothing gets through
        if (fullscreenSession != null) {
            fullscreenSession.exitFullscreen();
            return;
        }
        if (editing) {
            stopEdit();
            return;
        }
        // Back opens the Mini window, so what is playing stays in sight; if that is not possible, just leave. Never navigate
        // the page back mid-call (that could unload it). The pages keep running and the notification brings you back.
        if (!enterPip()) moveTaskToBack(true);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (inPip) return;   // the Mini window is not affected by rotation
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

    /**
     * The Mini window takes the shape of what the pages look like on screen when it opens (the page area, without the bar or any
     * empty space around the picture), and keeps it: rotating the phone does not change it.
     */
    private PictureInPictureParams.Builder pipParams() {
        List<Session> pages = pipPages();
        float ratio = paneAspect > 0 ? paneAspect : 9f / 16f;
        // The shape of the box that holds every page (forced shapes, stacked and overlapping pages, letterboxing all included), so the
        // window has no empty bars: that box is what the stage shows.
        android.graphics.Rect cropNow = lastCrop;
        if (cropNow != null && cropNow.width() > 16 && cropNow.height() > 16 && !editing) ratio = cropNow.width() / (float) cropNow.height();
        if (pages.size() == 1 && pages.get(0).fullscreenRatio() > 0) ratio = pages.get(0).fullscreenRatio();   // a video in full screen: its own shape
        if (inPip && pipRatio > 0) ratio = pipRatio;
        int[] shape = SplitLayout.shapeOfRatio(ratio);
        pipRatio = shape[0] / (float) shape[1];
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder().setAspectRatio(new Rational(shape[0], shape[1]));
        // The mic button works for any page: it is the same mute as the notification's (for a page that is not using the
        // microphone it simply keeps it from being sent when one is asked for).
        if (!Sessions.active().isEmpty()) {
            boolean muted = Sessions.allMuted();
            List<RemoteAction> actions = new ArrayList<>();
            // What each Mini window button does is set in Settings > Mini window buttons (PipButtons). Android shows only as many as
            // it allows (usually three), taking them in order.
            Session f = front();
            for (int i = 0; i < PipButtons.SLOTS; i++) {
                String fn = PipButtons.get(this, i);
                if (fn.equals("none")) continue;
                int code = 20 + i;
                switch (fn) {
                    case "pause": {
                        boolean paused = f != null && f.isFrozen();
                        actions.add(pipAction(code, paused ? R.drawable.ic_play : R.drawable.ic_pause, paused ? "Resume" : "Pause", fn));
                        break;
                    }
                    case "mute":
                        actions.add(pipAction(code, muted ? R.drawable.ic_mic_off : R.drawable.ic_mic, muted ? "Unmute" : "Mute", fn));
                        break;
                    case "record":
                        actions.add(pipAction(code, R.drawable.ic_record, f != null && f.isRecording() ? "Stop recording" : "Record", fn));
                        break;
                    case "both":
                        actions.add(pipAction(code, R.drawable.ic_both, f != null && f.awayAtWall != 0 ? "Back" : "Pause and record", fn));
                        break;
                    case "play":
                        actions.add(pipAction(code, f != null && f.isMediaPlaying() ? R.drawable.ic_pause : R.drawable.ic_play, "Play or pause the video", fn));
                        break;
                    case "fwd":
                        actions.add(pipAction(code, R.drawable.ic_forward, "Forward 10 seconds", fn));
                        break;
                    case "back":
                        actions.add(pipAction(code, R.drawable.ic_back, "Back 10 seconds", fn));
                        break;
                    default:
                        if (fn.startsWith("workflow:")) {
                            Workflow w = WorkflowStore.find(this, fn.substring(9));
                            if (w != null) actions.add(pipAction(code, R.drawable.ic_flow, w.name, fn));
                        }
                }
            }
            int max = Build.VERSION.SDK_INT >= 26 ? getMaxNumPictureInPictureActions() : 3;
            while (actions.size() > Math.max(1, max)) actions.remove(actions.size() - 1);
            b.setActions(actions);
        }
        // From Android 12 the window shrinks by itself when you go Home with a page open.
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(!Sessions.active().isEmpty() && !allShownLocked() && !editing);
        return b;
    }

    private RemoteAction pipAction(int code, int icon, String title, String op) {
        PendingIntent pi = PendingIntent.getBroadcast(this, code, new Intent(ACTION_PIP_MEDIA).setPackage(getPackageName()).putExtra(EXTRA_OP, op),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new RemoteAction(Icon.createWithResource(this, icon), title, title, pi);
    }

    private final BroadcastReceiver pipMute = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PIP_MEDIA.equals(intent.getAction())) {
                Session f = front();
                String op = intent.getStringExtra(EXTRA_OP);
                if (f == null || op == null) return;
                switch (op) {
                    case "pause": {
                        // Pause freezes and silences every page in the Mini window (and the same button lets them go again).
                        boolean freeze = !f.isFrozen();
                        for (Session p : pipPages()) p.setFrozen(freeze);
                        break;
                    }
                    case "mute":
                        Sessions.toggleAllMuted();
                        break;
                    case "record":
                        if (f.isRecording()) f.stopRecording(); else f.startRecording();
                        break;
                    case "both":
                        stepAwayOrBack();
                        break;
                    case "play":
                        f.mediaToggle();
                        break;
                    case "fwd":
                        f.mediaSeek(10);
                        break;
                    case "back":
                        f.mediaSeek(-10);
                        break;
                    default:
                        if (op.startsWith("workflow:")) WorkflowRunner.start(SessionActivity.this, op.substring(9), false);
                }
                updatePip();
                return;
            }
            Sessions.toggleAllMuted();
        }
    };

    private void registerPipReceiver() {
        if (pipReceiverOn || !canPip()) return;
        IntentFilter f = new IntentFilter(ACTION_PIP_MUTE);
        f.addAction(ACTION_PIP_MEDIA);
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

    private String pipSignature = "";

    private void updatePip() {
        if (!canPip()) return;
        try {
            PictureInPictureParams params = pipParams().build();
            // Setting the same params again makes Android re-evaluate the window, which is what made it jump while it was being
            // resized: only a real change (a different shape or button state) is passed on.
            String sig = pipRatio + "|" + PipButtons.get(this, 0) + PipButtons.get(this, 1) + PipButtons.get(this, 2) + PipButtons.get(this, 3) + "|"
                    + Sessions.allMuted() + "|" + (front() != null && front().isFrozen()) + "|" + (front() != null && front().isRecording()) + "|"
                    + (front() != null && front().isMediaPlaying()) + "|" + (front() != null && front().awayAtWall != 0);
            if (sig.equals(pipSignature)) return;
            pipSignature = sig;
            setPictureInPictureParams(params);
        } catch (RuntimeException ignored) {
            // Not allowed right now; the Home gesture still works through onUserLeaveHint.
        }
    }

    /** Shrinks the page in front into a small floating window, which also keeps Android from unloading the app. */
    private boolean enterPip() {
        if (!canPip() || isInPictureInPictureMode() || Sessions.active().isEmpty() || allShownLocked()) return false;
        if (editing) stopEdit();
        if (sheet != null) sheet.dismiss();
        pipRatio = 0;
        pipSignature = "";
        prepareMini();
        try {
            return enterPictureInPictureMode(pipParams().build());
        } catch (RuntimeException e) {
            Toast.makeText(this, "Picture in picture is not available here.", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < 31) enterPip();
        else if (canPip() && !Sessions.active().isEmpty() && !allShownLocked() && !editing) prepareMini();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean in, Configuration config) {
        super.onPictureInPictureModeChanged(in, config);
        inPip = in;
        if (!in) {
            pipRatio = 0;
            unprepareMini();
        } else {
            prepareMini();   // entered by the Home gesture without a warning: the sizes kept from before are used
        }
        for (Session s : Sessions.active()) s.setMiniMode(in);   // each page takes the zoom and shape set for that mode
        chromeBox.setVisibility(in ? View.GONE : View.VISIBLE);
        rebuild();
        // The window is still changing size while this runs; a page (or a full-screen video) that was measured against the old
        // size is left filling a corner of the new one. Lay everything out again once the window has settled.
        for (long delay : new long[]{150, 600}) mainHandler.postDelayed(this::relayoutPanes, delay);
        getWindow().getDecorView().post(this::applyBarMode);
    }

    /** Where the pages really are inside the page area: the box that holds all of them (not the empty space around or between). */
    private android.graphics.Rect computeCrop() {
        if (paneStage == null || paneStage.getWidth() < 8) return null;
        int w = paneStage.getWidth(), h = paneStage.getHeight();
        android.graphics.Rect full = new android.graphics.Rect(0, 0, w, h);
        int[] so = new int[2];
        paneStage.getLocationOnScreen(so);
        android.graphics.Rect u = null;
        for (Session s : gridPages) {
            View c = null;
            MirrorView m = mirrors[s.slot];
            if (s.privateDisplay() != null && m != null && m.getParent() != null) c = m.contentView();
            else {
                WebView wv = s.peekWebView();
                if (wv != null && wv.getParent() == boxes[s.slot]) c = wv;
            }
            if (c == null || c.getWidth() < 8 || !c.isShown()) continue;
            int[] at = new int[2];
            c.getLocationOnScreen(at);
            android.graphics.Rect r = new android.graphics.Rect(at[0] - so[0], at[1] - so[1], at[0] - so[0] + c.getWidth(), at[1] - so[1] + c.getHeight());
            if (u == null) u = r; else u.union(r);
        }
        if (u == null || !u.intersect(0, 0, w, h) || u.width() < 16 || u.height() < 16) return full;
        return u;
    }

    private void refreshCrop() {
        if (inPip || preparedMini) return;
        android.graphics.Rect c = computeCrop();
        if (c == null) return;
        boolean changed = lastCrop == null || !lastCrop.equals(c);
        lastCrop = c;
        lastStageW = paneStage.getWidth();
        lastStageH = paneStage.getHeight();
        if (changed) updatePip();   // the window is made in this shape if it has to be made from the Home gesture
    }

    /**
     * Gets the page area ready for the Mini window: pages move to private displays of the size they have (so they are never resized),
     * and the stage shows only the part that has pages in it. Done before the window shrinks.
     */
    private void prepareMini() {
        if (preparedMini || paneStage == null) return;
        android.graphics.Rect crop = lastCrop != null && lastStageW > 0 ? lastCrop : computeCrop();
        int sw = lastStageW > 0 ? lastStageW : paneStage.getWidth(), sh = lastStageH > 0 ? lastStageH : paneStage.getHeight();
        if (crop == null || sw < 16) return;
        for (Session s : gridPages) {
            WebView wv = s.peekWebView();
            if (s.privateDisplay() == null && wv != null && wv.getWidth() >= 16) s.hostForMini(wv.getWidth(), wv.getHeight());
        }
        paneStage.enter(sw, sh, crop);
        preparedMini = true;
        rebuild();
    }

    /** Back to the normal screen: pages that were moved for the Mini window come home, and the stage fills the space again. */
    private void unprepareMini() {
        if (!preparedMini && !paneStage.isPip()) return;
        preparedMini = false;
        paneStage.exit();
        for (Session s : Sessions.all()) s.leaveMiniHost();
    }

    private void relayoutPanes() {
        if (isFinishing() || isDestroyed()) return;
        paneHolder.requestLayout();
        for (int i = 0; i < Sessions.COUNT; i++) {
            View full = fullscreenViews[i];
            if (full != null) full.requestLayout();
            WebView wv = Sessions.get(i).peekWebView();
            if (wv != null && wv.getParent() == boxes[i]) wv.requestLayout();
        }
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
        splitButton = new IconButton(this, IconButton.Icon.SPLIT, "Show several pages at once");
        splitButton.setOnClickListener(v -> toggleSplit());
        tabRow.addView(splitButton, new LinearLayout.LayoutParams(Ui.dp(this, 48), ViewGroup.LayoutParams.WRAP_CONTENT));
        chromeBox.addView(tabRow, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        // The bar's buttons. Which ones show, and in what order, is set in Settings > Top bar (BarItems).
        int side2 = side;
        pauseButton = new IconButton(this, IconButton.Icon.PAUSE, "Pause: freeze the page and silence it on this phone");
        pauseButton.setOnClickListener(v -> pauseOrResume());
        barItems.put("pause", pauseButton);
        awayButton = new IconButton(this, IconButton.Icon.BOTH, "Pause and record: catch up on what you missed when you come back");
        awayButton.setOnClickListener(v -> stepAwayOrBack());
        barItems.put("both", awayButton);
        recButton = new IconButton(this, IconButton.Icon.RECORD, "Record (hold for marks, playback and captions)");
        recButton.setOnClickListener(v -> toggleRecording());
        recButton.setOnLongClickListener(v -> {
            recordingMenu();
            return true;
        });
        barItems.put("record", recButton);
        ccButton = new IconButton(this, IconButton.Icon.CAPTIONS, "Live captions");
        ccButton.setOnClickListener(v -> toggleCaptions());
        barItems.put("captions", ccButton);
        muteButton = new IconButton(this, IconButton.Icon.MIC, "Mute or unmute your microphone");
        muteButton.setOnClickListener(v -> {
            Session s = front();
            if (s != null) s.setMuted(!s.isMuted());
        });
        barItems.put("mute", muteButton);
        IconButton timer = new IconButton(this, IconButton.Icon.TIMER, "Timer: hang up after a while or at a time");
        timer.setOnClickListener(v -> showTimerDialog());
        barItems.put("timer", timer);
        if (canPip()) {
            IconButton pip = new IconButton(this, IconButton.Icon.MINI, "Mini window");
            pip.setOnClickListener(v -> enterPip());
            barItems.put("mini", pip);
        }
        layoutButton = new IconButton(this, IconButton.Icon.LAYOUT, "Arrange the pages (hold for more)");
        layoutButton.setOnClickListener(v -> {
            if (editing) stopEdit(); else startEdit(false, null);   // one tap: arrange the pages right here, live
        });
        layoutButton.setOnLongClickListener(v -> {
            layoutMenu();
            return true;
        });
        barItems.put("layout", layoutButton);
        IconButton lock = new IconButton(this, IconButton.Icon.LOCK, "Lock this page against touches");
        lock.setOnClickListener(v -> startPocketLock());
        barItems.put("lock", lock);
        IconButton rotate = new IconButton(this, IconButton.Icon.ROTATE, "Force rotation: other way, then held, then normal");
        rotate.setOnClickListener(v -> forceRotation());
        barItems.put("rotate", rotate);
        IconButton vol = new IconButton(this, IconButton.Icon.SPEAKER, "Volumes");
        vol.setOnClickListener(v -> VolumeSheet.show(this));
        barItems.put("volume", vol);
        IconButton hide = new IconButton(this, IconButton.Icon.HIDE, "Hide this bar");
        hide.setOnClickListener(v -> {
            Prefs.setBarHidden(this, true);
            applyBarMode();
        });
        barItems.put("hide", hide);
        IconButton settings = new IconButton(this, IconButton.Icon.GEAR, "This page's settings");
        settings.setOnClickListener(v -> showSettings());
        barItems.put("settings", settings);
        IconButton close = new IconButton(this, IconButton.Icon.CLOSE, "Close this page");
        close.setOnClickListener(v -> confirmClose());
        barItems.put("close", close);
        barRow1 = new LinearLayout(this);
        barRow1.setGravity(Gravity.CENTER_VERTICAL);
        barRow1.setPadding(side2, Ui.dp(this, 6), side2, 0);
        barRow2 = new LinearLayout(this);
        barRow2.setGravity(Gravity.CENTER_VERTICAL);
        barRow2.setPadding(side2, Ui.dp(this, 6), side2, Ui.dp(this, 4));
        chromeBox.addView(barRow1, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));
        chromeBox.addView(barRow2, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));
        countdown = Ui.text(this, "", 13f, Ui.DIM, "sans-serif-medium");
        countdown.setGravity(Gravity.CENTER);
        countdown.setVisibility(View.GONE);
        chromeBox.addView(countdown, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));
        arrangeBar();

        // Workflows marked as a hotkey: one tap runs them, no questions asked.
        hotkeyScroll = new android.widget.HorizontalScrollView(this);
        hotkeyScroll.setHorizontalScrollBarEnabled(false);
        hotkeyRow = new LinearLayout(this);
        hotkeyScroll.addView(hotkeyRow);
        hotkeyScroll.setPadding(side, 0, side, Ui.dp(this, 4));
        chromeBox.addView(hotkeyScroll, Ui.match(ViewGroup.LayoutParams.WRAP_CONTENT));

        strip = new LevelMeterView(this);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, Ui.dp(this, 5));
        ml.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 6));
        chromeBox.addView(strip, ml);

        root.addView(chromeBox, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        paneHolder = new LinearLayout(this);
        paneHolder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (inPip || editing || r - l < 50 || b - t < 50) return;
            float now = (r - l) / (float) (b - t);
            if (Math.abs(now - paneAspect) > 0.01f) paneAspect = now;
            refreshCrop();
        });
        for (int i = 0; i < Sessions.COUNT; i++) {
            panes[i] = new FrameLayout(this);
            boxes[i] = new AspectFrame(this);
            panes[i].addView(boxes[i], new FrameLayout.LayoutParams(-1, -1));
        }
        buildEditBar();
        root.addView(editBar, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        paneStage = new PipStage(this);
        paneStage.addView(paneHolder, new FrameLayout.LayoutParams(-1, -1));
        root.addView(paneStage, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Brightness veil over everything, the same trick as Tether's main screen.
        FrameLayout frame = new FrameLayout(this);
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setClickable(false);
        dim.setFocusable(false);
        frame.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        frame.getViewTreeObserver().addOnGlobalLayoutListener(this::checkKeyboard);
    }

    /** Rotation of this screen: with the phone's setting, always with the sensor, or portrait only (Settings). */
    private void applyRotation() {
        int force = Prefs.forceRotate(this);
        if (force != 0) {
            boolean basePortrait = Prefs.forceBase(this) == 1;
            boolean portrait = force == 1 ? !basePortrait : basePortrait;   // 1: the other way, 2: held as it was
            setRequestedOrientation(portrait ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            return;
        }
        int mode = Prefs.rotation(this);
        setRequestedOrientation(mode == 1 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                : mode == 2 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    /** The bar's rotate button: current -> other -> current but forced -> current. */
    private void forceRotation() {
        int state = Prefs.cycleForce(this, isLandscape());
        applyRotation();
        updateControls();
        Toast.makeText(this, state == 1 ? "Turned the other way" : state == 2 ? "Held as it was" : "Back to normal rotation", Toast.LENGTH_SHORT).show();
    }

    /**
     * Typing: the page area gets smaller by the height the keyboard takes, so the page (shrunk, not cut off) stays above it. Only for
     * pages that have "Shrink for the keyboard" on (the default).
     */
    private void checkKeyboard() {
        if (paneHolder == null || inPip) return;
        int overlap = 0;
        Session f = front();
        if (Build.VERSION.SDK_INT >= 30 && f != null && (f.pin() == null || f.pin().shrinkOnType)) {
            android.view.WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
            if (wi != null && wi.isVisible(android.view.WindowInsets.Type.ime())) {
                int kb = wi.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                int nav = wi.getInsets(android.view.WindowInsets.Type.systemBars()).bottom;
                overlap = Math.max(0, kb - nav);
            }
        } else if (Build.VERSION.SDK_INT < 30 && f != null && (f.pin() == null || f.pin().shrinkOnType)) {
            android.graphics.Rect vis = new android.graphics.Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(vis);
            int[] at = new int[2];
            paneHolder.getLocationOnScreen(at);
            overlap = Math.max(0, at[1] + paneHolder.getHeight() + paneHolder.getPaddingBottom() - vis.bottom);
        }
        if (overlap != paneHolder.getPaddingBottom()) paneHolder.setPadding(0, 0, 0, overlap);
    }

    void refreshBrightness() {
        applyBrightness();
    }

    private void applyBrightness() {
        // The slider is the screen's brightness for this app: the window's own brightness follows it, and below 20% a black veil
        // darkens further than the phone's lowest setting can.
        float b = Math.max(0f, Math.min(1f, Prefs.brightness(this)));
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b >= 0.999f ? android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.01f, b);
        getWindow().setAttributes(lp);
        dim.setAlpha(b >= 0.2f ? 0f : (0.2f - b) / 0.2f * 0.9f);
    }

    /** The page the bar acts on, or null when none is open. */
    private Session front() {
        Session s = Sessions.get(focus);
        return s.isActive() ? s : null;
    }

    /** The pages in the split view, in slot order (at least the page in front). */
    private List<Session> members() {
        List<Session> out = new ArrayList<>();
        if (splitOrder != null && splitOrder.size() == splitSet.size() && splitSet.containsAll(splitOrder)) {
            for (int slot : splitOrder) if (Sessions.get(slot).isActive()) out.add(Sessions.get(slot));   // as the layout numbers them
            if (out.size() == splitSet.size()) return out;
            out.clear();
        }
        for (Session s : Sessions.active()) if (splitSet.contains(s.slot)) out.add(s);
        if (out.isEmpty() && front() != null) out.add(front());
        return out;
    }

    private void toggleSplit() {
        split = !split;
        splitOrder = null;
        if (split) {
            splitSet.clear();
            for (Session s : Sessions.active()) if (splitSet.size() < 4) splitSet.add(s.slot);
            Toast.makeText(this, "Tap tabs to choose which pages share the screen. Tap the highlighted tab again to take it out.", Toast.LENGTH_LONG).show();
        }
        rebuild();
    }

    /** A tab was tapped: the page in front, or (with Split on) in or out of the split. */
    private void tabTapped(int slot) {
        if (Sessions.get(slot).isLocked()) {
            Toast.makeText(this, "That page is locked. Hold both circles on it to unlock.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (split && (!splitSet.contains(slot) || (focus == slot && splitSet.size() > 1))) splitOrder = null;
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
        if (active.size() < 2 && !editing && liveRects == null && liveMiniRects == null) split = false;   // one page stays in a layout while it has one
        if (split) {
            java.util.Iterator<Integer> it = splitSet.iterator();
            while (it.hasNext()) if (!Sessions.get(it.next()).isActive()) it.remove();
            if (!splitSet.contains(focus)) {
                if (splitSet.size() >= 4) splitSet.remove(splitSet.iterator().next());
                splitSet.add(focus);
            }
        }

        boolean landscape = isLandscape();
        List<Session> shown = new ArrayList<>(inPip ? pipPages() : split ? members() : new ArrayList<Session>());
        if (shown.isEmpty() && front() != null) shown.add(front());
        boolean together = split && (shown.size() >= 2 || editing || (liveRects != null && liveRects.length == 1) || (inPip && liveMiniRects != null && liveMiniRects.length == 1));
        // An arrangement made for another number of pages no longer applies.
        if (liveRects != null && (liveRects.length != shown.size() || liveFloats == null || liveFloats.length != shown.size())) liveRects = null;
        if (liveMiniRects != null && (liveMiniRects.length != shown.size() || liveMiniFloats == null || liveMiniFloats.length != shown.size())) liveMiniRects = null;
        if (editing && (inPip || !together || (editMini ? liveMiniRects : liveRects) == null)) editing = false;
        Arr arr = together ? arrangement(inPip || editMini && editing, shown.size(), landscape) : null;
        boolean custom0 = arr != null && arr.rects != null;
        int[] rows = custom0 || arr == null ? new int[]{1} : arr.rows;

        boolean custom = custom0;
        List<Session> placed = shown;
        for (int i = 0; i < Sessions.COUNT; i++) {
            Session s = Sessions.get(i);
            WebView wv = s.peekWebView();
            if (s.isActive() && !placed.contains(s) && s.isHosted()) {
                s.attach(this);   // already on its private display; leave it there
            } else if (s.isActive()) {
                wv = s.webView(this);
                s.attach(this);
                if (s.privateDisplay() != null) {
                    // The page lives on its own display; the pane shows a live picture of it.
                    if (mirrors[i] == null) mirrors[i] = new MirrorView(this);
                    if (mirrors[i].getParent() != boxes[i]) {
                        if (mirrors[i].getParent() instanceof ViewGroup) ((ViewGroup) mirrors[i].getParent()).removeView(mirrors[i]);
                        boxes[i].addView(mirrors[i], new FrameLayout.LayoutParams(-1, -1));
                    }
                    mirrors[i].setHost(s.privateDisplay());
                    boxes[i].setRatio(editing ? 0f : Pin.ASPECTS[s.effectiveAspect()]);   // while arranging, the frame you drag is the page
                } else if (wv.getParent() != boxes[i]) {
                    if (wv.getParent() instanceof ViewGroup) ((ViewGroup) wv.getParent()).removeView(wv);
                    boxes[i].addView(wv, new FrameLayout.LayoutParams(-1, -1));
                }
                boxes[i].setRatio(editing ? 0f : Pin.ASPECTS[s.effectiveAspect()]);   // while arranging, the frame you drag is the page
            } else if (wv != null && wv.getParent() == boxes[i]) {
                boxes[i].removeView(wv);
            }
            if (!s.isActive() && mirrors[i] != null) {
                mirrors[i].release();
                if (mirrors[i].getParent() instanceof ViewGroup) ((ViewGroup) mirrors[i].getParent()).removeView(mirrors[i]);
                mirrors[i] = null;
            }
            if (panes[i].getParent() != null) ((ViewGroup) panes[i].getParent()).removeView(panes[i]);
        }
        paneHolder.removeAllViews();
        paneHolder.setOrientation(LinearLayout.VERTICAL);
        grid = null;
        gridPages = shown;
        overlay = null;
        editStack = null;
        if (custom) {
            // Rectangles: page 1 is rectangle 1; the order says which page is in front.
            FractionLayout stack = new FractionLayout(this);
            int n = shown.size();
            for (int k = 0; k < n; k++) stack.addView(panes[shown.get(arr.z[k]).slot]);
            stack.setRects(permuted(arr.rects, arr.z));
            View content = stack;
            if (editing) {
                // The frames sit over the live pages. The Mini window is arranged in a box of its own shape.
                FrameLayout canvas = new FrameLayout(this);
                canvas.addView(stack, new FrameLayout.LayoutParams(-1, -1));
                overlay = new MiniLayoutEditor(this, (rects, order, floating) -> {
                    int[] before = editMini ? liveMiniZ : liveZ;
                    int[] copy = order.clone();
                    if (editMini) {
                        liveMiniZ = copy;
                        liveMiniFloats = floating.clone();
                    } else {
                        liveZ = copy;
                        liveFloats = floating.clone();
                    }
                    editSelected = overlay != null ? overlay.selected() : -1;
                    if (!java.util.Arrays.equals(before, copy)) mainHandler.post(this::rebuild);   // the stacking changed
                    else if (editStack != null) editStack.setRects(permuted(rects, copy));
                });
                overlay.set(arr.rects, arr.z, arr.floats != null ? arr.floats : new boolean[n]);
                overlay.setSnap(Prefs.snapEdges(this));
                overlay.select(editSelected);
                canvas.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
                editStack = stack;
                content = canvas;
                if (editMini) {
                    AspectFrame boxFrame = new AspectFrame(this);
                    int[] sh = MiniLayout.shape(Prefs.miniShape(this));
                    boxFrame.setRatio(sh[0] / (float) sh[1]);
                    boxFrame.addView(canvas, new FrameLayout.LayoutParams(-1, -1));
                    content = boxFrame;
                }
            }
            if (!editing && !inPip && MiniLayout.anyFloat(arr.floats)) {
                // Floating pages: drag them around; touches on them never reach the page.
                FrameLayout withFloat = new FrameLayout(this);
                withFloat.addView(stack, new FrameLayout.LayoutParams(-1, -1));
                final float[][] r = arr.rects;
                final int[] zOrder = arr.z;
                final boolean[] fl = arr.floats;
                floatLayer = new FloatLayer(this, moved -> {
                    stack.setRects(permuted(moved, zOrder));
                    Prefs.setLastArrangement(this, false, moved.length, isLandscape(), MiniLayout.pack(moved, zOrder, fl));
                });
                floatLayer.set(r, zOrder, fl, Prefs.snapEdges(this));
                withFloat.addView(floatLayer, new FrameLayout.LayoutParams(-1, -1));
                content = withFloat;
            }
            paneHolder.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            grid = new SplitGrid(this);
            String key = joinRows(rows);
            grid.configure(rows, key, null, false);   // no dividers: arranging is done live, with Layout > Arrange
            grid.setOnSaved((k, v) -> {
                Prefs.setSplitLayout(this, k, v);
                updateFreeze();   // the covers follow the panes to where the divider left them
                updateLocks();
            });
            for (Session s : shown) grid.addView(panes[s.slot]);
            paneHolder.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
            updateRatios();
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
        if (f != null) applyBrightness();
        updatePip();
        updateControls();
        updateFreeze();
        updateLocks();
        boolean anyPrivate = false;
        for (Session a : Sessions.active()) anyPrivate |= a.privateDisplay() != null;
        // With the pages on private displays the keyboard types into an invisible box, so the app must not move or shrink for it.
        getWindow().setSoftInputMode(anyPrivate ? android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                : android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /** The rectangles in the order the pages are stacked (back first), which is the order of the children in the stack. */
    private static float[][] permuted(float[][] rects, int[] z) {
        float[][] out = new float[z.length][];
        for (int k = 0; k < z.length; k++) out[k] = rects[z[k]];
        return out;
    }

    private SplitGrid grid;
    private List<Session> gridPages = new ArrayList<>();

    private static String joinRows(int[] rows) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rows.length; i++) b.append(i == 0 ? "" : "-").append(rows[i]);
        return b.toString();
    }

    /** The shape a page is held to: its forced aspect ratio, or the video that is full screen in it. 0 = free. */
    private static float shapeOf(Session s) {
        float full = s.fullscreenRatio();
        return full > 0 ? full : Pin.ASPECTS[s.effectiveAspect()];
    }

    /** Tells the split which panes are held to a shape (so the rest can use what they leave empty). */
    private void updateRatios() {
        if (grid == null) return;
        float[] r = new float[gridPages.size()];
        for (int i = 0; i < r.length; i++) r[i] = shapeOf(gridPages.get(i));
        grid.setRatios(r, Prefs.reclaimEmpty(this));
    }

    /** Puts the bar's buttons in the order set in Settings > Top bar, hiding the ones switched off; seven to a row. */
    private void arrangeBar() {
        barRow1.removeAllViews();
        barRow2.removeAllViews();
        int n = 0;
        for (String id : BarItems.visibleOrder(this)) {
            IconButton b = barItems.get(id);
            if (b == null) continue;
            if (b.getParent() instanceof ViewGroup) ((ViewGroup) b.getParent()).removeView(b);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.leftMargin = Ui.dp(this, n % 7 == 0 ? 0 : 5);
            (n < 7 ? barRow1 : barRow2).addView(b, p);
            n++;
        }
        barRow2.setVisibility(n > 7 ? View.VISIBLE : View.GONE);
    }

    private void updateHotkeys() {
        hotkeyRow.removeAllViews();
        for (Workflow w : WorkflowStore.load(this)) {
            if (!w.hotkey) continue;
            TextView b = Ui.pill(this, w.name.isEmpty() ? "Workflow" : w.name, WorkflowRunner.isRunning(w.id) ? Ui.GREEN : 0);
            b.setSingleLine(true);
            b.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
            b.setOnClickListener(v -> {
                if (WorkflowRunner.isRunning(w.id)) {
                    WorkflowRunner.stop(w.id);
                } else {
                    WorkflowRunner.start(this, w.id, false);
                }
                v.postDelayed(this::updateHotkeys, 400);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.rightMargin = Ui.dp(this, 6);
            hotkeyRow.addView(b, p);
        }
        hotkeyScroll.setVisibility(hotkeyRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void updateControls() {
        updateHotkeys();
        IconButton rotateBtn = barItems.get("rotate");
        if (rotateBtn != null) {
            int force = Prefs.forceRotate(this);   // white = normal, amber = turned the other way, lime = held as it was
            rotateBtn.setIconColor(force == 1 ? Ui.AMBER : force == 2 ? Ui.GREEN : Ui.TEXT);
            Ui.tint(this, rotateBtn, force == 1 ? Ui.AMBER : force == 2 ? Ui.GREEN : 0);
        }
        Session f = front();
        for (int i = 0; i < Sessions.COUNT; i++) {
            Session s = Sessions.get(i);
            tabs[i].setVisibility(s.isActive() ? View.VISIBLE : View.GONE);
            if (!s.isActive()) continue;
            tabs[i].setText(s.label() + (s.isLocked() ? " (locked)" : ""));
            tabs[i].setSingleLine(true);
            tabs[i].setEllipsize(android.text.TextUtils.TruncateAt.END);
            Ui.tint(this, tabs[i], i == focus ? Ui.GREEN : split && splitSet.contains(i) ? 0xFF6B8F1F : 0);
        }
        splitButton.setVisibility(Sessions.active().size() > 1 ? View.VISIBLE : View.GONE);
        layoutButton.setVisibility(View.VISIBLE);
        updateEditBar();
        Ui.tint(this, layoutButton, editing ? Ui.GREEN : 0);
        Ui.tint(this, splitButton, split ? Ui.GREEN : 0);
        if (f == null) return;
        muteButton.setIcon(f.isMuted() ? IconButton.Icon.MIC_OFF : IconButton.Icon.MIC);
        Ui.tint(this, muteButton, f.isMuted() ? Ui.RED : 0); // red while muted, so the state is obvious at a glance
        onTimer(f, f.getRemainingMs());
        recButton.setIcon(f.isRecording() ? IconButton.Icon.STOP : IconButton.Icon.RECORD);
        Ui.tint(this, recButton, f.isRecording() ? Ui.RED : 0);
        Ui.tint(this, ccButton, f.isTranscribing() ? Ui.GREEN : 0);
        boolean away = f.awayAtWall != 0;
        boolean paused = f.isFrozen();
        pauseButton.setIcon(paused && !away ? IconButton.Icon.PLAY : IconButton.Icon.PAUSE);
        Ui.tint(this, pauseButton, paused && !away ? Ui.AMBER : 0);
        awayButton.setIcon(away ? IconButton.Icon.PLAY : IconButton.Icon.BOTH);
        Ui.tint(this, awayButton, away ? Ui.GREEN : 0);
        awayButton.setContentDescription(away ? "Back: play what you missed" : "Pause and record: catch up on what you missed when you come back");
    }

    // ---- the options bar, retracted into a button ---------------------------------------------

    /**
     * The bar across the top can be put away with its Hide bar button: the page then gets the whole screen and a small round
     * button you can drag stays over it; tapping that brings the bar back. The button is a window of its own, like the freeze
     * cover, so a recording of the page never shows it.
     */
    private void applyBarMode() {
        for (Session a : Sessions.active()) {
            a.refit();   // the page area changes size when the bar goes or comes back: fit the page to it again
        }
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

    // ---- page lock ----------------------------------------------------------------------------------

    /** Locks the page in front: its area ignores every touch until both circles are held. */
    private void startPocketLock() {
        Session f = front();
        if (f == null) return;
        if (f.isLocked()) {
            Toast.makeText(this, "Hold both circles on the page to unlock it.", Toast.LENGTH_SHORT).show();
            return;
        }
        f.setLocked(true);
        if (sheet != null) sheet.dismiss();
        if (split) {
            // Keep the bar working on a page that is not locked.
            for (Session s : members()) {
                if (!s.isLocked()) {
                    focus = s.slot;
                    break;
                }
            }
        }
        rebuild();
    }

    /** Every page on screen is locked (then the bar and tabs are covered too and Back does nothing). */
    private boolean allShownLocked() {
        if (gridPages.isEmpty()) return false;
        for (Session s : gridPages) if (!s.isLocked()) return false;
        return true;
    }

    /**
     * A locked page is covered by a window of its own (so the recording, which copies this activity's window, never shows
     * it, and Step away's cover underneath is covered too). Touches stop there. When every page on screen is locked the
     * cover takes the whole screen, bar included; with a split, only the locked page's area. To unlock, hold both circles at
     * once for a moment.
     */
    private void updateLocks() {
        dismissLocks();
        if (inPip) return;
        if (!allShownLocked()) hideNavigation(false);
        paneHolder.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            View decor = getWindow().getDecorView();
            boolean everything = allShownLocked();
            hideNavigation(everything);
            for (Session s : gridPages) {
                if (!s.isLocked() || lockPopups[s.slot] != null) continue;
                FrameLayout pane = panes[s.slot];
                if (pane.getParent() == null || pane.getWidth() < 8 || decor.getWidth() < 8) continue;
                int[] at = new int[2];
                int w = pane.getWidth(), h = pane.getHeight();
                if (everything) {
                    at[0] = 0;
                    at[1] = 0;
                    w = decor.getWidth();
                    h = decor.getHeight();
                } else {
                    pane.getLocationOnScreen(at);
                }
                final Session locked = s;
                PocketLockView face = new PocketLockView(this, () -> {
                    locked.setLocked(false);
                    updateLocks();
                    updateControls();
                    updatePip();
                    Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show();
                });
                PopupWindow popup = new PopupWindow(face, w, h, false);
                popup.setClippingEnabled(false);
                try {
                    popup.showAtLocation(decor, Gravity.TOP | Gravity.START, at[0], at[1]);
                    lockPopups[s.slot] = popup;
                } catch (RuntimeException ignored) {
                    // The window is going away.
                }
            }
        });
        updateControls();
        updatePip();
    }

    /**
     * While the whole screen is locked the navigation buttons are hidden too (the status bar stays, as a way out and to see
     * notifications). A swipe from the edge still brings them back for a moment.
     */
    private void hideNavigation(boolean hide) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (hide) {
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(android.view.WindowInsets.Type.navigationBars());
            } else {
                c.show(android.view.WindowInsets.Type.navigationBars());
            }
        } else {
            View d = getWindow().getDecorView();
            int base = d.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            d.setSystemUiVisibility(hide ? base | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : base);
        }
    }

    private void dismissLocks() {
        for (int i = 0; i < lockPopups.length; i++) {
            PopupWindow d = lockPopups[i];
            lockPopups[i] = null;
            if (d != null) {
                try {
                    d.dismiss();
                } catch (RuntimeException ignored) {
                    // Already gone.
                }
            }
        }
    }

    // ---- actions -----------------------------------------------------------------------------

    /** Closes at once, unless this page is set to ask first (page settings > Ask before closing). */
    private void confirmClose() {
        Session s = front();
        if (s == null) return;
        if (s.pin() == null || !s.pin().confirmClose) {
            s.close("you closed the page");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Close " + s.title() + "?")
                .setPositiveButton("Close page", (d, w) -> s.close("you closed the page"))
                .setNegativeButton("Keep it open", null)
                .show();
    }

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
            Toast.makeText(this, "Paused and recording. Tap play when you return.", Toast.LENGTH_SHORT).show();
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

    /** Pause: the page is frozen and silenced on this phone (nothing is recorded because of it); tapping again lets it go. */
    private void pauseOrResume() {
        Session s = front();
        if (s == null) return;
        if (s.awayAtWall != 0) {
            stepAwayOrBack();   // paused together with recording: this is the Back that plays what was missed
            return;
        }
        s.setFrozen(!s.isFrozen());
        updateControls();
    }

    /** Record: starts or stops the rolling recording of the page. */
    private void toggleRecording() {
        Session s = front();
        if (s == null) return;
        if (s.isRecording()) {
            s.stopRecording();
        } else {
            s.startRecording();
            if (s.isRecording()) {
                Toast.makeText(this, String.format(Locale.US, "Recording. The last %d minutes are kept.", Prefs.bufferMinutes(this)), Toast.LENGTH_SHORT).show();
            }
        }
        updateControls();
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
            countdown.setText("");
            countdown.setVisibility(View.GONE);
            return;
        }
        long sec = (remainingMs + 999) / 1000;
        countdown.setVisibility(View.VISIBLE);
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
    public void onMedia(Session s) {
        if (s.slot == focus) updatePip();   // the Mini window's play / pause button shows the state
    }

    @Override
    public void onAspect(Session s) {
        boxes[s.slot].setRatio(Pin.ASPECTS[s.effectiveAspect()]);
        updateRatios();
        updateFreeze();
        updatePip();
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
            updateRatios();
            updateFreeze();
        } else {
            fullscreenSession = s;
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            view.setBackgroundColor(Color.BLACK);
            pane.addView(view, new FrameLayout.LayoutParams(-1, -1));
            fullscreenViews[s.slot] = view;
            updateRatios();
            updateFreeze();
        }
    }

    @Override
    public void onNotice(Session s, String text) {
        Toast.makeText(getApplicationContext(), s.label() + ": " + text, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onField(Session s, String value, boolean multiline) {
        // The phone's own keyboard types into a small invisible box here, and every change is sent to the page's field at once,
        // so it behaves like typing into the page itself (suggestions, Enter and all).
        if (typeBox == null) {
            typeBox = new EditText(this);
            typeBox.setAlpha(0.01f);
            typeBox.setTextSize(1f);
            typeBox.setBackground(null);
            typeBox.setPadding(0, 0, 0, 0);
            ((FrameLayout) root.getParent()).addView(typeBox, new FrameLayout.LayoutParams(Ui.dp(this, 2), Ui.dp(this, 2), Gravity.BOTTOM | Gravity.START));
            typeBox.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }

                @Override
                public void afterTextChanged(android.text.Editable e) {
                    if (typeSession != null && !typeSilent) typeSession.setField(e.toString(), false);
                }
            });
            typeBox.setOnEditorActionListener((v, action, ev) -> {
                if (typeSession != null && !typeMulti) typeSession.setField(typeBox.getText().toString(), true);
                hideTypeBox();
                return true;
            });
        }
        typeSession = s;
        typeMulti = multiline;
        typeSilent = true;
        typeBox.setInputType(multiline ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : android.text.InputType.TYPE_CLASS_TEXT);
        typeBox.setImeOptions(multiline ? android.view.inputmethod.EditorInfo.IME_ACTION_NONE : android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        typeBox.setText(value);
        typeBox.setSelection(value.length());
        typeSilent = false;
        typeBox.setVisibility(View.VISIBLE);
        typeBox.requestFocus();
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        typeBox.post(() -> imm.showSoftInput(typeBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
    }

    @Override
    public void onFieldEnd(Session s) {
        if (s == typeSession) hideTypeBox();
    }

    private void hideTypeBox() {
        if (typeBox == null) return;
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(typeBox.getWindowToken(), 0);
        typeBox.clearFocus();
        typeBox.setVisibility(View.GONE);
        typeSession = null;
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
            if (sheet != null) sheet.dismiss();
            finish();
        } else {
            rebuild();
        }
    }
}
