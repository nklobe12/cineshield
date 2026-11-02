package com.klobe.cineshield;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final String HOME = "https://cinejoy.pk/";
    private static final long CURSOR_HIDE_MS = 4000;

    private final AdBlocker blocker = new AdBlocker();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private WebView web;
    private CursorView cursor;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private String guardJs;
    private boolean guardRunsEarly;
    private long lastBackAt;

    private boolean left, right, up, down;
    private int heldFrames;
    private long pressDownAt;

    private final Runnable hideCursor = () -> cursor.setVisibility(View.GONE);

    private final Runnable moveLoop = new Runnable() {
        @Override
        public void run() {
            if (!(left || right || up || down)) return;
            heldFrames++;
            float density = getResources().getDisplayMetrics().density;
            float step = Math.min(3f + heldFrames * 0.35f, 14f) * density;
            float x = cursor.px + ((right ? step : 0) - (left ? step : 0));
            float y = cursor.py + ((down ? step : 0) - (up ? step : 0));
            int w = root.getWidth() - 1;
            int h = root.getHeight() - 1;
            float edge = 12 * density;
            // Reaching the top or bottom of the screen scrolls the page.
            if (up && y <= edge) sendWheel(1f);
            if (down && y >= h - edge) sendWheel(-1f);
            x = Math.max(0, Math.min(w, x));
            y = Math.max(0, Math.min(h, y));
            cursor.moveTo(x, y);
            sendMouse(MotionEvent.ACTION_HOVER_MOVE, 0f);
            ui.postDelayed(this, 16);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        blocker.start(this);
        guardJs = readAsset("guard.js");

        root = new FrameLayout(this);
        web = new WebView(this);
        cursor = new CursorView(this);
        cursor.setFocusable(false);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        root.addView(cursor, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Popups: nothing opens a window without passing onCreateWindow below.
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "")
                .replaceAll("Version/[0-9.]+ ", ""));

        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new ShieldClient());
        web.setWebChromeClient(new ShieldChrome());
        web.setDownloadListener((url, ua, cd, mime, len) -> notice("Blocked a download"));

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, guardJs, Collections.singleton("*"));
            guardRunsEarly = true;
        }

        root.post(() -> {
            cursor.moveTo(root.getWidth() / 2f, root.getHeight() / 2f);
            showCursor();
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void notice(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static boolean isWeb(Uri uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                new ByteArrayInputStream(new byte[0]));
    }

    private final class ShieldClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String host = uri.getHost();
            if (request.isForMainFrame()) {
                if (isWeb(uri) && AdBlocker.isSite(host)) return false;
                notice("Blocked redirect" + (host == null ? "" : ": " + host));
                return true;
            }
            // Video players live in third-party frames, so those may load unless they
            // are ad hosts. Their attempts to take over the whole page hit the rule above.
            if (isWeb(uri)) return blocker.isAd(host);
            String scheme = uri.getScheme();
            return !("about".equals(scheme) || "blob".equals(scheme) || "data".equals(scheme));
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!isWeb(uri)) return null;
            String host = uri.getHost();
            if (request.isForMainFrame() && !AdBlocker.isSite(host)) return emptyResponse();
            return blocker.isAd(host) ? emptyResponse() : null;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            Uri uri = Uri.parse(url);
            if (isWeb(uri) && !AdBlocker.isSite(uri.getHost())) {
                view.stopLoading();
                if (view.canGoBack()) view.goBack();
                else view.loadUrl(HOME);
                return;
            }
            if (!guardRunsEarly) view.evaluateJavascript(guardJs, null);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame() && AdBlocker.isSite(request.getUrl().getHost())) {
                notice("Can't reach the site. Press Menu to retry.");
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Rebuild the screen rather than letting a dead renderer close the app.
            recreate();
            return true;
        }
    }

    private final class ShieldChrome extends WebChromeClient {
        /**
         * A new-window request is only honoured when it came from a real click and leads
         * to the site. A throwaway WebView reveals where it was headed.
         */
        @SuppressLint("SetJavaScriptEnabled")
        @Override
        public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message result) {
            if (!userGesture) {
                notice("Blocked popup");
                return false;
            }
            final WebView probe = new WebView(MainActivity.this);
            probe.setWebViewClient(new WebViewClient() {
                private boolean handled;

                private void decide(Uri uri) {
                    if (handled) return;
                    handled = true;
                    if (isWeb(uri) && AdBlocker.isSite(uri.getHost())) web.loadUrl(uri.toString());
                    else notice("Blocked popup");
                    ui.post(probe::destroy);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    decide(r.getUrl());
                    return true;
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    return emptyResponse();
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) result.obj;
            transport.setWebView(probe);
            result.sendToTarget();
            return true;
        }

        @Override
        public boolean onJsAlert(WebView v, String url, String message, JsResult r) {
            r.cancel();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView v, String url, String message, JsResult r) {
            r.cancel();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView v, String url, String message, String def, JsPromptResult r) {
            r.cancel();
            return true;
        }

        @Override
        public boolean onJsBeforeUnload(WebView v, String url, String message, JsResult r) {
            r.confirm();
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            cb.invoke(origin, false, false);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden();
                return;
            }
            fullscreenView = view;
            fullscreenCallback = callback;
            web.setVisibility(View.GONE);
            root.addView(view, 1, new FrameLayout.LayoutParams(-1, -1));
        }

        @Override
        public void onHideCustomView() {
            if (fullscreenView == null) return;
            root.removeView(fullscreenView);
            fullscreenView = null;
            fullscreenCallback = null;
            web.setVisibility(View.VISIBLE);
            web.requestFocus();
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    private void showCursor() {
        cursor.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideCursor);
        ui.postDelayed(hideCursor, CURSOR_HIDE_MS);
    }

    private View pointerTarget() {
        return fullscreenView != null ? fullscreenView : web;
    }

    private MotionEvent mouseEvent(int action, long now, float wheel) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = cursor.px;
        coords.y = cursor.py;
        coords.pressure = 1f;
        coords.size = 1f;
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, wheel);
        return MotionEvent.obtain(now, now, action, 1,
                new MotionEvent.PointerProperties[]{props},
                new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    private void sendMouse(int action, float wheel) {
        MotionEvent e = mouseEvent(action, SystemClock.uptimeMillis(), wheel);
        pointerTarget().dispatchGenericMotionEvent(e);
        e.recycle();
    }

    private void sendWheel(float wheel) {
        sendMouse(MotionEvent.ACTION_SCROLL, wheel);
    }

    private void sendTouch(int action) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) pressDownAt = now;
        MotionEvent e = MotionEvent.obtain(pressDownAt, now, action, cursor.px, cursor.py, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        pointerTarget().dispatchTouchEvent(e);
        e.recycle();
    }

    private void setHeld(int keyCode, boolean held) {
        boolean wasMoving = left || right || up || down;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) left = held;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) right = held;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) up = held;
        else down = held;
        if (held && !wasMoving) {
            heldFrames = 0;
            ui.post(moveLoop);
        }
        showCursor();
    }

    /** Reaches the player even inside a cross-origin frame. See guard.js. */
    private void sendPlayerCommand(String command, int seconds) {
        web.evaluateJavascript("(function f(w){try{w.postMessage({cineshield:'" + command + "',s:"
                + seconds + "},'*')}catch(e){}for(var i=0;i<w.frames.length;i++)f(w.frames[i])})(window)",
                null);
    }

    private void onBack() {
        if (fullscreenView != null) {
            if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        } else if (web.canGoBack()) {
            web.goBack();
        } else if (SystemClock.uptimeMillis() - lastBackAt < 2500) {
            finish();
        } else {
            lastBackAt = SystemClock.uptimeMillis();
            notice("Press Back again to exit");
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int key = e.getKeyCode();
        boolean isDown = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = isDown && e.getRepeatCount() == 0;
        boolean isUp = e.getAction() == KeyEvent.ACTION_UP;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (first) setHeld(key, true);
                else if (isUp) setHeld(key, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (first) {
                    showCursor();
                    sendTouch(MotionEvent.ACTION_DOWN);
                } else if (isUp) {
                    sendTouch(MotionEvent.ACTION_UP);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (isUp) onBack();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (isUp) web.loadUrl(HOME);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (first) sendPlayerCommand("toggle", 0);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (isDown) sendPlayerCommand("seek", 30);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (isDown) sendPlayerCommand("seek", -30);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
