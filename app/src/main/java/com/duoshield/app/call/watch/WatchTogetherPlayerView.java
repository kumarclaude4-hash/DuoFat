package com.duoshield.app.call.watch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * WebView host + Java↔JS bridge for the YouTube IFrame Player used by Watch Together.
 *
 * <p><strong>What it does.</strong> It loads {@code assets/watch_together/player.html}
 * (which embeds YouTube's officially supported IFrame Player API) and exposes a small,
 * typed command surface to Java — {@link #cue}, {@link #play}, {@link #pause},
 * {@link #seek}, {@link #setRate}, {@link #stop} — plus a {@link Listener} for the
 * JS→Java callbacks. This keeps every WebView / JavaScript detail in one place so
 * {@code WatchTogetherActivity} deals only in typed calls and never in strings.
 *
 * <p><strong>Media path.</strong> The video is fetched directly from YouTube by the page
 * running inside each participant's own WebView. It never crosses Firestore and never
 * crosses the WebRTC media path — only the tiny {@link WatchTogetherState} fields sync.
 *
 * <p><strong>Bridge contract.</strong> Must stay in sync with {@code player.html}:
 * <ul>
 *   <li>Java → JS ({@code evaluateJavascript}): {@code WT.cue/play/pause/seek/setRate/stop}.</li>
 *   <li>JS → Java ({@code @JavascriptInterface} on {@code DuoShieldWT}):
 *       {@code onPlayerReady}, {@code onPlayerStateChange}, {@code onPositionTick},
 *       {@code onPlaybackRateChange}, {@code onPlayerError}.</li>
 * </ul>
 *
 * <p><strong>Security.</strong> JavaScript is enabled only for this WebView. File and
 * content access are disabled, universal/file-URL access is disabled, and navigation is
 * pinned to YouTube origins. Only a {@link YouTubeUrlParser#isValidVideoId(String)
 * validated} 11-char video ID is ever passed to the page; a raw pasted URL is never
 * interpolated into JavaScript.
 *
 * <p><strong>Threading.</strong> {@code @JavascriptInterface} methods are invoked on a
 * private WebView thread, so every callback is marshalled to the main thread before it
 * reaches the {@link Listener} or touches the WebView.
 */
public class WatchTogetherPlayerView extends WebView {

    private static final String TAG = "WatchTogetherPlayer";

    /** Name the page uses for the injected bridge object: {@code DuoShieldWT.*}. */
    private static final String BRIDGE_NAME = "DuoShieldWT";

    /** loadDataWithBaseURL base so the IFrame API's origin check passes. */
    private static final String BASE_URL = "https://www.youtube.com";

    private static final String PLAYER_ASSET = "watch_together/player.html";

    /** YouTube IFrame player state code for "playing". */
    public static final int YT_STATE_PLAYING = 1;
    /** YouTube IFrame player state code for "ended". */
    public static final int YT_STATE_ENDED = 0;

    /** Callbacks delivered on the main thread. */
    public interface Listener {
        void onPlayerReady();
        void onPlayerStateChange(int ytState, long positionMs);
        void onPlaybackRateChange(double rate);
        void onPlayerError(int code);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private Listener listener;

    /** Most recent locally observed playback position, kept fresh by JS position ticks. */
    private volatile long lastKnownPositionMs = 0L;
    private volatile boolean lastKnownPlaying = false;

    public WatchTogetherPlayerView(Context context) {
        super(context);
        init();
    }

    public WatchTogetherPlayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WatchTogetherPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ── Setup ───────────────────────────────────────────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void init() {
        WebSettings s = getSettings();
        // The IFrame Player API is JavaScript; it cannot work without this.
        s.setJavaScriptEnabled(true);
        // Programmatic playVideo() must be allowed to start playback without a tap,
        // because playback is driven by the synchronized state, not by a user gesture.
        s.setMediaPlaybackRequiresUserGesture(false);
        // The IFrame API uses DOM storage.
        s.setDomStorageEnabled(true);
        // Lock the WebView down: no local file or content access of any kind.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);

        setBackgroundColor(0xFF000000);

        // Pin navigation to YouTube. Any attempt to navigate elsewhere is refused so the
        // player cannot be redirected into arbitrary web content.
        setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl() != null ? request.getUrl().getHost() : null;
                return !isYouTubeHost(host);
            }
        });

        // Expose the JS→Java bridge. Only the small, typed callback surface below is
        // reachable from the page.
        addJavascriptInterface(new Bridge(), BRIDGE_NAME);

        loadPlayerPage();
    }

    private static boolean isYouTubeHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.US);
        return host.equals("youtube.com")
                || host.endsWith(".youtube.com")
                || host.equals("youtu.be")
                || host.endsWith(".youtu.be")
                || host.endsWith(".ytimg.com")
                || host.endsWith(".ggpht.com")
                || host.endsWith(".googlevideo.com")
                || host.endsWith(".google.com")
                || host.endsWith(".gstatic.com")
                || host.endsWith(".doubleclick.net");
    }

    private void loadPlayerPage() {
        String html = readAsset();
        if (html == null) {
            Log.e(TAG, "Could not read " + PLAYER_ASSET + " — player will not load");
            return;
        }
        // Base URL youtube.com so the IFrame API sees a matching origin.
        loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null);
    }

    private String readAsset() {
        try (InputStream in = getContext().getAssets().open(PLAYER_ASSET);
             BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "readAsset failed: " + e.getMessage());
            return null;
        }
    }

    // ── Java → JS commands ────────────────────────────────────────────────────

    /**
     * Loads and positions a video. The {@code videoId} MUST already be validated by
     * {@link YouTubeUrlParser#isValidVideoId(String)}; this method re-checks and refuses
     * anything else so an unvalidated or malicious id can never reach the page.
     */
    public void cue(String videoId, long positionMs, boolean playing, double rate) {
        if (!YouTubeUrlParser.isValidVideoId(videoId)) {
            Log.w(TAG, "cue refused — invalid videoId");
            return;
        }
        // videoId is constrained to [A-Za-z0-9_-]{11}, so it is safe to embed in quotes.
        eval("WT.cue('" + videoId + "'," + Math.max(0L, positionMs) + ","
                + (playing ? "true" : "false") + "," + safeRate(rate) + ")");
    }

    public void play() {
        eval("WT.play()");
    }

    public void pause() {
        eval("WT.pause()");
    }

    public void seek(long positionMs) {
        eval("WT.seek(" + Math.max(0L, positionMs) + ")");
    }

    public void setRate(double rate) {
        eval("WT.setRate(" + safeRate(rate) + ")");
    }

    public void stop() {
        eval("WT.stop()");
    }

    /** Latest locally observed playback position, in ms. Used for drift comparison. */
    public long getLastKnownPositionMs() {
        return lastKnownPositionMs;
    }

    /** Whether the local player last reported that it was playing. */
    public boolean isLocallyPlaying() {
        return lastKnownPlaying;
    }

    private static double safeRate(double rate) {
        return rate > 0d ? rate : WatchTogetherState.DEFAULT_PLAYBACK_RATE;
    }

    private void eval(final String js) {
        main.post(() -> {
            try {
                evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.w(TAG, "eval failed (non-fatal): " + e.getMessage());
            }
        });
    }

    // ── JS → Java bridge ────────────────────────────────────────────────────

    private final class Bridge {

        @JavascriptInterface
        public void onPlayerReady() {
            main.post(() -> {
                if (listener != null) listener.onPlayerReady();
            });
        }

        @JavascriptInterface
        public void onPlayerStateChange(final int ytState, final double positionMs) {
            final long pos = (long) Math.max(0d, positionMs);
            lastKnownPositionMs = pos;
            lastKnownPlaying = ytState == YT_STATE_PLAYING;
            main.post(() -> {
                if (listener != null) listener.onPlayerStateChange(ytState, pos);
            });
        }

        @JavascriptInterface
        public void onPositionTick(final double positionMs, final boolean playing,
                                   final double rate) {
            lastKnownPositionMs = (long) Math.max(0d, positionMs);
            lastKnownPlaying = playing;
        }

        @JavascriptInterface
        public void onPlaybackRateChange(final double rate) {
            main.post(() -> {
                if (listener != null) listener.onPlaybackRateChange(rate);
            });
        }

        @JavascriptInterface
        public void onPlayerError(final int code) {
            main.post(() -> {
                if (listener != null) listener.onPlayerError(code);
            });
        }
    }
}
