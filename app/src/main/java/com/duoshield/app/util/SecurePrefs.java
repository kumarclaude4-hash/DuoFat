package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Returns an EncryptedSharedPreferences instance for storing crypto material
 * (Signal identity key pair, prekeys, PIN hashes).
 *
 * §3.3 fix: the old code silently fell back to plain SharedPreferences on any
 * Keystore/EncryptedSharedPreferences failure, which could expose key material and PIN
 * hashes in plaintext without any indication to callers.
 *
 * New behaviour:
 *  - The result is cached after the first successful call (thread-safe double-checked locking).
 *  - If EncryptedSharedPreferences is unavailable, {@code encryptionAvailable} is set to false
 *    and the fallback is still used so the app doesn't crash — BUT callers check
 *    {@link #isAvailable()} and treat all crypto material reads as absent (null), forcing
 *    re-identity rather than silently operating on potentially plaintext-stored key material.
 */
public class SecurePrefs {

    private static final String TAG       = "SecurePrefs";
    private static final String FILE_NAME = "duoshield_secure_prefs";

    private static volatile SharedPreferences cached;
    private static volatile boolean           encryptionAvailable = true;
    private static volatile boolean           initialized         = false;

    public static SharedPreferences get(Context context) {
        if (cached != null) return cached;
        synchronized (SecurePrefs.class) {
            if (cached != null) return cached;
            try {
                // Uses MasterKey.Builder (security-crypto 1.1.0-alpha06+).
                // The underlying AndroidKeyStore alias is unchanged from the legacy API, so
                // existing encrypted prefs remain readable without any data migration.
                MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                cached = EncryptedSharedPreferences.create(
                    context.getApplicationContext(),
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                encryptionAvailable = true;
                Log.d(TAG, "EncryptedSharedPreferences initialised successfully.");
            } catch (Exception e) {
                Log.e(TAG, "EncryptedSharedPreferences UNAVAILABLE — falling back to plain prefs. "
                    + "All crypto-material reads will be treated as absent to prevent "
                    + "silent use of potentially plaintext-stored key data.", e);
                encryptionAvailable = false;
                cached = context.getApplicationContext()
                               .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
            }
            initialized = true;
            return cached;
        }
    }

    /**
     * Returns true if the underlying storage is actually encrypted.
     * Call {@link #get(Context)} at least once before relying on this —
     * it reflects the outcome of the first initialisation attempt.
     */
    public static boolean isAvailable() {
        return initialized && encryptionAvailable;
    }

    /**
     * Resets the cache — intended for use in WipeHelper / tests only.
     * After calling this, the next get() will re-attempt EncryptedSharedPreferences creation.
     */
    public static void reset() {
        synchronized (SecurePrefs.class) {
            cached              = null;
            encryptionAvailable = true;
            initialized         = false;
        }
    }
}
