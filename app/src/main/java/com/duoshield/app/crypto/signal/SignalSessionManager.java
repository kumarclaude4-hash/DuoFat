package com.duoshield.app.crypto.signal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.SessionBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Step 3 — X3DH Session Initiation.
 *
 * <h3>What happens inside {@link #establishSession}:</h3>
 * <ol>
 *   <li>Fetch the recipient's public key bundle from Firestore
 *       {@code /users/{recipientUid}/public_keys/bundle}.</li>
 *   <li>Pick the first available one-time pre-key from the bundle.</li>
 *   <li>Construct a {@link PreKeyBundle} and call
 *       {@link SessionBuilder#process(PreKeyBundle)} — this runs the full X3DH
 *       handshake and stores the resulting Double Ratchet session in Room DB via
 *       {@link DuoShieldSignalStore}.</li>
 *   <li>Atomically remove the consumed one-time pre-key from the recipient's
 *       Firestore document so it cannot be reused.</li>
 * </ol>
 *
 * After this call succeeds, {@code DuoShieldSignalStore} holds a live
 * {@code SessionRecord} for the recipient. Pass that store + the recipient's
 * {@link SignalProtocolAddress} into {@code SessionCipher} to encrypt/decrypt
 * messages (Step 4 / 5).
 *
 * <h3>Thread safety</h3>
 * Firestore callbacks come on the main thread; the X3DH computation and Room
 * write are dispatched to a dedicated single-thread executor so they never
 * block the UI.
 */
public final class SignalSessionManager {

    private static final String TAG      = "SignalSessionManager";
    /** Device ID is always 1 in DuoShield (single device per user). */
    public static final  int    DEVICE_ID = 1;

    private static final Executor executor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "signal-session"));

    private SignalSessionManager() {}

    // ── Public callback interface ─────────────────────────────────────────────

    public interface SessionCallback {
        /**
         * Called on the main thread when the session is established and ready.
         * @param address The remote address — pass this to {@code SessionCipher}.
         * @param store   The store holding the fresh session — pass this to {@code SessionCipher}.
         */
        void onEstablished(SignalProtocolAddress address, DuoShieldSignalStore store);

        /**
         * Called on the main thread if anything goes wrong.
         * @param reason Human-readable description of the failure.
         */
        void onError(String reason);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Establishes (or refreshes) a Signal Protocol session with {@code recipientUid}.
     *
     * <p>If a session already exists in Room DB it is returned immediately without
     * hitting Firestore — the Double Ratchet handles key derivation per-message from
     * that point on.
     *
     * <p>If no session exists, the full X3DH handshake is performed:
     * fetch bundle → build PreKeyBundle → SessionBuilder.process() → consume OTP key.
     *
     * @param ctx          Application or activity context.
     * @param recipientUid Firebase Auth UID of the message recipient.
     * @param callback     Delivered on the main thread.
     */
    public static void establishSession(Context ctx,
                                        String recipientUid,
                                        SessionCallback callback) {
        if (recipientUid == null || recipientUid.isEmpty()) {
            deliver(callback, null, null, "recipientUid must not be null or empty");
            return;
        }

        SignalProtocolAddress address =
                new SignalProtocolAddress(recipientUid, DEVICE_ID);
        DuoShieldSignalStore store =
                DuoShieldSignalStore.getInstance(ctx);

        // Fast path: session already established — no Firestore round-trip needed.
        executor.execute(() -> {
            if (store.containsSession(address)) {
                Log.d(TAG, "Session already exists for " + recipientUid + " — reusing.");
                deliver(callback, address, store, null);
                return;
            }
            // Slow path: fetch public key bundle and run X3DH.
            fetchBundleAndRunX3DH(ctx, recipientUid, address, store, callback);
        });
    }

    // ── Private: Firestore fetch ───────────────────────────────────────────────

    private static void fetchBundleAndRunX3DH(Context ctx,
                                               String recipientUid,
                                               SignalProtocolAddress address,
                                               DuoShieldSignalStore store,
                                               SessionCallback callback) {
        FirebaseFirestore.getInstance()
                .collection("users").document(recipientUid)
                .collection("public_keys").document("bundle")
                .get()
                .addOnSuccessListener(doc -> {
                    // Firestore callback arrives on the main thread — dispatch the
                    // CPU-bound X3DH + Room write to the background executor.
                    executor.execute(() ->
                            runX3DH(ctx, recipientUid, address, store, doc, callback));
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to fetch public key bundle for " + recipientUid, e);
                    deliver(callback, null, null,
                            "Could not fetch " + recipientUid + "'s public keys: " + e.getMessage());
                });
    }

    // ── Private: X3DH handshake ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void runX3DH(Context ctx,
                                 String recipientUid,
                                 SignalProtocolAddress address,
                                 DuoShieldSignalStore store,
                                 DocumentSnapshot doc,
                                 SessionCallback callback) {
        if (!doc.exists()) {
            deliver(callback, null, null,
                    recipientUid + " has not uploaded Signal keys yet — ask them to re-open the app.");
            return;
        }

        try {
            // ── 1. Parse identity key ─────────────────────────────────────────
            String identityKeyB64 = doc.getString("identityKey");
            if (identityKeyB64 == null)
                throw new IllegalArgumentException("identityKey missing from bundle");
            IdentityKey identityKey = new IdentityKey(
                    Base64.decode(identityKeyB64, Base64.NO_WRAP), 0);

            int recipientRegId = toInt(doc.get("registrationId"), 0);

            // ── 2. Parse signed pre-key ───────────────────────────────────────
            Map<String, Object> spkMap = (Map<String, Object>) doc.get("signedPreKey");
            if (spkMap == null)
                throw new IllegalArgumentException("signedPreKey missing from bundle");
            int         spkId  = toInt(spkMap.get("id"), 0);
            ECPublicKey spkPub = Curve.decodePoint(
                    Base64.decode((String) spkMap.get("publicKey"), Base64.NO_WRAP), 0);
            byte[] spkSig = Base64.decode((String) spkMap.get("signature"), Base64.NO_WRAP);

            // ── 3. Pick one one-time pre-key (optional but strongly recommended) ─
            List<Map<String, Object>> otpks =
                    (List<Map<String, Object>>) doc.get("oneTimePreKeys");
            int                 otpkId    = -1;
            ECPublicKey         otpkPub   = null;
            Map<String, Object> chosenEntry = null; // retained for atomic arrayRemove below

            if (otpks != null && !otpks.isEmpty()) {
                Map<String, Object> chosen = otpks.get(0); // pick the first available
                chosenEntry = chosen;
                otpkId  = toInt(chosen.get("id"), -1);
                String pubB64 = (String) chosen.get("publicKey");
                if (otpkId >= 0 && pubB64 != null) {
                    otpkPub = Curve.decodePoint(
                            Base64.decode(pubB64, Base64.NO_WRAP), 0);
                }
            }

            // ── 4. Build PreKeyBundle and run X3DH via SessionBuilder ─────────
            //
            // PreKeyBundle(registrationId, deviceId,
            //              preKeyId, preKey,           ← one-time pre-key (may be null)
            //              signedPreKeyId, signedPreKey, signedPreKeySignature,
            //              identityKey)
            //
            // Pass preKeyId=0 / preKey=null when no one-time pre-key is available
            // (X3DH still works but forward secrecy is slightly reduced for this session).
            if (otpkPub == null) {
                Log.w(TAG, "No one-time pre-keys available for " + recipientUid
                        + " — session established without OTP key. Forward secrecy reduced for this session."
                        + " Partner should re-open the app to replenish their pre-key pool.");
            }

            PreKeyBundle bundle = new PreKeyBundle(
                    recipientRegId,
                    DEVICE_ID,
                    otpkId,  otpkPub,
                    spkId,   spkPub, spkSig,
                    identityKey);

            new SessionBuilder(store, address).process(bundle);
            // SessionBuilder.process() calls store.storeSession() internally,
            // persisting the Double Ratchet state to Room DB.

            Log.d(TAG, "X3DH complete for " + recipientUid
                    + (otpkPub != null ? " (OTP key #" + otpkId + " consumed)" : " (no OTP key)"));

            // ── 5. Atomically remove the consumed one-time pre-key from Firestore ─
            if (otpkId >= 0 && otpkPub != null && chosenEntry != null) {
                consumeOtpkOnFirestore(recipientUid, otpkId, chosenEntry);
                if (otpks.size() == 1) {
                    Log.w(TAG, "OTP key pool for " + recipientUid + " is now exhausted after this session."
                            + " Pre-key refresh will be triggered on next outbound message.");
                }
            }

            deliver(callback, address, store, null);

        } catch (InvalidKeyException | UntrustedIdentityException e) {
            Log.e(TAG, "X3DH failed for " + recipientUid, e);
            deliver(callback, null, null, "Session establishment failed: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during X3DH for " + recipientUid, e);
            deliver(callback, null, null, "Unexpected error: " + e.getMessage());
        }
    }

    // ── Private: consume OTP key on Firestore ─────────────────────────────────

    /**
     * Atomically removes the consumed one-time pre-key from the recipient's Firestore bundle.
     *
     * <p>Uses {@link FieldValue#arrayRemove(Object...)} which is a Firestore atomic
     * operation — concurrent reads by two initiating devices can no longer both consume
     * the same key and then each overwrite the other's removal (BUG-CR02).
     *
     * <p>If the network fails the worst case is unchanged: the key may be reused once
     * more, which is a privacy (not security) degradation, consistent with Signal's own
     * behaviour when servers are unreachable.
     */
    private static void consumeOtpkOnFirestore(String recipientUid, int consumedId,
                                                Map<String, Object> chosenEntry) {
        // BUG-S11 fix: retry up to 3 times with linear back-off (5 s / 10 s / 15 s).
        // Failure means the key may be reused once, which is a privacy (not security)
        // degradation — consistent with Signal's own behaviour on server unreachability.
        consumeOtpkWithRetry(recipientUid, consumedId, chosenEntry, 3);
    }

    private static void consumeOtpkWithRetry(String recipientUid, int consumedId,
                                              Map<String, Object> chosenEntry, int retriesLeft) {
        FirebaseFirestore.getInstance()
                .collection("users").document(recipientUid)
                .collection("public_keys").document("bundle")
                .update("oneTimePreKeys", FieldValue.arrayRemove(chosenEntry))
                .addOnSuccessListener(v ->
                        Log.d(TAG, "OTP key #" + consumedId + " removed for " + recipientUid))
                .addOnFailureListener(e -> {
                    if (retriesLeft > 0) {
                        long delayMs = 5_000L * (4 - retriesLeft); // 5 s, 10 s, 15 s
                        Log.w(TAG, "OTP key #" + consumedId + " removal failed — retrying in "
                                + delayMs / 1000 + "s (" + retriesLeft + " left)", e);
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> consumeOtpkWithRetry(
                                        recipientUid, consumedId, chosenEntry, retriesLeft - 1),
                                delayMs);
                    } else {
                        Log.w(TAG, "OTP key #" + consumedId + " removal failed after 3 attempts"
                                + " — key may be reused once (BUG-S11)", e);
                    }
                });
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private static void deliver(SessionCallback callback,
                                 SignalProtocolAddress address,
                                 DuoShieldSignalStore store,
                                 String errorMsg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (errorMsg != null) {
                callback.onError(errorMsg);
            } else {
                callback.onEstablished(address, store);
            }
        });
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt((String) value); }
            catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
