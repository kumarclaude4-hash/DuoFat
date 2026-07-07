package com.duoshield.app.call;

import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.CallRecord;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.webrtc.EglBase;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

/**
 * Full-screen call UI — voice or video.
 *
 * <p>Started by either:
 * <ul>
 *   <li>The caller (via {@link ChatMediaActivity}) with {@code is_caller=true}</li>
 *   <li>The callee (via {@link IncomingCallActivity}) with {@code is_caller=false}</li>
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

    // Views
    private SurfaceViewRenderer remoteVideoView;
    private SurfaceViewRenderer localVideoView;
    private View localVideoPip;
    private View voiceOnlyBg;
    private TextView tvCallPartnerName;
    private TextView tvCallStatus;
    private TextView tvCallDuration;
    private TextView tvCallStatusOverlay;
    private TextView tvCallAvatarInitial;
    private ImageView btnMute;
    private ImageView btnCamera;
    private View btnCameraLayout;
    private ImageView btnEndCall;
    private ImageView btnSpeaker;
    private ImageView btnFlipCamera;
    private View btnFlipLayout;

    private boolean isMuted = false;
    private boolean isCameraOff = false;
    private boolean isSpeakerOn = false;
    private boolean isCaller;
    private boolean isVideo;
    private String partnerName;
    private String myUid;
    private String callId;
    private String partnerId;

    private final Handler durationHandler = new Handler(Looper.getMainLooper());
    private long callStartMs = 0;
    private final Runnable durationTick = new Runnable() {
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

        initVideoRenderers();

        if (isCaller) {
            String chatId = getIntent().getStringExtra(EXTRA_CHAT_ID);
            callManager.startCall(myUid, partnerId, isVideo, chatId);
        } else {
            callManager.acceptCall(myUid, callId, isVideo);
        }

        updateStatusUi(CallManager.CallState.OUTGOING_RINGING);
    }

    private void bindViews() {
        remoteVideoView    = findViewById(R.id.remoteVideoView);
        localVideoView     = findViewById(R.id.localVideoView);
        localVideoPip      = findViewById(R.id.localVideoPip);
        voiceOnlyBg        = findViewById(R.id.voiceOnlyBg);
        tvCallPartnerName  = findViewById(R.id.tvCallPartnerName);
        tvCallStatus       = findViewById(R.id.tvCallStatus);
        tvCallDuration     = findViewById(R.id.tvCallDuration);
        tvCallStatusOverlay = findViewById(R.id.tvCallStatusOverlay);
        tvCallAvatarInitial = findViewById(R.id.tvCallAvatarInitial);
        btnMute            = findViewById(R.id.btnMute);
        btnCamera          = findViewById(R.id.btnCamera);
        btnCameraLayout    = findViewById(R.id.btnCameraLayout);
        btnEndCall         = findViewById(R.id.btnEndCall);
        btnSpeaker         = findViewById(R.id.btnSpeaker);
        btnFlipCamera      = findViewById(R.id.btnFlipCamera);
        btnFlipLayout      = findViewById(R.id.btnFlipLayout);

        tvCallPartnerName.setText(partnerName);
        tvCallAvatarInitial.setText(partnerName.substring(0, 1).toUpperCase());

        if (isVideo) {
            btnCameraLayout.setVisibility(View.VISIBLE);
            btnFlipLayout.setVisibility(View.VISIBLE);
        }
    }

    private void setupButtons() {
        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            callManager.setMuted(isMuted);
            btnMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic);
            btnMute.setAlpha(isMuted ? 0.5f : 1f);
        });

        btnCamera.setOnClickListener(v -> {
            isCameraOff = !isCameraOff;
            callManager.setCameraEnabled(!isCameraOff);
            btnCamera.setAlpha(isCameraOff ? 0.5f : 1f);
            localVideoPip.setVisibility(isCameraOff ? View.GONE : View.VISIBLE);
        });

        btnEndCall.setOnClickListener(v -> {
            callManager.hangup();
            finish();
        });

        btnSpeaker.setOnClickListener(v -> {
            isSpeakerOn = !isSpeakerOn;
            callManager.setSpeakerOn(isSpeakerOn);
            btnSpeaker.setAlpha(isSpeakerOn ? 1f : 0.5f);
        });

        btnFlipCamera.setOnClickListener(v -> callManager.flipCamera());
    }

    private void setupAudio() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            am.setSpeakerphoneOn(false);
        }
    }

    private void initVideoRenderers() {
        EglBase eglBase = callManager.getEglBase();
        if (eglBase == null) return;
        remoteVideoView.init(eglBase.getEglBaseContext(), null);
        remoteVideoView.setMirror(false);
        localVideoView.init(eglBase.getEglBaseContext(), null);
        localVideoView.setMirror(true);
    }

    // ─── CallManager.CallListener ────────────────────────────────────────────

    @Override
    public void onCallStateChanged(CallManager.CallState state) {
        runOnUiThread(() -> updateStatusUi(state));
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

    private void updateStatusUi(CallManager.CallState state) {
        String statusText;
        switch (state) {
            case OUTGOING_RINGING: statusText = isCaller ? "Ringing…" : "Connecting…"; break;
            case INCOMING_RINGING: statusText = "Incoming call…"; break;
            case CONNECTING:       statusText = "Connecting…"; break;
            case CONNECTED:
                statusText = "";
                callStartMs = System.currentTimeMillis();
                durationHandler.post(durationTick);
                break;
            case ENDED:
                statusText = "Call ended";
                durationHandler.removeCallbacksAndMessages(null);
                saveCallRecord(CallRecord.OUTCOME_ANSWERED);
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
        if (tvCallStatus != null) tvCallStatus.setText(statusText);
        if (tvCallStatusOverlay != null) {
            tvCallStatusOverlay.setText(statusText);
            tvCallStatusOverlay.setVisibility(statusText.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void saveCallRecord(String outcome) {
        if (partnerId == null || myUid == null) return;
        long now = System.currentTimeMillis();
        int durationSec = callStartMs > 0
                ? (int) ((now - callStartMs) / 1000)
                : 0;
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

    private void releaseVideoRenderers() {
        try {
            if (remoteVideoView != null) remoteVideoView.release();
            if (localVideoView != null) localVideoView.release();
        } catch (Exception ignored) {}
    }
}
