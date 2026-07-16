package com.duoshield.app.call;

import android.content.Intent;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
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

        // Prefetch fresh TURN credentials from the push server (non-blocking).
        // By the time startCall/acceptCall fires ICE gathering, credentials are
        // typically ready; if the fetch is still in-flight the call falls back
        // to STUN-only and TurnCredentialFetcher logs a warning.
        TurnCredentialFetcher.prefetch();

        // Show TURN quota warning before the call starts so user knows what to expect.
        checkAndShowTurnWarning();

        // FIX #1: startCall()/acceptCall() both call initFactory() synchronously as their
        // first statement, which creates eglBase.  initVideoRenderers() must run AFTER
        // that so eglBase is non-null when the SurfaceViewRenderers are initialised.
        if (isCaller) {
            String chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
            callManager.startCall(myUid, partnerId, isVideo, chatId);
        } else {
            callManager.acceptCall(myUid, callId, isVideo);
        }
        initVideoRenderers(); // eglBase guaranteed non-null now

        updateStatusUi(CallManager.CallState.OUTGOING_RINGING);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        historyExecutor.shutdownNow();
        durationHandler.removeCallbacksAndMessages(null);
        if (callManager != null) {
            CallManager.CallState state = callManager.getCurrentState();
            if (state != CallManager.CallState.ENDED && state != CallManager.CallState.FAILED) {
                callManager.hangup();
            }
        }
        releaseVideoRenderers();
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_NORMAL);
            am.setSpeakerphoneOn(false);
        }
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
        if (am != null) {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(false);
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
                    "⚠\uFE0F Monthly TURN data cap reached (900 GB). "
                    + "This call uses direct connection only — it may not connect on mobile data or corporate networks.");
            bannerTurnWarning.setBackgroundColor(0xCC8B1A00);
            bannerTurnWarning.setVisibility(View.VISIBLE);
        } else if (tracker.isNearLimit()) {
            tvTurnWarningText.setText(
                    "⚠\uFE0F TURN relay usage is above 800 GB this month ("
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

    // ── Renderer release ──────────────────────────────────────────────────────

    private void releaseVideoRenderers() {
        try {
            if (remoteVideoView != null) remoteVideoView.release();
            if (localVideoView  != null) localVideoView.release();
        } catch (Exception ignored) { }
    }
}
