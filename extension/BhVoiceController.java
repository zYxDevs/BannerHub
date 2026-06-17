package com.xj.winemu.sidebar;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebSettings;

/**
 * WebRTC voice-call engine for the in-game BannerHub voice pill.
 *
 * <p>Ported from the v6 (GameHub 6.0.8) BhVoiceController, with the Steam
 * identity layer removed: instead of SteamID64 pairs resolved from the Steam
 * friends list, callers identify themselves with a self-chosen <b>nickname</b>
 * plus a stable, locally-generated <b>client id</b>, and reach each other via a
 * shared <b>room code</b> (or shared link). No Steam account, friends list, or
 * login is involved — which is exactly why this ports cleanly to the 5.3.5 base.
 *
 * <p>The call runs inside an attached (1×1, invisible) {@link WebView} that loads
 * a page served from our bannerhub-api worker at a <b>real https origin</b>
 * ({@code /voice/room}). Earlier v6 builds used {@code loadDataWithBaseURL},
 * whose opaque origin made Chromium refuse microphone access
 * ({@code getUserMedia} hung forever); serving from a real origin fixes that.
 * The hosted page owns the whole call — {@code getUserMedia} +
 * {@code RTCPeerConnection} mesh — with SDP/ICE relayed through the worker's
 * R2-backed mailbox (keyed by room code) and STUN/TURN from {@code /voice/turn}
 * (Cloudflare Realtime). Java only attaches the WebView, surfaces call state to
 * the pill through the {@code BhVoice} JS bridge, and can mute / hang up.
 *
 * <p>The exact same worker + signalling + TURN that v6 uses are reused verbatim,
 * so a 3.7.5 nickname user and a v6 Steam user can share one room and talk: the
 * mesh connects purely by peer-id + room, with no friendship check.
 */
public final class BhVoiceController {

    private static final String TAG = "BhVoice";
    private static final String BASE = "https://bannerhub-api.the412banner.workers.dev";

    /** WebView (Chromium) major versions below this can't reliably open the mic
     *  for WebRTC inside an embedded WebView — observed on the v6 device: 113
     *  hangs getUserMedia forever while Chrome 149 on the same device works.
     *  Below the threshold we skip the embedded WebView and open the room in the
     *  external browser instead. */
    private static final int MIN_WEBVIEW_MAJOR = 120;

    /** Pill hook: surface call-state changes + the live participant roster. */
    public interface Host {
        /** state = calling / connecting / in-call / external / ended / failed. */
        void onVoiceState(String state, String detail);
        /** Live participant roster: comma-separated client ids, including self.
         *  (Same format v6 uses — kept stable so the shared worker page doesn't
         *  regress v6.) */
        void onVoiceRoster(String idsCsv);
        /** Optional id→nickname map (JSON object) for the roster. v6's bridge has
         *  no such method, so the worker page only calls it for this client. */
        void onVoiceRosterNames(String namesJson);
    }

    private final Activity act;
    private final String roomCode;
    private final String selfId;    // stable local client id (UUID), not a SteamID
    private final String nickname;  // user-chosen display name
    private final Host host;
    private WebView web;
    private ViewGroup webDecor;
    private boolean webAttached;
    private boolean muted;
    private volatile boolean ended;
    private String roomUrl;            // the /voice/room URL for this call
    private boolean fellBackToBrowser; // already escalated to the external browser

    public BhVoiceController(Activity act, String roomCode, String selfId, String nickname, Host host) {
        this.act = act;
        this.roomCode = roomCode;
        this.selfId = selfId;
        this.nickname = nickname == null ? "" : nickname;
        this.host = host;
    }

    public String roomCode() { return roomCode; }

    /** Begin the call: attach the WebView and load the hosted room page, which
     *  opens the mic and drives the SDP/ICE mesh itself. The page reports state
     *  back via the {@code BhVoice} bridge. Must run on the UI thread. */
    @SuppressWarnings({"SetJavaScriptEnabled"})
    public void start() {
        // peer is intentionally omitted: the worker page joins the room and
        // discovers peers via the roster, building a full mesh. self carries our
        // stable client id; name carries the nickname shown in everyone's roster.
        roomUrl = BASE + "/voice/room?room=" + enc(roomCode)
                + "&self=" + enc(selfId)
                + "&name=" + enc(nickname);

        // Some devices ship an ancient System WebView (e.g. 113) whose embedded
        // WebRTC can't open the mic — getUserMedia hangs forever. Detect that up
        // front and run the call in the external browser instead.
        int wv = webViewMajor();
        Log.i(TAG, "voice: webview major=" + wv + " (min " + MIN_WEBVIEW_MAJOR + ")");
        if (wv > 0 && wv < MIN_WEBVIEW_MAJOR) {
            Log.i(TAG, "voice: webview too old, opening call in browser");
            openInBrowser();
            return;
        }

        try {
            web = new WebView(act);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setDomStorageEnabled(true);
            web.setWebChromeClient(new WebChromeClient() {
                @Override public void onPermissionRequest(final PermissionRequest req) {
                    // Grant the page's mic request (app-level RECORD_AUDIO is
                    // requested separately before a call starts).
                    act.runOnUiThread(new Runnable() { public void run() {
                        try { req.grant(req.getResources()); } catch (Throwable ignored) {}
                    }});
                }
            });
            web.addJavascriptInterface(new Bridge(), "BhVoice");
            // The WebView MUST be attached to a window or Chromium backgrounds the
            // page and getUserMedia never resolves; attach it 1×1 and invisible.
            attachHeadless();
            web.loadUrl(roomUrl);
        } catch (Throwable t) {
            Log.w(TAG, "voice start failed", t);
            host.onVoiceState("ended", "init failed");
            cleanup();
        }
    }

