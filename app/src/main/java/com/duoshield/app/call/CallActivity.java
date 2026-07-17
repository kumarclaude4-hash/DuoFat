package com.duoshield.app.call;

import android.content.Intent;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.CallRecord;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.webrtc.EglBase;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import com.duoshield.app.call.TurnBandwidthTracker;
import com.duoshield.app.call.TurnCredentialFetcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.PowerManager;
import androidx.core.content.ContextCompat;

/**
 * Full-screen call UI — voice or video.
 *
 * <p>Started by either:
 * <ul>
 *   <li>The caller (via {@link ChatMediaActivity}) with {@code is_caller=true}</li>
 *   <li>The callee (via {@link IncomingCallActivity}) with {@code is_caller=false}</li>
 * </ul>
 *
 * <h3>Layout overview</h3>
 * <ul>
 *   <li>Full-screen remote video / voice-only background fills the screen.</li>
 *   <li>A gradient header bar (top) shows the partner name, call duration, and audio-output
 *       picker trigger.</li>
 *   <li>The local video PiP (lower-right) carries the "You" label and flip-camera button.</li>
 *   <li>A gradient controls bar (bottom) holds Camera, Mute, Chat, and End-call buttons.</li>
 * </ul>
 */
public class CallActivity extends AppCompatActivity implements CallManager.CallListener {

    private static final String TAG = "CallActivity";
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();

    public static final String EXTRA_CALL_ID      = "call_id";
    public static final String EXTRA_CALLEE_ID    = "callee_id";
    public static final String EXTRA_CALLER_ID    = "caller_id";
    public static final String EXTRA_IS_VIDEO     = "is_video";
    public static final String EXTRA_IS_CALLER    = "is_caller";
    public static final String EXTRA_PARTNER_NAME = "partner_name";
    public static final String EXTRA_MY_UID       = "my_uid";
    /** F6: deterministic chatId for bilateral contact gate in Firestore rules. */
    public static final String EXTRA_CHAT_ID      = "chat_id";

    private CallManager callManager;

    // ── Views ─────────────────────────────────────────────────────────────────
    private SurfaceViewRenderer remoteVideoView;
    private SurfaceViewRenderer localVideoView;
    private View                localVideoPip;
    private View                voiceOnlyBg;
    private TextView            tvCallPartnerName;
    private TextView            tvCallStatus;
    private TextView            tvCallDuration;
    private TextView            tvCallStatusOverlay;
    private TextView            tvCallAvatarInitial;
    private ImageView           btnMute;
    private ImageView           btnCamera;
    private View                btnCameraLayout;
    private ImageView           btnEndCall;
    private ImageView           btnSpeaker;
    private ImageView           btnFlipCamera;
    private View                btnFlipLayout;
    private View                btnBack;
    private ImageView           btnChat;

    // TURN quota warning banner
    private View     bannerTurnWarning;
    private TextView tvTurnWarningText;

    // ── Audio focus ───────────────────────────────────────────────────────────
    private AudioFocusRequest audioFocusRequest; // API 26+

    // ── Proximity screen-off (voice calls only) ───────────────────────────────
    /** Turns the screen off when the phone is held to the user's ear during voice calls. */
    private PowerManager.WakeLock proximityWakeLock;

