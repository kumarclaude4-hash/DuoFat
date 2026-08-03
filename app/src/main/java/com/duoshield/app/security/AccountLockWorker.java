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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * WorkManager job that writes {@code accountLock/{uid}.locked = true} to Firestore
 * some time after {@link DuressManager#performLogout}.
 *
 * <h3>Why this exists</h3>
 * {@link DuressManager}'s local wipe (SecurePrefs, Room DB, all SharedPreferences)
 * cannot survive an uninstall. Without a server-side record, a reinstall would let
 * an attacker restore with nothing but a coerced seed phrase and Account ID.
 * This job is a retry fallback: the primary synchronous lock write happens inside
 * {@code performLogout()} itself (while the Firebase session is still live). This
 * worker retries via the push server if that write failed (offline, etc.).
 *
 * <h3>Auth: HMAC instead of a stored bearer token</h3>
 * Previous versions stored the user's Firebase ID token in WorkManager's persistent
 * input data, leaving a durable, reusable owner credential on disk after the wipe.
 * This version instead generates a short-lived HMAC signature over
 * {@code "duress-lock:<uid>:<ts>"} using {@code WORKER_SECRET} — a secret already
 * shared between the app and the push server for Cloudflare Worker calls. The push
 * server verifies the signature and writes the lock via the Admin SDK, so no Firebase
 * credential ever needs to be stored post-wipe.
 *
 * <h3>Clearing the flag</h3>
 * Clearing an {@code accountLock} doc is a manual, out-of-band operation
 * (Firebase console / Admin SDK only — see Firestore rules).
 */
public class AccountLockWorker extends Worker {

    private static final String TAG      = "AccountLockWorker";
    private static final String DATA_UID = "uid";
    private static final String DATA_TS  = "ts";
    private static final String DATA_SIG = "sig";

    /** Jitter window: 5-40 seconds, matching FcmUnregisterWorker. */
    private static final long JITTER_MIN_MS   = 5_000L;
    private static final long JITTER_RANGE_MS = 35_000L;

    public AccountLockWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Schedules a jittered account-lock retry for {@code uid}, authenticated
     * with an HMAC signature (no bearer token stored). Safe to call any time
     * after the UID is known — does not require a live Firebase session.
     */
    public static void enqueue(Context ctx, String uid) {
        if (uid == null || uid.isEmpty()) {
            Log.w(TAG, "enqueue skipped — missing uid.");
            return;
        }
        String workerSecret = BuildConfig.WORKER_SECRET;
        if (workerSecret == null || workerSecret.isEmpty()) {
            Log.w(TAG, "enqueue skipped — WORKER_SECRET not configured.");
            return;
        }
        long ts = System.currentTimeMillis();
        String sig;
        try {
            sig = computeHmac(workerSecret, "duress-lock:" + uid + ":" + ts);
        } catch (Exception e) {
            Log.w(TAG, "HMAC computation failed: " + e.getMessage());
            return;
        }

        long jitterMs = JITTER_MIN_MS + (long) (new SecureRandom().nextDouble() * JITTER_RANGE_MS);
        Data input = new Data.Builder()
                .putString(DATA_UID, uid)
                .putLong(DATA_TS, ts)
                .putString(DATA_SIG, sig)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AccountLockWorker.class)
                .setInitialDelay(jitterMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(input)
                .addTag("account_lock_" + uid)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext()).enqueue(request);
        Log.d(TAG, "AccountLockWorker enqueued (retry via push server).");
    }

    @NonNull
    @Override
    public Result doWork() {
        String uid = getInputData().getString(DATA_UID);
        long   ts  = getInputData().getLong(DATA_TS, 0L);
        String sig = getInputData().getString(DATA_SIG);
        if (uid == null || uid.isEmpty() || sig == null || ts == 0L) {
            Log.w(TAG, "Missing input data — dropping job.");
            return Result.success(); // don't retry a misconfigured job
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

            JSONObject body = new JSONObject();
            body.put("uid", uid);
            body.put("ts",  ts);
            body.put("sig", sig);
            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

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
                if (code == 400 || code == 401 || code == 403) {
                    // Bad request / expired signature — retrying will not help.
                    Log.w(TAG, "Push server rejected duress-lock (HTTP " + code + ") — dropping.");
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

    private static String computeHmac(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
