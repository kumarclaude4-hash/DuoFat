package com.duoshield.app.call;

import android.util.Log;

import com.duoshield.app.BuildConfig;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Fetches fresh Cloudflare TURN credentials from the push server's
 * {@code POST /turnCredentials} endpoint and stores them in
 * {@link TurnCredentialCache}.
 *
 * <p>Call {@link #prefetch} early (e.g., in {@code CallActivity.onCreate}) so
 * credentials are ready before {@link CallManager#startCall} /
 * {@link CallManager#acceptCall} fires.  Subsequent calls within the 1-hour
 * TTL are no-ops.
 */
public class TurnCredentialFetcher {

    private static final String TAG  = "TurnCredFetcher";
    private static final MediaType JSON_TYPE =
            MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public interface Callback {
        void onResult(boolean success);
    }

    /** Fire-and-forget prefetch; no callback. */
    public static void prefetch() {
        prefetch(null);
    }

    /**
     * Fetches TURN credentials if the cache is empty or expired.
     * Calls {@code callback.onResult(true/false)} on a background thread.
     */
    public static void prefetch(Callback callback) {
        if (TurnCredentialCache.get().isValid()) {
            if (callback != null) callback.onResult(true);
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "No signed-in user — skipping TURN credential fetch");
            if (callback != null) callback.onResult(false);
            return;
        }

        // getIdToken runs on the main thread; the network call moves to a bg thread.
        user.getIdToken(/* forceRefresh= */ false)
            .addOnSuccessListener(result -> {
                String idToken = result.getToken();
                if (idToken == null) {
                    Log.w(TAG, "getIdToken returned null");
                    if (callback != null) callback.onResult(false);
                    return;
                }
                new Thread(() -> doFetch(idToken, callback), "turn-cred-fetch").start();
            })
            .addOnFailureListener(e -> {
                Log.w(TAG, "getIdToken failed: " + e.getMessage());
                if (callback != null) callback.onResult(false);
            });
    }

    // ── Network call (runs on background thread) ─────────────────────────────

    private static void doFetch(String idToken, Callback callback) {
        String baseUrl = BuildConfig.PUSH_SERVER_URL;
        if (baseUrl == null || baseUrl.isEmpty()) {
            Log.w(TAG, "PUSH_SERVER_URL not configured — cannot fetch TURN credentials");
            if (callback != null) callback.onResult(false);
            return;
        }

        Request request = new Request.Builder()
                .url(baseUrl + "/turnCredentials")
                .addHeader("Authorization", "Bearer " + idToken)
                .post(RequestBody.create("{}", JSON_TYPE))
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "TURN credential fetch HTTP " + response.code());
                if (callback != null) callback.onResult(false);
                return;
            }

            String body = response.body() != null ? response.body().string() : "{}";
            JSONObject json = new JSONObject(body);

            JSONArray urlsArr = json.getJSONArray("urls");
            String[] urls = new String[urlsArr.length()];
            for (int i = 0; i < urlsArr.length(); i++) urls[i] = urlsArr.getString(i);

            String username   = json.getString("username");
            String credential = json.getString("credential");

            TurnCredentialCache.get().set(urls, username, credential);
            Log.d(TAG, "TURN credentials cached — " + urls.length + " URL(s)");
            if (callback != null) callback.onResult(true);

        } catch (Exception e) {
            Log.w(TAG, "TURN credential fetch failed: " + e.getMessage());
            if (callback != null) callback.onResult(false);
        }
    }
}
