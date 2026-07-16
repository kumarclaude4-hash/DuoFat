package com.duoshield.app.crypto;

import android.util.Base64;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption / decryption for group messages.
 *
 * <p>Each group has a single shared 32-byte AES key generated at creation time
 * by the creator and distributed to every member encrypted via their Signal
 * session. Once stored locally ({@code Group.groupKey}), this helper uses it
 * for all subsequent group message encryption.
 *
 * <p>Wire format (Base64-encoded ciphertext stored in Firestore):
 * <pre>
 *   [ 12-byte IV ][ AES-256-GCM ciphertext + 16-byte auth tag ]
 * </pre>
 */
public class GroupCipherHelper {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int    IV_LEN         = 12;
    private static final int    TAG_BITS       = 128;

    private GroupCipherHelper() {}

    /** Generates a fresh random 32-byte AES-256 group key (Base64). */
    public static String generateGroupKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.encodeToString(key, Base64.NO_WRAP);
    }

    /**
     * Encrypts {@code plaintext} with {@code groupKeyB64}.
     *
     * @return Base64-encoded [ IV || ciphertext+tag ]
     * @throws Exception on any crypto error
     */
    public static String encrypt(String plaintext, String groupKeyB64) throws Exception {
        byte[] key = Base64.decode(groupKeyB64, Base64.NO_WRAP);
        byte[] iv  = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes("UTF-8"));

        byte[] blob = new byte[IV_LEN + ct.length];
        System.arraycopy(iv, 0, blob, 0,      IV_LEN);
        System.arraycopy(ct, 0, blob, IV_LEN, ct.length);
        return Base64.encodeToString(blob, Base64.NO_WRAP);
    }

    /**
     * Decrypts a ciphertext produced by {@link #encrypt}.
     *
     * @return plaintext string
     * @throws Exception on any crypto or authentication error
     */
    public static String decrypt(String ciphertextB64, String groupKeyB64) throws Exception {
        byte[] blob = Base64.decode(ciphertextB64, Base64.NO_WRAP);
        if (blob.length <= IV_LEN) throw new IllegalArgumentException("Ciphertext too short");

        byte[] iv = new byte[IV_LEN];
        byte[] ct = new byte[blob.length - IV_LEN];
        System.arraycopy(blob, 0,      iv, 0, IV_LEN);
        System.arraycopy(blob, IV_LEN, ct, 0, ct.length);

        byte[] key = Base64.decode(groupKeyB64, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(ct), "UTF-8");
    }
}
