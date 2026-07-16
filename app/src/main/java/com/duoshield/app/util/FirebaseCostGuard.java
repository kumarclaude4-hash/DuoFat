package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

/**
 * Bug 13 fix: converted to a per-day singleton so the 80% quota warning fires
 * reliably even when a new instance is constructed after the threshold is
 * already crossed.
 *
 * Previous behaviour: `warnIfNearing()` compared `wasBelow` (true at construction
 * time) against `isAtOrAbove` (also at construction time). If the threshold was
 * already exceeded when the guard was constructed, both flags were computed from
 * the same `current` value and the condition `wasBelow && isAtOrAbove` could
 * never be true.
 *
 * New behaviour: a persistent `warned_<type>` boolean in SharedPreferences tracks
 * whether the warning has been shown today. It is reset alongside the counters in
 * `rolloverIfNeeded()`. This survives multiple instances and survives the process
 * being killed and relaunched within the same calendar day.
 */
public class FirebaseCostGuard {

    private static final String PREFS       = "duoshield_cost_guard";
    private static final int    MAX_READS   = 40_000;
    private static final int    MAX_WRITES  = 16_000;
    private static final int    MAX_DELETES = 16_000;
    private static final float  WARN_PCT    = 0.80f;

    private static FirebaseCostGuard instance;

    private final SharedPreferences prefs;
    private final Context           appContext;

    private FirebaseCostGuard(Context ctx) {
        appContext = ctx.getApplicationContext();
        prefs      = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        rolloverIfNeeded();
    }

    public static synchronized FirebaseCostGuard getInstance(Context ctx) {
        if (instance == null) {
            instance = new FirebaseCostGuard(ctx);
        } else {
            // Check for day rollover even on existing instance
            instance.rolloverIfNeeded();
        }
        return instance;
    }

    /**
     * Resets counters if the calendar day has changed.
     *
     * <p>The previous implementation stored {@code todayKey} as a {@code final} field
     * computed once at construction time.  Since this is a process-lifetime singleton,
     * the guard would compare a stale "yesterday" key against itself after midnight and
     * conclude no rollover was needed — counters never reset (BUG-T03).
     *
     * <p>Fix: compute the current day key fresh on every call so midnight crossings are
     * detected correctly.
     */
    private void rolloverIfNeeded() {
        String currentDayKey = String.valueOf(System.currentTimeMillis() / 86_400_000L);
        String stored = prefs.getString("day_key", "");
        if (!stored.equals(currentDayKey)) {
            prefs.edit()
                .putString("day_key",        currentDayKey)
                .putInt("reads",   0).putInt("writes",   0).putInt("deletes",   0)
                .putBoolean("warned_reads",   false)
                .putBoolean("warned_writes",  false)
                .putBoolean("warned_deletes", false)
                .apply();
        }
    }

    public boolean canRead(int n) {
        int current = prefs.getInt("reads", 0);
        warnIfNearing("reads", current, MAX_READS);
        return current + n <= MAX_READS;
    }

    public boolean canWrite(int n) {
        int current = prefs.getInt("writes", 0);
        warnIfNearing("writes", current, MAX_WRITES);
        return current + n <= MAX_WRITES;
    }

    public boolean canDelete(int n) {
        int current = prefs.getInt("deletes", 0);
        warnIfNearing("deletes", current, MAX_DELETES);
        return current + n <= MAX_DELETES;
    }

    public void recordReads(int n)   { add("reads",   n); }
    public void recordWrites(int n)  { add("writes",  n); }
    public void recordDeletes(int n) { add("deletes", n); }

    private void add(String key, int n) {
        prefs.edit().putInt(key, prefs.getInt(key, 0) + n).apply();
    }

    /**
     * Shows the warning toast at most once per day per quota type.
     * Uses a persistent "warned_<type>" flag so the warning fires correctly
     * even when a new instance is created after the threshold is already crossed.
     */
    private void warnIfNearing(String type, int current, int max) {
        String warnedKey = "warned_" + type;
        if (prefs.getBoolean(warnedKey, false)) return;   // already warned today
        if (current >= (int)(max * WARN_PCT)) {
            prefs.edit().putBoolean(warnedKey, true).apply();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(appContext,
                    "Warning: " + type + " quota at 80% for today",
                    Toast.LENGTH_LONG).show());
        }
    }

    public int getReads()   { return prefs.getInt("reads",   0); }
    public int getWrites()  { return prefs.getInt("writes",  0); }
    public int getDeletes() { return prefs.getInt("deletes", 0); }
}
