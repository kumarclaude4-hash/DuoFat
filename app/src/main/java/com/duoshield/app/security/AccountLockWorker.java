package com.duoshield.app.security;

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
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager job that writes {@code accountLock/{uid}.locked = true} to Firestore
 * some time after {@link DuressManager#performLogout}.
 *
 * <h3>Why this exists</h3>
 * {@link DuressManager}'s local wipe (SecurePrefs, Room DB, all SharedPreferences)
 * cannot survive an uninstall — the very first thing an attacker who suspects
 * something is wrong is likely to try is uninstalling and reinstalling the app to
 * get a "fresh" restore screen. Without a server-side record, that reinstall would
 * succeed with nothing but the seed phrase and Account ID coerced from the victim.
 * This flag is checked by {@code RestoreFromSeedActivity} on every restore attempt
 * for the account, on any device, so the restriction survives the reinstall.
 *
 * <h3>Why delayed/jittered instead of a synchronous write inside performLogout()</h3>
 * Same reasoning as {@link com.duoshield.app.util.FcmUnregisterWorker}: a write that
 * always lands some fixed instant after the triggering PIN entry is itself an
 * observable pattern. WorkManager's persistent queue means the job still fires even
 * if the process is killed or the device reboots before the delay elapses.
 *
 * <h3>Clearing the flag</h3>
 * Not yet implemented client-side — see docs/DURESS_PIN_SECURITY_PLAN.md §8. Until a
 * normalization flow exists, clearing an {@code accountLock} doc is a manual,
 * out-of-band operation (Firebase console / Admin SDK), the same posture already
 * used for {@code duressEligibility} enrollment.
 */
public class AccountLockWorker extends Worker {

    private static final String TAG      = "AccountLockWorker";
    private static final String DATA_UID = "uid";

    /** Jitter window: 5-40 seconds, matching FcmUnregisterWorker. */
    private static final long JITTER_MIN_MS   = 5_000L;
    private static final long JITTER_RANGE_MS = 35_000L;

    public AccountLockWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Schedules a jittered account-lock write for {@code uid}. {@code uid} must be
     * captured by the caller before any local wipe removes the means to read it.
     */
    public static void enqueue(Context ctx, String uid) {
        if (uid == null || uid.isEmpty()) return;
        long jitterMs = JITTER_MIN_MS + (long) (new SecureRandom().nextDouble() * JITTER_RANGE_MS);

        Data input = new Data.Builder().putString(DATA_UID, uid).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AccountLockWorker.class)
                .setInitialDelay(jitterMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(input)
                .addTag("account_lock_" + uid)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext()).enqueue(request);
    }

    @NonNull
    @Override
    public Result doWork() {
        String uid = getInputData().getString(DATA_UID);
        if (uid == null || uid.isEmpty()) return Result.success();

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("locked", true);
            data.put("lockedAt", FieldValue.serverTimestamp());
            Tasks.await(
                    FirebaseFirestore.getInstance()
                            .collection("accountLock").document(uid)
                            .set(data, SetOptions.merge()),
                    20, TimeUnit.SECONDS);
            Log.d(TAG, "Account lock flag written.");
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Account lock write failed — will retry: " + e.getMessage());
            return Result.retry();
        }
    }
}
