package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.DateFormat;
import java.util.Date;

/**
 * Durable record of manual safety-number (key fingerprint) verifications.
 *
 * <p>UX-1 fix: before this existed, a successful QR fingerprint match showed a one-shot
 * {@code AlertDialog}, cleared {@code safety_num_changed_<uid>}, and left no trace. Users
 * had no way to tell whether they had ever verified a contact, so the app's highest-trust
 * action produced nothing durable. This class persists the outcome so a "Verified" badge
 * can be surfaced on the fingerprint screen and contact detail screen.
 *
 * <p>Two values are stored per contact:
 * <ul>
 *   <li>{@code verified_at_<uid>} — epoch millis of the successful match.</li>
 *   <li>{@code verified_fp_<uid>} — the fingerprint hex that was verified, so a later
 *       identity-key change can be detected as invalidating that verification rather than
 *       silently leaving a stale "Verified" badge on a key nobody ever checked.</li>
 * </ul>
 *
 * <p>Stored in the {@code duoshield_prefs} file — the same file as
 * {@code safety_num_changed_<uid>} — so the "changed" flag and the "verified" record can be
 * written and cleared together and stay consistent with each other.
 *
 * <p>These are trust-state booleans and timestamps, not secrets: a fingerprint is the hash
 * of a <em>public</em> key and is designed to be read aloud or shown as a QR code, so
 * plain {@code SharedPreferences} is appropriate here and {@code SecurePrefs} is not needed.
 */
public final class VerificationStore {

    private static final String PREFS_NAME  = "duoshield_prefs";
    private static final String KEY_AT_PREFIX = "verified_at_";
    private static final String KEY_FP_PREFIX = "verified_fp_";
    /** Written by DuoShieldSignalStore when a partner's identity key changes. */
    private static final String KEY_CHANGED_PREFIX = "safety_num_changed_";

    private VerificationStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Records a successful fingerprint match for {@code uid} and clears the
     * safety-number-changed warning in the same commit.
     *
     * @param fingerprintHex the full fingerprint hex that matched, or {@code null} if
     *                       unavailable (the verification is still recorded).
     */
    public static void markVerified(Context ctx, String uid, String fingerprintHex) {
        if (ctx == null || uid == null) return;
        SharedPreferences.Editor e = prefs(ctx).edit()
                .putLong(KEY_AT_PREFIX + uid, System.currentTimeMillis())
                .remove(KEY_CHANGED_PREFIX + uid);
        if (fingerprintHex != null) {
            e.putString(KEY_FP_PREFIX + uid, fingerprintHex.toLowerCase());
        }
        e.apply();
    }

    /**
     * Drops any verification record for {@code uid}. Called when the partner's identity key
     * changes: the key the user verified is no longer the key in use, so the badge must not
     * survive. Leaves {@code safety_num_changed_<uid>} alone — that flag is owned by
     * {@code DuoShieldSignalStore}, which sets it on the same event.
     */
    public static void clearVerification(Context ctx, String uid) {
        if (ctx == null || uid == null) return;
        prefs(ctx).edit()
                .remove(KEY_AT_PREFIX + uid)
                .remove(KEY_FP_PREFIX + uid)
                .apply();
    }

    /** @return true when this contact has a recorded successful fingerprint verification. */
    public static boolean isVerified(Context ctx, String uid) {
        return getVerifiedAt(ctx, uid) > 0L;
    }

    /** @return epoch millis of the verification, or {@code 0} if never verified. */
    public static long getVerifiedAt(Context ctx, String uid) {
        if (ctx == null || uid == null) return 0L;
        return prefs(ctx).getLong(KEY_AT_PREFIX + uid, 0L);
    }

    /** @return the fingerprint hex that was verified, or {@code null}. */
    public static String getVerifiedFingerprint(Context ctx, String uid) {
        if (ctx == null || uid == null) return null;
        return prefs(ctx).getString(KEY_FP_PREFIX + uid, null);
    }

    /**
     * True when a verification exists but the fingerprint now on file differs from the one
     * that was verified — i.e. the badge is stale and must not be shown as trusted.
     */
    public static boolean isStale(Context ctx, String uid, String currentFingerprintHex) {
        String verified = getVerifiedFingerprint(ctx, uid);
        if (verified == null || currentFingerprintHex == null) return false;
        return !verified.equalsIgnoreCase(currentFingerprintHex);
    }

    /**
     * Localised short date for display in the badge, e.g. "Verified on 16 Aug 2026".
     * Uses the device locale and format via {@link DateFormat} rather than a hardcoded
     * pattern so it reads correctly outside en-US.
     */
    public static String formatVerifiedOn(Context ctx, String uid) {
        long at = getVerifiedAt(ctx, uid);
        if (at <= 0L) return null;
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(at));
    }
}
