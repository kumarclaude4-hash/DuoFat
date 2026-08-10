package com.duoshield.app.security;

import android.content.Context;
import android.content.SharedPreferences;

import com.duoshield.app.util.SecurePrefs;

/**
 * Wipe-surviving store for the account-lock intent and the teardown resume marker.
 *
 * <h3>Why this exists (S06-H3)</h3>
 * The duress path used to have two ways to set {@code accountLock}, and <em>both</em>
 * required network at, or within 24 hours of, trigger time:
 * <ul>
 *   <li>A synchronous Firestore {@code set()}. Offline, the SDK queues it locally — and
 *       then the wipe destroys the app's storage, so the queued mutation is deleted
 *       before it can flush. It is not "eventually consistent"; it is gone.</li>
 *   <li>A nonce fetch plus {@code AccountLockWorker}. Offline, the ID-token fetch and the
 *       {@code /requestLockNonce} call both fail, so the worker was never even
 *       enqueued.</li>
 * </ul>
 * So a device in airplane mode performed the full destructive local wipe and locked
 * nothing, silently, while the user believed their account was protected. That is
 * directly attacker-controlled: bagging or airplane-moding a seized phone is routine,
 * and it also defeats remote wipe, so it is standard practice. Worse than an honest
 * failure, because the user is left confident.
 *
 * <h3>What this changes</h3>
 * The lock <em>decision</em> becomes durable before any erasure runs, in a container the
 * wipe deliberately preserves ({@link SecurePrefs#getSessionState}) — mirroring how the
 * device gate is kept structurally isolated from every wipe path. A launch-time drain
 * ({@link DuressManager#drainPendingLockIntent}) retries it, so the lock survives
 * reboots, force-stops, and an indefinitely offline device rather than expiring with a
 * 24-hour nonce.
 *
 * <p>The credential problem is solved by moving the nonce fetch <strong>off</strong> the
 * duress path: {@link DuressManager#maintainLockCredential} pre-fetches and rotates a
 * nonce during ordinary foreground operation while online and parks it here, so the
 * duress path always has a usable credential even when it is triggered offline.
 *
 * <p><strong>This depends on that method actually being called.</strong> Its only caller
 * is {@code BaseActivity.onStart()}, in the branch reached when the session is valid and
 * the app is foregrounded and unlocked. Between the original S06-H3 change and
 * 2026-08-10 the method had <em>no caller at all</em>, which silently made the entire
 * offline path above inert while this javadoc claimed otherwise — the warm token was
 * never written, so {@code getWarmToken} always returned null on the duress path. If a
 * future refactor removes that call site, the offline duress lock stops working and
 * nothing will fail loudly; treat the call in {@code BaseActivity} as load-bearing.
 *
 * <h3>Deniability constraints on this file</h3>
 * Every key name here reads as ordinary session/migration plumbing, and the file name
 * itself is neutral ({@code session_state_prefs}). This matters more here than anywhere
 * else in the app: the file's contents describe a teardown, so a self-describing name
 * would need no interpretation by anyone who found it. The store is encrypted
 * (EncryptedSharedPreferences, hardware-backed where a TEE exists), which is a real
 * improvement on the previous design — the old {@code duress_wipe_in_progress} flag sat
 * as a literal English string in unencrypted XML.
 *
 * <p><strong>Residual risk, stated honestly:</strong> {@link #getSubject} holds a uid in
 * recoverable form until the lock is confirmed, because the retry cannot work without
 * knowing which account to lock. It is encrypted rather than plaintext, it is scoped to
 * one short-lived record instead of WorkManager's indefinitely-retained rows, and
 * {@link #clearLockIntent} destroys it the moment the server confirms. That is the
 * smallest window the retry requirement allows.
 */
public final class PendingLockStore {

    private PendingLockStore() {}

    /** Teardown is in flight or was interrupted; drives the resume path (S06-M5). */
    private static final String KEY_RESET_PENDING = "session_migration_pending";

    /** Account the pending lock applies to. */
    private static final String KEY_SUBJECT       = "session_migration_subject";
    /** Server-issued single-use lock nonce for the pending intent. */
    private static final String KEY_INTENT_TOKEN  = "session_migration_token";
    /** When the intent was recorded (ms since epoch), for diagnostics only. */
    private static final String KEY_INTENT_TS     = "session_migration_ts";

    /** Pre-fetched nonce kept warm during normal online operation. */
    private static final String KEY_WARM_TOKEN    = "session_credential_token";
    private static final String KEY_WARM_TS       = "session_credential_ts";

    /**
     * Server said this account was unfrozen and owes a primary-PIN rotation.
     * Mirrors {@code accountLock/{uid}.rotationRequired} locally so the forced
     * rotation survives being backgrounded mid-flow (S06-M6).
     */
    private static final String KEY_ROTATION_DUE  = "session_rotation_due";

    private static SharedPreferences sp(Context ctx) {
        return SecurePrefs.getSessionState(ctx);
    }

