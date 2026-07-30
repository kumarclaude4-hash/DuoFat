package com.duoshield.app.crypto;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.duoshield.app.util.SecurePrefs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Derives and manages the per-user backup encryption key.
 *
 * Key derivation (deterministic, never stored on server):
 *   mnemonic → 64-byte seed (PBKDF2-SHA512 via SeedPhraseHelper)
 *               → 32-byte AES key (HKDF-SHA256, info = "DUOSHIELD_BACKUP_V1")
 *
 * The 32-byte key is stored locally in SecurePrefs (EncryptedSharedPreferences
 * backed by Android Keystore).  It is re-derived from the seed phrase on restore.
 *
 * Encryption: AES-256-GCM, random 12-byte IV per message.
 * Wire format: Base64(IV) + ":" + Base64(ciphertext||GCM-tag)
 *
 * Compressed variant (preferred for new writes):
 *   plaintext → GZIP → AES-256-GCM → same wire format
 *   Indicated by a "compressed:true" field stored alongside in Firestore.
 *
 * Integrity: SHA-256 checksum of plaintext stored as a separate Firestore field.
 *   Verified on restore; mismatch is logged and the document is skipped.
 */
public final class BackupCryptoHelper {

    private static final String TAG          = "BackupCryptoHelper";
    public  static final String PREF_KEY     = "backup_key_b64";
    private static final String HKDF_INFO    = "DUOSHIELD_BACKUP_V1";
    private static final int    GCM_TAG_BITS = 128;
    private static final int    IV_LEN       = 12;

    private BackupCryptoHelper() {}

    // ── Key derivation ────────────────────────────────────────────────────────

    /**
     * Derives the 32-byte backup AES key from a 12-word BIP39 mnemonic.
     * Deterministic: same mnemonic always yields the same key.
     */
    public static byte[] deriveBackupKey(String mnemonic) throws Exception {
        byte[] seed = SeedPhraseHelper.mnemonicToSeed(mnemonic.trim());
        return SeedPhraseHelper.hkdfSha256(seed, HKDF_INFO.getBytes(StandardCharsets.UTF_8), 32);
    }

    /**
     * Derives the backup key from the mnemonic and stores it in SecurePrefs.
     * Call this at account creation and at restore time.
     */
    public static void storeKey(Context ctx, String mnemonic) {
        try {
            byte[] key = deriveBackupKey(mnemonic);
            boolean stored = SecurePrefs.get(ctx).edit()
                    .putString(PREF_KEY, Base64.encodeToString(key, Base64.NO_WRAP))
                    .commit();
            if (!stored) {
                throw new IllegalStateException("Unable to persist backup key");
            }
        } catch (Exception e) {
            Log.e(TAG, "storeKey: derivation failed", e);
            throw new IllegalStateException("Unable to prepare encrypted backup restore", e);
        }
    }

    /**
     * Returns the stored 32-byte backup key, or null if not yet derived.
     * Callers must handle null (e.g. backup silently skipped).
     */
    public static byte[] getStoredKey(Context ctx) {
        if (!SecurePrefs.isAvailable()) return null;
        String b64 = SecurePrefs.get(ctx).getString(PREF_KEY, null);
        if (b64 == null) return null;
        try {
            return Base64.decode(b64, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "getStoredKey: decode failed", e);
            return null;
        }
    }

    // ── Integrity checksum ────────────────────────────────────────────────────

    /**
     * Computes a SHA-256 checksum of the plaintext for integrity verification.
     * Stored as a separate Firestore field ("checksum") alongside the encrypted blob.
     *
     * @return lowercase hex string of the SHA-256 digest
     */
    public static String computeChecksum(String plaintext) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Verifies that the plaintext matches the expected checksum.
     *
     * @param plaintext        the decrypted plaintext string
     * @param expectedChecksum hex SHA-256 digest previously stored in Firestore
     * @return true if checksums match; false on mismatch or computation failure
     */
    public static boolean verifyChecksum(String plaintext, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.isEmpty()) return true; // legacy doc
        try {
            return expectedChecksum.equalsIgnoreCase(computeChecksum(plaintext));
        } catch (Exception e) {
            Log.e(TAG, "verifyChecksum: failed", e);
            return false;
        }
    }

    // ── AES-256-GCM encrypt / decrypt (legacy — uncompressed) ─────────────────

    /**
     * Encrypts {@code plaintext} with AES-256-GCM using {@code key}.
     * Returns "ivBase64:ciphertextBase64" (ciphertext includes the GCM tag).
     *
     * Kept for backward compatibility when reading old backup docs that were
     * written without compression. For new writes use {@link #encryptCompressed}.
     */
    public static String encrypt(byte[] key, String plaintext) throws Exception {
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] enc = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        return Base64.encodeToString(iv, Base64.NO_WRAP)
                + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
    }

    /**
     * Decrypts a blob produced by {@link #encrypt}.
     */
    public static String decrypt(byte[] key, String blob) throws Exception {
        String[] parts = blob.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid backup blob format");

        byte[] iv  = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
    }

    // ── AES-256-GCM with GZIP compression (preferred for new writes) ──────────

    /**
     * GZIP-compresses {@code plaintext} then AES-256-GCM encrypts the result.
     *
     * Wire format is identical to {@link #encrypt} — "ivBase64:ciphertextBase64" —
     * so the same parsing logic applies. The caller must store {@code compressed:true}
     * in Firestore so the restore path knows to call {@link #decryptCompressed}.
     *
     * Expected size reduction: 40–60% for typical JSON message blobs.
     */
    public static String encryptCompressed(byte[] key, String plaintext) throws Exception {
        // Step 1: GZIP compress
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(plaintext.getBytes(StandardCharsets.UTF_8));
        }
        byte[] compressed = bos.toByteArray();

        // Step 2: AES-256-GCM encrypt
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] enc = cipher.doFinal(compressed);

        return Base64.encodeToString(iv, Base64.NO_WRAP)
                + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
    }

    /**
     * Decrypts a blob produced by {@link #encryptCompressed} and GZIP-decompresses it.
     */
    public static String decryptCompressed(byte[] key, String blob) throws Exception {
        String[] parts = blob.split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid backup blob format");

        byte[] iv  = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);

        // Step 1: AES-256-GCM decrypt
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] compressed = cipher.doFinal(enc);

        // Step 2: GZIP decompress
        ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream gis = new GZIPInputStream(bis)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = gis.read(buf)) != -1) out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
