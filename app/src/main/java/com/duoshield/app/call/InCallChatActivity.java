package com.duoshield.app.call;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.R;

import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ephemeral in-call chat screen.
 *
 * <p>Messages are stored in {@code calls/{callId}/chat} and are <strong>not</strong> persisted
 * to Room — they disappear when the call document is swept by the scheduled self-destruct
 * Cloud Function. This mirrors the "Messages won't be saved when the call ends" notice shown
 * in the UI (matching the design reference).
 *
 * <p>Uses {@link AppCompatActivity} (not BaseActivity) because:
 * <ul>
 *   <li>The user is already authenticated — they passed the full call setup flow.</li>
 *   <li>Messages are ephemeral; there is no persistent sensitive data at risk.</li>
 *   <li>Triggering the app-lock redirect mid-call would disrupt an active call.</li>
 * </ul>
 */
public class InCallChatActivity extends AppCompatActivity {

    private static final String TAG = "InCallChatActivity";

    /** Intent extras — set by CallActivity when opening this screen. */
    public static final String EXTRA_CALL_ID      = "incall_call_id";
    public static final String EXTRA_MY_UID       = "incall_my_uid";
    public static final String EXTRA_PARTNER_NAME = "incall_partner_name";
    /** False for voice-only calls, where there is no video to float. */
    public static final String EXTRA_IS_VIDEO     = "incall_is_video";

    private String callId;
    private String myUid;
    private String partnerName;
    private boolean isVideo;

    private RecyclerView rvMessages;
    private EditText     etMessage;

    // ── Floating call PiP ─────────────────────────────────────────────────────
    private FrameLayout        pipContainer;
    private SurfaceViewRenderer pipVideoView;
    private ImageView          ivPipMuteBadge;
    private TextView           tvPipLabel;

    /** True once {@link SurfaceViewRenderer#init} ran — guards a double release(). */
    private boolean pipInitialised = false;
    /** Which side the PiP is showing. Tapping the PiP flips it. */
    private boolean pipShowsRemote = true;
    /**
     * The track this screen's renderer is currently a sink of.
     *
     * <p>Tracked explicitly because it must be detached again in {@link #onPause()} /
     * {@link #onDestroy()}: attaching a second sink to a track that {@code CallActivity} is
     * already rendering is fine, but leaving it attached after this screen goes away leaks the
     * renderer and can crash libwebrtc when the track is disposed at the end of the call.
     */
    private VideoTrack pipTrack;

    /**
     * Repaint loop for the PiP's non-push state.
     *
     * <p>{@link CallManager} publishes tracks and the mic-mute flag but has no listener API a
     * secondary screen can subscribe to, and the remote track often arrives after this screen
     * is already open. A 1 s poll while resumed is cheap and avoids adding a second listener
     * path through the call session.
     */
    private final android.os.Handler pipHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pipRefresh = new Runnable() {
        @Override public void run() {
            syncPip();
            pipHandler.postDelayed(this, 1_000L);
        }
    };

    private final List<InCallChatMessage> messages = new ArrayList<>();
    private InCallChatAdapter adapter;
    private ListenerRegistration chatListener;

