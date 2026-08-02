package com.duoshield.app.auth;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.duoshield.app.BuildConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Talks to the push server's invite-only waitlist endpoints. Account creation
 * is gated behind manual approval — a fresh install has no way to create an
 * account until an operator flips a Firestore doc to "approved" out-of-band.
 *
 * <p>Mirrors {@link AuthTokenHelper}'s plain {@link HttpURLConnection} style:
 * all I/O runs on a background thread, callbacks are always delivered on the
 * main thread.
 */
public final class WaitlistHelper {

    private static final String TAG = "WaitlistHelper";

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS    = 30_000;

    public interface RequestCallback {
        void onSuccess(String requestId);
        void onFailure(Exception e);
    }

    public interface StatusCallback {
        /** status is one of "pending", "approved", "used", "not_found". */
        void onSuccess(String status);
        void onFailure(Exception e);
    }

    /** POSTs /requestAccess and returns a fresh waitlist request id. */
    public static void requestAccess(RequestCallback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String requestId = doRequestAccess();
                main.post(() -> cb.onSuccess(requestId));
            } catch (Exception e) {
                Log.e(TAG, "requestAccess FAILED", e);
                main.post(() -> cb.onFailure(e));
            }
        }, "waitlist-request").start();
    }

    /** GETs /waitlistStatus?requestId=... for a previously issued request id. */
    public static void checkStatus(String requestId, StatusCallback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String status = doCheckStatus(requestId);
                main.post(() -> cb.onSuccess(status));
            } catch (Exception e) {
                Log.e(TAG, "checkStatus FAILED", e);
                main.post(() -> cb.onFailure(e));
            }
        }, "waitlist-status").start();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static String baseUrl() throws Exception {
        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) {
            throw new Exception("PUSH_SERVER_URL is not configured. "
                    + "Set push.server.url in local.properties or the PUSH_SERVER_URL env var.");
        }
        return serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
    }

    private static String doRequestAccess() throws Exception {
        URL url = new URL(baseUrl() + "/requestAccess");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(0);
            conn.getOutputStream().close();

            int code = conn.getResponseCode();
            if (code == 429) throw new Exception(
                    "Too many requests. Please wait a few minutes and try again.");
            if (code != 200) throw new Exception("Server returned HTTP " + code);

            try (InputStream is = conn.getInputStream()) {
                JSONObject resp = new JSONObject(readFully(is));
                String requestId = resp.getString("requestId");
                if (requestId == null || requestId.isEmpty())
                    throw new Exception("Server returned an empty request id.");
                return requestId;
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String doCheckStatus(String requestId) throws Exception {
        URL url = new URL(baseUrl() + "/waitlistStatus?requestId="
                + java.net.URLEncoder.encode(requestId, "UTF-8"));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            int code = conn.getResponseCode();
            if (code == 429) throw new Exception(
                    "Too many requests. Please wait a few minutes and try again.");
            if (code != 200) throw new Exception("Server returned HTTP " + code);

            try (InputStream is = conn.getInputStream()) {
                JSONObject resp = new JSONObject(readFully(is));
                return resp.getString("status");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String readFully(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private WaitlistHelper() {}
}
