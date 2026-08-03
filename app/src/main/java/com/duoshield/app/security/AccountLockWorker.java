package com.duoshield.app.security;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.duoshield.app.BuildConfig;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager job that writes {@code accountLock/{uid}.locked = true} via the
 * push server's {@code /duress-lock} endpoint, as a retry fallback for when
 * {@link DuressManager}'s primary synchronous Firestore lock write failed
 * (e.g. the device was offline at trigger time).
 *
 * <h3>Auth: server-issued one-time nonce</h3>
 * This job stores a single-use nonce in its WorkManager input data — not a
 * Firebase ID token or any APK-embedded shared secret. The nonce is issued by
 * the server's {@code /requestLockNonce} endpoint <em>before</em> sign-out,
 * while the Firebase session is still live, and is bound server-side to the
 * requesting uid. Properties:
 * <ul>
 *   <li>Not a Firebase credential — cannot authenticate to Firebase Auth.</li>
 *   <li>uid-bound — cannot be used to lock any other account.</li>
 *   <li>Single-use — the server deletes it atomically with the lock write.</li>
 *   <li>24-hour expiry — generous retry window for WorkManager backoff.</li>
 * </ul>
 *
 * <h3>Clearing the flag</h3>
 * Clearing an {@code accountLock} doc is a manual, out-of-band operation
 * (Firebase console / Admin SDK only — see Firestore rules).
 */
public class AccountLockWorker extends Worker {

    private static final String TAG        = "AccountLockWorker";
    private static final String DATA_UID   = "uid";
    private static final String DATA_NONCE = "nonce";

    /** Jitter window: 5-40 seconds, matching FcmUnregisterWorker. */
    private static final long JITTER_MIN_MS   = 5_000L;
    private static final long JITTER_RANGE_MS = 35_000L;

    public AccountLockWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Schedules a jittered account-lock retry authenticated with a server-issued
     * one-time nonce. The nonce must have been obtained via {@code /requestLockNonce}
     * while the Firebase session was still live (before sign-out and wipe).
     */
    public static void enqueue(Context ctx, String uid, String nonce) {
        if (uid == null || uid.isEmpty() || nonce == null || nonce.isEmpty()) {
            Log.w(TAG, "enqueue skipped — missing uid or nonce.");
            return;
        }
        long jitterMs = JITTER_MIN_MS + (long) (new SecureRandom().nextDouble() * JITTER_RANGE_MS);
        Data input = new Data.Builder()
                .putString(DATA_UID, uid)
                .putString(DATA_NONCE, nonce)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AccountLockWorker.class)
                .setInitialDelay(jitterMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(input)
                .addTag("account_lock_" + uid)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext()).enqueue(request);
        Log.d(TAG, "AccountLockWorker enqueued (nonce-based retry).");
    }

    @NonNull
    @Override
    public Result doWork() {
        String uid   = getInputData().getString(DATA_UID);
        String nonce = getInputData().getString(DATA_NONCE);
        if (uid == null || uid.isEmpty() || nonce == null || nonce.isEmpty()) {
            Log.w(TAG, "Missing input data — dropping job.");
            return Result.success();
        }

        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) {
            Log.w(TAG, "PUSH_SERVER_URL not configured — dropping lock job.");
            return Result.success();
        }

        try {
            String endpoint = serverUrl.endsWith("/")
                    ? serverUrl + "duress-lock"
                    : serverUrl + "/duress-lock";

            byte[] bodyBytes = new JSONObject()
                    .put("nonce", nonce)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(15_000);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = conn.getOutputStream()) { os.write(bodyBytes); }

                int code = conn.getResponseCode();
                if (code == 200 || code == 204) {
                    Log.d(TAG, "Account lock confirmed by push server.");
                    return Result.success();
                }
                if (code == 400 || code == 403) {
                    // Invalid / already-consumed nonce — retrying cannot recover this.
                    Log.w(TAG, "Push server rejected nonce (HTTP " + code + ") — dropping job.");
                    return Result.success();
                }
                if (code == 401) {
                    // Nonce expired — the 24-hour window has elapsed without network.
                    // No path to obtain a fresh credential exists post-wipe; drop the job.
                    // The synchronous lock write (step 1a in DuressManager) was already
                    // attempted; if that also failed the account was offline for >24 h.
                    Log.w(TAG, "Lock nonce expired (HTTP 401) — no retry path available post-wipe; dropping.");
                    return Result.success();
                }
                Log.w(TAG, "Push server returned HTTP " + code + " — will retry.");
                return Result.retry();
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Account lock push failed — will retry: " + e.getMessage());
            return Result.retry();
        }
    }
}
