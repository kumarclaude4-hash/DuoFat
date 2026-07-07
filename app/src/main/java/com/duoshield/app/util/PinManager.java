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

    private static final String KEY_PIN_PREFIX = "app_pin_hash_";
    private static final String KEY_PIN_LEGACY = "app_pin_hash";
    private static final int    ITERATIONS     = 310_000;
    private static final int    KEY_LEN        = 256;

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
            SecurePrefs.get(ctx).edit()
                    .putString(key, stored)
                    .remove(KEY_PIN_LEGACY)
                    .apply();
        } catch (Exception e) {
            android.util.Log.e("PinManager", "Failed to store PIN hash", e);
        }
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
