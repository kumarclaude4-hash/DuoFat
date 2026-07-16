package com.duoshield.app.crypto.signal;

import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Schedules the periodic {@link SignedPreKeyRotationWorker}.
 *
 * <p>Call {@link #schedule(Context)} once from
 * {@link com.duoshield.app.DuoShieldApp#onCreate()}. WorkManager's
 * {@link ExistingPeriodicWorkPolicy#KEEP} means re-scheduling on every app
 * launch does not reset the timer — the existing work item is reused.
 *
 * <p>The worker runs once per day but only actually rotates keys when the
 * current signed pre-key is at least 7 days old
 * (see {@link SignedPreKeyRotationWorker#ROTATION_INTERVAL_MS}).
 */
public final class SignedPreKeyScheduler {

    private static final String WORK_TAG = "SignalSignedPreKeyRotation";

    private SignedPreKeyScheduler() {}

    /**
     * Enqueues a periodic check that runs once per day.
     * Safe to call multiple times — KEEP policy prevents re-queuing.
     *
     * @param ctx Application context.
     */
    public static void schedule(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                SignedPreKeyRotationWorker.class, 1, TimeUnit.DAYS)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        WORK_TAG,
                        ExistingPeriodicWorkPolicy.KEEP,
                        req);
    }
}
