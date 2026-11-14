package app.turbobox.enginetest;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;

/**
 * Loads an address in Mozilla's engine (GeckoView) in a private session, with a desktop identity or the phone's own, and
 * keeps a short log of what happened (title, load errors with their category and code) that can be copied. Camera and
 * microphone are refused whatever the page asks.
 */
public class MainActivity extends Activity {
    private static GeckoRuntime runtime;

    private GeckoView view;
    private GeckoSession session;
    private EditText address;
    private CheckBox desktop;
    private TextView logView;
    private final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (runtime == null) runtime = GeckoRuntime.create(getApplicationContext());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 20, 20));
        root.setPadding(0, dp(28), 0, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));

        address = new EditText(this);
        address.setText("https://example.com/");
        address.setSingleLine(true);
        address.setTextColor(Color.WHITE);
        address.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v, a, e) -> { go(); return true; });
        bar.addView(address, new LinearLayout.LayoutParams(0, -2, 1f));

        Button goBtn = new Button(this);
        goBtn.setText("Go");
        goBtn.setOnClickListener(v -> go());
        bar.addView(goBtn);
        root.addView(bar);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(8), dp(4));
        desktop = new CheckBox(this);
        desktop.setText("Desktop site");
        desktop.setTextColor(Color.WHITE);
        desktop.setChecked(true);
        row.addView(desktop, new LinearLayout.LayoutParams(0, -2, 1f));
        Button copy = new Button(this);
        copy.setText("Copy log");
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("engine test log", log.toString()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        row.addView(copy);
        root.addView(row);

        logView = new TextView(this);
        logView.setTextColor(Color.rgb(166, 226, 46));
        logView.setTextSize(11f);
        logView.setMaxLines(7);
        logView.setPadding(dp(12), 0, dp(12), dp(4));
        root.addView(logView);

        view = new GeckoView(this);
        root.addView(view, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        note("Engine: Gecko 140, private session, camera and microphone refused.");
        newSession();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void note(String s) {
        log.append(s).append('\n');
        runOnUiThread(() -> {
            String all = log.toString();
            String[] lines = all.split("\n");
            StringBuilder tail = new StringBuilder();
            for (int i = Math.max(0, lines.length - 7); i < lines.length; i++) tail.append(lines[i]).append('\n');
            logView.setText(tail.toString());
        });
    }

    private void newSession() {
        if (session != null) {
            view.releaseSession();
            session.close();
        }
        GeckoSessionSettings.Builder sb = new GeckoSessionSettings.Builder().usePrivateMode(true);
        if (desktop.isChecked()) {
            sb.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
              .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP);
        } else {
            sb.userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
              .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
        }
        session = new GeckoSession(sb.build());

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onTitleChange(GeckoSession s, String title) { note("title: " + title); }
            @Override public void onCrash(GeckoSession s) { note("the page process crashed"); }
            @Override public void onKill(GeckoSession s) { note("the page process was killed"); }
        });
        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession s, String url) { note("start: " + url); }
            @Override public void onPageStop(GeckoSession s, boolean ok) { note(ok ? "finished" : "stopped without success"); }
        });
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError e) {
                note("LOAD ERROR category " + e.category + " code " + e.code + " for " + uri);
                return null; // the engine shows its own error page
            }
            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                note("page asked for a new window: " + uri + " (opened here)");
                s.loadUri(uri);
                return null;
            }
        });
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public void onMediaPermissionRequest(GeckoSession s, String uri,
                    MediaSource[] video, MediaSource[] audio, MediaCallback cb) {
                note("camera/microphone request refused");
                cb.reject();
            }
            @Override
            public void onAndroidPermissionsRequest(GeckoSession s, String[] perms, Callback cb) {
                note("Android permission request refused");
                cb.reject();
            }
            @Override
            public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission p) {
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
        });

        session.open(runtime);
        view.setSession(session);
    }

    private void go() {
        String u = address.getText().toString().trim();
        if (u.isEmpty()) return;
        if (!u.contains("://")) u = "https://" + u;
        note("--- " + (desktop.isChecked() ? "desktop" : "phone") + " identity");
        newSession();
        session.loadUri(u);
        view.requestFocus();
    }

    @Override
    protected void onDestroy() {
        if (session != null) { view.releaseSession(); session.close(); }
        super.onDestroy();
    }
}
