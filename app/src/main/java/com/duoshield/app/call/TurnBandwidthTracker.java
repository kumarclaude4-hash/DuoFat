package com.duoshield.app.call;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tracks cumulative TURN relay bytes consumed in the current calendar month and
 * enforces an internal hard cap of {@link #MONTHLY_LIMIT_BYTES} (900 GB) to stay
 * safely below Cloudflare's 1 TB free-tier allowance.
 *
 * <p><b>How it works:</b>
 * <ul>
 *   <li>Each call reports its total transport bytes (sent + received) via
 *       {@link #recordCallBytes(long)} when the call ends.</li>
 *   <li>On the first call of a new calendar month the counter automatically resets.</li>
 *   <li>{@link #isLimitReached()} returns {@code true} when usage ≥ 900 GB.
 *       {@link com.duoshield.app.call.CallManager} checks this before adding any TURN
 *       ICE server entry, falling back to STUN-only mode so the user gets a clear
 *       "TURN quota exhausted" warning instead of a silent call failure.</li>
 * </ul>
 *
 * <p><b>Storage:</b> {@code duoshield_turn_tracker} SharedPreferences (plaintext — bytes
 * transferred are not sensitive data).
 */
public class TurnBandwidthTracker {

    private static final String TAG = "TurnBandwidthTracker";

    /** 900 GB in bytes — hard monthly TURN cap. */
    public static final long MONTHLY_LIMIT_BYTES = 900L * 1024L * 1024L * 1024L;

    /** Warning threshold — 800 GB. Used to show an early-warning in call UI. */
    public static final long WARNING_THRESHOLD_BYTES = 800L * 1024L * 1024L * 1024L;

    private static final String PREFS_NAME    = "duoshield_turn_tracker";
    private static final String KEY_MONTH     = "turn_month";       // "YYYY-MM"
    private static final String KEY_BYTES     = "turn_bytes";       // long
    private static final String KEY_CALLS     = "turn_calls";       // int count for diagnostics

    private static final SimpleDateFormat MONTH_FMT =
            new SimpleDateFormat("yyyy-MM", Locale.US);

    private static volatile TurnBandwidthTracker instance;

    private final SharedPreferences prefs;

    private TurnBandwidthTracker(Context context) {
        prefs = context.getApplicationContext()
                       .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns the singleton, creating it on first call. */
    public static TurnBandwidthTracker get(Context context) {
        if (instance == null) {
            synchronized (TurnBandwidthTracker.class) {
                if (instance == null) {
                    instance = new TurnBandwidthTracker(context);
                }
            }
        }
        return instance;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Records {@code bytes} bytes consumed by a completed call.
     *
     * <p>Thread-safe; can be called from any thread.
     *
     * @param bytes total transport bytes (sent + received) for the call
     */
    public synchronized void recordCallBytes(long bytes) {
        if (bytes <= 0) return;
        rollMonthIfNeeded();

        long current = prefs.getLong(KEY_BYTES, 0L);
        int  calls   = prefs.getInt(KEY_CALLS, 0);
        long updated = current + bytes;

        prefs.edit()
             .putLong(KEY_BYTES, updated)
             .putInt(KEY_CALLS, calls + 1)
             .apply();

        Log.i(TAG, String.format(Locale.US,
                "TURN usage recorded: +%s this call | month total: %s / %s GB",
                humanBytes(bytes), humanBytes(updated), humanBytes(MONTHLY_LIMIT_BYTES)));

        if (updated >= MONTHLY_LIMIT_BYTES) {
            Log.e(TAG, "⚠️  TURN monthly cap REACHED — TURN disabled until next month.");
        } else if (updated >= WARNING_THRESHOLD_BYTES) {
            Log.w(TAG, String.format(Locale.US,
                    "⚠️  TURN usage warning: %.1f GB of 900 GB used this month.",
                    updated / (1024.0 * 1024.0 * 1024.0)));
        }
    }

    /**
     * Returns {@code true} if the 900 GB monthly cap has been reached.
     * When this is {@code true} the app should skip adding TURN ICE servers.
     */
    public synchronized boolean isLimitReached() {
        rollMonthIfNeeded();
        return prefs.getLong(KEY_BYTES, 0L) >= MONTHLY_LIMIT_BYTES;
    }

    /**
     * Returns {@code true} if usage has crossed the 800 GB early-warning threshold.
     * The call UI shows a banner when this is {@code true}.
     */
    public synchronized boolean isNearLimit() {
        rollMonthIfNeeded();
        long used = prefs.getLong(KEY_BYTES, 0L);
        return used >= WARNING_THRESHOLD_BYTES && used < MONTHLY_LIMIT_BYTES;
    }

    /** Returns bytes used so far this calendar month. */
    public synchronized long getUsedBytes() {
        rollMonthIfNeeded();
        return prefs.getLong(KEY_BYTES, 0L);
    }

    /** Returns bytes remaining before the monthly cap. */
    public synchronized long getRemainingBytes() {
        return Math.max(0L, MONTHLY_LIMIT_BYTES - getUsedBytes());
    }

    /** Human-readable used/total string, e.g. "342.7 GB / 900 GB". */
    public synchronized String getSummary() {
        long used = getUsedBytes();
        return String.format(Locale.US, "%.2f GB / 900 GB used this month",
                used / (1024.0 * 1024.0 * 1024.0));
    }

    /** Returns the current month key (YYYY-MM). */
    public String getCurrentMonthKey() {
        return MONTH_FMT.format(new Date());
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Resets the counter if we have entered a new calendar month.
     * Must be called inside a {@code synchronized} block.
     */
    private void rollMonthIfNeeded() {
        String thisMonth = MONTH_FMT.format(new Date());
        String storedMonth = prefs.getString(KEY_MONTH, "");
        if (!thisMonth.equals(storedMonth)) {
            long oldBytes = prefs.getLong(KEY_BYTES, 0L);
            Log.i(TAG, String.format(Locale.US,
                    "New month (%s → %s) — resetting TURN counter. Previous month used: %s",
                    storedMonth, thisMonth, humanBytes(oldBytes)));
            prefs.edit()
                 .putString(KEY_MONTH, thisMonth)
                 .putLong(KEY_BYTES, 0L)
                 .putInt(KEY_CALLS, 0)
                 .apply();
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
