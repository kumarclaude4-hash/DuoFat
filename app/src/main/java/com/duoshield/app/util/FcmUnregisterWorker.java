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
import com.google.firebase.messaging.FirebaseMessaging;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager job that de-registers this device's FCM token some time after a
 * sign-out, so the push server stops delivering notifications to this device.
 *
 * <h3>Why not just call FcmTokenHelper.unregister() synchronously?</h3>
 * A synchronous "sign-out at time T → token cleared at time T" write is a network
 * event with a fixed, observable timing relationship to the sign-out. For a
 * security-triggered sign-out that relies on plausible deniability (see
 * {@link com.duoshield.app.security.DuressManager#performLogout}), an attacker
 * watching network traffic could correlate "notifications stopped arriving the
 * instant this PIN was entered" with the PIN just entered being significant.
 * A jittered, WorkManager-scheduled delete decouples that timing, and — because
 * WorkManager persists its queue — the job still runs even after a reboot.
 *
 * <p>This same jittered de-registration is used for every sign-out path (duress
 * and ordinary), not just the duress one, so there is nothing to correlate.
 *
 * <h3>Auth: FCM's own deleteToken() instead of a stored bearer token</h3>
 * Previous versions stored the user's Firebase ID token in WorkManager's persistent
 * input data to authenticate a post-sign-out Firestore write. Storing a reusable
 * owner credential on disk after a wipe is a security risk (it survives the wipe
 * and is valid for up to one hour). {@link FirebaseMessaging#deleteToken()} does
 * not require any caller-supplied auth — the FCM SDK manages its own registration
 * independently of the Firebase Auth session — so no credential is stored at all.
 */
public class FcmUnregisterWorker extends Worker {

    private static final String TAG      = "FcmUnregisterWorker";
    private static final String DATA_UID = "uid";

    /** Jitter window: 5-40 seconds after the sign-out that scheduled this job. */
    private static final long JITTER_MIN_MS   = 5_000L;
    private static final long JITTER_RANGE_MS = 35_000L;

    public FcmUnregisterWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Schedules a jittered FCM token de-registration for {@code uid}.
     * No bearer token required — {@link FirebaseMessaging#deleteToken()} handles
     * its own authentication via the FCM SDK's device registration state.
     */
    public static void enqueue(Context ctx, String uid) {
        if (uid == null || uid.isEmpty()) {
            Log.w(TAG, "enqueue skipped — missing uid.");
            return;
        }
        long jitterMs = JITTER_MIN_MS + (long) (new SecureRandom().nextDouble() * JITTER_RANGE_MS);

        Data input = new Data.Builder()
                .putString(DATA_UID, uid)
                .build();
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
            // deleteToken() invalidates this device's FCM registration token at the
            // FCM protocol level — no Firebase Auth session required. The push server
            // will naturally stop delivering to a deleted token on its next attempt.
            Tasks.await(FirebaseMessaging.getInstance().deleteToken(), 30, TimeUnit.SECONDS);
            Log.d(TAG, "FCM token deleted for signed-out account (" + uid + ").");
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "FCM token delete failed — will retry: " + e.getMessage());
            return Result.retry();
        }
    }
}
