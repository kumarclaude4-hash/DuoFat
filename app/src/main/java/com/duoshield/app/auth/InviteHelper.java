package com.duoshield.app.auth;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.duoshield.app.BuildConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Validates admin-issued invite codes without persisting or logging them. */
public final class InviteHelper {

    private static final String TAG = "InviteHelper";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    public interface Callback {
        void onSuccess(boolean valid);
        void onFailure(Exception e);
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    public static void validate(String inviteToken, Callback callback) {
        final String normalized = normalize(inviteToken);
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                boolean valid = doValidate(normalized);
                main.post(() -> callback.onSuccess(valid));
            } catch (Exception e) {
                Log.e(TAG, "Invite validation failed");
                main.post(() -> callback.onFailure(e));
            }
        }, "invite-validation").start();
    }

    private static String endpoint() throws Exception {
        String serverUrl = BuildConfig.PUSH_SERVER_URL;
        if (serverUrl == null || serverUrl.isEmpty()) {
            throw new Exception("PUSH_SERVER_URL is not configured.");
        }
        return (serverUrl.endsWith("/") ? serverUrl : serverUrl + "/") + "invite/validate";
    }

    private static boolean doValidate(String inviteToken) throws Exception {
        JSONObject json = new JSONObject();
        json.put("inviteToken", inviteToken);
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint()).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int code = connection.getResponseCode();
            if (code == 429) throw new Exception("Too many attempts. Please wait and try again.");
            if (code != 200) throw new Exception("Unable to validate invite right now.");
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(readFully(input)).optBoolean("valid", false);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readFully(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toString("UTF-8");
    }

    private InviteHelper() {}
}
