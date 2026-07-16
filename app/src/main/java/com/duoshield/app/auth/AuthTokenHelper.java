package com.duoshield.app.auth;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.duoshield.app.BuildConfig;
import com.google.firebase.auth.FirebaseAuth;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Obtains a Firebase custom token from the DuoShield push server and signs
 * in with it.  The resulting Firebase UID equals the caller-supplied
 * {@code userId}, which is deterministically derived from the BIP39 seed
 * phrase — so the UID never changes across sign-outs.
 *
 * <p>All network I/O is performed on the calling thread.  The callback is
 * always delivered on the <strong>main thread</strong>.
 *
 * <h3>Security</h3>
 * The server verifies {@code identityPubKeyHex} against the stored
 * {@code identityPubKeyHash} before minting a token.  For brand-new accounts
 * (no Firestore identity record yet) the token is minted unconditionally
 * because the userId is derived from a 128-bit-entropy seed that only the
 * legitimate user knows.
 */
public final class AuthTokenHelper {

    private static final String TAG = "AuthTokenHelper";

    /**
     * Connect timeout (ms).  Set to 30 s to handle Render free-tier cold starts
     * (spin-up can take 20–50 s after 15 min of inactivity).
     */
    private static final int CONNECT_TIMEOUT_MS = 30_000;

    /**
     * Read timeout (ms).  Server mints the token quickly once running;
     * 30 s gives ample headroom even on slow connections.
     */
    private static final int READ_TIMEOUT_MS = 30_000;

    public interface Callback {
        void onSuccess(String firebaseUid);
        void onFailure(Exception e);
    }

    /**
     * Derives a Firebase custom token for the given userId and signs in.
     * Must NOT be called on the main thread.
     *
     * @param userId               deterministic account ID (e.g. "ABCDE-FGHIJ-KLM")
     * @param identityPubKeyBytes  raw bytes of the Signal identity public key
     * @param cb                   result callback, always invoked on the main thread
     */
    public static void signInWithSeed(String userId,
                                      byte[] identityPubKeyBytes,
                                      Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                Log.i(TAG, "signInWithSeed: fetching custom token for userId=" + userId);
                String customToken = fetchCustomToken(userId, toHex(identityPubKeyBytes));
                Log.i(TAG, "signInWithSeed: token received, signing in with Firebase…");
                String uid = doSignIn(customToken);
                Log.i(TAG, "signInWithSeed: Firebase sign-in SUCCESS  uid=" + uid);
                main.post(() -> cb.onSuccess(uid));
            } catch (Exception e) {
                Log.e(TAG, "signInWithSeed FAILED  userId=" + userId, e);
                main.post(() -> cb.onFailure(e));
            }
        }, "auth-token").start();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static String fetchCustomToken(String userId, String pubKeyHex) throws Exception {
        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) {
            throw new Exception("PUSH_SERVER_URL is not configured. "
                    + "Set push.server.url in local.properties or the PUSH_SERVER_URL env var.");
        }
        String endpoint = serverUrl.endsWith("/")
                ? serverUrl + "mintToken"
                : serverUrl + "/mintToken";

        Log.d(TAG, "fetchCustomToken: POST " + endpoint);

        JSONObject body = new JSONObject();
        body.put("userId",            userId);
        body.put("identityPubKeyHex", pubKeyHex);
        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "fetchCustomToken: HTTP " + code);

            if (code == 429) throw new Exception(
                    "Too many sign-in attempts. Please wait a moment and try again.");
            if (code == 403) throw new Exception(
                    "Recovery phrase does not match this Account ID. "
                    + "Please check both and try again.");
            if (code != 200) throw new Exception("Auth server returned HTTP " + code);

            // Use manual byte-by-byte read for full API-level compatibility.
            // InputStream.readAllBytes() is Java 11 — while desugar_jdk_libs covers
            // it on Android, an explicit loop removes any desugaring dependency.
            try (InputStream is = conn.getInputStream()) {
                String json = readFully(is);
                Log.d(TAG, "fetchCustomToken: response received (" + json.length() + " chars)");
                JSONObject resp  = new JSONObject(json);
                String    token  = resp.getString("token");
                if (token == null || token.isEmpty())
                    throw new Exception("Server returned empty token.");
                return token;
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String doSignIn(String customToken) throws Exception {
        final Object      lock      = new Object();
        final String[]    uidHolder = {null};
        final Exception[] errHolder = {null};

        FirebaseAuth.getInstance().signInWithCustomToken(customToken)
                .addOnSuccessListener(result -> {
                    synchronized (lock) {
                        uidHolder[0] = result.getUser() != null
                                ? result.getUser().getUid() : null;
                        lock.notifyAll();
                    }
                })
                .addOnFailureListener(e -> {
                    synchronized (lock) {
                        errHolder[0] = e;
                        lock.notifyAll();
                    }
                });

        synchronized (lock) {
            if (uidHolder[0] == null && errHolder[0] == null) {
                lock.wait(30_000);
            }
        }
        if (errHolder[0] != null) throw errHolder[0];
        if (uidHolder[0] == null) throw new Exception("Firebase sign-in timed out.");
        return uidHolder[0];
    }

    /**
     * Reads all bytes from {@code is} into a String.
     * Uses a manual buffer loop for full API-level compatibility (API 26+).
     */
    private static String readFully(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    private AuthTokenHelper() {}
}
