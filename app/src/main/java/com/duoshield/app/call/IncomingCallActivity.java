package com.duoshield.app.call;

import android.app.KeyguardManager;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.CallRecord;
import com.duoshield.app.notifications.NotificationStyler;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen incoming call screen, shown over the lock screen.
 *
 * <p>Auto-times out after 30 seconds, writing {@code status: "timeout"} and
 * showing a missed-call notification.
 */
public class IncomingCallActivity extends AppCompatActivity {

    private static final String TAG = "IncomingCallActivity";
    public static final String EXTRA_CALL_ID     = "call_id";
    public static final String EXTRA_CALLER_ID   = "caller_id";
    public static final String EXTRA_CALLER_NAME = "caller_name";
    public static final String EXTRA_IS_VIDEO    = "is_video";

    private static final int TIMEOUT_SECONDS = 30;

    private String callId;
    private String callerId;
    private String callerName;
    private boolean isVideo;

    private Ringtone ringtone;
    private Vibrator vibrator;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();
    private boolean handled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over the lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        setContentView(R.layout.activity_incoming_call);

        callId     = getIntent().getStringExtra(EXTRA_CALL_ID);
        callerId   = getIntent().getStringExtra(EXTRA_CALLER_ID);
        callerName = getIntent().getStringExtra(EXTRA_CALLER_NAME);
        isVideo    = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);

        if (callerName == null) callerName = "Unknown";

        TextView tvName = findViewById(R.id.tvIncomingCallerName);
        TextView tvType = findViewById(R.id.tvIncomingCallType);
        TextView tvInitial = findViewById(R.id.tvIncomingAvatarInitial);

        tvName.setText(callerName);
        tvInitial.setText(callerName.substring(0, 1).toUpperCase());
        tvType.setText(isVideo ? "Incoming video call" : "Incoming voice call");

        ImageView btnAccept = findViewById(R.id.btnAcceptCall);
        ImageView btnDecline = findViewById(R.id.btnDeclineCall);

        btnAccept.setOnClickListener(v -> acceptCall());
        btnDecline.setOnClickListener(v -> declineCall());

        startRinging();
        startTimeout();
    }

    private void startRinging() {
        try {
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(this, ringtoneUri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.setLooping(true);
            }
            ringtone.play();
        } catch (Exception e) {
            Log.w(TAG, "Ringtone failed: " + e.getMessage());
        }

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 500, 500};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
    }

    private void stopRinging() {
        try { if (ringtone != null && ringtone.isPlaying()) ringtone.stop(); } catch (Exception ignored) {}
        try { if (vibrator != null) vibrator.cancel(); } catch (Exception ignored) {}
    }

    private void startTimeout() {
        timeoutHandler.postDelayed(() -> {
            if (!handled) {
                Log.d(TAG, "Call timed out after " + TIMEOUT_SECONDS + "s");
                onTimeout();
            }
        }, TIMEOUT_SECONDS * 1000L);
    }

    private void acceptCall() {
        if (handled) return;
        handled = true;
        stopRinging();
        timeoutHandler.removeCallbacksAndMessages(null);

        Intent intent = new Intent(this, CallActivity.class);
        intent.putExtra(CallActivity.EXTRA_CALL_ID,   callId);
        intent.putExtra(CallActivity.EXTRA_CALLER_ID, callerId);
        intent.putExtra(CallActivity.EXTRA_IS_VIDEO,  isVideo);
        intent.putExtra(CallActivity.EXTRA_IS_CALLER, false);
        intent.putExtra(CallActivity.EXTRA_PARTNER_NAME, callerName);
        startActivity(intent);
        finish();
    }

    private void declineCall() {
        if (handled) return;
        handled = true;
        stopRinging();
        timeoutHandler.removeCallbacksAndMessages(null);

        if (callId != null) {
            CallManager tmp = new CallManager(this);
            tmp.declineCall(callId);
        }
        saveMissedRecord(CallRecord.OUTCOME_DECLINED);
        finish();
    }

    private void onTimeout() {
        handled = true;
        stopRinging();

        if (callId != null) {
            CallManager tmp = new CallManager(this);
            tmp.timeoutCall(callId);
        }

        saveMissedRecord(CallRecord.OUTCOME_MISSED);
        // Show missed-call notification
        NotificationStyler.showMissedCall(this, callerName, callId);
        finish();
    }

    private void saveMissedRecord(String outcome) {
        if (callerId == null) return;
        CallRecord record = new CallRecord();
        record.id              = UUID.randomUUID().toString();
        record.partnerId       = callerId;
        record.partnerName     = callerName != null ? callerName : callerId;
        record.isVideo         = isVideo;
        record.direction       = CallRecord.DIRECTION_INCOMING;
        record.outcome         = outcome;
        record.startedAt       = System.currentTimeMillis();
        record.durationSeconds = 0;
        historyExecutor.execute(() ->
                AppDatabase.getInstance(getApplicationContext()).callHistoryDao().insert(record));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRinging();
        timeoutHandler.removeCallbacksAndMessages(null);
        historyExecutor.shutdownNow();
    }
}
