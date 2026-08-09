package com.duoshield.app.call.watch;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;

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

    private String callId;
    private String myUid;
    private String partnerName;

    private WatchTogetherRepository repo;
    private ListenerRegistration stateListener;

    private WatchTogetherPlayerView player;
    private TextView  tvPlaceholder;
    private TextView  tvStatus;
    private ImageView btnPlayPause;
    private View      controlsRow;
    private EditText  etUrl;

    /** The most recently applied state and the local monotonic time it was applied. */
    private WatchTogetherState appliedState;
    private long appliedReceiptRealtime;

    /** The video ID currently cued into the WebView, so we only reload on a real change. */
    private String loadedVideoId;

    private boolean playerReady;

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
        if (player != null) {
            player.setListener(null);
            player.destroy();
            player = null;
        }
    }

    // ── View setup ────────────────────────────────────────────────────────────

    private void bindViews() {
        player        = findViewById(R.id.watchPlayer);
        tvPlaceholder = findViewById(R.id.tvPlayerPlaceholder);
        tvStatus      = findViewById(R.id.tvWatchStatus);
        btnPlayPause  = findViewById(R.id.btnPlayPause);
        controlsRow   = findViewById(R.id.watchControls);
        etUrl         = findViewById(R.id.etWatchUrl);

        if (player != null) player.setListener(this);

        View btnMinimize = findViewById(R.id.btnMinimizeWatch);
        if (btnMinimize != null) btnMinimize.setOnClickListener(v -> finish());

        // Closing the screen ends the session for both participants.
        View btnClose = findViewById(R.id.btnCloseWatch);
        if (btnClose != null) btnClose.setOnClickListener(v -> endSessionAndFinish());

        Button btnStart = findViewById(R.id.btnStartWatch);
        if (btnStart != null) btnStart.setOnClickListener(v -> startFromInput());

        if (etUrl != null) {
            etUrl.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    startFromInput();
                    return true;
                }
                return false;
            });
        }

        if (btnPlayPause != null) btnPlayPause.setOnClickListener(v -> togglePlayPause());

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
            // ended/idle placeholder.
            player.stop();
            loadedVideoId = null;
            showEndedUi(state);
            return;
        }

        // Never load a video id we have not validated (also re-validates remote input).
        if (!YouTubeUrlParser.isValidVideoId(state.videoId)) {
            Log.w(TAG, "ignoring state with invalid videoId");
            return;
        }

        showActiveUi();

        // Project the target position from locally measured elapsed time — writer and
        // reader clocks are NOT comparable, so updatedAtMs is never used here.
        long elapsed = SystemClock.elapsedRealtime() - appliedReceiptRealtime;
        long target  = WatchTogetherState.projectedPositionMs(state, elapsed);

        if (!state.videoId.equals(loadedVideoId)) {
            loadedVideoId = state.videoId;
            player.cue(state.videoId, target, state.playing, state.playbackRate);
        } else {
            long local = player.getLastKnownPositionMs();
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
    }

    // ── Local control actions ─────────────────────────────────────────────────

    private void startFromInput() {
        if (etUrl == null) return;
        String raw = etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(raw)) return;

        String videoId = YouTubeUrlParser.extractVideoId(raw);
        if (videoId == null) {
            Toast.makeText(this, "That doesn't look like a YouTube link", Toast.LENGTH_SHORT).show();
            return;
        }

        etUrl.setText("");

        WatchTogetherState s = new WatchTogetherState();
        s.active       = true;
        s.videoId      = videoId;
        s.hostUid      = myUid;
        s.playing      = true;
        s.positionMs   = YouTubeUrlParser.extractStartMs(raw);
        s.playbackRate = WatchTogetherState.DEFAULT_PLAYBACK_RATE;
        performLocalWrite(WatchTogetherState.ACTION_START, s);
    }

    private void togglePlayPause() {
        if (appliedState == null || !appliedState.isPlayable() || player == null) return;

        WatchTogetherState s = appliedState.copy();
        s.positionMs = player.getLastKnownPositionMs();
        s.playing    = !appliedState.playing;
        performLocalWrite(
                s.playing ? WatchTogetherState.ACTION_PLAY : WatchTogetherState.ACTION_PAUSE, s);
    }

    private void seekBy(long deltaMs) {
        if (appliedState == null || !appliedState.isPlayable() || player == null) return;

        WatchTogetherState s = appliedState.copy();
        long base = player.getLastKnownPositionMs();
        s.positionMs = Math.max(0L, base + deltaMs);
        performLocalWrite(WatchTogetherState.ACTION_SEEK, s);
    }

    private void maybeWriteHeartbeat() {
        // Only the participant who performed the last action heartbeats, so the two
        // devices never both write. This keeps write cost to at most one per interval.
        if (appliedState == null || !appliedState.isPlayable() || !appliedState.playing) return;
        if (!myUid.equals(appliedState.lastActionBy)) return;
        if (player == null) return;

        WatchTogetherState s = appliedState.copy();
        s.positionMs = player.getLastKnownPositionMs();
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
            repo.endSession(callId, myUid);
        }
        finish();
    }

    // ── UI state ────────────────────────────────────────────────────────────

    private void showActiveUi() {
        if (tvPlaceholder != null) tvPlaceholder.setVisibility(View.GONE);
        if (controlsRow   != null) controlsRow.setVisibility(View.VISIBLE);
        if (tvStatus      != null) {
            tvStatus.setText("You and " + partnerName + " are watching together");
        }
    }

    private void showEndedUi(WatchTogetherState state) {
        if (controlsRow != null) controlsRow.setVisibility(View.GONE);
        if (tvPlaceholder != null) {
            boolean hadSession = state != null && state.videoId != null && !state.videoId.isEmpty();
            tvPlaceholder.setText(hadSession
                    ? "Watch Together ended. Paste a link to start again."
                    : "Paste a YouTube link below to start watching together.");
            tvPlaceholder.setVisibility(View.VISIBLE);
        }
        if (tvStatus != null) tvStatus.setText("");
    }

    private void updatePlayPauseIcon(boolean playing) {
        if (btnPlayPause == null) return;
        btnPlayPause.setImageResource(playing ? R.drawable.ic_pause_audio : R.drawable.ic_play_audio);
        btnPlayPause.setContentDescription(playing ? "Pause" : "Play");
    }

    // ── WatchTogetherPlayerView.Listener ──────────────────────────────────────

    @Override
    public void onPlayerReady() {
        playerReady = true;
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
    }
}