    // ── Teardown resume marker (S06-M5) ───────────────────────────────────────

    /**
     * Marks a teardown as in flight. Uses {@code commit()} deliberately: this must be
     * on disk before the caller starts anything destructive, or a crash in the first
     * few milliseconds leaves an un-resumable half-wiped install.
     */
    public static void markResetPending(Context ctx) {
        try { sp(ctx).edit().putBoolean(KEY_RESET_PENDING, true).commit(); }
        catch (Exception ignored) {}
    }

    public static boolean isResetPending(Context ctx) {
        try { return sp(ctx).getBoolean(KEY_RESET_PENDING, false); }
        catch (Exception e) { return false; }
    }

    public static void clearResetPending(Context ctx) {
        try { sp(ctx).edit().remove(KEY_RESET_PENDING).commit(); }
        catch (Exception ignored) {}
    }

    // ── Durable lock intent (S06-H3) ──────────────────────────────────────────

    /**
     * Records that {@code uid} must be locked, with whatever credential is available.
     * A null/empty nonce is still worth recording: the drain path can do nothing with
     * it, but {@link #hasLockIntent} then correctly reports that the account is
     * <em>believed unlocked</em>, which is what surfaces the failure instead of
     * swallowing it (S06-L4).
     */
    public static void recordLockIntent(Context ctx, String uid, String nonce) {
        if (uid == null || uid.isEmpty()) return;
        try {
            SharedPreferences.Editor ed = sp(ctx).edit()
                    .putString(KEY_SUBJECT, uid)
                    .putLong(KEY_INTENT_TS, System.currentTimeMillis());
            if (nonce != null && !nonce.isEmpty()) ed.putString(KEY_INTENT_TOKEN, nonce);
            ed.commit();
        } catch (Exception ignored) {}
    }

    public static boolean hasLockIntent(Context ctx) {
        try { return sp(ctx).getString(KEY_SUBJECT, null) != null; }
        catch (Exception e) { return false; }
    }

    public static String getSubject(Context ctx) {
        try { return sp(ctx).getString(KEY_SUBJECT, null); }
        catch (Exception e) { return null; }
    }

    public static String getIntentToken(Context ctx) {
        try { return sp(ctx).getString(KEY_INTENT_TOKEN, null); }
        catch (Exception e) { return null; }
    }

    /**
     * Destroys the intent. Call this <strong>only</strong> once the server has
     * confirmed the lock (or has told us the nonce was already consumed, which means
     * the same thing). Clearing it on any other failure path is how the old design
     * lost locks silently.
     */
    public static void clearLockIntent(Context ctx) {
        try {
            sp(ctx).edit()
                   .remove(KEY_SUBJECT)
                   .remove(KEY_INTENT_TOKEN)
                   .remove(KEY_INTENT_TS)
                   .commit();
        } catch (Exception ignored) {}
    }

    // ── Warm lock credential (S06-H3 secondary) ───────────────────────────────

    /**
     * Parks a nonce fetched during ordinary online operation, so the duress path has a
     * credential without needing the network at trigger time.
     */
    public static void putWarmToken(Context ctx, String nonce) {
        if (nonce == null || nonce.isEmpty()) return;
        try {
            sp(ctx).edit()
                   .putString(KEY_WARM_TOKEN, nonce)
                   .putLong(KEY_WARM_TS, System.currentTimeMillis())
                   .commit();
        } catch (Exception ignored) {}
    }

    public static String getWarmToken(Context ctx) {
        try { return sp(ctx).getString(KEY_WARM_TOKEN, null); }
        catch (Exception e) { return null; }
    }

    /** Age of the warm nonce in ms, or {@link Long#MAX_VALUE} if there isn't one. */
    public static long getWarmTokenAgeMs(Context ctx) {
        try {
            if (sp(ctx).getString(KEY_WARM_TOKEN, null) == null) return Long.MAX_VALUE;
            long ts = sp(ctx).getLong(KEY_WARM_TS, 0L);
            if (ts <= 0L) return Long.MAX_VALUE;
            return Math.max(0L, System.currentTimeMillis() - ts);
        } catch (Exception e) { return Long.MAX_VALUE; }
    }

    public static void clearWarmToken(Context ctx) {
        try { sp(ctx).edit().remove(KEY_WARM_TOKEN).remove(KEY_WARM_TS).commit(); }
        catch (Exception ignored) {}
    }

    // ── Forced rotation after unfreeze (S06-M6) ───────────────────────────────

    public static void setRotationDue(Context ctx, boolean due) {
        try {
            if (due) sp(ctx).edit().putBoolean(KEY_ROTATION_DUE, true).commit();
            else     sp(ctx).edit().remove(KEY_ROTATION_DUE).commit();
        } catch (Exception ignored) {}
    }

    public static boolean isRotationDue(Context ctx) {
        try { return sp(ctx).getBoolean(KEY_ROTATION_DUE, false); }
        catch (Exception e) { return false; }
    }
}
