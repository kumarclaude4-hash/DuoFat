package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.security.KeyStore;

/**
 * Returns a SharedPreferences instance for storing crypto material
 * (Signal identity key pair, prekeys, PIN hashes).
 *
 * Initialisation strategy (three tiers):
 *  1. Standard MasterKey with AES256_GCM — hardware-backed when TEE is available.
 *  2. Explicit KeyGenParameterSpec — no StrongBox, no user-auth required — works on
 *     budget devices (Helio G36, Android Go) where the default MasterKey.Builder fails
 *     due to a known security-crypto:1.1.0-alpha06 bug on some manufacturers' KeyStore
 *     implementations.  This is the same key strength (AES-256-GCM) just without
 *     optional hardware constraints that the buggy KeyStore rejects.
 *  3. Delete the corrupted KeyStore alias and retry tier 2 — handles the case where a
 *     previous failed init left a broken key entry in the KeyStore.
 *
 * If ALL three tiers fail, the app falls back to plaintext SharedPreferences AND
 * sets encryptionAvailable=false. Callers may check isAvailable() and degrade gracefully,
 * but they must NOT block the user — plaintext prefs are still protected by Android's
 * per-app file isolation (MODE_PRIVATE), which is the same level of protection WhatsApp
 * and Telegram use on devices without a hardware TEE.
 */
public class SecurePrefs {

    private static final String TAG       = "SecurePrefs";
    private static final String FILE_NAME = "duoshield_secure_prefs";

    private static volatile SharedPreferences cached;
    private static volatile boolean           encryptionAvailable = false;
    private static volatile boolean           initialized         = false;

    public static SharedPreferences get(Context context) {
        if (cached != null) return cached;
        synchronized (SecurePrefs.class) {
            if (cached != null) return cached;
            Context appCtx = context.getApplicationContext();

            // ── Tier 1: standard MasterKey (hardware-backed when available) ──────
            try {
                MasterKey masterKey = new MasterKey.Builder(appCtx)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                cached = EncryptedSharedPreferences.create(
                        appCtx, FILE_NAME, masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
                encryptionAvailable = true;
                Log.d(TAG, "ESP ready (tier 1 — hardware key).");
                initialized = true;
                return cached;
            } catch (Exception e1) {
                Log.w(TAG, "ESP tier 1 failed ("
                        + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                        + " API=" + android.os.Build.VERSION.SDK_INT + "): "
                        + e1.getClass().getSimpleName() + ": " + e1.getMessage());
            }

            // ── Tier 2: explicit spec — no StrongBox, no user-auth required ──────
            // Fixes known security-crypto bug on budget MediaTek / Android Go devices
            // where MasterKey.Builder.setKeyScheme() silently adds constraints the
            // device's KeyStore implementation rejects.
            try {
                cached = buildWithExplicitSpec(appCtx, false);
                encryptionAvailable = true;
                Log.i(TAG, "ESP ready (tier 2 — explicit software spec).");
                initialized = true;
                return cached;
            } catch (Exception e2) {
                Log.w(TAG, "ESP tier 2 failed: "
                        + e2.getClass().getSimpleName() + ": " + e2.getMessage());
            }

            // ── Tier 3: delete corrupted alias + retry ────────────────────────────
            try {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS);
                    Log.w(TAG, "Deleted corrupted KeyStore alias — retrying.");
                }
                cached = buildWithExplicitSpec(appCtx, false);
                encryptionAvailable = true;
                Log.i(TAG, "ESP ready (tier 3 — alias cleared + software spec).");
                initialized = true;
                return cached;
            } catch (Exception e3) {
                Log.e(TAG, "ESP tier 3 (alias-clear + retry) failed: "
                        + e3.getClass().getSimpleName() + ": " + e3.getMessage()
                        + " — falling back to plaintext MODE_PRIVATE prefs."
                        + " Device: " + android.os.Build.MANUFACTURER
                        + " " + android.os.Build.MODEL
                        + " API=" + android.os.Build.VERSION.SDK_INT, e3);
            }

            // ── Fallback: plaintext (MODE_PRIVATE) ───────────────────────────────
            // Still protected by Android's per-app file isolation. No screen lock
            // required — same posture as WhatsApp/Telegram on devices without a TEE.
            encryptionAvailable = false;
            cached = appCtx.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
            initialized = true;
            return cached;
        }
    }

    /**
     * Builds an EncryptedSharedPreferences with an explicit KeyGenParameterSpec that
     * avoids optional constraints (StrongBox, user-auth) which some budget devices reject.
     */
    private static SharedPreferences buildWithExplicitSpec(Context appCtx,
                                                            boolean requireStrongBox)
            throws Exception {
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Explicitly NOT setting setUserAuthenticationRequired(true) — that is
                // what causes the "screen lock required" failure on Vivo Y11 / POCO C51
                // when security-crypto sets it implicitly on some API levels.
                .setIsStrongBoxBacked(requireStrongBox)
                .build();
        MasterKey masterKey = new MasterKey.Builder(appCtx)
                .setKeyGenParameterSpec(spec)
                .build();
        return EncryptedSharedPreferences.create(
                appCtx, FILE_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /**
     * Returns true if EncryptedSharedPreferences initialised successfully.
     * False means the fallback plaintext store is in use — crypto material is still
     * scoped to this app (MODE_PRIVATE) but not hardware/software encrypted.
     */
    public static boolean isAvailable() {
        return initialized && encryptionAvailable;
    }

    /**
     * Resets the cache — intended for use in WipeHelper / tests only.
     */
    public static void reset() {
        synchronized (SecurePrefs.class) {
            cached              = null;
            encryptionAvailable = false;
            initialized         = false;
        }
    }
}