    // ── Wired headset routing ─────────────────────────────────────────────────
    /** Re-routes audio when the user plugs/unplugs wired earphones mid-call. */
    private final BroadcastReceiver headsetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!AudioManager.ACTION_HEADSET_PLUG.equals(intent.getAction())) return;
            int state = intent.getIntExtra("state", -1);
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            if (state == 1) {
                // Headset plugged in — route to headset (earpiece/headphones), not speaker.
                am.setSpeakerphoneOn(false);
                isSpeakerOn = false;
                if (callManager != null) callManager.setSpeakerOn(false);
            } else if (state == 0) {
                // Headset unplugged — restore the speaker state from before it was plugged in.
                am.setSpeakerphoneOn(isSpeakerOn);
                if (callManager != null) callManager.setSpeakerOn(isSpeakerOn);
            }
        }
    };

    // ── Notification quick-action receiver ────────────────────────────────────
    /** Handles "End" and "Mute" taps on the ongoing call notification. */
    private final BroadcastReceiver notifActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CallForegroundService.BROADCAST_END_CALL.equals(intent.getAction())) {
                if (callManager != null) callManager.hangup();
                finish();
            } else if (CallForegroundService.BROADCAST_TOGGLE_MUTE.equals(intent.getAction())) {
                isMuted = !isMuted;
                if (callManager != null) callManager.setMuted(isMuted);
                if (btnMute != null) {
                    btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
                    btnMute.setAlpha(isMuted ? 0.5f : 1f);
                }
            }
        }
    };

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isMuted      = false;
    private boolean isCameraOff  = false;
    private boolean isSpeakerOn  = false;
    private boolean isCaller;
    private boolean isVideo;
    private String  partnerName;
    private String  myUid;
    private String  callId;
    private String  partnerId;

    /** Cancels the TURN-credential wait if the activity is destroyed before it fires. */
    private Handler turnTimeoutHandler;
    private final Handler  durationHandler = new Handler(Looper.getMainLooper());
    private long           callStartMs     = 0;
    private final Runnable durationTick    = new Runnable() {
        @Override
        public void run() {
            if (callStartMs > 0) {
                long elapsed = (System.currentTimeMillis() - callStartMs) / 1000;
                long min = elapsed / 60;
                long sec = elapsed % 60;
                String text = String.format(java.util.Locale.US, "%02d:%02d", min, sec);
                if (tvCallDuration != null) {
                    tvCallDuration.setText(text);
                    tvCallDuration.setVisibility(View.VISIBLE);
                }
            }
            durationHandler.postDelayed(this, 1000);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_call);

        isCaller    = getIntent().getBooleanExtra(EXTRA_IS_CALLER, true);
        isVideo     = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);
        partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        myUid       = getIntent().getStringExtra(EXTRA_MY_UID);
        callId      = getIntent().getStringExtra(EXTRA_CALL_ID);
        partnerId   = isCaller
                ? getIntent().getStringExtra(EXTRA_CALLEE_ID)
                : getIntent().getStringExtra(EXTRA_CALLER_ID);

        if (partnerName == null) partnerName = "Unknown";

        bindViews();
        setupButtons();
        setupAudio();

        callManager = new CallManager(this);
        callManager.setListener(this);

        // Start the foreground service — this keeps the process alive when the user
        // presses Home, preventing the OS from killing the WebRTC PeerConnection.
        startForegroundCallService();

        // Acquire the proximity wake lock so the screen turns off when the phone is
        // held to the user's ear during a voice call (same behaviour as WhatsApp).
        acquireProximityWakeLock();

        // Register receivers: (a) wired headset plug/unplug for auto audio-routing,
        // (b) End/Mute actions from the ongoing call notification.
        IntentFilter headsetFilter = new IntentFilter(AudioManager.ACTION_HEADSET_PLUG);
        registerReceiver(headsetReceiver, headsetFilter);
        IntentFilter notifFilter = new IntentFilter();
        notifFilter.addAction(CallForegroundService.BROADCAST_END_CALL);
        notifFilter.addAction(CallForegroundService.BROADCAST_TOGGLE_MUTE);
        ContextCompat.registerReceiver(this, notifActionReceiver, notifFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // ── TURN credential warm-up ───────────────────────────────────────────
        // ROOT CAUSE OF 20-SECOND LAG (fixed here):
        //
        // The previous code called TurnCredentialFetcher.prefetch() (fire-and-forget)
        // and then IMMEDIATELY called startCall()/acceptCall().  Both of those call
        // createPeerConnection() → buildIceServers() synchronously.  Because the async
        // HTTP prefetch had not completed yet, buildIceServers() found an empty cache
        // and created the PeerConnection with STUN-only — no TURN relay servers.
        //
        // On mobile networks with CGNAT or symmetric NAT, STUN-only ICE gathers
        // candidates but they never connect (both sides are behind non-traversable NAT).
        // ICE then waits the full 60-second CONNECTION_TIMEOUT_MS before failing, which
        // manifests as the 20-second lag the user observes before audio/video starts
        // (on lucky networks ICE stumbles into a working path eventually; on strict
        // networks it never connects at all).
        //
        // FIX: use the callback form of prefetch().  The call only starts after
        // credentials are confirmed cached (success path) or after a 3-second hard
        // timeout (failure path — avoids blocking the callee's ring-accept window).
        // For callees the disk-warmed cache (TurnCredentialCache.init) handles the
        // common cold-start case; the 3 s timeout covers the rare "first ever call"
        // case where no credentials were ever persisted.
        TurnCredentialCache.init(this); // load previously persisted creds from disk

        // Show TURN quota warning before the call starts so user knows what to expect.
        checkAndShowTurnWarning();

        final String    chatId      = isCaller ? getIntent().getStringExtra(EXTRA_CHAT_ID) : null;
        final boolean[] started     = {false};
        turnTimeoutHandler = new Handler(Looper.getMainLooper());

        // doStartCall is idempotent — the timeout and the callback both reference it;
        // the boolean guard ensures the PeerConnection is created exactly once.
        final Runnable doStartCall = () -> {
            if (started[0]) return;
            started[0] = true;
            // startCall/acceptCall call initFactory() synchronously as their first step,
            // which creates eglBase.  initVideoRenderers() must run right after so
            // eglBase is non-null when the SurfaceViewRenderers are initialised.
            if (isCaller) {
                callManager.startCall(myUid, partnerId, isVideo, chatId);
            } else {
                callManager.acceptCall(myUid, callId, isVideo);
            }
            initVideoRenderers();
        };

        // Hard deadline: start the call after 3 s even if TURN fetch is still in-flight.
        // Callee ring timeout is 30 s, so 3 s is safe but still generous for slow networks.
        turnTimeoutHandler.postDelayed(doStartCall, 3_000);

        // Preferred path: start as soon as credentials are confirmed ready (typically <1 s
        // if the disk cache is warm, or 1–3 s for a fresh network fetch).
        TurnCredentialFetcher.prefetch(success -> runOnUiThread(() -> {
            Log.d(TAG, "TURN prefetch done (success=" + success + ") — starting call");
            turnTimeoutHandler.removeCallbacks(doStartCall);
            doStartCall.run();
        }));

        updateStatusUi(CallManager.CallState.OUTGOING_RINGING);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Stop the foreground service (removes the ongoing notification).
        stopForegroundCallService();

        // Release the proximity wake lock — screen can turn back on normally.
        releaseProximityWakeLock();

        // Unregister dynamic broadcast receivers.
        try { unregisterReceiver(headsetReceiver);   } catch (Exception ignored) {}
        try { unregisterReceiver(notifActionReceiver); } catch (Exception ignored) {}

        historyExecutor.shutdownNow();
        durationHandler.removeCallbacksAndMessages(null);
        // Cancel the TURN-credential wait if it hasn't fired yet.
        if (turnTimeoutHandler != null) turnTimeoutHandler.removeCallbacksAndMessages(null);

        // Release SurfaceViewRenderers FIRST — this removes the video sink from the tracks
        // so the tracks can be safely disposed by hangup()/release() without rendering to a
        // released surface (which can crash the GL thread in libwebrtc).
        releaseVideoRenderers();

        if (callManager != null) {
            CallManager.CallState state = callManager.getCurrentState();
            if (state != CallManager.CallState.ENDED && state != CallManager.CallState.FAILED) {
                // Normal teardown: write "ended" to Firestore, then free native resources.
                callManager.hangup();
            } else {
                // Remote side ended the call: Firestore was already updated; we still must
                // free the PeerConnection, tracks, camera, EglBase, and factory — if we skip
                // this the process holds the camera open indefinitely and the GC cannot
                // collect the native peer-connection allocation.
                callManager.release();
            }
        }

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_NORMAL);
            am.setSpeakerphoneOn(false);
        }
        abandonAudioFocus();
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private void bindViews() {
        remoteVideoView     = findViewById(R.id.remoteVideoView);
        localVideoView      = findViewById(R.id.localVideoView);
        localVideoPip       = findViewById(R.id.localVideoPip);
        voiceOnlyBg         = findViewById(R.id.voiceOnlyBg);
        tvCallPartnerName   = findViewById(R.id.tvCallPartnerName);
        tvCallStatus        = findViewById(R.id.tvCallStatus);
        tvCallDuration      = findViewById(R.id.tvCallDuration);
        tvCallStatusOverlay = findViewById(R.id.tvCallStatusOverlay);
        tvCallAvatarInitial = findViewById(R.id.tvCallAvatarInitial);
        btnMute             = findViewById(R.id.btnMute);
        btnCamera           = findViewById(R.id.btnCamera);
        btnCameraLayout     = findViewById(R.id.btnCameraLayout);
        btnEndCall          = findViewById(R.id.btnEndCall);
        btnSpeaker          = findViewById(R.id.btnSpeaker);
        btnFlipCamera       = findViewById(R.id.btnFlipCamera);
        btnFlipLayout       = findViewById(R.id.btnFlipLayout);
        btnBack             = findViewById(R.id.btnBack);
        btnChat             = findViewById(R.id.btnChat);

        // TURN banner
        bannerTurnWarning = findViewById(R.id.bannerTurnWarning);
        tvTurnWarningText = findViewById(R.id.tvTurnWarningText);
        ImageView btnDismiss = findViewById(R.id.btnTurnWarningDismiss);
        if (btnDismiss != null) {
            btnDismiss.setOnClickListener(v -> bannerTurnWarning.setVisibility(View.GONE));
        }

        // Populate static text
        tvCallPartnerName.setText(partnerName);
        tvCallAvatarInitial.setText(partnerName.substring(0, 1).toUpperCase());

        // Camera and flip are only relevant in video calls
        if (isVideo) {
            btnCameraLayout.setVisibility(View.VISIBLE);
            // btnFlipLayout lives inside localVideoPip; already visible within the PiP
        }
    }

    // ── Button listeners ──────────────────────────────────────────────────────

    private void setupButtons() {
        // Back (ends the call)
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> {
                callManager.hangup();
                finish();
            });
        }

        // Mute microphone
        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            callManager.setMuted(isMuted);
            btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            btnMute.setAlpha(isMuted ? 0.5f : 1f);
        });

        // Camera toggle (video calls only)
        btnCamera.setOnClickListener(v -> {
            isCameraOff = !isCameraOff;
            callManager.setCameraEnabled(!isCameraOff);
            btnCamera.setAlpha(isCameraOff ? 0.5f : 1f);
            localVideoPip.setVisibility(isCameraOff ? View.GONE : View.VISIBLE);
        });

        // End call
        btnEndCall.setOnClickListener(v -> {
            callManager.hangup();
            finish();
        });

        // Audio output — opens a bottom-sheet picker instead of simple toggle
        btnSpeaker.setOnClickListener(v -> showAudioOutputPicker());

        // Flip camera (button is inside the PiP FrameLayout)
        btnFlipCamera.setOnClickListener(v -> callManager.flipCamera());

        // In-call chat
        if (btnChat != null) {
            btnChat.setOnClickListener(v -> openInCallChat());
        }
    }

    // ── Audio setup ───────────────────────────────────────────────────────────

    private void setupAudio() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;

        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        // Video calls default to speaker-on (WhatsApp/FaceTime behaviour — the user is
        // looking at the screen so earpiece audio doesn't make sense).
        // Voice calls default to earpiece so the user can hold the phone naturally.
        if (isVideo) {
            am.setSpeakerphoneOn(true);
            isSpeakerOn = true;
        } else {
            am.setSpeakerphoneOn(false);
        }

        // Request exclusive audio focus so music / media apps pause automatically
        // and the OS knows a voice call is in progress (affects Bluetooth routing,
        // notification ducking, etc.). Mandatory for WhatsApp-quality call behaviour.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        // LOSS_TRANSIENT: another app briefly grabbed focus (e.g. TTS).
                        // LOSS: e.g. another call arrived; mute ourselves so the user
                        // doesn't hear feedback. We don't hang up automatically because
                        // the user may return to us.
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                                || focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            if (callManager != null) callManager.setMuted(true);
                        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                            if (callManager != null) callManager.setMuted(isMuted);
                        }
                    })
                    .build();
            am.requestAudioFocus(audioFocusRequest);
        } else {
            //noinspection deprecation
            am.requestAudioFocus(null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && audioFocusRequest != null) {
            am.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            //noinspection deprecation
            am.abandonAudioFocus(null);
        }
    }

    // ── Video renderer init ───────────────────────────────────────────────────

    private void initVideoRenderers() {
        EglBase eglBase = callManager.getEglBase();
        if (eglBase == null) return;
        remoteVideoView.init(eglBase.getEglBaseContext(), null);
        remoteVideoView.setMirror(false);
        localVideoView.init(eglBase.getEglBaseContext(), null);
        localVideoView.setMirror(true);
    }

    // ── Audio output picker ───────────────────────────────────────────────────

    /**
     * Shows a bottom-sheet audio-output picker that mirrors the design in the
     * reference screenshot: Speaker / Phone (earpiece) / Bluetooth devices /
     * Turn off sound / Cancel. The active device is indicated by a purple
     * checkmark on the right.
     */
    private void showAudioOutputPicker() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_audio_output, null);
        dialog.setContentView(sheetView);

        // Make the BottomSheetDialog's wrapper FrameLayout transparent so the
        // rounded-corner bg_audio_sheet drawable on our content view shows correctly.
        sheetView.post(() -> {
            View parent = (View) sheetView.getParent();
            if (parent != null) parent.setBackgroundColor(Color.TRANSPARENT);
        });

        LinearLayout container = sheetView.findViewById(R.id.audioOutputContainer);

        boolean speakerActive    = am != null && am.isSpeakerphoneOn();
        boolean bluetoothActive  = am != null && am.isBluetoothScoOn();
        boolean earpieceActive   = !speakerActive && !bluetoothActive;

        // ── Speaker ──
        addAudioOutputItem(container, R.drawable.ic_speaker_on, "Speaker",
                speakerActive, () -> {
                    if (am != null) { am.setSpeakerphoneOn(true); am.setBluetoothScoOn(false); }
                    isSpeakerOn = true;
                    callManager.setSpeakerOn(true);
                    dialog.dismiss();
                });

        // ── Phone (earpiece) ──
        addAudioOutputItem(container, R.drawable.ic_call_phone, "Phone",
                earpieceActive, () -> {
                    if (am != null) { am.setSpeakerphoneOn(false); am.setBluetoothScoOn(false); }
                    isSpeakerOn = false;
                    callManager.setSpeakerOn(false);
                    dialog.dismiss();
                });

        // ── Bluetooth output devices ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && am != null) {
            try {
                AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo dev : devices) {
                    int type = dev.getType();
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        CharSequence name = dev.getProductName();
                        String label = (name != null && name.length() > 0)
                                ? name.toString() : "Bluetooth device";
                        addAudioOutputItem(container, R.drawable.ic_bluetooth_audio,
                                label, bluetoothActive, () -> {
                                    try {
                                        am.setBluetoothScoOn(true);
                                        am.startBluetoothSco();
                                        am.setSpeakerphoneOn(false);
                                        isSpeakerOn = false;
                                        callManager.setSpeakerOn(false);
                                    } catch (Exception ignored) { }
                                    dialog.dismiss();
                                });
                    }
                }
            } catch (SecurityException ignored) {
                // BLUETOOTH_CONNECT permission not granted — skip Bluetooth items
            }
        }

        // ── Turn off sound ──
        addAudioOutputItem(container, R.drawable.ic_volume_off, "Turn off sound",
                false, () -> {
                    if (am != null) {
                        am.setSpeakerphoneOn(false);
                        am.setBluetoothScoOn(false);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL,
                                    AudioManager.ADJUST_MUTE, 0);
                        } else {
                            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0);
                        }
                    }
                    dialog.dismiss();
                });

        // ── Cancel ──
        addAudioOutputItem(container, R.drawable.ic_close, "Cancel",
                false, dialog::dismiss);

        dialog.show();
    }

    /** Inflates one row into the audio-output bottom sheet. */
    private void addAudioOutputItem(LinearLayout container, int iconRes,
                                    String label, boolean checked, Runnable onClick) {
        View item = getLayoutInflater().inflate(R.layout.item_audio_output, container, false);
        ((ImageView) item.findViewById(R.id.ivAudioIcon)).setImageResource(iconRes);
        ((TextView)  item.findViewById(R.id.tvAudioLabel)).setText(label);
        item.findViewById(R.id.ivAudioCheck)
                .setVisibility(checked ? View.VISIBLE : View.GONE);
        item.setOnClickListener(v -> onClick.run());
        container.addView(item);
    }

    // ── In-call chat ──────────────────────────────────────────────────────────

    private void openInCallChat() {
        if (callId == null || myUid == null) {
            Toast.makeText(this, "Chat unavailable — call not yet established",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, InCallChatActivity.class);
        intent.putExtra(InCallChatActivity.EXTRA_CALL_ID,      callId);
        intent.putExtra(InCallChatActivity.EXTRA_MY_UID,       myUid);
        intent.putExtra(InCallChatActivity.EXTRA_PARTNER_NAME, partnerName);
        startActivity(intent);
    }

    // ── TURN quota warning ────────────────────────────────────────────────────

    /**
     * Shows or updates the TURN quota warning banner.
     *
     * <ul>
     *   <li><b>Near limit (≥800 GB):</b> amber banner — user can dismiss it.</li>
     *   <li><b>Hard limit reached (≥900 GB):</b> red-tinted banner — TURN is disabled.</li>
     * </ul>
     */
    private void checkAndShowTurnWarning() {
        if (bannerTurnWarning == null || tvTurnWarningText == null) return;
        TurnBandwidthTracker tracker = TurnBandwidthTracker.get(this);

        if (tracker.isLimitReached()) {
            tvTurnWarningText.setText(
                    "⚠\uFE0F Monthly TURN data cap reached (100 GB). "
                    + "This call uses direct connection only — it may not connect on mobile data or corporate networks.");
            bannerTurnWarning.setBackgroundColor(0xCC8B1A00);
            bannerTurnWarning.setVisibility(View.VISIBLE);
        } else if (tracker.isNearLimit()) {
            tvTurnWarningText.setText(
                    "⚠\uFE0F TURN relay usage is above 90 GB this month ("
                    + tracker.getSummary() + "). "
                    + "Calls may stop relaying near month end.");
            bannerTurnWarning.setBackgroundColor(0xCC7B2F00);
            bannerTurnWarning.setVisibility(View.VISIBLE);
        } else {
            bannerTurnWarning.setVisibility(View.GONE);
        }
    }

    // ── CallManager.CallListener ──────────────────────────────────────────────

    @Override
    public void onCallStateChanged(CallManager.CallState state) {
        runOnUiThread(() -> {
            updateStatusUi(state);
            // Refresh TURN banner on CONNECTED (relay may have been skipped due to cap).
            if (state == CallManager.CallState.CONNECTED) {
                checkAndShowTurnWarning();
            }
        });
    }

    @Override
    public void onRemoteVideoTrack(VideoTrack track) {
        runOnUiThread(() -> {
            remoteVideoView.setVisibility(View.VISIBLE);
            voiceOnlyBg.setVisibility(View.GONE);
            track.addSink(remoteVideoView);
        });
    }

    @Override
    public void onLocalVideoTrack(VideoTrack track) {
        runOnUiThread(() -> {
            localVideoPip.setVisibility(View.VISIBLE);
            track.addSink(localVideoView);
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            Log.e(TAG, "Call error: " + message);
        });
    }

    // ── Status UI ─────────────────────────────────────────────────────────────

    private void updateStatusUi(CallManager.CallState state) {
        String statusText;
        switch (state) {
            case OUTGOING_RINGING: statusText = isCaller ? "Ringing…" : "Connecting…"; break;
            case INCOMING_RINGING: statusText = "Incoming call…"; break;
            case CONNECTING:       statusText = "Connecting…"; break;
            case CONNECTED:
                statusText  = "";
                callStartMs = System.currentTimeMillis();
                durationHandler.post(durationTick);
                break;
            case ENDED:
                statusText = "Call ended";
                durationHandler.removeCallbacksAndMessages(null);
                // callStartMs > 0 means CONNECTED was reached at least once.
                saveCallRecord(callStartMs > 0 ? CallRecord.OUTCOME_ANSWERED : CallRecord.OUTCOME_MISSED);
                finish();
                return;
            case FAILED:
                statusText = "Call failed — network unavailable";
                durationHandler.removeCallbacksAndMessages(null);
                saveCallRecord(CallRecord.OUTCOME_FAILED);
                Toast.makeText(this, "Call failed — check your network", Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).postDelayed(this::finish, 2000);
                break;
            default: statusText = ""; break;
        }
        if (tvCallStatus        != null) tvCallStatus.setText(statusText);
        if (tvCallStatusOverlay != null) {
            tvCallStatusOverlay.setText(statusText);
            tvCallStatusOverlay.setVisibility(statusText.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ── Call history ──────────────────────────────────────────────────────────

    private void saveCallRecord(String outcome) {
        if (partnerId == null || myUid == null) return;
        long now         = System.currentTimeMillis();
        int  durationSec = callStartMs > 0 ? (int) ((now - callStartMs) / 1000) : 0;
        String direction = isCaller ? CallRecord.DIRECTION_OUTGOING : CallRecord.DIRECTION_INCOMING;

        CallRecord record = new CallRecord();
        record.id              = UUID.randomUUID().toString();
        record.partnerId       = partnerId;
        record.partnerName     = partnerName != null ? partnerName : partnerId;
        record.isVideo         = isVideo;
        record.direction       = direction;
        record.outcome         = outcome;
        record.startedAt       = callStartMs > 0 ? callStartMs : now;
        record.durationSeconds = durationSec;
        historyExecutor.execute(() ->
                AppDatabase.getInstance(getApplicationContext()).callHistoryDao().insert(record));
    }

    // ── Foreground service lifecycle ──────────────────────────────────────────

    /**
     * Starts {@link CallForegroundService}, which shows the ongoing call notification and
     * keeps the process alive when the user presses Home during a call.
     */
    private void startForegroundCallService() {
        Intent svcIntent = new Intent(this, CallForegroundService.class);
        svcIntent.setAction(CallForegroundService.ACTION_START);
        svcIntent.putExtra(CallForegroundService.EXTRA_PARTNER_NAME, partnerName);
        svcIntent.putExtra(CallForegroundService.EXTRA_CALL_ID, callId);
        svcIntent.putExtra(CallForegroundService.EXTRA_IS_VIDEO, isVideo);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent);
        } else {
            startService(svcIntent);
        }
    }

    /** Stops {@link CallForegroundService} — removes the ongoing notification. */
    private void stopForegroundCallService() {
        Intent svcIntent = new Intent(this, CallForegroundService.class);
        svcIntent.setAction(CallForegroundService.ACTION_STOP);
        startService(svcIntent);
    }

    // ── Proximity wake lock (screen off when held to ear) ─────────────────────

    /**
     * Acquires {@code PROXIMITY_SCREEN_OFF_WAKE_LOCK} for voice calls, which makes the OS
     * automatically turn the screen off when the proximity sensor detects a nearby object
     * (e.g. the user's cheek).  This prevents accidental touches and saves battery —
     * the same mechanism used by WhatsApp, Signal, and the stock Phone app.
     *
     * <p>Not used for video calls — the user needs to see the screen.
     */
    private void acquireProximityWakeLock() {
        if (isVideo) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = pm.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "duoshield:proximity_call");
            proximityWakeLock.setReferenceCounted(false);
            proximityWakeLock.acquire();
            Log.d(TAG, "Proximity wake lock acquired");
        }
    }

    /**
     * Releases the proximity wake lock.
     *
     * <p>Passes {@code PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY} (flag value 1) so
     * the screen only turns back on once the sensor reports "far" — prevents a brief
     * screen flash if the phone is still against the user's ear when the call ends.
     */
    private void releaseProximityWakeLock() {
        if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
            proximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            proximityWakeLock = null;
            Log.d(TAG, "Proximity wake lock released");
        }
    }

    // ── Renderer release ──────────────────────────────────────────────────────

    private void releaseVideoRenderers() {
        try {
            if (remoteVideoView != null) remoteVideoView.release();
            if (localVideoView  != null) localVideoView.release();
        } catch (Exception ignored) { }
    }
}