    /** Backoff state for re-attaching a listener that Firestore rejected (see listenForMessages). */
    private final android.os.Handler retryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int listenAttempts = 0;
    private static final int MAX_LISTEN_ATTEMPTS = 8;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incall_chat);

        callId      = getIntent().getStringExtra(EXTRA_CALL_ID);
        myUid       = getIntent().getStringExtra(EXTRA_MY_UID);
        partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        isVideo     = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);
        if (partnerName == null) partnerName = "Unknown";

        if (callId == null || myUid == null) {
            Log.e(TAG, "Missing callId or myUid — closing in-call chat");
            finish();
            return;
        }

        bindViews();
        initPip();
        listenForMessages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Attach the sink only while visible: a backgrounded renderer would keep decoding
        // frames into a surface nobody can see.
        syncPip();
        pipHandler.removeCallbacks(pipRefresh);
        pipHandler.postDelayed(pipRefresh, 1_000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pipHandler.removeCallbacks(pipRefresh);
        detachPipSink();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        retryHandler.removeCallbacksAndMessages(null);
        if (chatListener != null) { chatListener.remove(); chatListener = null; }

        // Order matters: drop the sink from the (still live) track before releasing the
        // renderer, otherwise repeatedly opening and closing this screen leaks renderers and
        // can crash the GL thread in libwebrtc.
        detachPipSink();
        if (pipInitialised && pipVideoView != null) {
            pipVideoView.release();
            pipInitialised = false;
        }
    }

    // ── View setup ────────────────────────────────────────────────────────────

    private void bindViews() {
        rvMessages = findViewById(R.id.rvInCallMessages);
        etMessage  = findViewById(R.id.etInCallMessage);

        // RecyclerView — stack from end so newest messages appear at the bottom
        adapter = new InCallChatAdapter(messages, partnerName);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        rvMessages.setLayoutManager(llm);
        rvMessages.setAdapter(adapter);

        // Header navigation
        View btnMinimize = findViewById(R.id.btnMinimizeChat);
        View btnClose    = findViewById(R.id.btnCloseChat);
        if (btnMinimize != null) btnMinimize.setOnClickListener(v -> finish());
        if (btnClose    != null) btnClose.setOnClickListener(v -> finish());

        // Emoji button — opens the soft keyboard so the user can switch to emoji panel
        ImageView btnEmoji = findViewById(R.id.btnEmoji);
        if (btnEmoji != null) {
            btnEmoji.setOnClickListener(v -> {
                etMessage.requestFocus();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etMessage, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }

        // Send button
        ImageView btnSend = findViewById(R.id.btnSendInCall);
        if (btnSend != null) btnSend.setOnClickListener(v -> sendMessage());

        // IME "Send" action key
        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    // ── Floating call PiP ─────────────────────────────────────────────────────

    /**
     * Prepares the floating video window.
     *
     * <p>This screen is a separate activity from {@link CallActivity}, so it cannot own a
     * camera or a peer connection of its own — starting a second capture would fight the live
     * call for the camera. Instead it borrows the running session's {@link EglBase} and adds an
     * extra sink to the tracks {@link CallManager} already owns, reached through
     * {@link CallManager#getActive()}.
     *
     * <p>The PiP stays hidden for voice-only calls and whenever no call session is reachable.
     */
    private void initPip() {
        pipContainer   = findViewById(R.id.callPipContainer);
        pipVideoView   = findViewById(R.id.pipVideoView);
        ivPipMuteBadge = findViewById(R.id.ivPipMuteBadge);
        tvPipLabel     = findViewById(R.id.tvPipLabel);
        if (pipContainer == null || pipVideoView == null) return;

        if (!isVideo) {
            // Voice-only call — nothing to float.
            pipContainer.setVisibility(View.GONE);
            return;
        }

        CallManager call = CallManager.getActive();
        EglBase egl = call != null ? call.getEglBase() : null;
        if (egl == null) {
            // No live session (e.g. the call ended while this screen was opening).
            pipContainer.setVisibility(View.GONE);
            return;
        }

        pipVideoView.init(egl.getEglBaseContext(), null);
        pipVideoView.setMirror(false);
        pipVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        pipVideoView.setEnableHardwareScaler(true);
        pipInitialised = true;

        setupPipGestures();
    }

    /**
     * Tap to swap between the partner's video and the self-view; drag to reposition.
     *
     * <p>Free-float rather than corner-snapping: this window shares the screen with a message
     * list and an input row, so the user needs to be able to park it anywhere that is not
     * covering the text they are reading.
     */
    private void setupPipGestures() {
        final float[]   downX  = {0f};
        final float[]   downY  = {0f};
        final float[]   offX   = {0f};
        final float[]   offY   = {0f};
        final boolean[] dragged = {false};
        final float threshold = 8f * getResources().getDisplayMetrics().density;

        pipContainer.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    offX[0]    = v.getX() - event.getRawX();
                    offY[0]    = v.getY() - event.getRawY();
                    downX[0]   = event.getRawX();
                    downY[0]   = event.getRawY();
                    dragged[0] = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (!dragged[0]
                            && (Math.abs(event.getRawX() - downX[0]) > threshold
                             || Math.abs(event.getRawY() - downY[0]) > threshold)) {
                        dragged[0] = true;
                    }
                    if (dragged[0]) {
                        View parent = (View) v.getParent();
                        v.setX(Math.max(0, Math.min(event.getRawX() + offX[0],
                                parent.getWidth() - v.getWidth())));
                        v.setY(Math.max(0, Math.min(event.getRawY() + offY[0],
                                parent.getHeight() - v.getHeight())));
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragged[0]) {
                        pipShowsRemote = !pipShowsRemote;
                        syncPip();
                    }
                    return true;
            }
            return false;
        });
    }

    /**
     * Points the renderer at the track the user asked for and refreshes the overlays.
     *
     * <p>Called on resume, on tap, and once a second: the remote track usually arrives after
     * this screen is already open, and the mic-mute badge has to track the call's state without
     * a listener.
     */
    private void syncPip() {
        if (!pipInitialised || pipContainer == null) return;

        CallManager call = CallManager.getActive();
        if (call == null) {
            // Call ended while the chat was open — stop rendering a track that is about to be
            // disposed rather than leaving a frozen frame on screen.
            detachPipSink();
            pipContainer.setVisibility(View.GONE);
            return;
        }

        VideoTrack remote = call.getRemoteVideoTrack();
        VideoTrack local  = call.getLocalVideoTrack();
        // Prefer the partner's video, fall back to the self-view until it arrives.
        boolean showRemote = pipShowsRemote && remote != null;
        VideoTrack target  = showRemote ? remote : local;

        if (target == null) {
            detachPipSink();
            pipContainer.setVisibility(View.GONE);
            return;
        }

        if (target != pipTrack) {
            detachPipSink();
            try {
                target.addSink(pipVideoView);
                pipTrack = target;
            } catch (Exception e) {
                Log.w(TAG, "PiP addSink failed", e);
                pipContainer.setVisibility(View.GONE);
                return;
            }
        }

        pipContainer.setVisibility(View.VISIBLE);
        if (tvPipLabel != null) {
            tvPipLabel.setText(showRemote ? partnerName : "You");
        }
        // The badge reflects *our* microphone, so it only belongs on the self-view.
        if (ivPipMuteBadge != null) {
            ivPipMuteBadge.setVisibility(!showRemote && call.isMicMuted()
                    ? View.VISIBLE : View.GONE);
        }
    }

    /** Detaches the renderer from whatever track it is rendering. Safe to call repeatedly. */
    private void detachPipSink() {
        if (pipTrack == null) return;
        try {
            pipTrack.removeSink(pipVideoView);
        } catch (Exception e) {
            // The track may already be disposed if the call ended first — nothing to undo.
            Log.w(TAG, "PiP removeSink failed", e);
        }
        pipTrack = null;
    }

    // ── Firestore ─────────────────────────────────────────────────────────────

    private void sendMessage() {
        if (etMessage == null) return;
        String text = etMessage.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        etMessage.setText("");

        Map<String, Object> doc = new HashMap<>();
        doc.put("senderId", myUid);
        doc.put("text", text);
        doc.put("ts", System.currentTimeMillis());

        FirebaseFirestore.getInstance()
                .collection("calls").document(callId)
                .collection("chat")
                .add(doc)
                .addOnFailureListener(e -> Log.w(TAG, "send failed", e));
    }

    private void listenForMessages() {
        if (chatListener != null) return;
        chatListener = FirebaseFirestore.getInstance()
                .collection("calls").document(callId)
                .collection("chat")
                .orderBy("ts", Query.Direction.ASCENDING)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        // A rejected listener is dead for good in Firestore. Opening this
                        // screen in the first moment of a call can race the creation of the
                        // parent calls/{callId} document that the security rule resolves,
                        // which used to leave the thread permanently empty on that device.
                        // Drop the dead registration and re-attach with backoff.
                        Log.w(TAG, "listener error — re-attaching (attempt "
                                + listenAttempts + ")", e);
                        if (chatListener != null) { chatListener.remove(); chatListener = null; }
                        if (isFinishing() || isDestroyed()) return;
                        if (listenAttempts >= MAX_LISTEN_ATTEMPTS) {
                            Log.e(TAG, "in-call chat listener gave up after "
                                    + listenAttempts + " attempts");
                            return;
                        }
                        long delay = Math.min(600L * (1L << listenAttempts), 4_000L);
                        listenAttempts++;
                        retryHandler.postDelayed(this::listenForMessages, delay);
                        return;
                    }
                    if (snapshots == null) return;
                    listenAttempts = 0;

                    List<InCallChatMessage> updated = new ArrayList<>();
                    for (DocumentSnapshot ds : snapshots.getDocuments()) {
                        String senderId = ds.getString("senderId");
                        String text     = ds.getString("text");
                        Long   ts       = ds.getLong("ts");
                        if (TextUtils.isEmpty(text) || senderId == null) continue;
                        updated.add(new InCallChatMessage(
                                ds.getId(), senderId, text,
                                ts != null ? ts : 0L,
                                senderId.equals(myUid)));
                    }

                    runOnUiThread(() -> {
                        messages.clear();
                        messages.addAll(updated);
                        adapter.notifyDataSetChanged();
                        if (!messages.isEmpty()) {
                            rvMessages.scrollToPosition(messages.size() - 1);
                        }
                    });
                });
    }
}
