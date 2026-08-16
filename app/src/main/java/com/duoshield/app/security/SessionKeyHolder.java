package com.duoshield.app.security;

import android.util.Log;

import java.util.Arrays;

/**
 * Holds the unwrapped database passphrase in memory for the duration of an
 * unlocked session (S08-M3).
 *
 * <h3>Why this exists</h3>
 * {@link PinKeyGate} makes the PIN a required input to recovering the database
 * key, which means the key is only obtainable at the moment the user types the
 * PIN. But the app has 52 {@code AppDatabase.getInstance()} call sites, 12 of
 * them outside Activities, and three that run with no PIN available at all:
 * the {@code DuoShieldApp} warm-up, {@code SelfDestructWorker}, and the
 * {@code MessageBuilder} notification-reply path. Without somewhere to keep the
 * unwrapped key, PIN-binding it would break every one of those.
 *
 * <p>So the key is unwrapped once, at unlock, and held here. Background work
 * that runs after the user has unlocked finds it and proceeds; background work
 * that runs before first unlock finds nothing and defers (see
 * {@code SelfDestructWorker}, which returns {@code Result.retry()} rather than
 * treating "locked" as "nothing to delete").
 *
 * <h3>What this deliberately is not</h3>
 * This is not persistence. There is no disk write anywhere in this class, and
 * there must never be one — the whole point of the PIN gate is that the key
 * does not exist at rest in recoverable form. The key lives only in this
 * process's heap and dies with the process, which is why a cold start always
 * requires the PIN again.
 *
 * <h3>Memory hygiene</h3>
 * The key is stored as a {@code byte[]} rather than a {@code String} so it can
 * be explicitly zeroed on {@link #lock()}. Callers get a defensive copy from
 * {@link #getKey()} and are responsible for zeroing their copy — the same
 * contract {@link com.duoshield.app.db.DatabaseKeyProvider} already documents.
 * Zeroing is best-effort: it does not defeat an attacker who can read process
 * memory at will, and is not claimed to. It shortens the window in which a heap
 * dump yields the key, which is worth having and is all it is.
 *
 * <p>Thread-safe: every accessor synchronises on the class monitor, because
 * background workers and the UI thread both reach this.
 */
public final class SessionKeyHolder {

    private static final String TAG = "SessionKeyHolder";

    /**
     * The unwrapped database passphrase, or {@code null} when locked.
     * Guarded by {@code SessionKeyHolder.class}.
     */
    private static byte[] key;

    /**
     * When the current session was unlocked, for diagnostics only. Never used
     * to decide whether the session is valid — {@code key != null} is the only
     * authority, so there is no clock-based bypass.
     */
    private static long unlockedAtMs;

    private SessionKeyHolder() {}

    /**
     * Publishes a freshly unwrapped key for this session.
     *
     * <p>Takes a defensive copy, so the caller may (and should) zero the array
     * it passed in once this returns.
     *
     * @param unwrapped the plaintext database passphrase; must be non-null and
     *                  non-empty. Passing an empty array would silently produce
     *                  an "unlocked" session with no usable key.
     */
    public static synchronized void unlock(byte[] unwrapped) {
        if (unwrapped == null || unwrapped.length == 0) {
            throw new IllegalArgumentException(
                    "Refusing to unlock the session with a null/empty key.");
        }
        // Zero any previous session's key rather than letting the GC decide when
        // to reclaim it. Re-unlocking without an intervening lock() is legitimate
        // (e.g. the user re-enters their PIN after a re-wrap), so this is not an
        // error — but leaking the old buffer would be.
        if (key != null) Arrays.fill(key, (byte) 0);
        key          = unwrapped.clone();
        unlockedAtMs = System.currentTimeMillis();
        Log.i(TAG, "Session unlocked — database key held in memory for this process.");
    }

    /**
     * Returns a copy of the session key, or {@code null} when locked.
     *
     * <p>Callers must zero the returned array once they are done with it.
     * Returning a copy rather than the live array means a caller that zeroes
     * its copy — as {@code AppDatabase} does immediately after handing the
     * passphrase to {@code SupportFactory} — does not accidentally destroy the
     * session for everyone else.
     */
    public static synchronized byte[] getKey() {
        return key == null ? null : key.clone();
    }

    /** True when a key is currently held, i.e. the session is usable. */
    public static synchronized boolean isUnlocked() {
        return key != null;
    }

    /**
     * Milliseconds since this session was unlocked, or {@code -1} when locked.
     * Diagnostics only.
     */
    public static synchronized long unlockedAgeMs() {
        return key == null ? -1L : System.currentTimeMillis() - unlockedAtMs;
    }

    /**
     * Zeroes and drops the session key.
     *
     * <p>Must be called on logout, duress trigger, and wipe. It is deliberately
     * safe to call when already locked so the wipe/duress paths can call it
     * unconditionally without first checking state — a teardown path that has to
     * remember to check something is a teardown path that will eventually forget.
     */
    public static synchronized void lock() {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
            key = null;
            Log.i(TAG, "Session locked — database key zeroed.");
        }
        unlockedAtMs = 0L;
    }
}
