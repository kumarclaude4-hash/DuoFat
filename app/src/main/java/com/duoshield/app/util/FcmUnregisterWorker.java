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

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
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
 *
 * <h3>Authenticating a write that runs after sign-out</h3>
 * By design this job fires well after sign-out has already happened, so there is
 * no live Firebase session left to authenticate the write with. The caller must
 * capture a Firebase ID token from the user object <em>before</em> calling
 * {@code signOut()} and pass it into {@link #enqueue}; {@link #doWork} authenticates
 * the Firestore REST call with that token via {@link FirestoreRestWriter} instead of
 * relying on the SDK's (by-then absent) ambient signed-in state.
 */
public class FcmUnregisterWorker extends Worker {

    private static final String TAG        = "FcmUnregisterWorker";
    private static final String DATA_UID   = "uid";
    private static final String DATA_TOKEN = "id_token";

    /** Jitter window: 5-40 seconds after the sign-out that scheduled this job. */
    private static final long JITTER_MIN_MS = 5_000L;
    private static final long JITTER_RANGE_MS = 35_000L;

    public FcmUnregisterWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Schedules a jittered FCM token de-registration for {@code uid}, authenticated
     * with {@code idToken}. Both must be captured by the caller BEFORE sign-out and
     * BEFORE any wipe that would clear the SharedPreferences the uid would otherwise
     * have been read from — see the class javadoc.
     */
    public static void enqueue(Context ctx, String uid, String idToken) {
        if (uid == null || uid.isEmpty() || idToken == null || idToken.isEmpty()) {
            Log.w(TAG, "enqueue skipped — missing uid or ID token, write would be unauthenticated.");
            return;
        }
        long jitterMs = JITTER_MIN_MS + (long) (new SecureRandom().nextDouble() * JITTER_RANGE_MS);

        Data input = new Data.Builder()
                .putString(DATA_UID, uid)
                .putString(DATA_TOKEN, idToken)
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
        String idToken = getInputData().getString(DATA_TOKEN);
        if (uid == null || uid.isEmpty()) return Result.success();

        try {
            Map<String, JSONObject> fields = new HashMap<>();
            fields.put("fcmToken", FirestoreRestWriter.stringValue(""));
            FirestoreRestWriter.mergeDocument(idToken, "users", uid, fields);
            Log.d(TAG, "FCM token cleared for signed-out account.");
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "FCM unregister failed — will retry: " + e.getMessage());
            return Result.retry();
        }
    }
}
