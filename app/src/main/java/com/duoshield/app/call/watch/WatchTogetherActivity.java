package com.duoshield.app.call.watch;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.R;
import com.duoshield.app.util.YouTubeSearchClient;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.List;

/**
 * Ephemeral in-call <strong>Watch Together</strong> screen.
 *
 * <p>Two participants already in a DuoShield call can watch the same YouTube video in
 * sync. Each device loads the video <em>directly from YouTube</em> inside its own
 * {@link WatchTogetherPlayerView}; the video never crosses Firestore and never crosses
 * the WebRTC media path. Only the small {@link WatchTogetherState} fields sync, through a
 * single Firestore document at {@code calls/{callId}/watch/state} — the same signaling
 * mechanism the call already uses. {@code CallManager} and the WebRTC path are untouched.
 *
 * <p>Uses {@link AppCompatActivity} (not {@code BaseActivity}) for the same reasons
 * documented on {@code InCallChatActivity}:
 * <ul>
 *   <li>The user is already authenticated — they passed the full call setup flow.</li>
 *   <li>The session state is ephemeral; nothing persistent or sensitive is at risk.</li>
 *   <li>Triggering the app-lock redirect mid-call would disrupt an active call.</li>
 * </ul>
 *
 * <p><strong>Sync model.</strong> Exactly one snapshot listener is attached in
 * {@link #onStart()} and removed in {@link #onStop()} (project rule #3). All Firestore
 * reads/writes go through {@link WatchTogetherRepository}, which gates every op with
 * {@code FirebaseCostGuard} (project rule #2). Local control actions are written to
 * Firestore and then applied to the local player; remote snapshots are applied to the
 * local player but never written back — the player's own {@code controls} are disabled,
 * so there is no source of an accidental write-back feedback loop.
 */
