package com.duoshield.app.backup;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Periodic WorkManager task that syncs all local messages to the Firestore backup.
 *
 * Scheduled by {@link BackupScheduler} — runs once every 24 hours with a NETWORK_CONNECTED
 * constraint.  Idempotent: already-backed-up messages are re-encrypted and overwritten, which is
 * safe because the Firestore doc ID equals the Room message ID.
 *
 * Auth requirement: skips silently if no Firebase user is signed in (user has logged out).
 */
public final class BackupSyncWorker extends Worker {

    private static final String TAG = "BackupSyncWorker";

    public BackupSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.d(TAG, "No signed-in user — skipping backup sync.");
            return Result.success();
        }

        Log.d(TAG, "Starting scheduled backup sync");

        final CountDownLatch latch   = new CountDownLatch(1);
        final boolean[]      success = {false};

        BackupManager.syncIncremental(getApplicationContext(), result -> {
            Log.d(TAG, "Backup sync complete — written=" + result.written
                    + " failed=" + result.failed + " total=" + result.total);
            success[0] = result.failed == 0 || result.written > 0;
            latch.countDown();
        });

        try {
            latch.await(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Backup sync interrupted");
            return Result.retry();
        }

        return success[0] ? Result.success() : Result.retry();
    }
}
