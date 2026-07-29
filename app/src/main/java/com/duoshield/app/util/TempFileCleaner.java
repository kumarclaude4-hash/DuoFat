package com.duoshield.app.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager worker that deletes AES-decrypted temp files from getCacheDir().
 *
 * DuoShield writes decrypted voice (.3gp) and video (.mp4) bytes to the cache
 * directory just before playback.  These files must not persist — they contain
 * plaintext media.
 *
 * Deletion policy:
 *   • Prefixes matched: "voice_*.3gp"  and  "vid_*.mp4"
 *   • Maximum age:      {@value MAX_AGE_MS} ms  (5 minutes)
 *   • Run schedule:     every {@value INTERVAL_MIN} minutes (WorkManager minimum)
 *
 * Schedule once at app start via {@link #schedule(Context)}.
 * Uses {@link ExistingPeriodicWorkPolicy#KEEP} so subsequent app launches do
 * not reset the timer.
 */
public class TempFileCleaner extends Worker {

    private static final String TAG          = "TempFileCleaner";
    private static final String WORK_TAG     = "duoshield_temp_cleanup";
    private static final long   MAX_AGE_MS   = 5L * 60 * 1000;   // 5 minutes
    private static final int    INTERVAL_MIN = 15;                // WorkManager minimum

    public TempFileCleaner(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        File cacheDir = getApplicationContext().getCacheDir();
        if (cacheDir == null || !cacheDir.exists()) return Result.success();

        File[] files = cacheDir.listFiles();
        if (files == null) return Result.success();

        long now     = System.currentTimeMillis();
        int  deleted = 0;

        for (File f : files) {
            if (!isTempMediaFile(f.getName())) continue;
            long ageMs = now - f.lastModified();
            if (ageMs >= MAX_AGE_MS) {
                if (f.delete()) {
                    deleted++;
                    Log.d(TAG, "Deleted temp media: " + f.getName()
                            + " (age " + (ageMs / 1000) + "s)");
                } else {
                    Log.w(TAG, "Could not delete: " + f.getName());
                }
            }
        }

        if (deleted > 0) {
            Log.i(TAG, "Cleaned " + deleted + " decrypted temp file(s) from cache.");
        }
        return Result.success();
    }

    /**
     * Schedules the periodic cleanup job once at app launch.
     * Safe to call on every app start — {@link ExistingPeriodicWorkPolicy#KEEP}
     * leaves an already-queued job untouched.
     */
    public static void schedule(Context context) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                TempFileCleaner.class, INTERVAL_MIN, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build();
        WorkManager.getInstance(context)
                   .enqueueUniquePeriodicWork(
                           WORK_TAG,
                           ExistingPeriodicWorkPolicy.KEEP,
                           req);
        Log.d(TAG, "TempFileCleaner scheduled (interval=" + INTERVAL_MIN + "min).");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean isTempMediaFile(String name) {
        return (name.startsWith("voice_")           && name.endsWith(".3gp"))
            || (name.startsWith("voice_")           && name.endsWith(".m4a"))  // F25 fix: recorder uses .m4a
            || (name.startsWith("vid_")             && name.endsWith(".mp4"))
            || (name.startsWith("share_")           && name.endsWith(".jpg"))
            // F15 fix: ExportHelper now writes .txt (PDF export was removed); .pdf kept
            // for any stale files left over from the previous export format.
            || (name.startsWith("duoshield_export_") && name.endsWith(".pdf"))
            || (name.startsWith("duoshield_export_") && name.endsWith(".txt"));
    }
}
