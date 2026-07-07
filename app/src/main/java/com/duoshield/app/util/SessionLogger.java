package com.duoshield.app.util;

import android.content.Context;
import android.os.Build;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.SessionEvent;
import java.util.concurrent.Executors;

/**
 * Records sign-in and sign-out events to the local Room database.
 * All writes are fire-and-forget on a background thread.
 * No network calls — log is entirely local and offline.
 */
public class SessionLogger {

    public static final String SIGN_IN       = "SIGN_IN";
    public static final String SIGN_OUT      = "SIGN_OUT";
    public static final String AUTO_SIGN_OUT = "AUTO_SIGN_OUT";
    public static final String DURESS_LOGOUT = "DURESS_LOGOUT";

    /**
     * Shared single-thread executor — one instance for the process lifetime.
     *
     * <p>The previous implementation called {@code Executors.newSingleThreadExecutor()}
     * on every {@link #log} call, allocating and immediately abandoning a new thread pool
     * for each event.  A static executor reuses the same background thread (BUG-Q03).
     */
    private static final java.util.concurrent.ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor();

    public static void log(Context context, String eventType) {
        SessionEvent event = new SessionEvent(
            eventType,
            System.currentTimeMillis(),
            Build.MANUFACTURER + " " + Build.MODEL,
            "Android " + Build.VERSION.RELEASE
        );
        EXECUTOR.execute(() -> {
            try {
                AppDatabase.getInstance(context.getApplicationContext())
                    .sessionEventDao()
                    .insert(event);
            } catch (Exception e) {
                android.util.Log.e("SessionLogger", "Failed to persist session event: " + eventType, e);
            }
        });
    }

    /**
     * Synchronous variant — writes directly on the calling thread (blocking).
     *
     * <p>Must only be called from a background thread, never the main thread.
     * Used by {@link com.duoshield.app.security.DuressManager#performLogout}
     * to guarantee the sign-out row is inserted <em>before</em>
     * {@code AppDatabase.clearInstance()} runs, providing the ordering guarantee
     * that the async {@link #log} cannot (F16 fix).
     */
    public static void logSync(Context context, String eventType) {
        SessionEvent event = new SessionEvent(
            eventType,
            System.currentTimeMillis(),
            Build.MANUFACTURER + " " + Build.MODEL,
            "Android " + Build.VERSION.RELEASE
        );
        try {
            AppDatabase.getInstance(context.getApplicationContext())
                .sessionEventDao()
                .insert(event);
        } catch (Exception e) {
            android.util.Log.e("SessionLogger", "logSync failed for: " + eventType, e);
        }
    }
}
