package com.duoshield.app.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager job that clears {@code users/{uid}.fcmToken} in Firestore some time
 * after a sign-out, so the push server stops delivering notifications to this device.
 *
 * <h3>Why not just call FcmTokenHelper.unregister() synchronously?</h3>
 * A synchronous "sign-out at time T → token cleared at time T" write is a network
 * event with a fixed, observable timing relationship to the sign-out. For a
 * security-triggered sign-out that relies on plausible deniability (see
 * {@link com.duoshield.app.security.DuressManager#performLogout}), an attacker
 * watching network traffic could correlate "notifications stopped arriving the
 * instant this PIN was entered" with the PIN just entered being significant.
 * A jittered, WorkManager-scheduled write decouples that timing, and — because
 * WorkManager persists its queue — the job still runs even if the app process
 * dies or the device reboots before the delay elapses.
 *
 * <p>This same jittered de-registration is used for every sign-out path (duress
 * and ordinary), not just the duress one, so there's nothing to correlate: token
 * clearing always happens some random interval after any sign-out.
 */
public class FcmUnregisterWorker extends Worker {

    private static final String TAG     = "FcmUnregisterWorker";
    private static final String DATA_UID = "uid";

    /** Jitter window: 5-40 seconds after the sign-out that scheduled this job. */
    private static final long JITTER_MIN_MS = 5_000L;
    private static final long JITTER_RANGE_MS = 35_000L;

    public FcmUnregisterWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Schedules a jittered FCM token de-registration for {@code uid}. Safe to call
     * from any thread, including a background wipe thread that is about to clear
     * the SharedPreferences the UID would otherwise have been read from — {@code uid}
     * must therefore be captured by the caller BEFORE any such wipe.
     */
    public static void enqueue(Context ctx, String uid) {
        if (uid == null || uid.isEmpty()) return;
        long jitterMs = JITTER_MIN_MS + (long) (new SecureRandom().nextDouble() * JITTER_RANGE_MS);

        Data input = new Data.Builder().putString(DATA_UID, uid).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(FcmUnregisterWorker.class)
                .setInitialDelay(jitterMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(input)
                .addTag("fcm_unregister_" + uid)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext()).enqueue(request);
    }

    @NonNull
    @Override
    public Result doWork() {
        String uid = getInputData().getString(DATA_UID);
        if (uid == null || uid.isEmpty()) return Result.success();

        try {
            Tasks.await(
                    FirebaseFirestore.getInstance()
                            .collection("users").document(uid)
                            .set(Collections.singletonMap("fcmToken", ""), SetOptions.merge()),
                    20, TimeUnit.SECONDS);
            Log.d(TAG, "FCM token cleared for signed-out account.");
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "FCM unregister failed — will retry: " + e.getMessage());
            return Result.retry();
        }
    }
}
