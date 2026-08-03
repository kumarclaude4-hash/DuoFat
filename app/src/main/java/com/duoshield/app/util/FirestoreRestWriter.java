package com.duoshield.app.util;

import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Minimal Firestore REST client for writes that must authenticate with a
 * <em>previously captured</em> Firebase ID token rather than the Firebase SDK's
 * ambient signed-in state.
 *
 * <h3>Why this exists</h3>
 * {@code AccountLockWorker} and {@code FcmUnregisterWorker} are deliberately
 * scheduled to run 5-40 seconds after a sign-out (see their javadocs for why —
 * timing-correlation resistance). But sign-out is synchronous and local: by the
 * time either job runs, {@code FirebaseAuth.getInstance().getCurrentUser()} is
 * already {@code null} for that account. The normal Firestore SDK write path
 * (`FirebaseFirestore.getInstance().collection(...).set(...)`) authenticates
 * using whatever is currently signed in, so it has nothing to authenticate with
 * and every write is silently rejected by the security rules
 * (`request.auth.uid == uid`) — the jittered writes never actually land.
 * <p>
 * A Firebase ID token captured from the user object <em>before</em> sign-out
 * remains valid for up to an hour (Firebase's standard ID token lifetime),
 * comfortably covering the jitter window, and authenticates a raw REST call
 * independent of the SDK's current session state.
 */
public final class FirestoreRestWriter {

    private static final String TAG = "FirestoreRestWriter";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    /**
     * Merges {@code fields} into {@code collection/documentId}, authenticating
     * with {@code idToken} instead of any currently signed-in Firebase user.
     * Throws on any network/HTTP failure or non-2xx response — callers decide
     * whether that should be a retry.
     */
    public static void mergeDocument(String idToken, String collection, String documentId,
                                      Map<String, JSONObject> fields) throws Exception {
        if (idToken == null || idToken.isEmpty()) {
            throw new Exception("No ID token captured before sign-out — cannot authenticate this write.");
        }

        String projectId = FirebaseFirestore.getInstance().getApp().getOptions().getProjectId();
        if (projectId == null || projectId.isEmpty()) {
            throw new Exception("Firebase project id unavailable.");
        }

        StringBuilder url = new StringBuilder("https://firestore.googleapis.com/v1/projects/")
                .append(projectId).append("/databases/(default)/documents/")
                .append(collection).append('/').append(documentId).append('?');
        boolean first = true;
        for (String field : fields.keySet()) {
            if (!first) url.append('&');
            url.append("updateMask.fieldPaths=").append(URLEncoder.encode(field, "UTF-8"));
            first = false;
        }

        JSONObject fieldsObj = new JSONObject();
        for (Map.Entry<String, JSONObject> e : fields.entrySet()) {
            fieldsObj.put(e.getKey(), e.getValue());
        }
        byte[] bodyBytes = new JSONObject().put("fields", fieldsObj)
                .toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
        try {
            conn.setRequestMethod("PATCH");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + idToken);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("Firestore REST write failed: HTTP " + code + " " + readStream(conn.getErrorStream()));
            }
            Log.d(TAG, collection + "/" + documentId + " merged via REST (HTTP " + code + ")");
        } finally {
            conn.disconnect();
        }
    }

    public static JSONObject boolValue(boolean v) throws Exception {
        return new JSONObject().put("booleanValue", v);
    }

    public static JSONObject stringValue(String v) throws Exception {
        return new JSONObject().put("stringValue", v);
    }

    /** Firestore REST {@code timestampValue} — RFC3339 UTC ("Zulu") format. */
    public static JSONObject timestampValueNow() throws Exception {
        return new JSONObject().put("timestampValue", Instant.now().toString());
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
    }

    private FirestoreRestWriter() {}
}
