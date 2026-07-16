package com.duoshield.app.crypto.signal;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin wrapper around libsignal-android's {@link SessionCipher} for encrypting and
 * decrypting individual messages over an already-established Signal Protocol session.
 *
 * <h3>Threading contract</h3>
 * Both {@link #encrypt} and {@link #decrypt} perform Room I/O (session state reads/writes)
 * and <strong>must be called from a background thread</strong>.  Never call them on the
 * main/UI thread.
 *
 * <p><strong>Per-address serialization:</strong> {@link SessionCipher#encrypt}/{@code decrypt}
 * mutate the Double Ratchet session state with a load-mutate-store cycle that is NOT
 * internally synchronized by libsignal, and {@link DuoShieldSignalStore}'s Room-backed
 * {@code loadSession()}/{@code storeSession()} do no locking of their own either. Two
 * concurrent callers for the *same* {@link SignalProtocolAddress} (e.g. a notification
 * quick-reply firing while the chat screen is decrypting an incoming message from the same
 * contact) can silently clobber each other's session update, reusing a message key or
 * discarding a ratchet advance with no visible error. To prevent this, every call into this
 * class acquires a static per-address lock for the full span of the libsignal call, mirroring
 * upstream {@code libsignal-protocol-java}'s own static {@code SessionCipher.SESSION_LOCK}
 * pattern. This makes it safe to call {@link #encrypt}/{@link #decrypt} for the same address
 * from independent executors (e.g. {@code MessageBuilder}'s and {@code EditMessageHelper}'s
 * own throwaway single-thread executors) without funneling every caller through one shared
 * executor — though doing so remains a reasonable belt-and-suspenders companion practice.
 *
 * <h3>Session requirement</h3>
 * A session must have been established with the remote party via
 * {@link SignalSessionManager#establishSession} before calling {@link #encrypt}.
 * {@link #decrypt} auto-establishes the <em>inbound</em> session for
 * {@link CiphertextMessage#PREKEY_TYPE} messages (X3DH reply step).
 *
 * <h3>sigType values</h3>
 * <ul>
 *   <li>{@link CiphertextMessage#WHISPER_TYPE} (2) — regular Double-Ratchet
 *       {@link SignalMessage}; sent once an outbound session exists.</li>
 *   <li>{@link CiphertextMessage#PREKEY_TYPE} (3) — X3DH initial message
 *       ({@link PreKeySignalMessage}); establishes the <em>inbound</em> session on decrypt.</li>
 * </ul>
 */
public final class SignalCipherHelper {

    private static final String TAG = "SignalCipherHelper";

    /**
     * One lock object per {@link SignalProtocolAddress} (keyed by its string form), shared
     * across every caller in the process. Guards the full encrypt/decrypt span so a session's
     * load-mutate-store cycle can never interleave with another thread's cycle for the same
     * address. Entries are intentionally never evicted — the key space is bounded by the
     * number of distinct contacts/devices this installation ever talks to, which is small.
     */
    private static final ConcurrentHashMap<String, Object> SESSION_LOCKS = new ConcurrentHashMap<>();

    private static Object lockFor(SignalProtocolAddress address) {
        String key = address.toString();
        Object lock = SESSION_LOCKS.get(key);
        if (lock == null) {
            Object newLock = new Object();
            Object existing = SESSION_LOCKS.putIfAbsent(key, newLock);
            lock = existing != null ? existing : newLock;
        }
        return lock;
    }

    private SignalCipherHelper() {}

    // ── Inner result class ────────────────────────────────────────────────────

    /**
     * Holds the output of {@link #encrypt}.
     */
    public static final class EncryptResult {
        /**
         * Base64 (NO_WRAP) encoding of the serialised {@link CiphertextMessage} bytes.
         * Store this string verbatim in Firestore {@code "text"} field.
         */
        public final String ciphertextB64;
        /**
         * {@link CiphertextMessage#WHISPER_TYPE} or {@link CiphertextMessage#PREKEY_TYPE}.
         * Store as Firestore {@code "sigType"} field so the receiver knows which wrapper to
         * use during decryption.
         */
        public final int sigType;

        EncryptResult(String ciphertextB64, int sigType) {
            this.ciphertextB64 = ciphertextB64;
            this.sigType       = sigType;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Encrypts {@code plaintext} for {@code recipientUid} using the established Signal session.
     *
     * <p><strong>Background thread only.</strong>
     *
     * @param ctx          Application or Activity context (used for Room and SecurePrefs).
     * @param recipientUid Firebase Auth UID of the message recipient.
     * @param plaintext    UTF-8 plaintext to encrypt.
     * @return {@link EncryptResult} containing the Base64 ciphertext and the Signal message type.
     * @throws Exception If no session has been established ({@code NoSessionException}),
     *                   or any other Signal / I/O error.
     */
    public static EncryptResult encrypt(Context ctx, String recipientUid, String plaintext)
            throws Exception {
        DuoShieldSignalStore  store   = DuoShieldSignalStore.getInstance(ctx);
        SignalProtocolAddress address =
                new SignalProtocolAddress(recipientUid, SignalSessionManager.DEVICE_ID);
        synchronized (lockFor(address)) {
            SessionCipher     cipher = new SessionCipher(store, address);
            CiphertextMessage msg    = cipher.encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
            String            b64   = Base64.encodeToString(msg.serialize(), Base64.NO_WRAP);
            Log.d(TAG, "encrypt → sigType=" + msg.getType()
                    + " len=" + msg.serialize().length + " recipient=" + recipientUid);
            return new EncryptResult(b64, msg.getType());
        }
    }

    /**
     * Decrypts a Signal Protocol message from {@code senderUid}.
     *
     * <p><strong>Background thread only.</strong>
     *
     * <p>For {@link CiphertextMessage#PREKEY_TYPE} messages, decryption automatically
     * establishes the inbound Double-Ratchet session via X3DH.
     *
     * @param ctx           Application or Activity context.
     * @param senderUid     Firebase Auth UID of the message sender.
     * @param ciphertextB64 Base64-encoded serialised message bytes (from Firestore {@code "text"}).
     * @param sigType       {@link CiphertextMessage#PREKEY_TYPE} (3) or
     *                      {@link CiphertextMessage#WHISPER_TYPE} (2).
     * @return Decrypted UTF-8 plaintext.
     * @throws Exception If decryption fails (duplicate, bad MAC, no session, etc.).
     */
    public static String decrypt(Context ctx, String senderUid, String ciphertextB64, int sigType)
            throws Exception {
        byte[]                bytes   = Base64.decode(ciphertextB64, Base64.NO_WRAP);
        DuoShieldSignalStore  store   = DuoShieldSignalStore.getInstance(ctx);
        SignalProtocolAddress address =
                new SignalProtocolAddress(senderUid, SignalSessionManager.DEVICE_ID);
        synchronized (lockFor(address)) {
            SessionCipher cipher = new SessionCipher(store, address);
            byte[] plaintext;
            if (sigType == CiphertextMessage.PREKEY_TYPE) {
                PreKeySignalMessage msg = new PreKeySignalMessage(bytes);
                plaintext = cipher.decrypt(msg);
                Log.d(TAG, "decrypt PreKeySignalMessage from " + senderUid + " OK");
            } else if (sigType == CiphertextMessage.WHISPER_TYPE) {
                SignalMessage msg = new SignalMessage(bytes);
                plaintext = cipher.decrypt(msg);
                Log.d(TAG, "decrypt SignalMessage from " + senderUid + " OK");
            } else {
                throw new IllegalArgumentException(
                        "Unsupported sigType " + sigType + " from " + senderUid);
            }
            return new String(plaintext, StandardCharsets.UTF_8);
        }
    }
}
