package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Stores the app PIN hash in EncryptedSharedPreferences (SecurePrefs), scoped
 * to the currently signed-in Firebase UID.
 *
 * <h3>Why UID-scoped?</h3>
 * The duress logout intentionally preserves the PIN hash so a restore attempt
 * is still gated. But if a NEW user signs in on the same device after a duress
 * wipe they must not see a lock screen for a PIN they never set — that would be
 * suspicious. Keying by UID means each account has its own hash entry and a new
 * account starts with no hash → no lock screen.
 *
 * <h3>Migration</h3>
 * Existing installs that stored the hash under the legacy global key
 * {@code "app_pin_hash"} are migrated transparently on first access: the value
 * is moved to the UID-scoped key and the legacy entry is removed.
 */
public class PinManager {

    private static final String KEY_PIN_PREFIX    = "app_pin_hash_";
    private static final String KEY_PIN_LEGACY    = "app_pin_hash";
    private static final String KEY_LEN_PREFIX    = "app_pin_length_";
    private static final int    ITERATIONS        = 310_000;
    private static final int    KEY_LEN           = 256;
    private static final int    DEFAULT_PIN_LEN   = 6;

    /**
     * Device-scoped PIN — independent of any Firebase UID. Gates
     * {@link com.duoshield.app.SignInActivity} (Welcome / Create / Restore)
     * on fresh installs, before any account exists. See
     * {@link com.duoshield.app.ui.DevicePinGateActivity}.
     */
    private static final String KEY_DEVICE_PIN_HASH = "device_gate_pin_hash";
    private static final String KEY_DEVICE_PIN_LEN  = "device_gate_pin_length";

    /**
     * True once the device-level PIN gate has been satisfied for the current
     * process — by successful verification, fresh setup, or because this
     * device predates the feature and is exempt ({@link #looksLikePreExistingDevice}).
     * Resets naturally on process death; {@code SignInActivity} re-checks on
     * every cold start so the gate can never be bypassed by relaunching.
     */
    public static volatile boolean deviceGateSatisfiedThisProcess = false;

