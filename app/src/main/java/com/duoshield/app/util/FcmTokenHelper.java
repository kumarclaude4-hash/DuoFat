package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper for registering and unregistering the FCM token in Firestore.
 *
 * <h3>New-account race</h3>
 * {@link FirebaseMessaging#getToken()} is async. If the UID isn't in SharedPrefs or
 * FirebaseAuth yet when the callback fires (e.g. the user is mid-onboarding), the upload
 * is retried up to 3 times with 3 s → 6 s → 12 s exponential back-off rather than
 * silently dropped.  The push server requires {@code users/{uid}.fcmToken} to exist
 * before it can deliver notifications.
 */
public class FcmTokenHelper {

    private static final String TAG      = "FcmTokenHelper";
    private static final int    MAX_RETRY = 3;

    public static void register(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token -> {
                if (token == null || token.isEmpty()) {
                    Log.w(TAG, "register: received null/empty FCM token — skipping upload");
                    return;
                }
                prefs.edit().putString("fcm_token", token).apply();
                uploadWithRetry(ctx.getApplicationContext(), token, 0);
            })
            .addOnFailureListener(e ->
                Log.w(TAG, "register: getToken() failed: " + e.getMessage()));
    }

    /**
     * Uploads {@code token} to Firestore under {@code users/{uid}.fcmToken}.
     * Retries up to {@value #MAX_RETRY} times with exponential back-off when the UID
     * is not yet available (fresh-install race between getToken() and sign-in completion).
     */
    private static void uploadWithRetry(Context appCtx, String token, int attempt) {
        SharedPreferences prefs = appCtx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);

        String myUid = prefs.getString("my_uid", null);
        if (myUid == null || myUid.isEmpty()) {
            myUid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        }

        if (myUid != null && !myUid.isEmpty()) {
            final String uid = myUid;
            Map<String, Object> data = new HashMap<>();
            data.put("fcmToken",  token);
            data.put("platform",  "android");
            data.put("updatedAt", FieldValue.serverTimestamp());
            FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .set(data, SetOptions.merge())
                .addOnSuccessListener(v -> Log.d(TAG, "FCM token uploaded for uid=" + uid))
                .addOnFailureListener(e -> Log.w(TAG, "FCM token upload failed: " + e.getMessage()));
        } else if (attempt < MAX_RETRY) {
            long delayMs = 3000L * (1L << attempt); // 3 s, 6 s, 12 s
            Log.d(TAG, "register: uid not ready, retry " + (attempt + 1) + " in " + delayMs + "ms");
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> uploadWithRetry(appCtx, token, attempt + 1), delayMs);
        } else {
            Log.w(TAG, "register: uid still null after " + MAX_RETRY + " retries — token not uploaded");
        }
    }

    public static void unregister(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String myUid = prefs.getString("my_uid", null);
        if (myUid == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("fcmToken", "");
        FirebaseFirestore.getInstance()
            .collection("users").document(myUid)
            .set(data, SetOptions.merge());
        prefs.edit().remove("fcm_token").apply();
    }
}
