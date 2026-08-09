package com.duoshield.app.security;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.duoshield.app.BuildConfig;
import com.duoshield.app.SignInActivity;
import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.backup.BackupScheduler;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.util.SecurePrefs;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class DuressManager {

    private static final String PREFS_NAME          = "duoshield_prefs";

    /**
     * Secondary unlock slot. Deliberately named as an ordinary "slot B" rather than
     * anything containing "duress": these keys live in SecurePrefs, but a neutral
     * name means a dump of that store still reads like routine PIN plumbing instead
     * of naming a feature that is supposed to be undiscoverable.
     *
     * <p>Superseded {@code duress_pin_hash_<uid>} / {@code duress_pin_hash}, which
     * are still read once for migration in {@link #migrateLegacySlot}.
     */
    private static final String KEY_SLOT_B_PREFIX   = "pin_slot_b_";
    private static final String KEY_SLOT_B_ARMED    = "pin_slot_b_armed_";
    private static final String KEY_DURESS_PREFIX   = "duress_pin_hash_";
    private static final String KEY_DURESS_LEGACY   = "duress_pin_hash";
    private static final String KEY_ELIGIBLE_PREFIX = "duress_eligible_";
    private static final int    ITERATIONS          = 310_000;
    private static final int    KEY_LEN             = 256;

    /**
     * Neutral replacement for the old {@code duress_wipe_in_progress} flag.
     *
     * <p>The old flag was a self-describing English string written to the
     * <em>unencrypted</em> {@code duoshield_prefs} XML, and it is only cleared at the
     * end of the wipe — up to ~20s later. A force-stop, crash, reboot or dead battery
     * inside that window left a plaintext file on disk stating that a duress wipe had
     * happened here, which is strictly worse than any other residue in the app: it
     * needs no interpretation. Now stored in SecurePrefs under a name that reads like
     * ordinary session plumbing.
     */
    private static final String KEY_RESET_PENDING   = "session_migration_pending";
    /** Legacy plaintext flag; read once so an interrupted old-build wipe still routes. */
    private static final String KEY_RESET_PENDING_LEGACY = "duress_wipe_in_progress";

    /**
     * Returns the UID-scoped SecurePrefs key for the currently signed-in user,
     * or {@code null} if no user is signed in.
     *
     * <h3>Why UID-scoped?</h3>
     * Duress logout intentionally keeps the hash so that a restore attempt for
     * the same account is still gated. But a brand-new user signing in on the
     * same device must not inherit the old account's duress PIN — that would
     * be indistinguishable from the old account still being active.
     */
    private static String duressKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_SLOT_B_PREFIX + user.getUid() : null;
    }

    private static String armedKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_SLOT_B_ARMED + user.getUid() : null;
    }

    /**
     * Moves a pre-existing {@code duress_pin_hash*} value into slot B and marks it
     * armed. Idempotent; safe to call on every access.
     */
    private static void migrateLegacySlot(SharedPreferences sp, String slotKey, String armKey) {
        String legacyUidKey = legacyUidKey();
        String legacy = legacyUidKey != null ? sp.getString(legacyUidKey, null) : null;
        if (legacy == null) legacy = sp.getString(KEY_DURESS_LEGACY, null);
        if (legacy == null) return;
        SharedPreferences.Editor ed = sp.edit()
                .putString(slotKey, legacy)
                .putBoolean(armKey, true)
                .remove(KEY_DURESS_LEGACY);
        if (legacyUidKey != null) ed.remove(legacyUidKey);
        ed.apply();
    }

    /** Pre-migration UID-scoped key name, or null if no user is signed in. */
    private static String legacyUidKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_DURESS_PREFIX + user.getUid() : null;
    }

    /**
     * Guarantees slot B holds a well-formed {@code salt:hash} value for the current
     * account, writing an unmatchable random decoy if it is empty.
     *
     * <h3>Why every account needs a decoy</h3>
     * {@code isDuressPin()} used to return early when no secondary hash was stored,
     * so an account <em>with</em> a secondary code ran two PBKDF2 derivations per
     * unlock attempt while an account without ran one. At 310,000 iterations that is
     * a few hundred milliseconds — the "Verifying…" state was visibly about twice as
     * long, on every unlock and every wrong guess, with {@code DevicePinGateActivity}
     * conveniently providing a one-derivation baseline to compare against. No tooling
     * was required to detect it. That leak defeated the hidden UI row, the
     * server-side enrollment gate, and every other measure taken to keep the
     * capability undiscoverable.
     *
     * <p>Both halves matter and both are handled here: the <em>work</em> is now
     * constant (always exactly one derivation against slot B) and the <em>stored
     * shape</em> is constant (every account has a slot B of identical form, so its
     * presence discloses nothing). The decoy is 32 random bytes, so no PIN can ever
     * match it.
     *
     * <p>Called from {@code PinManager.setPin} so enrolled and non-enrolled accounts
     * are indistinguishable from the moment a PIN first exists.
     *
     * <p><strong>Residual risk, stated honestly:</strong> {@code pin_slot_b_armed_*}
     * still distinguishes a real secondary code from a decoy to anyone who can read
     * SecurePrefs itself (root on an unlocked device). Removing that last bit would
     * require deriving armed-ness from the entered PIN, which is a substantially
     * larger change. What this closes is the <em>remote/observational</em> leak that
     * needed nothing but a wall clock.
     */
    public static void ensureSecondarySlotInitialized(Context context) {
        String slotKey = duressKey();
        String armKey  = armedKey();
        if (slotKey == null || armKey == null) return;
        SharedPreferences sp = SecurePrefs.get(context);
        migrateLegacySlot(sp, slotKey, armKey);
        if (sp.getString(slotKey, null) != null) return;
        try {
            byte[] salt  = new byte[16];
            byte[] decoy = new byte[KEY_LEN / 8];
            SecureRandom rng = new SecureRandom();
            rng.nextBytes(salt);
            rng.nextBytes(decoy);
            sp.edit()
              .putString(slotKey, bytesToHex(salt) + ":" + bytesToHex(decoy))
              .putBoolean(armKey, false)
              .apply();
        } catch (Exception e) {
            android.util.Log.e("DuressManager", "Failed to initialise secondary slot", e);
        }
    }

    /**
     * Arms slot B with a real secondary code.
     *
     * @return false if the code was rejected. The only rejection reason is a length
     *         that differs from the primary PIN — see the length note in
     *         {@code ManageUnlockCodesActivity}; a mismatched length produces a code
     *         that physically cannot be entered on the lock screen.
     */
    public static boolean setDuressPin(Context context, String pin) {
        String slotKey = duressKey();
        String armKey  = armedKey();
        if (slotKey == null || armKey == null) return false;
        if (pin == null || pin.length() != com.duoshield.app.util.PinManager.getPinLength(context)) {
            return false;
        }
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash   = pbkdf2(pin, salt);
            String stored = bytesToHex(salt) + ":" + bytesToHex(hash);
            SecurePrefs.get(context).edit()
                    .putString(slotKey, stored)
                    .putBoolean(armKey, true)
                    .remove(KEY_DURESS_LEGACY)
                    .apply();
            return true;
        } catch (Exception e) {
            android.util.Log.e("DuressManager", "Failed to store secondary code", e);
            return false;
        }
    }

    /**
     * Constant-work check of the entered PIN against slot B.
     *
     * <p>Always performs exactly one PBKDF2 derivation for every account, armed or
     * not — including the no-user and malformed-value paths, which derive against an
     * ephemeral decoy rather than returning early. Do not add a fast path here; the
     * timing difference is directly observable by the user of the device. See
     * {@link #ensureSecondarySlotInitialized}.
     */
    public static boolean isDuressPin(Context context, String enteredPin) {
        String slotKey = duressKey();
        String armKey  = armedKey();
        String stored  = null;
        boolean armed  = false;

        if (slotKey != null && armKey != null) {
            SharedPreferences sp = SecurePrefs.get(context);
            migrateLegacySlot(sp, slotKey, armKey);
            stored = sp.getString(slotKey, null);
            armed  = sp.getBoolean(armKey, false);
        }

        // Derive against a throwaway decoy when slot B is absent or malformed, so the
        // work performed is identical to the armed case.
        byte[] salt, expected;
        try {
            int sep = stored != null ? stored.indexOf(':') : -1;
            if (sep > 0) {
                salt     = hexToBytes(stored.substring(0, sep));
                expected = hexToBytes(stored.substring(sep + 1));
            } else {
                salt     = new byte[16];
                expected = new byte[KEY_LEN / 8];
                SecureRandom rng = new SecureRandom();
                rng.nextBytes(salt);
                rng.nextBytes(expected);
                armed = false;
            }
            byte[] actual = pbkdf2(enteredPin, salt);
            boolean match = constantTimeEquals(expected, actual);
            return armed && match;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns true if slot B holds a real secondary code (not a decoy). */
    public static boolean hasDuressPin(Context context) {
        String slotKey = duressKey();
        String armKey  = armedKey();
        if (slotKey == null || armKey == null) return false;
        SharedPreferences sp = SecurePrefs.get(context);
        migrateLegacySlot(sp, slotKey, armKey);
        return sp.getBoolean(armKey, false);
    }

    /**
     * Disarms slot B by overwriting it with a fresh unmatchable decoy.
     *
     * <p>Deliberately not a {@code remove()}: deleting the entry would restore the
     * one-derivation-vs-two timing difference described in
     * {@link #ensureSecondarySlotInitialized}, so clearing a secondary code would
     * become observable as the unlock screen suddenly getting faster.
     */
    public static void clearDuressPin(Context context) {
        String slotKey = duressKey();
        String armKey  = armedKey();
        if (slotKey == null || armKey == null) return;
        try {
            byte[] salt  = new byte[16];
            byte[] decoy = new byte[KEY_LEN / 8];
            SecureRandom rng = new SecureRandom();
            rng.nextBytes(salt);
            rng.nextBytes(decoy);
            SharedPreferences.Editor ed = SecurePrefs.get(context).edit()
                    .putString(slotKey, bytesToHex(salt) + ":" + bytesToHex(decoy))
                    .putBoolean(armKey, false)
                    .remove(KEY_DURESS_LEGACY);
            String legacyUidKey = legacyUidKey();
            if (legacyUidKey != null) ed.remove(legacyUidKey);
            ed.apply();
        } catch (Exception e) {
            android.util.Log.e("DuressManager", "Failed to disarm secondary slot", e);
        }
    }

    /**
     * True if a local reset is mid-flight (see {@link #KEY_RESET_PENDING}). Routing
     * screens use this to send the user to sign-in instead of a half-wiped session.
     */
    public static boolean isResetPending(Context context) {
        try {
            if (SecurePrefs.get(context).getBoolean(KEY_RESET_PENDING, false)) return true;
        } catch (Exception ignored) {}
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                      .getBoolean(KEY_RESET_PENDING_LEGACY, false);
    }

    /**
     * "Sync then Wipe" — plausible-deniability duress logout.
     *
     * <p>Triggered exclusively by an exact duress-PIN match in
     * {@code LockScreenActivity}. There is no wrong-guess-count fallback — see
     * {@code LockScreenActivity}'s javadoc for why that was removed.
     *
     * <h3>Sequence</h3>
     * <ol>
     *   <li><b>Instant navigation</b> — {@code SignInActivity} starts immediately
     *       with {@code FLAG_ACTIVITY_CLEAR_TASK}. The chat screen disappears at once.</li>
     *   <li><b>Panic sync</b> — {@link BackupManager#syncIncrementalSync} uploads any
     *       unsynced messages to Firestore. Hard deadline: 10 seconds.</li>
     *   <li><b>Destructive local wipe</b>:
     *     <ul>
     *       <li>Room DB closed and deleted ({@code duoshield_db}).</li>
     *       <li>All {@link SecurePrefs} keys destroyed synchronously ({@code .commit()}).</li>
     *       <li>Local contact backup cleared.</li>
     *       <li>All SharedPreferences files cleared synchronously.</li>
     *     </ul>
     *   </li>
     *   <li><b>Firebase sign-out</b> — local only, no Firestore writes or deletes.</li>
     * </ol>
     *
     * <h3>Security guarantees</h3>
     * <ul>
     *   <li>No cloud deletion — Firestore data is preserved for recovery via seed phrase.</li>
     *   <li>Forensic resistance — SQLCipher DB file and all key material removed from NAND.</li>
     *   <li>Plausible deniability — device presents as unconfigured/factory-reset.</li>
     * </ul>
     *
     * <h3>Recovery</h3>
     * User opens the (now empty) app, selects "Restore Account", enters their 12-word
     * seed phrase. {@code RestoreFromSeedActivity} re-derives keys and pulls all chats
     * (including those uploaded by the panic sync) back from Firestore.
     *
     * <p><strong>Silent:</strong> no Toast, no dialog, no animation visible to an observer.
     */
    public static void performLogout(Context context) {
        // Capture the UID before anything below signs out or wipes prefs — both would
        // erase the one piece of information the delayed FCM de-registration job needs.
        FirebaseUser userBeforeWipe = FirebaseAuth.getInstance().getCurrentUser();
        String uidBeforeWipe = userBeforeWipe != null ? userBeforeWipe.getUid() : null;

        // Enqueue FcmUnregisterWorker immediately — no credential needed
        // (FirebaseMessaging.deleteToken() handles its own auth).
        // AccountLockWorker is enqueued later, inside the background thread
        // (step 1b), once a server-issued one-time nonce has been obtained while
        // the Firebase session is still live. If nonce acquisition fails (offline),
        // the worker is not enqueued; the synchronous write in step 1a is the primary
        // mechanism and covers the online case.
        final Context appCtx = context.getApplicationContext();
        if (uidBeforeWipe != null) {
            com.duoshield.app.util.FcmUnregisterWorker.enqueue(appCtx, uidBeforeWipe);
        }

        // F30 fix: Write a synchronous routing-guard flag BEFORE launching SignInActivity.
        // SignInActivity (and SplashActivity / MainActivity) check this flag and skip the
        // returning-user auto-route while the background wipe is still in flight.
        // The flag is cleared at the very end of the background thread, after signOut(),
        // so SignInActivity cannot bounce back before both keys and session are destroyed.
        // Written to SecurePrefs (encrypted) under a neutral name — NOT to the plaintext
        // duoshield_prefs XML, where it previously sat as a literal
        // "duress_wipe_in_progress" string for the whole ~20s wipe window.
        try {
            SecurePrefs.get(context).edit().putBoolean(KEY_RESET_PENDING, true).commit();
        } catch (Exception e) {
            android.util.Log.e("DuressManager", "Failed to set reset-pending flag", e);
        }

        // 1. Instant navigation — removes chat screen from view immediately.
        //    To an observer, it looks like the app is simply processing the PIN.
        Intent intent = new Intent(context, SignInActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);

        // Cancel the daily backup sync — the session is intentionally destroyed.
        try { BackupScheduler.cancel(context); } catch (Exception ignored) {}

        // Full "sync then wipe" on a background thread
        new Thread(() -> {

            // 1a. Synchronous account-lock write — performed BEFORE the panic sync
            //     and BEFORE sign-out, while the Firebase session is still live.
            //     This closes the race window where a concurrent restore attempt on
            //     another device could succeed during the WorkManager jitter delay.
            //     A 5-second cap keeps the wipe responsive.
            if (uidBeforeWipe != null) {
                try {
                    final Object   lockSync = new Object();
                    final boolean[] written = {false};
                    java.util.Map<String, Object> lockData = new java.util.HashMap<>();
                    lockData.put("locked",   true);
                    lockData.put("lockedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
                    FirebaseFirestore.getInstance()
                            .collection("accountLock")
                            .document(uidBeforeWipe)
                            .set(lockData)
                            .addOnCompleteListener(task -> {
                                synchronized (lockSync) { written[0] = true; lockSync.notifyAll(); }
                            });
                    synchronized (lockSync) {
                        if (!written[0]) lockSync.wait(5_000);
                    }
                    android.util.Log.d("DuressManager", "Synchronous account-lock write complete.");
                } catch (Exception ignored) {
                    android.util.Log.w("DuressManager",
                            "Synchronous account-lock write failed — will attempt nonce retry.");
                }
            }

            // 1b. Request a server-issued one-time nonce for AccountLockWorker to use
            //     as a retry fallback. Done here — before sign-out — so the nonce
            //     request is authenticated with the live Firebase session rather than
            //     any credential stored persistently in WorkManager input data.
            //     If this fails (offline), AccountLockWorker is not enqueued; the
            //     synchronous write in step 1a covers the online case.
            if (uidBeforeWipe != null && userBeforeWipe != null) {
                try {
                    // Get Firebase ID token synchronously (5-second timeout).
                    final Object tokenSync   = new Object();
                    final String[] tokenHolder = {null};
                    userBeforeWipe.getIdToken(false)
                            .addOnSuccessListener(r -> {
                                synchronized (tokenSync) { tokenHolder[0] = r.getToken() != null ? r.getToken() : ""; tokenSync.notifyAll(); }
                            })
                            .addOnFailureListener(e -> {
                                synchronized (tokenSync) { tokenHolder[0] = ""; tokenSync.notifyAll(); }
                            });
                    synchronized (tokenSync) {
                        if (tokenHolder[0] == null) tokenSync.wait(5_000);
                    }
                    String idToken = tokenHolder[0] != null ? tokenHolder[0] : "";

                    if (!idToken.isEmpty()) {
                        // POST /requestLockNonce authenticated with the live session.
                        String nonce = requestLockNonce(idToken);
                        if (nonce != null && !nonce.isEmpty()) {
                            AccountLockWorker.enqueue(appCtx, uidBeforeWipe, nonce);
                            android.util.Log.d("DuressManager", "AccountLockWorker enqueued with nonce.");
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("DuressManager",
                            "Could not obtain lock nonce — WorkManager retry skipped: " + e.getMessage());
                }
            }

            // 2. Panic sync — upload unsynced messages to Firestore before local wipe.
            //    Hard deadline: 10 seconds. If the sync doesn't finish in time,
            //    BackupManager aborts automatically and we proceed to the wipe.
            BackupManager.syncIncrementalSync(context);

            // F35 / F16 fix: Write the sign-out event synchronously on THIS thread,
            // immediately before clearInstance(). Using logSync() (not the async log())
            // guarantees the insert lands in the database before we delete it.
            // Event type is SIGN_OUT — indistinguishable from a voluntary sign-out,
            // preserving plausible deniability in the Session Log.

            // 3. Destructive local wipe ─────────────────────────────────────────

            // 3a. Canonical local erasure — the SAME routine used by "Wipe & Exit" and
            //     "Unpair Device", running in DURESS mode. See WipeHelper for the full
            //     ordered step list (gallery media, decrypted-media disk cache, key
            //     material, SQLCipher DB, scheduled work, prefs, Firebase sign-out,
            //     temp/export cache).
            //
            //     Do NOT re-inline erasure steps here. This path previously maintained
            //     its own copy of the sequence and silently fell behind: it was missing
            //     both B2StorageHelper.clearDiskCache() and the cache-directory delete,
            //     so decrypted photos and videos survived a duress wipe in readable form.
            //     Any new erasure step belongs in WipeHelper.eraseLocalData().
            //
            //     DURESS mode additionally destroys the contact backup, so the
            //     "Restore Contacts" path cannot rebuild the social graph afterwards.
            try {
                com.duoshield.app.util.WipeHelper.eraseLocalData(
                        context, com.duoshield.app.util.WipeHelper.WipeMode.DURESS);
            } catch (Exception e) {
                android.util.Log.e("DuressManager", "eraseLocalData failed during duress wipe", e);
            }

            // F30 fix: Clear the routing-guard flag LAST, after signOut() and after all
            // prefs are wiped. The step-3d clear above already removes it as part of the
            // full PREFS_NAME wipe, but this explicit remove is a safety net in case
            // step 3d failed — without it, SignInActivity would remain blocked forever.
            try {
                SecurePrefs.get(context).edit().remove(KEY_RESET_PENDING).apply();
            } catch (Exception ignored) {}
            // Also clear the pre-rename plaintext flag, so a device upgrading from an
            // old build that was interrupted mid-wipe does not stay blocked forever —
            // and so that self-describing string is gone from the unencrypted XML.
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                       .edit().remove(KEY_RESET_PENDING_LEGACY).apply();
            } catch (Exception ignored) {}

        }, "duress-logout").start();
    }

    // ── Server-side eligibility gate ──────────────────────────────────────────
    //
    // Whether the secondary-code feature is even offered to this account is
    // controlled server-side, NOT by anything the client can set. A generic
    // account created by anyone probing the app is never enrolled and never
    // sees the option. Enrollment happens out-of-band (the operator adds
    // duressEligibility/{accountId} in the Firebase console) — the client only
    // ever reads a yes/no flag for its own account, and the PIN itself never
    // leaves the device either way.
    //
    // The result is cached in SecurePrefs so the app keeps working offline and
    // doesn't need a network round trip on every launch; a later successful
    // check can still flip the cached value (including revoking it).

    private static String eligibilityCacheKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_ELIGIBLE_PREFIX + user.getUid() : null;
    }

    /** Cached (offline-safe) read of whether this account may configure a duress PIN. */
    public static boolean isDuressEligibleCached(Context context) {
        String key = eligibilityCacheKey();
        if (key == null) return false;
        return SecurePrefs.get(context).getBoolean(key, false);
    }

    /**
     * Refreshes the cached eligibility flag from Firestore. Safe to call on
     * every sign-in / app foreground — reads a single small document via the
     * account's own UID, which the Firestore rules restrict to that account.
     * No-ops silently on failure (offline, etc.) — the previously cached value
     * is left untouched either way.
     */
    public static void refreshEligibility(Context context) {
        refreshEligibility(context, null);
    }

    /**
     * Same as {@link #refreshEligibility(Context)}, but invokes {@code onComplete}
     * on the main thread once the cached flag has been updated (or the attempt
     * has failed and the previous value has been kept).
     *
     * <h3>Why a callback is needed</h3>
     * Enrollment is granted out-of-band by the operator, which means it almost
     * always lands while the account is <em>already signed in</em>. Refreshing
     * only at sign-in left a freshly-enrolled account unable to see the option
     * until it signed out and back in — the enrollment appeared to do nothing.
     * Screens that surface the option call this on resume and re-render from the
     * callback, so an enrollment granted seconds ago is visible on the next
     * visit to the screen.
     *
     * <p>Revocation travels the same path: a later refresh that reads
     * {@code eligible == false} flips the cached value back off.
     *
     * @param onComplete run on the main thread after the cache write; may be null
     */
    public static void refreshEligibility(Context context, Runnable onComplete) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String key = user != null ? eligibilityCacheKey() : null;
        if (user == null || key == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        String uid = user.getUid();
        Context appCtx = context.getApplicationContext();
        FirebaseFirestore.getInstance()
                .collection("duressEligibility")
                .document(uid)
                .get()
                .addOnSuccessListener(snap -> {
                    boolean eligible = snap != null && snap.exists()
                            && Boolean.TRUE.equals(snap.getBoolean("eligible"));
                    // .apply() is deliberate: it updates the in-memory map
                    // synchronously (so the callback below re-reads the new value
                    // via isDuressEligibleCached) while the disk write happens off
                    // the main thread. .commit() here would block the UI thread on
                    // an EncryptedSharedPreferences write for a single boolean.
                    SecurePrefs.get(appCtx).edit().putBoolean(key, eligible).apply();
                    if (onComplete != null) onComplete.run();
                })
                .addOnFailureListener(e -> {
                    /* keep last-known cached value */
                    if (onComplete != null) onComplete.run();
                });
    }

    // ── Lock-nonce helper ─────────────────────────────────────────────────────

    /**
     * Requests a single-use account-lock nonce from the push server, authenticated
     * with the supplied Firebase ID token. The nonce is used by {@link AccountLockWorker}
     * as a retry credential — it has no auth power of its own, is uid-bound server-side,
     * expires in 24 hours, and is deleted after one successful {@code /duress-lock} call.
     *
     * <p>Must NOT be called on the main thread (blocking HTTP call).
     *
     * @param idToken valid Firebase ID token captured before sign-out
     * @return nonce string, or {@code null} if the request failed
     */
    private static String requestLockNonce(String idToken) {
        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) return null;
        String endpoint = serverUrl.endsWith("/")
                ? serverUrl + "requestLockNonce"
                : serverUrl + "/requestLockNonce";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("Authorization", "Bearer " + idToken);
                conn.setRequestProperty("Content-Length", "0");
                conn.setDoOutput(false);

                int code = conn.getResponseCode();
                if (code != 200) {
                    android.util.Log.w("DuressManager",
                            "requestLockNonce: server returned HTTP " + code);
                    return null;
                }
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[2048];
                int n;
                while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
                String body = buf.toString("UTF-8");
                JSONObject json = new JSONObject(body);
                String nonce = json.optString("nonce", null);
                if (nonce == null || nonce.isEmpty()) {
                    android.util.Log.w("DuressManager", "requestLockNonce: empty nonce in response");
                    return null;
                }
                return nonce;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            android.util.Log.w("DuressManager", "requestLockNonce failed: " + e.getMessage());
            return null;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static byte[] pbkdf2(String pin, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LEN);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
    }

    private static void deleteDir(java.io.File dir) {
        if (dir == null) return;
        java.io.File[] files = dir.listFiles();
        if (files != null) for (java.io.File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        return out;
    }
}