    /**
     * Returns the UID-scoped SecurePrefs key for the currently signed-in user,
     * or {@code null} if no user is signed in.
     */
    private static String pinKey() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? KEY_PIN_PREFIX + user.getUid() : null;
    }

    public static void setPin(Context ctx, String pin) {
        String key = pinKey();
        if (key == null) return;
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(pin, salt);
            String stored = bytesToHex(salt) + ":" + bytesToHex(hash);
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            SharedPreferences.Editor ed = SecurePrefs.get(ctx).edit()
                    .putString(key, stored)
                    .remove(KEY_PIN_LEGACY);
            if (user != null) {
                ed.putInt(KEY_LEN_PREFIX + user.getUid(), pin.length());
            }
            ed.apply();
        } catch (Exception e) {
            android.util.Log.e("PinManager", "Failed to store PIN hash", e);
        }
    }

    /**
     * Returns the length of the PIN the user set (4–6).
     * Defaults to {@link #DEFAULT_PIN_LEN} if the length was never stored
     * (e.g. the PIN was set on an older build).
     */
    public static int getPinLength(Context ctx) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return DEFAULT_PIN_LEN;
        return SecurePrefs.get(ctx).getInt(KEY_LEN_PREFIX + user.getUid(), DEFAULT_PIN_LEN);
    }

    public static boolean hasPinSet(Context ctx) {
        String key = pinKey();
        if (key == null) return false;
        SharedPreferences sp = SecurePrefs.get(ctx);
        if (sp.getString(key, null) != null) return true;
        // Migration: move legacy global hash to UID-scoped key
        String legacy = sp.getString(KEY_PIN_LEGACY, null);
        if (legacy != null) {
            sp.edit().putString(key, legacy).remove(KEY_PIN_LEGACY).apply();
            return true;
        }
        return false;
    }

    public static boolean verifyPin(Context ctx, String entered) {
        String key = pinKey();
        if (key == null) return false;
        SharedPreferences sp = SecurePrefs.get(ctx);
        String stored = sp.getString(key, null);
        if (stored == null) {
            // Fallback to legacy key (handles the window between hasPinSet and
            // verifyPin being called before migration has run)
            stored = sp.getString(KEY_PIN_LEGACY, null);
        }
        if (stored == null) return false;
        int sep = stored.indexOf(':');
        if (sep < 0) return false;
        try {
            byte[] salt     = hexToBytes(stored.substring(0, sep));
            byte[] expected = hexToBytes(stored.substring(sep + 1));
            byte[] actual   = pbkdf2(entered, salt);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) { return false; }
    }

    public static void clearPin(Context ctx) {
        String key = pinKey();
        if (key == null) return;
        SecurePrefs.get(ctx).edit()
                .remove(key)
                .remove(KEY_PIN_LEGACY)
                .apply();
    }

    // ── Device-scoped gate PIN ────────────────────────────────────────────

    public static boolean hasDevicePinSet(Context ctx) {
        return SecurePrefs.get(ctx).getString(KEY_DEVICE_PIN_HASH, null) != null;
    }

    public static void setDevicePin(Context ctx, String pin) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(pin, salt);
            String stored = bytesToHex(salt) + ":" + bytesToHex(hash);
            SecurePrefs.get(ctx).edit()
                    .putString(KEY_DEVICE_PIN_HASH, stored)
                    .putInt(KEY_DEVICE_PIN_LEN, pin.length())
                    .apply();
        } catch (Exception e) {
            android.util.Log.e("PinManager", "Failed to store device-level PIN hash", e);
        }
    }

    public static int getDevicePinLength(Context ctx) {
        return SecurePrefs.get(ctx).getInt(KEY_DEVICE_PIN_LEN, DEFAULT_PIN_LEN);
    }

    public static boolean verifyDevicePin(Context ctx, String entered) {
        SharedPreferences sp = SecurePrefs.get(ctx);
        String stored = sp.getString(KEY_DEVICE_PIN_HASH, null);
        if (stored == null) return false;
        int sep = stored.indexOf(':');
        if (sep < 0) return false;
        try {
            byte[] salt     = hexToBytes(stored.substring(0, sep));
            byte[] expected = hexToBytes(stored.substring(sep + 1));
            byte[] actual   = pbkdf2(entered, salt);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) { return false; }
    }

    /**
     * Copies the device-level PIN hash into the just-created/just-restored
     * account's UID-scoped slot, so the existing AppLockManager /
     * LockScreenActivity background-lock mechanism (which reads
     * {@code app_pin_hash_<uid>}) works immediately without asking the user
     * to set a PIN a second time. Copying the stored {@code salt:hash}
     * string is sufficient — the plaintext PIN is never needed again.
     */
    public static void promoteDevicePinToCurrentUser(Context ctx) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        SharedPreferences sp = SecurePrefs.get(ctx);
        String stored = sp.getString(KEY_DEVICE_PIN_HASH, null);
        if (stored == null) return;
        int len = sp.getInt(KEY_DEVICE_PIN_LEN, DEFAULT_PIN_LEN);
        sp.edit()
                .putString(KEY_PIN_PREFIX + user.getUid(), stored)
                .putInt(KEY_LEN_PREFIX + user.getUid(), len)
                .apply();
    }

    /**
     * True if this device shows signs of an account that existed before the
     * upfront device-PIN gate was introduced — a persisted {@code my_uid}, or
     * any pre-existing account-scoped PIN hash. Used to exempt pre-existing
     * installs from being retroactively forced through the new gate (see
     * {@link com.duoshield.app.ui.DevicePinGateActivity}); this fix only
     * applies going forward to genuinely fresh installs.
     */
    public static boolean looksLikePreExistingDevice(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        if (prefs.getString("my_uid", null) != null) return true;
        SharedPreferences sp = SecurePrefs.get(ctx);
        if (sp.getString(KEY_PIN_LEGACY, null) != null) return true;
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(KEY_PIN_PREFIX)) return true;
        }
        return false;
    }

    private static byte[] pbkdf2(String pin, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LEN);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
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