public class WatchTogetherActivity extends AppCompatActivity
        implements WatchTogetherPlayerView.Listener {

    private static final String TAG = "WatchTogetherActivity";

    /** Intent extras — set by CallActivity when opening this screen. */
    public static final String EXTRA_CALL_ID      = "watch_call_id";
    public static final String EXTRA_MY_UID       = "watch_my_uid";
    public static final String EXTRA_PARTNER_NAME = "watch_partner_name";

    /** Skip amount for the rewind / forward controls. */
    private static final long SEEK_STEP_MS = 10_000L;

    /** Cycle of selectable playback speeds for {@link #btnPlaybackRate}. */
    private static final double[] RATE_STEPS = {0.5d, 0.75d, 1.0d, 1.25d, 1.5d, 2.0d};

    /**
     * Grace period after cueing a video during which drift correction is suppressed, giving
     * the embed time to load, buffer and start reporting real positions. Long enough to cover
     * a slow network load, short enough that genuine drift is still corrected promptly by the
     * heartbeat that follows.
     */
    private static final long CUE_SETTLE_MS = 5_000L;

    /**
     * Shown in place of the player when YouTube refuses the video on this device. Deliberately
     * phrased around "here", because the usual cause is error 101/150 — the owner disallowed
     * embedded playback — which is not a broken link and not something a retry fixes.
     */
    private static final String PLAYER_ERROR_MESSAGE =
            "This video can't be played inside Watch Together. Pick another video to keep watching together.";

    private String callId;
    private String myUid;
    private String partnerName;

    private WatchTogetherRepository repo;
    private ListenerRegistration stateListener;

    private WatchTogetherPlayerView player;
    private TextView  tvPlaceholder;
    private TextView  tvStatus;
    private ImageView btnPlayPause;
    private Button    btnPlaybackRate;
    private View      controlsRow;
    private EditText  etUrl;

    // ── YouTube search (picker) views ──
    private View         searchPanel;
    private RecyclerView rvResults;
    private ProgressBar  searchProgress;
    private View         searchMessageBox;
    private TextView     tvSearchMessage;
    private Button       btnSearchRetry;
    private YouTubeSearchAdapter searchAdapter;

    /**
     * Search UI state. Pure logic, unit tested; the Activity only renders it.
     *
     * <p>Search adds no Firestore reads or writes of its own — it talks to the push server
     * over HTTPS and, on selection, reuses the one existing
     * {@link #performLocalWrite(String, WatchTogetherState)} path. So the single-listener rule
     * (#3) and the {@code FirebaseCostGuard} gating (#2) are untouched by this feature.
     */
    private final YouTubeSearchState searchState = new YouTubeSearchState();

    /** Debounce timer for search-as-you-type. Cancelled on every keystroke and in onDestroy. */
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    /** Set while the Activity is editing {@link #etUrl} itself, to mute the TextWatcher. */
    private boolean suppressTextWatcher;

    /** The most recently applied state and the local monotonic time it was applied. */
    private WatchTogetherState appliedState;
    private long appliedReceiptRealtime;

    /** The video ID currently cued into the WebView, so we only reload on a real change. */
    private String loadedVideoId;

    /**
     * {@link SystemClock#elapsedRealtime()} before which drift correction is skipped, set
     * whenever a video is cued.
     *
     * <p>A freshly cued embed reports position 0 until it has loaded and buffered, which is
     * indistinguishable from "hopelessly behind" to the drift check. Correcting against that
     * re-seeks a player that has not started yet, which restarts buffering and can loop.
     */
    private long suppressDriftUntilRealtime;

    /**
     * The video ID the local player refused to play, if any.
     *
     * <p>Remembered per-video because reconcile() runs on every heartbeat: without this, the
     * "can't be played" overlay would be wiped a second later by the next showActiveUi(),
     * exposing YouTube's error card again. Cleared as soon as a different video is cued.
     */
    private String erroredVideoId;

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            maybeWriteHeartbeat();
            heartbeatHandler.postDelayed(this, WatchTogetherState.HEARTBEAT_INTERVAL_MS);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch_together);

        callId      = getIntent().getStringExtra(EXTRA_CALL_ID);
        myUid       = getIntent().getStringExtra(EXTRA_MY_UID);
        partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        if (partnerName == null) partnerName = "your partner";

        if (callId == null || myUid == null) {
            Log.e(TAG, "Missing callId or myUid — closing Watch Together");
            finish();
            return;
        }

        repo = new WatchTogetherRepository(this);
        bindViews();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Exactly one snapshot listener, owned by this screen (project rule #3).
        if (repo != null && stateListener == null) {
            stateListener = repo.listenToState(callId, this::onStateSnapshot);
        }
        heartbeatHandler.postDelayed(heartbeatRunnable, WatchTogetherState.HEARTBEAT_INTERVAL_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.onResume();

        // Re-sync after returning from the background.
        //
        // onPause() calls WebView.onPause(), which freezes this device's playback while
        // the peer keeps watching. Coming back, the snapshot listener alone is NOT enough
        // to recover: if THIS device performed the last action, nothing advanced `seq`
        // while we were away (the peer only heartbeats when it is `lastActionBy`, and our
        // own heartbeat was cancelled in onStop). The re-delivered snapshot then carries
        // `seq == appliedState.seq`, so shouldApply() — which requires a strictly greater
        // seq — drops it and reconcile() never runs, leaving us silently behind forever.
        //
        // Reconciling against the already-applied state fixes it without any Firestore
        // op: appliedReceiptRealtime is SystemClock.elapsedRealtime(), which keeps
        // counting across the background window, so projectedPositionMs() already
        // accounts for the time we missed and shouldSeek() closes the gap. No write, no
        // extra read, no second listener.
        //
        // The cue settle window is dropped first. It exists to let a loading embed report a
        // real position before we correct it, but WebView.onPause() froze that loading — so
        // on the way back the window is just a leftover that would suppress the one catch-up
        // seek this whole path exists to perform.
        if (appliedState != null && appliedState.isPlayable()) {
            suppressDriftUntilRealtime = 0L;
            reconcile(appliedState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (stateListener != null) {
            stateListener.remove();
            stateListener = null;
        }
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (stateListener != null) {
            stateListener.remove();
            stateListener = null;
        }
        heartbeatHandler.removeCallbacksAndMessages(null);
        // Drop any queued debounce so a pending search cannot fire against a dead Activity.
        searchHandler.removeCallbacksAndMessages(null);
        pendingSearch = null;
        if (player != null) {
            player.setListener(null);
            player.destroy();
            player = null;
        }
    }

    /**
     * Back dismisses the search panel first, so browsing results is escapable without
     * leaving the screen (and without ending the session for both participants).
     */
    @Override
    public void onBackPressed() {
        if (searchState.isPanelVisible()) {
            cancelPendingSearch();
            searchState.reset();
            if (etUrl != null) {
                suppressTextWatcher = true;
                etUrl.setText("");
                suppressTextWatcher = false;
            }
            renderSearch();
            return;
        }
        super.onBackPressed();
    }

    // ── View setup ────────────────────────────────────────────────────────────

    private void bindViews() {
        player        = findViewById(R.id.watchPlayer);
        tvPlaceholder = findViewById(R.id.tvPlayerPlaceholder);
        tvStatus      = findViewById(R.id.tvWatchStatus);
        btnPlayPause    = findViewById(R.id.btnPlayPause);
        btnPlaybackRate = findViewById(R.id.btnPlaybackRate);
        controlsRow     = findViewById(R.id.watchControls);
        etUrl           = findViewById(R.id.etWatchUrl);

        if (player != null) player.setListener(this);

        View btnMinimize = findViewById(R.id.btnMinimizeWatch);
        if (btnMinimize != null) btnMinimize.setOnClickListener(v -> finish());

        // Closing the screen ends the session for both participants.
        View btnClose = findViewById(R.id.btnCloseWatch);
        if (btnClose != null) btnClose.setOnClickListener(v -> endSessionAndFinish());

        Button btnStart = findViewById(R.id.btnStartWatch);
        if (btnStart != null) btnStart.setOnClickListener(v -> submitInput());

        if (etUrl != null) {
            etUrl.setOnEditorActionListener((v, actionId, event) -> {
                // The layout now asks for actionSearch, but accept GO too: some IMEs
                // substitute it, and an unhandled action would silently do nothing.
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_GO) {
                    submitInput();
                    return true;
                }
                return false;
            });

            etUrl.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    if (suppressTextWatcher) return;
                    onQueryChanged(s == null ? "" : s.toString());
                }
            });
        }

        bindSearchViews();

        if (btnPlayPause != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());
        if (btnPlaybackRate != null) btnPlaybackRate.setOnClickListener(v -> cycleRate());

        View btnBack = findViewById(R.id.btnSeekBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> seekBy(-SEEK_STEP_MS));

        View btnForward = findViewById(R.id.btnSeekForward);
        if (btnForward != null) btnForward.setOnClickListener(v -> seekBy(SEEK_STEP_MS));
    }

    // ── Snapshot handling ───────────────────────────────────────────────────

    /** Delivered on the main thread by Firestore. */
    private void onStateSnapshot(DocumentSnapshot snap, com.google.firebase.firestore.FirebaseFirestoreException e) {
        if (e != null) {
            Log.w(TAG, "state listener error (non-fatal): " + e.getMessage());
            return;
        }
        WatchTogetherState incoming =
                (snap != null && snap.exists()) ? WatchTogetherState.fromMap(snap.getData()) : null;
        if (incoming == null) return;

        // seq ordering suppresses our own local echo and any out-of-order delivery.
        if (!WatchTogetherState.shouldApply(appliedState, incoming)) return;

        repo.observeRemoteSeq(incoming.seq);
        appliedState = incoming;
        appliedReceiptRealtime = SystemClock.elapsedRealtime();
        reconcile(incoming);
    }

    /**
     * Brings the local player in line with the applied state. This is the single place
     * that talks to the WebView, and it is used for both remote snapshots and this
     * device's own writes.
     */
    private void reconcile(WatchTogetherState state) {
        if (state == null || player == null) return;

        if (!state.isPlayable()) {
            // Session ended (or a malformed doc). Stop the local player and show the
            // ended/idle placeholder. reset() as well as stop(): stop() cannot clear a
            // YouTube error card or a paused last frame, and anything left behind would sit
            // under the placeholder we are about to show.
            player.stop();
            player.reset();
            loadedVideoId  = null;
            erroredVideoId = null;
            suppressDriftUntilRealtime = 0L;
            showEndedUi(state);
            return;
        }

        // Never load a video id we have not validated (also re-validates remote input).
        if (!YouTubeUrlParser.isValidVideoId(state.videoId)) {
            Log.w(TAG, "ignoring state with invalid videoId");
            return;
        }

        showActiveUi(state.videoId);

        // Project the target position from locally measured elapsed time — writer and
        // reader clocks are NOT comparable, so updatedAtMs is never used here.
        long elapsed = SystemClock.elapsedRealtime() - appliedReceiptRealtime;
        long target  = WatchTogetherState.projectedPositionMs(state, elapsed);

        if (!state.videoId.equals(loadedVideoId)) {
            loadedVideoId  = state.videoId;
            erroredVideoId = null;
            // cue() is asynchronous: the embed has to load and buffer before it reports any
            // position. Suppress drift correction until it does. Without this, the next
            // reconcile pass (a heartbeat arrives every 10s) compares a still-loading player
            // reporting ~0 against a target that has raced ahead, "corrects" the difference,
            // and restarts buffering — so a slow-loading video could be seek-stormed
            // indefinitely and never actually begin playing.
            suppressDriftUntilRealtime =
                    SystemClock.elapsedRealtime() + CUE_SETTLE_MS;
            player.cue(state.videoId, target, state.playing, state.playbackRate);
        } else if (SystemClock.elapsedRealtime() < suppressDriftUntilRealtime) {
            // Still settling from a recent cue(). Keep the rate and play/pause intent in
            // sync — those are cheap and idempotent — but leave the position alone.
            player.setRate(state.playbackRate);
            if (state.playing) {
                player.play();
            } else {
                player.pause();
            }
        } else {
            // Compare like with like: `target` was projected to this instant, so the local
            // side must be too. getLastKnownPositionMs() is a raw tick sample up to one
            // 500ms tick old and always stale in the same direction (it reads low while
            // playing), so measuring it against a live target overstated how far behind
            // this device was and produced a forward seek on every single pass — twice as
            // many ms of video at 2x rate. getEstimatedPositionMs() advances that sample by
            // its own age, so a device that is actually in sync now measures as in sync and
            // stays put.
            long local = player.getEstimatedPositionMs();
            if (WatchTogetherState.shouldSeek(local, target)) {
                player.seek(target);
            }
            player.setRate(state.playbackRate);
            if (state.playing) {
                player.play();
            } else {
                player.pause();
            }
        }
        updatePlayPauseIcon(state.playing);
        updateRateButton(state.playbackRate);
    }

    // ── YouTube search ────────────────────────────────────────────────────────

    private void bindSearchViews() {
        searchPanel      = findViewById(R.id.searchPanel);
        rvResults        = findViewById(R.id.rvSearchResults);
        searchProgress   = findViewById(R.id.searchProgress);
        searchMessageBox = findViewById(R.id.searchMessageBox);
        tvSearchMessage  = findViewById(R.id.tvSearchMessage);
        btnSearchRetry   = findViewById(R.id.btnSearchRetry);

        if (rvResults != null) {
            searchAdapter = new YouTubeSearchAdapter(this, this::onResultChosen);
            rvResults.setLayoutManager(new LinearLayoutManager(this));
            rvResults.setAdapter(searchAdapter);
            // Fixed row height, so skip the per-item remeasure on every change.
            rvResults.setHasFixedSize(true);
        }

        if (btnSearchRetry != null) {
            btnSearchRetry.setOnClickListener(v -> retrySearch());
        }
    }

    /**
     * Called for every keystroke. Decides between "start a session now" (a pasted link),
     * "search after a pause" and "say nothing yet".
     */
    private void onQueryChanged(String raw) {
        cancelPendingSearch();

        String normalized = YouTubeSearchState.normalizeQuery(raw);

        if (normalized.isEmpty()) {
            searchState.reset();
            renderSearch();
            return;
        }

        // A pasted link is not a search term. Leave the panel alone and let the user hit
        // Go/Search — firing a session start mid-paste would be hostile.
        if (YouTubeUrlParser.extractVideoId(normalized) != null) {
            searchState.reset();
            renderSearch();
            return;
        }

        if (!YouTubeSearchState.isSearchable(normalized)) {
            searchState.markTooShort();
            renderSearch();
            return;
        }

        // Debounce: one request per pause in typing, not one per character. This is the main
        // protection for the shared server-side YouTube quota.
        pendingSearch = () -> {
            pendingSearch = null;
            dispatchSearch(normalized, false);
        };
        searchHandler.postDelayed(pendingSearch, YouTubeSearchState.DEBOUNCE_MS);
    }

    /** Go button / IME action: a link starts a session, anything else searches immediately. */
    private void submitInput() {
        if (etUrl == null) return;
        String raw = etUrl.getText().toString();
        String normalized = YouTubeSearchState.normalizeQuery(raw);
        if (normalized.isEmpty()) return;

        String videoId = YouTubeUrlParser.extractVideoId(normalized);
        if (videoId != null) {
            startSessionWithVideoId(videoId, YouTubeUrlParser.extractStartMs(normalized));
            return;
        }

        if (!YouTubeSearchState.isSearchable(normalized)) {
            searchState.markTooShort();
            renderSearch();
            return;
        }

        // Explicit intent beats the timer: drop any queued debounce and go now.
        cancelPendingSearch();
        hideKeyboard();
        dispatchSearch(normalized, true);
    }

    /**
     * @param force when {@code true} the request is sent even if the same query already has
     *              results on screen — that is what makes the Go button and Retry feel
     *              responsive instead of inert.
     */
    private void dispatchSearch(String normalized, boolean force) {
        String query = YouTubeSearchState.clampQuery(normalized);
        if (!force && !searchState.shouldDispatch(query)) return;

        final long token = searchState.beginSearch(query);
        renderSearch();

        YouTubeSearchClient.search(query, YouTubeSearchState.PAGE_SIZE,
                new YouTubeSearchClient.Callback() {
                    @Override
                    public void onResults(List<YouTubeSearchResult> results, boolean cached) {
                        // isDestroyed guard: the callback is main-thread but the Activity may
                        // have gone away while the request was in flight.
                        if (isFinishing() || isDestroyed()) return;
                        if (searchState.onResults(token, results)) renderSearch();
                    }

                    @Override
                    public void onError(int status, String message) {
                        if (isFinishing() || isDestroyed()) return;
                        Log.w(TAG, "YouTube search failed, status=" + status);
                        if (searchState.onError(token, message)) renderSearch();
                    }
                });
    }

    private void retrySearch() {
        String query = searchState.activeQuery();
        if (query == null || query.isEmpty()) {
            // Nothing to retry against — fall back to whatever is in the field.
            submitInput();
            return;
        }
        dispatchSearch(query, true);
    }

    private void cancelPendingSearch() {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
    }

    /**
     * A result row was tapped. The adapter reports the tapped position, so the selection is
     * resolved against the live search state. videoIdAt() enforces the results phase, bounds,
     * and video-id validity before the existing session flow receives the id.
     */
    private void onResultChosen(int position) {
        String videoId = searchState.videoIdAt(position);
        if (videoId == null) {
            Toast.makeText(this, "That result can't be played", Toast.LENGTH_SHORT).show();
            return;
        }
        startSessionWithVideoId(videoId, 0L);
    }

    /** The single entry point into the existing session/sync flow. */
    private void startSessionWithVideoId(String videoId, long startMs) {
        if (videoId == null || videoId.isEmpty()) return;

        cancelPendingSearch();
        searchState.reset();

        if (etUrl != null) {
            suppressTextWatcher = true;
            etUrl.setText("");
            suppressTextWatcher = false;
        }
        hideKeyboard();
        renderSearch();

        WatchTogetherState s = new WatchTogetherState();
        s.active       = true;
        s.videoId      = videoId;
        s.hostUid      = myUid;
        s.playing      = true;
        s.positionMs   = Math.max(0L, startMs);
        s.playbackRate = WatchTogetherState.DEFAULT_PLAYBACK_RATE;
        performLocalWrite(WatchTogetherState.ACTION_START, s);
    }

    /** Renders {@link #searchState}. The only place search view visibility is decided. */
    private void renderSearch() {
        if (searchPanel == null) return;

        YouTubeSearchState.Phase phase = searchState.phase();

        searchPanel.setVisibility(searchState.isPanelVisible() ? View.VISIBLE : View.GONE);

        if (searchProgress != null) {
            searchProgress.setVisibility(phase == YouTubeSearchState.Phase.LOADING
                    ? View.VISIBLE : View.GONE);
        }

        boolean hasResults = phase == YouTubeSearchState.Phase.RESULTS;
        if (rvResults != null) {
            rvResults.setVisibility(hasResults ? View.VISIBLE : View.GONE);
        }
        if (searchAdapter != null) {
            searchAdapter.setResults(hasResults ? searchState.results() : null);
            if (hasResults) rvResults.scrollToPosition(0);
        }

        String message;
        switch (phase) {
            case TOO_SHORT:
                message = "Keep typing to search YouTube\u2026";
                break;
            case EMPTY:
                message = "No results for \u201C" + searchState.activeQuery() + "\u201D";
                break;
            case ERROR:
                message = searchState.message();
                break;
            default:
                message = null;
                break;
        }

        if (searchMessageBox != null) {
            searchMessageBox.setVisibility(message == null ? View.GONE : View.VISIBLE);
        }
        if (tvSearchMessage != null && message != null) {
            tvSearchMessage.setText(message);
        }
        if (btnSearchRetry != null) {
            btnSearchRetry.setVisibility(phase == YouTubeSearchState.Phase.ERROR
                    ? View.VISIBLE : View.GONE);
        }
    }

    private void hideKeyboard() {
        if (etUrl == null) return;
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etUrl.getWindowToken(), 0);
    }

    // ── Local control actions ─────────────────────────────────────────────────

    private void togglePlayPause() {
        if (appliedState == null || !appliedState.isPlayable() || player == null) return;

        // Estimated, not raw: this position is what the peer will seek to, so a sample that
        // is half a tick old hands them a small rewind on every play/pause.
        WatchTogetherState s = appliedState.copy();
        s.positionMs = player.getEstimatedPositionMs();
        s.playing    = !appliedState.playing;
        performLocalWrite(
                s.playing ? WatchTogetherState.ACTION_PLAY : WatchTogetherState.ACTION_PAUSE, s);
    }

    private void seekBy(long deltaMs) {
        if (appliedState == null || !appliedState.isPlayable() || player == null) return;

        // Skips are relative, so a stale base makes every ±10s tap land short of where the
        // user actually was — and the error compounds across repeated taps.
        WatchTogetherState s = appliedState.copy();
        long base = player.getEstimatedPositionMs();
        s.positionMs = Math.max(0L, base + deltaMs);
        performLocalWrite(WatchTogetherState.ACTION_SEEK, s);
    }

    /** Advances to the next speed in {@link #RATE_STEPS}, wrapping back to the first. */
    private void cycleRate() {
        if (appliedState == null || !appliedState.isPlayable()) return;

        double current = appliedState.playbackRate;
        int nextIndex = 0;
        for (int i = 0; i < RATE_STEPS.length; i++) {
            if (Double.compare(RATE_STEPS[i], current) == 0) {
                nextIndex = (i + 1) % RATE_STEPS.length;
                break;
            }
        }

        WatchTogetherState s = appliedState.copy();
        if (player != null) s.positionMs = player.getEstimatedPositionMs();
        s.playbackRate = RATE_STEPS[nextIndex];
        performLocalWrite(WatchTogetherState.ACTION_RATE, s);
    }

    private void maybeWriteHeartbeat() {
        // Normally only the participant who performed the last action heartbeats, so the
        // two devices never both write — that keeps write cost to at most one per
        // interval. But if that participant backgrounds or closes the screen, its
        // heartbeat runnable stops (cancelled in onStop) and nobody else was heartbeating,
        // so the other device's drift correction silently stalls forever with no
        // recovery. `appliedReceiptRealtime` is refreshed by every applied state change —
        // this device's own writes AND remote snapshots alike — so "time since the last
        // update from anyone" is a purely local, monotonic (elapsedRealtime-based) signal
        // that needs no cross-device clock comparison. If that goes stale for more than
        // two heartbeat intervals, this device takes over: writeState() always stamps the
        // acting uid as lastActionBy, so the takeover is a natural, self-correcting
        // handover — the moment the original writer returns, it sees it is no longer
        // lastActionBy and steps back to just following.
        if (appliedState == null || !appliedState.isPlayable() || !appliedState.playing) return;

        boolean isDesignatedWriter = myUid.equals(appliedState.lastActionBy);
        long sinceLastUpdateMs = SystemClock.elapsedRealtime() - appliedReceiptRealtime;
        boolean writerSeemsStalled = sinceLastUpdateMs > WatchTogetherState.HEARTBEAT_INTERVAL_MS * 2;
        if (!isDesignatedWriter && !writerSeemsStalled) return;
        if (player == null) return;

        // A heartbeat is pure drift correction, so it must report where this device actually
        // is right now. The raw sample is up to a full tick stale and always low, and this
        // write repeats every 10s — so publishing it steadily dragged the peer backwards,
        // undoing the correction the heartbeat exists to provide.
        //
        // Only report a position we can still vouch for. If the local page has stopped
        // ticking (backgrounded WebView, torn-down embed), getEstimatedPositionMs() falls
        // back to a frozen sample; broadcasting that as authoritative would pull a peer who
        // IS playing correctly back to a dead timestamp. Skipping the write instead lets the
        // peer's own stall-takeover path (below) claim the writer role, which is exactly the
        // handover this design already relies on.
        if (!player.isLocallyPlaying()) return;

        WatchTogetherState s = appliedState.copy();
        s.positionMs = player.getEstimatedPositionMs();
        s.playing    = true;
        boolean ok = repo.writeState(callId, s, WatchTogetherState.ACTION_HEARTBEAT, myUid);
        if (ok) {
            appliedState = s;
            appliedReceiptRealtime = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Writes a control action to Firestore and, on success, treats it as the new applied
     * state and mirrors it into the local player. The stamped {@code seq} on the written
     * state suppresses the write's own Firestore echo in {@link #onStateSnapshot}.
     */
    private void performLocalWrite(String action, WatchTogetherState s) {
        if (s.hostUid == null) {
            s.hostUid = (appliedState != null && appliedState.hostUid != null)
                    ? appliedState.hostUid : myUid;
        }
        boolean ok = repo.writeState(callId, s, action, myUid);
        if (ok) {
            appliedState = s;
            appliedReceiptRealtime = SystemClock.elapsedRealtime();
            reconcile(s);
        } else {
            Toast.makeText(this, "Watch Together is temporarily unavailable",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void endSessionAndFinish() {
        if (repo != null && callId != null) {
            repo.endSession(callId, appliedState, myUid);
        }
        finish();
    }

    // ── UI state ────────────────────────────────────────────────────────────

    /**
     * Shows the live player for {@code videoId} — unless that exact video already failed
     * locally, in which case the failure message stays put instead of being replaced by a
     * player that cannot play anything.
     */
    private void showActiveUi(String videoId) {
        boolean failedHere = erroredVideoId != null && erroredVideoId.equals(videoId);

        // Controls follow the surface: transport buttons over an error message would claim
        // playback this device does not have.
        if (controlsRow != null) {
            controlsRow.setVisibility(failedHere ? View.GONE : View.VISIBLE);
        }
        if (tvStatus != null) {
            tvStatus.setText(failedHere
                    ? "" : "You and " + partnerName + " are watching together");
        }

        if (failedHere) {
            showOverlayMessage(PLAYER_ERROR_MESSAGE);
        } else {
            showPlayerSurface();
        }
    }

    private void showEndedUi(WatchTogetherState state) {
        if (controlsRow != null) controlsRow.setVisibility(View.GONE);
        boolean hadSession = state != null && state.videoId != null && !state.videoId.isEmpty();
        showOverlayMessage(hadSession
                ? "Watch Together ended. Search or paste a link to start again."
                : "Search YouTube or paste a link below to start watching together.");
        if (tvStatus != null) tvStatus.setText("");
    }

    /**
     * Reveals the player and hides the text overlay.
     *
     * <p>The two are strictly exclusive, and that is the whole point: the overlay is a
     * transparent, match_parent TextView stacked on top of the WebView, so whenever both were
     * visible their text composited into one illegible block — the idle "Search YouTube or
     * paste a link…" copy printed straight through YouTube's "This video is unavailable" card
     * and through live video. Toggling the WebView's visibility alongside the overlay makes
     * that state unreachable no matter what the page happens to be showing.
     */
    private void showPlayerSurface() {
        if (player        != null) player.setVisibility(View.VISIBLE);
        if (tvPlaceholder != null) tvPlaceholder.setVisibility(View.GONE);
    }

    private void showOverlayMessage(String message) {
        if (player != null) player.setVisibility(View.INVISIBLE);
        if (tvPlaceholder != null) {
            tvPlaceholder.setText(message);
            tvPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private void updatePlayPauseIcon(boolean playing) {
        if (btnPlayPause == null) return;
        btnPlayPause.setImageResource(playing ? R.drawable.ic_pause_audio : R.drawable.ic_play_audio);
        btnPlayPause.setContentDescription(playing ? "Pause" : "Play");
    }

    private void updateRateButton(double rate) {
        if (btnPlaybackRate == null) return;
        String label = (rate == Math.floor(rate))
                ? ((long) rate) + "x"
                : rate + "x";
        btnPlaybackRate.setText(label);
    }

    // ── WatchTogetherPlayerView.Listener ──────────────────────────────────────

    @Override
    public void onPlayerReady() {
        // If a session state arrived before the player finished constructing, the page
        // buffers the initial cue itself; nothing more to do here.
    }

    @Override
    public void onPlayerStateChange(int ytState, long positionMs) {
        // Keep the play/pause icon responsive to the actual player, but never write back:
        // remote/echo-driven changes must not generate new Firestore writes.
        if (ytState == WatchTogetherPlayerView.YT_STATE_PLAYING) {
            updatePlayPauseIcon(true);
        } else if (ytState == WatchTogetherPlayerView.YT_STATE_ENDED) {
            updatePlayPauseIcon(false);
        }
    }

    @Override
    public void onPlaybackRateChange(double rate) {
        // Informational only; rate is synced through the state document.
    }

    @Override
    public void onPlayerError(int code) {
        Log.w(TAG, "YouTube player error code=" + code);

        // Previously this only logged, so the page was left displaying YouTube's own error
        // card with our idle placeholder printed over the top of it, and the controls still
        // implied a running session. Own the failure instead: hide the dead player behind our
        // own message and remember the video so reconcile()'s next pass does not un-hide it.
        // The session document is left untouched on purpose — the error is per-device (an
        // embed can fail here and play fine for the other participant), so ending the shared
        // session from one side would be wrong.
        erroredVideoId = loadedVideoId;
        showOverlayMessage(PLAYER_ERROR_MESSAGE);
        if (controlsRow != null) controlsRow.setVisibility(View.GONE);
        if (tvStatus != null) tvStatus.setText("");
    }
}
