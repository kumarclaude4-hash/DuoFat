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
 * Returns SharedPreferences instances for storing crypto material
 * (Signal identity key pair, prekeys, PIN hashes).
 *
 * <h3>Three independent containers</h3>
 * {@link #get} returns the account-scoped file — Signal key material, the
 * app PIN hash, duress PIN hash, etc. It is blank-cleared wholesale by
 * {@code DuressManager.performLogout()}, {@code WipeHelper.wipeAll()}, and
 * the Danger Zone "unpair" flow whenever an account is wiped from this
 * device. {@link #getDeviceGate} returns a second, physically separate file
 * used only for the device-level PIN gate ({@code PinManager}'s device-scoped
 * methods, {@code DevicePinGateActivity}) — a protection that exists
 * independently of any signed-in account and must therefore survive every
 * one of those wipes. Keeping it in its own file makes that a structural
 * guarantee: none of the wipe call sites touch it, so there is no exclusion
 * list to keep in sync as the account-scoped file grows new keys over time.
 *
 * <p>{@link #getSessionState} is a third file, added for the same structural
 * reason. It holds the small amount of state that must outlive the wipe
 * <em>because</em> the wipe is what it describes: the pending account-lock
 * intent and the resume marker (see {@code PendingLockStore}). Previously the
 * duress path's lock intent lived only in a Firestore write queued in the
 * account-scoped store, so being offline at trigger time meant the wipe deleted
 * the queued mutation and the account was never locked at all — the attacker
 * chose whether the lock happened by pulling the network (S06-H3). Storing the
 * intent here instead makes the lock decision durable before any erasure runs,
 * so it survives a reboot, a force-stop, and an indefinitely offline device.
 *
 * <p><strong>Do not add erasure of this file to a wipe path.</strong> That is
 * exactly the bug the separate file prevents. {@code PendingLockStore} clears
 * its own keys once the server has confirmed the lock, and nothing else should.
 *
 * Initialisation strategy (three tiers, applied identically to whichever
 * file is being opened):
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
 *
 * Both files share the same AndroidKeyStore master-key alias when hardware/software
 * key tiers succeed — that is safe: the alias only protects each file's own generated
 * data key, and knowing one file's ciphertext reveals nothing about the other's.
 */
public class SecurePrefs {

    private static final String TAG                = "SecurePrefs";
    private static final String FILE_NAME          = "duoshield_secure_prefs";
    private static final String DEVICE_GATE_FILE   = "device_gate_prefs";
    /**
     * Deliberately neutral file name. This container's whole purpose is to hold
     * a marker that a teardown is in flight, so a file called anything like
     * "duress" or "wipe" would itself be the disclosure the marker is carefully
     * named to avoid — and unlike the other two files, an adversary has a
     * specific reason to go looking for this one.
     */
    private static final String SESSION_STATE_FILE = "session_state_prefs";

    private static volatile SharedPreferences cached;
    private static volatile boolean           encryptionAvailable = false;
    private static volatile boolean           initialized         = false;

    private static volatile SharedPreferences deviceGateCached;
    private static volatile boolean           deviceGateEncryptionAvailable = false;

    private static volatile SharedPreferences sessionStateCached;

    // Test-only injection point (see FakeSharedPreferences / DeviceGatePinIsolationTest
    // in app/src/test). All are null in production, so get()/getDeviceGate()/
    // getSessionState() behave exactly as before; when set, they short-circuit before
    // touching the real Context or Android Keystore, which aren't available in a plain
    // JVM unit test.
    private static volatile SharedPreferences testMainOverride;
    private static volatile SharedPreferences testDeviceGateOverride;
    private static volatile SharedPreferences testSessionStateOverride;

    public static SharedPreferences get(Context context) {
        if (testMainOverride != null) return testMainOverride;
        if (cached != null) return cached;
        synchronized (SecurePrefs.class) {
            if (cached != null) return cached;
            Built built = buildTiered(context, FILE_NAME);
            cached              = built.prefs;
            encryptionAvailable = built.encryptionAvailable;
            initialized         = true;
            return cached;
        }
    }

    /**
     * Isolated container for the device-level PIN gate. See the class javadoc
     * for why this must never share a file with {@link #get}.
     */
    public static SharedPreferences getDeviceGate(Context context) {
        if (testDeviceGateOverride != null) return testDeviceGateOverride;
        if (deviceGateCached != null) return deviceGateCached;
        synchronized (SecurePrefs.class) {
            if (deviceGateCached != null) return deviceGateCached;
            Built built = buildTiered(context, DEVICE_GATE_FILE);
            deviceGateCached              = built.prefs;
            deviceGateEncryptionAvailable = built.encryptionAvailable;
            return deviceGateCached;
        }
    }

    /**
     * Isolated container for wipe-surviving session/teardown state. See the class
     * javadoc for why this must never share a file with {@link #get}, and must
     * never be added to a wipe path.
     */
    public static SharedPreferences getSessionState(Context context) {
        if (testSessionStateOverride != null) return testSessionStateOverride;
        if (sessionStateCached != null) return sessionStateCached;
        synchronized (SecurePrefs.class) {
            if (sessionStateCached != null) return sessionStateCached;
            sessionStateCached = buildTiered(context, SESSION_STATE_FILE).prefs;
            return sessionStateCached;
        }
    }

    /** Result of {@link #buildTiered}: the resolved store plus which tier produced it. */
    private static final class Built {
        final SharedPreferences prefs;
        final boolean           encryptionAvailable;
        Built(SharedPreferences prefs, boolean encryptionAvailable) {
            this.prefs               = prefs;
            this.encryptionAvailable = encryptionAvailable;
        }
    }

    /**
     * Runs the three-tier EncryptedSharedPreferences initialisation strategy
     * (see class javadoc) against an arbitrary file name, falling back to
     * plaintext MODE_PRIVATE prefs if every tier fails.
     */
    private static Built buildTiered(Context context, String fileName) {
        Context appCtx = context.getApplicationContext();

        // ── Tier 1: standard MasterKey (hardware-backed when available) ──────
        try {
            MasterKey masterKey = new MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            SharedPreferences sp = EncryptedSharedPreferences.create(
                    appCtx, fileName, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            Log.d(TAG, "ESP ready (tier 1 — hardware key) for " + fileName + ".");
            return new Built(sp, true);
        } catch (Exception e1) {
            Log.w(TAG, "ESP tier 1 failed for " + fileName + " ("
                    + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " API=" + android.os.Build.VERSION.SDK_INT + "): "
                    + e1.getClass().getSimpleName() + ": " + e1.getMessage());
        }

        // ── Tier 2: explicit spec — no StrongBox, no user-auth required ──────
        // Fixes known security-crypto bug on budget MediaTek / Android Go devices
        // where MasterKey.Builder.setKeyScheme() silently adds constraints the
        // device's KeyStore implementation rejects.
        try {
            SharedPreferences sp = buildWithExplicitSpec(appCtx, fileName, false);
            Log.i(TAG, "ESP ready (tier 2 — explicit software spec) for " + fileName + ".");
            return new Built(sp, true);
        } catch (Exception e2) {
            Log.w(TAG, "ESP tier 2 failed for " + fileName + ": "
                    + e2.getClass().getSimpleName() + ": " + e2.getMessage());
        }

        // ── Tier 3: delete corrupted alias + retry ────────────────────────────
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS);
                Log.w(TAG, "Deleted corrupted KeyStore alias — retrying (" + fileName + ").");
            }
            SharedPreferences sp = buildWithExplicitSpec(appCtx, fileName, false);
            Log.i(TAG, "ESP ready (tier 3 — alias cleared + software spec) for " + fileName + ".");
            return new Built(sp, true);
        } catch (Exception e3) {
            Log.e(TAG, "ESP tier 3 (alias-clear + retry) failed for " + fileName + ": "
                    + e3.getClass().getSimpleName() + ": " + e3.getMessage()
                    + " — falling back to plaintext MODE_PRIVATE prefs."
                    + " Device: " + android.os.Build.MANUFACTURER
                    + " " + android.os.Build.MODEL
                    + " API=" + android.os.Build.VERSION.SDK_INT, e3);
        }

        // ── Fallback: plaintext (MODE_PRIVATE) ───────────────────────────────
        // Still protected by Android's per-app file isolation. No screen lock
        // required — same posture as WhatsApp/Telegram on devices without a TEE.
        SharedPreferences sp = appCtx.getSharedPreferences(fileName, Context.MODE_PRIVATE);
        return new Built(sp, false);
    }

    /**
     * Builds an EncryptedSharedPreferences with an explicit KeyGenParameterSpec that
     * avoids optional constraints (StrongBox, user-auth) which some budget devices reject.
     */
    private static SharedPreferences buildWithExplicitSpec(Context appCtx, String fileName,
                                                            boolean requireStrongBox)
            throws Exception {
        KeyGenParameterSpec.Builder specBuilder = new KeyGenParameterSpec.Builder(
                MasterKey.DEFAULT_MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);
                // Explicitly NOT setting setUserAuthenticationRequired(true) — that is
                // what causes the "screen lock required" failure on Vivo Y11 / POCO C51
                // when security-crypto sets it implicitly on some API levels.
        // setIsStrongBoxBacked() requires API 28; minSdk is 26. Devices below 28 never
        // have StrongBox anyway, so requireStrongBox is only ever true when SDK_INT >= 28.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(requireStrongBox);
        }
        KeyGenParameterSpec spec = specBuilder.build();
        MasterKey masterKey = new MasterKey.Builder(appCtx)
                .setKeyGenParameterSpec(spec)
                .build();
        return EncryptedSharedPreferences.create(
                appCtx, fileName, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /**
     * Returns true if EncryptedSharedPreferences initialised successfully for the
     * account-scoped file returned by {@link #get}.
     * False means the fallback plaintext store is in use — crypto material is still
     * scoped to this app (MODE_PRIVATE) but not hardware/software encrypted.
     */
    public static boolean isAvailable() {
        return initialized && encryptionAvailable;
    }

    /**
     * Resets all cached instances — intended for use in WipeHelper / tests only.
     * Safe to call even though the device-gate and session-state files' on-disk
     * contents are never touched by an account wipe: this only drops the in-memory
     * wrappers, forcing the next {@link #getDeviceGate} / {@link #getSessionState}
     * call to reopen the same untouched file.
     */
    public static void reset() {
        synchronized (SecurePrefs.class) {
            cached                        = null;
            encryptionAvailable           = false;
            initialized                   = false;
            deviceGateCached              = null;
            deviceGateEncryptionAvailable = false;
            sessionStateCached            = null;
        }
    }

    /**
     * Test-only. Injects fakes so classes built on top of SecurePrefs (e.g.
     * PinManager) can be exercised in a plain JUnit test without a real
     * Android runtime. See FakeSharedPreferences / DeviceGatePinIsolationTest.
     */
    static void setTestOverridesForUnitTests(SharedPreferences main, SharedPreferences deviceGate) {
        setTestOverridesForUnitTests(main, deviceGate, null);
    }

    /** Test-only. As above, additionally injecting the session-state container. */
    static void setTestOverridesForUnitTests(SharedPreferences main,
                                             SharedPreferences deviceGate,
                                             SharedPreferences sessionState) {
        testMainOverride         = main;
        testDeviceGateOverride   = deviceGate;
        testSessionStateOverride = sessionState;
    }

    /** Test-only. Clears injected fakes and resets caches. */
    static void clearTestOverridesForUnitTests() {
        testMainOverride         = null;
        testDeviceGateOverride   = null;
        testSessionStateOverride = null;
        reset();
    }
}
