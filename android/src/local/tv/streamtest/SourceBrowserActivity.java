package local.tv.streamtest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import org.json.JSONTokener;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** User-operated browser fallback. No challenge automation, cookie export or JS bridge. */
public final class SourceBrowserActivity extends Activity {
    private WebView web;
    private FrameLayout root, page;
    private LinearLayout content, toolbar, mediaControls;
    private boolean mediaReady;
    private final Runnable hideMedia = () -> { if (mediaControls != null) { boolean focused = mediaControls.hasFocus(); mediaControls.setVisibility(View.GONE); if (focused && web != null) web.requestFocus(); } };
    private TextView message;
    private Pointer pointer;
    private boolean pointerMode;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private String address;
    private final Handler inspector = new Handler();
    private int pageGeneration;

    static boolean allowed(String value) {
        return PlayerProtocol.browserAllowed(value) || providerAllowed(value);
    }
    static boolean providerAllowed(String value) {
        if (value == null) return false;
        try {
            java.net.URI uri = new java.net.URI(value);
            return "https".equals(uri.getScheme()) && "vidnest.fun".equals(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getPath().matches("/(?:movie/[0-9]+|tv/[0-9]+/[0-9]+/[0-9]+)/?");
        } catch (Exception error) { return false; }
    }
    /** Ad networks seen on the player pages; their hostnames rotate, so the loader proxy is matched by shape. */
    private static final String[] AD_HOSTS = {"chiripaethenes.com", "omg10.com", "rtmark.net", "4armn.com", "wvdme.com",
        "daleelerah.info", "nresystems.com", "googletagmanager.com", "google-analytics.com", "doubleclick.net", "googlesyndication.com"};
    /** True for the ad loader and known ad networks; the page, its player and the video stream are left alone. */
    static boolean adRequest(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            // VidNest loads every overlay, pop-up and redirect through one script fetched via a workers.dev proxy.
            if (host.endsWith(".workers.dev") && uri.getPath() != null && uri.getPath().startsWith("/fetch")
                && uri.getRawQuery() != null && uri.getRawQuery().contains("url=")) return true;
            for (String ad : AD_HOSTS) if (host.equals(ad) || host.endsWith("." + ad)) return true;
            return false;
        } catch (Exception error) { return false; }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        address = getIntent().getStringExtra("address");
        if (!allowed(address)) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this); toolbar = bar;
        addButton(bar, "Back", this::finish);
        addButton(bar, "Reload", () -> { message.setText("Loading source page…"); web.reload(); });
        addButton(bar, "Pointer", () -> {
            pointerMode = !pointerMode; pointer.setVisibility(pointerMode ? View.VISIBLE : View.GONE);
            message.setText(pointerMode ? "Arrows move pointer · OK clicks · Menu returns to controls" : "Complete any site verification, then use its player. Menu opens controls.");
            web.requestFocus();
        });
        addButton(bar, "Open in browser", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
            catch (android.content.ActivityNotFoundException error) { message.setText("No external browser is installed on this TV."); }
        });
        if (PlayerProtocol.browserAllowed(address)) addButton(bar, "Video server", this::chooseVideoServer);
        content.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
        message = new TextView(this); message.setTextColor(Color.WHITE); message.setTextSize(14);
        message.setPadding(dp(16), dp(4), dp(16), dp(8));
        message.setText("Complete any site verification, then use its player. Browser playback uses the site's controls and resume.");
        content.addView(message);
        page = new FrameLayout(this); web = new WebView(this);
        web.setDefaultFocusHighlightEnabled(false);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true);
        // Entering playback is already an explicit user action in GOLOVINEMA.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(true); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        // Keep the actual WebView user agent and standard certificate validation.
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                boolean blocked = !allowed(request.getUrl().toString());
                if (blocked) message.setText("This page tried to open a different site. Back returns to GOLOVINEMA.");
                return blocked;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!adRequest(request.getUrl().toString())) return null;
                Log.i("Cinema", "Blocked ad request host=" + request.getUrl().getHost());
                return new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    message.setText("Page could not load. Try Reload or Open in browser.");
                    Log.w("Cinema", "Source browser load failed: " + error.getErrorCode());
                }
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                pageGeneration++; mediaReady = false; inspector.removeCallbacksAndMessages(null);
            }
            @Override public void onPageFinished(WebView view, String url) {
                Log.i("Cinema", "Source browser loaded host=" + Uri.parse(url).getHost());
                if (PlayerProtocol.browserAllowed(url)) { diagnosePlayer(pageGeneration, 0); openSupportedProvider(pageGeneration, 0); }
                if (providerAllowed(url)) observeMedia(pageGeneration, 0);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreen != null) { callback.onCustomViewHidden(); return; }
                fullscreen = view; fullscreenCallback = callback;
                content.setVisibility(View.GONE); root.addView(view, new FrameLayout.LayoutParams(-1, -1)); view.requestFocus();
            }
            @Override public void onHideCustomView() { exitFullscreen(); }
        });
        page.addView(web, new FrameLayout.LayoutParams(-1, -1));
        pointer = new Pointer(); pointer.setVisibility(View.GONE); page.addView(pointer, new FrameLayout.LayoutParams(-1, -1));
        content.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        mediaControls = new LinearLayout(this); mediaControls.setBackgroundColor(0xDD101828);
        mediaControls.setPadding(dp(24), dp(8), dp(24), dp(8));
        addButton(mediaControls, "−30s", () -> controlMedia(-30));
        addButton(mediaControls, "Play / Pause", () -> controlMedia(0));
        addButton(mediaControls, "+30s", () -> controlMedia(30));
        mediaControls.setVisibility(View.GONE);
        root.addView(mediaControls, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM));
        setContentView(root);
        if (PlayerProtocol.browserAllowed(address)) {
            message.setText("Menu opens controls and Video server selection");
            toolbar.setVisibility(View.GONE); message.setVisibility(View.GONE);
            android.widget.Toast.makeText(this, "Menu: video server and browser controls", android.widget.Toast.LENGTH_LONG).show();
        }
        web.loadUrl(address); web.requestFocus();
    }
    private void openSupportedProvider(int token, int attempt) {
        if (isFinishing() || token != pageGeneration) return;
        web.evaluateJavascript("(function(){var f=document.querySelector('#video-iframe');return f?f.src:''})()", value -> {
            if (isFinishing() || token != pageGeneration) return;
            try {
                Object url = new JSONTokener(value).nextValue();
                if (url instanceof String && providerAllowed((String)url)) {
                    Log.i("Cinema", "Opening VidNest directly for remote media controls");
                    web.loadUrl((String)url); return;
                }
            } catch (Exception ignored) { }
            if (attempt < 20) inspector.postDelayed(() -> openSupportedProvider(token, attempt + 1), 1000);
        });
    }
    private void observeMedia(int token, int attempt) {
        if (isFinishing() || token != pageGeneration) return;
        web.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v||v.readyState<1)return false;"
            + "v.style.outline='none';v.style.border='none';return true})()", value -> {
            if (isFinishing() || token != pageGeneration) return;
            if ("true".equals(value)) {
                mediaReady = true;
                Log.i("Cinema", "Browser video metadata ready; remote media controls enabled");
                android.widget.Toast.makeText(this, "Left / Right: 30 seconds · OK: pause · Up: controls", android.widget.Toast.LENGTH_LONG).show();
                web.requestFocus(); return;
            }
            if (attempt < 60) inspector.postDelayed(() -> observeMedia(token, attempt + 1), 1000);
        });
    }
    private void controlMedia(int seconds) {
        if (!mediaReady || !providerAllowed(web.getUrl())) return;
        String action = seconds == 0 ? "if(v.paused){v.play().catch(function(){})}else{v.pause()}"
            : "if(!v.seekable.length)return false;var t=v.currentTime+(" + seconds + ");"
                + "v.currentTime=Math.max(v.seekable.start(0),Math.min(v.seekable.end(v.seekable.length-1),t));";
        web.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return false;" + action + "return true})()", value -> {
            if (!"true".equals(value)) android.widget.Toast.makeText(this, "Video is not ready to seek yet", android.widget.Toast.LENGTH_SHORT).show();
        });
        inspector.removeCallbacks(hideMedia); inspector.postDelayed(hideMedia, 4000);
    }
    private void chooseVideoServer() {
        new AlertDialog.Builder(this).setTitle("Server inside the website player")
            .setItems(new String[]{"Server 1", "Server 2", "Server 3", "Server 4", "Server 5"}, (dialog, which) -> {
                Uri original = Uri.parse(address);
                Uri.Builder target = original.buildUpon().clearQuery();
                for (String key : original.getQueryParameterNames())
                    if (!key.equals("sv")) target.appendQueryParameter(key, original.getQueryParameter(key));
                target.appendQueryParameter("sv", Integer.toString(which + 1));
                address = target.build().toString();
                toolbar.setVisibility(View.GONE); message.setVisibility(View.GONE);
                web.loadUrl(address); web.requestFocus();
                Log.i("Cinema", "Browser inner server selected: " + (which + 1));
            }).setNegativeButton("Cancel", null).show();
    }
    private void diagnosePlayer(int token, int attempt) {
        if (web == null || isFinishing() || token != pageGeneration) return;
        web.evaluateJavascript("(function(){var v=document.querySelector('video'),f=document.querySelector('#all-failed');"
            + "var frame=document.querySelector('#video-iframe'),r=frame?frame.getBoundingClientRect():null;"
            + "return JSON.stringify({viewport:[innerWidth,innerHeight],frameSize:r?[r.width,r.height]:null,frameHost:frame?new URL(frame.src||location.href).hostname:null,frames:document.querySelectorAll('iframe').length,video:!!v,paused:v?v.paused:null,"
            + "ready:v?v.readyState:null,error:v&&v.error?v.error.code:null,"
            + "providerFailed:!!f&&getComputedStyle(f).display!=='none'&&getComputedStyle(f).visibility!=='hidden'});})()", value -> {
            if (isFinishing() || token != pageGeneration) return;
            Log.i("Cinema", "Browser player state (outer page only): " + value);
            if (attempt < 3) inspector.postDelayed(() -> diagnosePlayer(token, attempt + 1), 5000);
        });
    }
    private void addButton(LinearLayout row, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run()); row.addView(button);
    }
    private void exitFullscreen() {
        if (fullscreen == null) return;
        root.removeView(fullscreen); fullscreen = null; content.setVisibility(View.VISIBLE);
        WebChromeClient.CustomViewCallback callback = fullscreenCallback; fullscreenCallback = null;
        if (callback != null) callback.onCustomViewHidden(); web.requestFocus();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_DOWN) {
            exitFullscreen(); boolean show = toolbar.getVisibility() != View.VISIBLE;
            toolbar.setVisibility(show ? View.VISIBLE : View.GONE); message.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) toolbar.getChildAt(0).requestFocus(); else web.requestFocus(); return true;
        }
        if (mediaReady && providerAllowed(web.getUrl()) && !pointerMode && toolbar.getVisibility() != View.VISIBLE) {
            boolean control = key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            boolean pageKey = (web.hasFocus() || fullscreen != null) && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER);
            if (control || pageKey) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                    controlMedia(key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND ? -30 : key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 30 : 0);
                return true;
            }
            if (web.hasFocus() && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) { mediaControls.setVisibility(View.VISIBLE); mediaControls.getChildAt(1).requestFocus(); }
                return true;
            }
        }
        if (pointerMode && fullscreen == null && web.hasFocus()) {
            boolean arrow = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
            boolean click = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
            if (arrow || click) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (click && event.getRepeatCount() == 0) pointer.click();
                    else if (arrow) pointer.move(key);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if (fullscreen != null) exitFullscreen();
        else if (mediaReady && (toolbar.getVisibility() == View.VISIBLE || mediaControls.getVisibility() == View.VISIBLE)) {
            toolbar.setVisibility(View.GONE); message.setVisibility(View.GONE); mediaControls.setVisibility(View.GONE); web.requestFocus();
        } else finish();
    }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onDestroy() {
        inspector.removeCallbacksAndMessages(null); pageGeneration++;
        exitFullscreen();
        if (web != null) { web.stopLoading(); web.loadUrl("about:blank"); ((ViewGroup)web.getParent()).removeView(web); web.destroy(); }
        super.onDestroy();
    }
    private final class Pointer extends View {
        private float x, y;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Pointer() { super(SourceBrowserActivity.this); setFocusable(false); setClickable(false); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { x = w / 2f; y = h / 2f; }
        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3)); paint.setColor(Color.BLACK); canvas.drawCircle(x, y, dp(9), paint);
            paint.setStrokeWidth(dp(2)); paint.setColor(Color.YELLOW); canvas.drawCircle(x, y, dp(7), paint);
        }
        void move(int key) {
            int step = dp(12);
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) x -= step;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) x += step;
            if (key == KeyEvent.KEYCODE_DPAD_UP) { if (y <= step) web.scrollBy(0, -dp(60)); y -= step; }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (y >= getHeight() - step) web.scrollBy(0, dp(60)); y += step; }
            x = Math.max(1, Math.min(getWidth() - 1, x)); y = Math.max(1, Math.min(getHeight() - 1, y)); invalidate();
        }
        void click() {
            long time = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, x, y, 0);
            web.dispatchTouchEvent(down); web.dispatchTouchEvent(up); down.recycle(); up.recycle();
            pointerMode = false; setVisibility(View.GONE);
            message.setText("Pointer off · Menu opens controls");
        }
    }
}
