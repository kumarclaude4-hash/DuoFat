package com.duoshield.app;

import android.app.Application;
import android.util.Log;
import androidx.work.Configuration;
import com.duoshield.app.crypto.signal.SignedPreKeyScheduler;
import com.duoshield.app.notifications.NotificationStyler;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.StorageCleanupWorker;
import com.duoshield.app.util.TempFileCleaner;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.util.B2StorageHelper;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.PersistentCacheSettings;
import org.signal.libsignal.protocol.ecc.Curve;

public class DuoShieldApp extends Application implements Configuration.Provider {

    private static final String TAG = "DuoShieldApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // ── libsignal native library explicit load ────────────────────────────
        // libsignal-android 0.54.x removed the automatic System.loadLibrary()
        // call from its Java static initializers.  Apps must now load the .so
        // explicitly before any JNI method is called.  Without this, every call
        // to Curve / ECPrivateKey / SignalCipher throws UnsatisfiedLinkError,
        // which the friendlyError() handler maps to "Encryption library failed
        // to load".
        //
        // This MUST run on the main thread, synchronously, before any other
        // code (including the Firestore init below) can trigger a background
        // thread that might call libsignal earlier.
        try {
            System.loadLibrary("signal_jni");
            Log.i(TAG, "signal_jni native library loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            // The .so is missing or the device ABI is not supported.
            // This is a fatal configuration error — log the full stack so
            // adb logcat -s DuoShieldApp surfaces it immediately.
            Log.e(TAG, "FATAL: signal_jni could not be loaded. "
                    + "Verify libsignal-android AAR is in the APK and the "
                    + "device ABI is one of arm64-v8a / armeabi-v7a / x86_64 / x86.", e);
        }

        // Enable Firestore offline persistence with a 100 MB disk cache so messages
        // and user documents load instantly without a network round-trip.
        try {
            FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder()
                            .setSizeBytes(100L * 1024 * 1024) // 100 MB offline cache
                            .build())
                    .build();
            FirebaseFirestore.getInstance().setFirestoreSettings(settings);
        } catch (Exception e) {
            Log.w(TAG, "Firestore persistence setup failed (may already be configured): " + e.getMessage());
        }

        // Pre-warm the TCP + TLS connection to B2 so the first upload/download
        // reuses a pooled socket instead of paying the full ~900-1400 ms handshake.
        B2StorageHelper.warmConnection();

        // Warm up Room/SQLCipher on a background thread so the first chat open
        // does not block the main thread while the encrypted DB is opened.
        new Thread(() -> {
            try {
                AppDatabase.getInstance(getApplicationContext());
                Log.i(TAG, "Room/SQLCipher warm-up complete.");
            } catch (Exception e) {
                Log.w(TAG, "Room warm-up failed (non-fatal): " + e.getMessage());
            }
        }, "room-warmup").start();

        NotificationStyler.createChannels(this);
        AppLockManager.init(this);

        // Delete decrypted temp media files (voice_*.3gp, vid_*.mp4) older than 5 min.
        // Runs every 15 minutes in the background; KEEP policy avoids re-queuing on each launch.
        TempFileCleaner.schedule(this);

        // Trim the app cache to 50 MB once per day (Bug 16 — was never scheduled).
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "StorageCleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            new PeriodicWorkRequest.Builder(StorageCleanupWorker.class, 1, TimeUnit.DAYS).build());

        // Rotate Signal signed pre-key when it is older than 7 days (checked daily).
        SignedPreKeyScheduler.schedule(this);

        // Purge stale call docs (ringing/missed/timeout older than 24 h) every 12 h.
        com.duoshield.app.call.CallCleanupWorker.scheduleIfNeeded(this);

        // ── libsignal JNI diagnostic ──────────────────────────────────────────
        // Runs once at startup on a background thread to confirm that the JNI
        // call works end-to-end after the explicit loadLibrary above.
        new Thread(() -> {
            try {
                Curve.generateKeyPair();
                Log.i(TAG, "libsignal JNI check PASSED — Curve.generateKeyPair() succeeded.");
            } catch (Throwable e) {
                Log.e(TAG, "libsignal JNI check FAILED — identity generation will crash. "
                        + "Check that libsignal-android AAR is correctly packaged in the APK "
                        + "and that the device ABI is supported (arm64-v8a / armeabi-v7a / x86_64).", e);
            }
        }, "libsignal-diag").start();
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build();
    }
}