    /** Hand the call off to the device's default browser (which uses an
     *  up-to-date Chromium that can open the mic). Used when the embedded
     *  WebView is too old, or as a backstop when its mic request times out. */
    private void openInBrowser() {
        if (fellBackToBrowser) return;
        fellBackToBrowser = true;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(roomUrl));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            host.onVoiceState("external", "");
        } catch (Throwable t) {
            Log.w(TAG, "voice browser fallback failed", t);
            host.onVoiceState("ended", "no browser for voice");
        }
        cleanup();
    }

    /** Major version of the active System WebView (Chromium), or -1 if unknown. */
    private int webViewMajor() {
        try {
            PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi == null || pi.versionName == null) return -1;
            String v = pi.versionName.trim();
            int dot = v.indexOf('.');
            return Integer.parseInt(dot > 0 ? v.substring(0, dot) : v);
        } catch (Throwable t) {
            return -1;
        }
    }

    public void setMuted(boolean m) {
        muted = m;
        runJs("bhSetMuted(" + (m ? "true" : "false") + ")");
        host.onVoiceState("in-call", m ? "muted" : "");
    }

    public boolean isMuted() { return muted; }

    public void hangup() {
        if (ended) return;
        runJs("bhHangup()");   // the page posts a bye into the peers' mailboxes
        host.onVoiceState("ended", "");
        cleanup();
    }

    /** Add the WebView to the activity DecorView as a 1×1 view so Chromium treats
     *  the page as foreground and mic capture can resolve. Uses DecorView (the
     *  proven overlay path on the 5.3.5 :wine WineActivity) rather than a separate
     *  TYPE_APPLICATION_PANEL window, which doesn't render over the Wine surface
     *  on this base. UI thread. */
    private void attachHeadless() {
        try {
            Window win = act.getWindow();
            webDecor = win != null ? (ViewGroup) win.getDecorView() : null;
            if (webDecor == null) { Log.w(TAG, "voice webview: no decor view"); return; }
            webDecor.addView(web, new FrameLayout.LayoutParams(1, 1));
            webAttached = true;
        } catch (Throwable t) {
            Log.w(TAG, "voice webview attach failed", t);
        }
    }

    private void cleanup() {
        ended = true;
        final WebView w = web;
        final boolean wasAttached = webAttached;
        final ViewGroup d = webDecor;
        web = null;
        webDecor = null;
        webAttached = false;
        if (w == null) return;
        act.runOnUiThread(new Runnable() { public void run() {
            if (wasAttached && d != null) {
                try { d.removeView(w); } catch (Throwable ignored) {}
            }
            try { w.loadUrl("about:blank"); w.removeAllViews(); w.destroy(); } catch (Throwable ignored) {}
        }});
    }

    private void runJs(final String js) {
        final WebView w = web;
        if (w == null) return;
        act.runOnUiThread(new Runnable() { public void run() {
            try { w.evaluateJavascript(js, null); } catch (Throwable ignored) {}
        }});
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Throwable t) { return s; }
    }

    // ── JS → Java bridge ──────────────────────────────────────────────────────
    private final class Bridge {
        /** Page lifecycle: calling / connecting / in-call / failed / ended. */
        @JavascriptInterface public void state(String st, String detail) {
            if ("in-call".equals(st)) host.onVoiceState("in-call", detail == null ? "" : detail);
            else if ("failed".equals(st)) {
                // If the embedded mic capture failed/timed out, the WebView's
                // WebRTC is the culprit — retry the whole call in the browser
                // rather than just giving up.
                String d = detail == null ? "" : detail;
                if (!fellBackToBrowser && d.toLowerCase().contains("mic")) {
                    act.runOnUiThread(new Runnable() { public void run() { openInBrowser(); } });
                } else {
                    host.onVoiceState("ended", d.isEmpty() ? "failed" : d); cleanup();
                }
            }
            else if ("ended".equals(st)) { host.onVoiceState("ended", detail == null ? "" : detail); cleanup(); }
            else host.onVoiceState(st, detail == null ? "" : detail);
        }
        /** Live roster from the mesh page: comma-separated client ids (incl. self). */
        @JavascriptInterface public void roster(String idsCsv) {
            host.onVoiceRoster(idsCsv == null ? "" : idsCsv);
        }
        /** id→nickname map (JSON) so the pill can label peers by their chosen name. */
        @JavascriptInterface public void rosterNames(String json) {
            host.onVoiceRosterNames(json == null ? "" : json);
        }
        @JavascriptInterface public void log(String m) { Log.i(TAG, "voicejs: " + m); }
    }
}
