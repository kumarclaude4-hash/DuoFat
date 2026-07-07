package com.duoshield.app.backup;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Schedules and cancels the daily {@link BackupSyncWorker}.
 *
 * <ul>
 *   <li>Call {@link #schedule(Context)} after a successful sign-in or restore — e.g. from
 *       {@code ConversationListActivity.onCreate()} and {@code RestoreFromSeedActivity}.</li>
 *   <li>Call {@link #cancel(Context)} from {@link com.duoshield.app.util.WipeHelper} and
 *       {@link com.duoshield.app.security.DuressManager} so the worker stops when the session
 *       is intentionally cleared.</li>
 * </ul>
 *
 * Uses {@link ExistingPeriodicWorkPolicy#KEEP} so re-scheduling on every app launch does not
 * reset the 24-hour window — the existing enqueue is preserved as-is.
 */
public final class BackupScheduler {

    private static final String TAG       = "BackupScheduler";
    private static final String WORK_NAME = "DuoShield_BackupSync";

    private BackupScheduler() {}

    /**
     * Enqueues a daily backup sync job.  No-op if already scheduled (KEEP policy).
     * Safe to call from the main thread — WorkManager schedules on its own threads.
     */
    public static void schedule(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(BackupSyncWorker.class, 24, TimeUnit.HOURS)
                        .setConstraints(constraints)
                        .setInitialDelay(1, TimeUnit.HOURS)
                        .build();

        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);

        Log.d(TAG, "Backup sync scheduled (24 h, network-connected).");
    }

    /**
     * Cancels any pending backup sync jobs.  Call on wipe / duress logout.
     */
    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx.getApplicationContext()).cancelUniqueWork(WORK_NAME);
        Log.d(TAG, "Backup sync cancelled.");
    }
}
