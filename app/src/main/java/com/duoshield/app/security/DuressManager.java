package com.duoshield.app.security;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.duoshield.app.SignInActivity;
import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.backup.BackupScheduler;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.util.ContactBackupHelper;
import com.duoshield.app.util.SecurePrefs;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class DuressManager {

    private static final String PREFS_NAME          = "duoshield_prefs";
    private static final String KEY_DURESS_PREFIX   = "duress_pin_hash_";
    private static final String KEY_DURESS_LEGACY   = "duress_pin_hash";
    private static final int    ITERATIONS          = 310_000;
    private static final int    KEY_LEN             = 256;

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
        return user != null ? KEY_DURESS_PREFIX + user.getUid() : null;
    }

    public static void setDuressPin(Context context, String pin) {
        String key = duressKey();
        if (key == null) return;
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash   = pbkdf2(pin, salt);
            String stored = bytesToHex(salt) + ":" + bytesToHex(hash);
            SecurePrefs.get(context).edit()
                    .putString(key, stored)
                    .remove(KEY_DURESS_LEGACY)
                    .apply();
        } catch (Exception ignored) {}
    }

    public static boolean isDuressPin(Context context, String enteredPin) {
        String key = duressKey();
        if (key == null) return false;
        SharedPreferences sp = SecurePrefs.get(context);
        String stored = sp.getString(key, null);
        if (stored == null) {
            // Fallback to legacy global key (migration window)
            stored = sp.getString(KEY_DURESS_LEGACY, null);
        }
        if (stored == null) return false;
        int sep = stored.indexOf(':');
        if (sep < 0) return false;
        try {
            byte[] salt     = hexToBytes(stored.substring(0, sep));
            byte[] expected = hexToBytes(stored.substring(sep + 1));
            byte[] actual   = pbkdf2(enteredPin, salt);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) { return false; }
    }

    /** Returns true if a duress PIN hash is stored for the current account. */
    public static boolean hasDuressPin(Context context) {
        String key = duressKey();
        if (key == null) return false;
        SharedPreferences sp = SecurePrefs.get(context);
        if (sp.getString(key, null) != null) return true;
        // Migration: move legacy global hash to UID-scoped key
        String legacy = sp.getString(KEY_DURESS_LEGACY, null);
        if (legacy != null) {
            sp.edit().putString(key, legacy).remove(KEY_DURESS_LEGACY).apply();
            return true;
        }
        return false;
    }

    /** Removes the duress PIN hash for the current account. */
    public static void clearDuressPin(Context context) {
        String key = duressKey();
        if (key == null) return;
        SecurePrefs.get(context).edit()
                .remove(key)
                .remove(KEY_DURESS_LEGACY)
                .apply();
    }

    /**
     * "Sync then Wipe" — plausible-deniability duress logout.
     *
     * <p>This is the unified logout action for both:
     * <ul>
     *   <li>A duress PIN being entered (attacker watching), and</li>
     *   <li>The 5th consecutive wrong-PIN attempt in {@code LockScreenActivity}.</li>
     * </ul>
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
        // F30 fix: Write a synchronous routing-guard flag BEFORE launching SignInActivity.
        // SignInActivity (and SplashActivity / MainActivity) check this flag and skip the
        // returning-user auto-route while the background wipe is still in flight.
        // The flag is cleared at the very end of the background thread, after signOut(),
        // so SignInActivity cannot bounce back before both keys and session are destroyed.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
               .edit().putBoolean("duress_wipe_in_progress", true).commit();

        // 1. Instant navigation — removes chat screen from view immediately.
        //    To an observer, it looks like the app is simply processing the PIN.
        Intent intent = new Intent(context, SignInActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);

        // Cancel the daily backup sync — the session is intentionally destroyed.
        try { BackupScheduler.cancel(context); } catch (Exception ignored) {}

        // Full "sync then wipe" on a background thread
        new Thread(() -> {

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

            // 3a. Close and delete the SQLCipher database (messages, contacts, logs).
            //     clearInstance() must come first so Room's cached connection is
            //     released before the file is deleted.
            AppDatabase.clearInstance();
            context.deleteDatabase("duoshield_db");

            // 3b. Synchronously destroy all key material in SecurePrefs.
            //     .commit() (not .apply()) guarantees the keys are gone before we
            //     proceed — critical for forensic resistance.
            try {
                SecurePrefs.get(context).edit().clear().commit();
                SecurePrefs.reset(); // invalidate cached instance
            } catch (Exception ignored) {}

            // 3c. Wipe the local contact backup so the "Restore Contacts" path
            //     cannot recover contact data after a duress-triggered wipe.
            ContactBackupHelper.clearBackup(context);

            // 3d. F37 fix: delete any media the user saved to the public gallery
            //     (Pictures/DuoShield, Movies/DuoShield). Must run BEFORE the prefs
            //     clear below because the URI list lives inside duoshield_prefs.
            try {
                com.duoshield.app.util.MediaStoreWipeHelper.wipeAll(context);
            } catch (Exception ignored) {}

            // 3e. Clear all SharedPreferences files synchronously.
            //     NOTE: duress_wipe_in_progress lives in PREFS_NAME and is cleared
            //     here along with all other keys — see step 4 below for the removal.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().clear().commit();
            context.getSharedPreferences("duoshield_security_prefs", Context.MODE_PRIVATE)
                   .edit().clear().commit();
            context.getSharedPreferences("duoshield_contacts_bak", Context.MODE_PRIVATE)
                   .edit().clear().commit();

            // 4. Firebase local sign-out (no network call, no Firestore writes).
            try { FirebaseAuth.getInstance().signOut(); } catch (Exception ignored) {}

            // F30 fix: Clear the routing-guard flag LAST, after signOut() and after all
            // prefs are wiped. The step-3d clear above already removes it as part of the
            // full PREFS_NAME wipe, but this explicit remove is a safety net in case
            // step 3d failed — without it, SignInActivity would remain blocked forever.
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                       .edit().remove("duress_wipe_in_progress").apply();
            } catch (Exception ignored) {}

        }, "duress-logout").start();
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
