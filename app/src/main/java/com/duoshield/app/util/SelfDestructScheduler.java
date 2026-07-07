package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.duoshield.app.db.SelfDestructWorker;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SelfDestructScheduler {

    private static final String WORK_TAG     = "self_destruct_work";
    private static final String PREFS_NAME   = "duoshield_prefs";
    private static final String KEY_DISAPPEAR = "disappear_ms";

    /**
     * Schedules the periodic self-destruct worker only when a non-zero disappearing-
     * messages duration is configured for the given conversation.
     * If {@code disappear_ms_<convId>} is 0 (off), any existing job is cancelled (BUG-D08).
     *
     * <p>F26 fix: key is now scoped per conversation to prevent cross-conversation timer bleed.
     *
     * @param convId the conversationId whose timer pref is checked
     */
    public static void schedule(Context ctx, String convId) {
        // F26 fix: scan ALL disappear_ms_* prefs — only cancel the global worker when
        // NO conversation has disappearing messages enabled.  Checking a single key was
        // wrong: turning off one conversation's timer cancelled work for all others.
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean anyEnabled = false;
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getKey().startsWith(KEY_DISAPPEAR + "_") && e.getValue() instanceof Long) {
                if ((Long) e.getValue() > 0L) { anyEnabled = true; break; }
            }
        }
        if (!anyEnabled) {
            cancel(ctx);
            return;
        }
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
            SelfDestructWorker.class, 15, TimeUnit.MINUTES)
            .addTag(WORK_TAG).build();
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP, req);
    }

    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(WORK_TAG);
    }
}
