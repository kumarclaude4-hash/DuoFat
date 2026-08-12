package com.duoshield.app.crypto;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.AEADBadTagException;
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
 *
 * <p><b>Associated data (S07-H3):</b> every group shares one AES key, so GCM's
 * confidentiality/integrity guarantee on its own does not stop a ciphertext
 * written for one (group, sender, message id) from being replayed or spliced
 * into another slot the same key protects — that binding previously existed
 * only in Firestore security rules, not in the ciphertext itself. {@link
 * #buildAad} folds the groupId, sender uid, and message id into the GCM tag
 * via {@link #encrypt(String, String, byte[])}/{@link #decrypt(String, String,
 * byte[])} so a ciphertext only decrypts under the exact context it was
 * created for, at the crypto layer, independent of whatever Firestore rules
 * currently say. The legacy 2-arg overloads (no AAD) are kept so messages
 * written before this fix — which have no bound context — still decrypt.
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
     * Builds canonical AAD bytes binding a group message to the exact context
     * it was sent in — the group it belongs to, who sent it, and its own
     * message id — so a copy of the ciphertext cannot be replayed into a
     * different group, attributed to a different sender, or spliced onto a
     * different message id without failing the GCM tag check.
     */
    public static byte[] buildAad(String groupId, String senderUid, String messageId) {
        String s = (groupId == null ? "" : groupId) + '\u0000'
                + (senderUid == null ? "" : senderUid) + '\u0000'
                + (messageId == null ? "" : messageId);
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encrypts {@code plaintext} with {@code groupKeyB64}. No AAD is bound —
     * kept only for callers that have not migrated to {@link #encrypt(String,
     * String, byte[])}. New call sites should always pass AAD built from
     * {@link #buildAad}.
     *
     * @return Base64-encoded [ IV || ciphertext+tag ]
     * @throws Exception on any crypto error
     */
    public static String encrypt(String plaintext, String groupKeyB64) throws Exception {
        return encrypt(plaintext, groupKeyB64, null);
    }

    /**
     * Same as {@link #encrypt(String, String)} but also authenticates {@code
     * aad} (see {@link #buildAad}) via AES-GCM associated data (S07-H3). {@code
     * aad} is never stored in the wire format — the caller must reconstruct
     * the identical bytes at decrypt time from the message's own group/sender/id
     * fields (already present alongside the ciphertext in Firestore) and pass
     * them to {@link #decrypt(String, String, byte[])}.
     */
    public static String encrypt(String plaintext, String groupKeyB64, byte[] aad) throws Exception {
        byte[] key = Base64.decode(groupKeyB64, Base64.NO_WRAP);
        byte[] iv  = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        byte[] ct = cipher.doFinal(plaintext.getBytes("UTF-8"));

        byte[] blob = new byte[IV_LEN + ct.length];
        System.arraycopy(iv, 0, blob, 0,      IV_LEN);
        System.arraycopy(ct, 0, blob, IV_LEN, ct.length);
        return Base64.encodeToString(blob, Base64.NO_WRAP);
    }

    /**
     * Decrypts a ciphertext produced by {@link #encrypt(String, String)}
     * (no AAD checked) — kept only for messages written before S07-H3.
     *
     * @return plaintext string
     * @throws Exception on any crypto or authentication error
     */
    public static String decrypt(String ciphertextB64, String groupKeyB64) throws Exception {
        return decrypt(ciphertextB64, groupKeyB64, null);
    }

    /**
     * Same as {@link #decrypt(String, String)} but verifies {@code aad}
     * against the GCM tag (S07-H3). Must be called with the exact bytes
     * {@link #buildAad} produces from the message's own (groupId, senderUid,
     * messageId) — a mismatch, including AAD supplied for a legacy message
     * that was encrypted without one, throws {@link AEADBadTagException}
     * rather than returning tampered/misattributed plaintext.
     */
    public static String decrypt(String ciphertextB64, String groupKeyB64, byte[] aad) throws Exception {
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
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        return new String(cipher.doFinal(ct), "UTF-8");
    }
}
