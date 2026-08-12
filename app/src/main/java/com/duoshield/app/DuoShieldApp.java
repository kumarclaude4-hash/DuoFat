package com.duoshield.app;

import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.util.Log;
import com.bumptech.glide.Glide;
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
import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.PersistentCacheSettings;
import org.signal.libsignal.protocol.ecc.Curve;

public class DuoShieldApp extends Application implements Configuration.Provider {

    private static final String TAG = "DuoShieldApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // ── S10-N1: App Check provider registration ────────────────────────────
        // Registers the attestation provider that lets Firestore/Storage tell a
        // genuine build of this app apart from a scripted client presenting a
        // valid Firebase Auth session (audit/SESSION-10-SYNTHESIS.md §S10-N1 —
        // grepping the whole repo for "appcheck"/"App Check" previously returned
        // nothing at all). Must run before FirebaseApp.getInstance() is used by
        // any other Firebase SDK call in this method (Firestore settings, below),
        // because the attestation provider has to be installed before the first
        // App Check token is requested — installing it later can race the first
        // network call and silently ship that call with no token attached.
        //
        // Play Integrity (release/debug builds alike) is the real-device
        // attestor; BuildConfig.DEBUG additionally layers in the Debug provider,
        // which mints a per-install debug token instead of requiring a real
        // Play Integrity verdict, so debug/CI/emulator builds are never blocked
        // by an attestation check they cannot pass. Registering DebugAppCheck-
        // ProviderFactory does nothing unless the resulting token is added as a
        // "debug token" for this app in the Firebase console — until then it
        // just fails open exactly like the Play Integrity factory does below.
        //
        // NOTE — this call alone does not enforce anything. Enforcement is a
        // two-part operator step, deliberately NOT flipped on by this change:
        //   1. Firebase console → App Check → Firestore/Storage → "Enforce"
        //      (roll out in monitoring/metrics mode first, per the audit's own
        //      recommendation, before switching to Enforce — flipping this
        //      blind risks locking out real traffic).
        //   2. firestore.rules' appCheckVerified() helper (see that file) is
        //      written but deliberately not yet added to any `allow` clause,
        //      for the same monitoring-first reason.
        // Sideloaded/rooted-device installs can still pass Play Integrity
        // verdicts on some devices and are accepted as a residual gap per the
        // audit (App Check raises attacker cost, it is not a control of record
        // for a compromised client — see README.md's threat model).
        try {
            FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance(FirebaseApp.getInstance());
            appCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance());
            if (BuildConfig.DEBUG) {
                appCheck.installAppCheckProviderFactory(
                        DebugAppCheckProviderFactory.getInstance());
            }
            Log.i(TAG, "App Check provider factory installed ("
                    + (BuildConfig.DEBUG ? "Debug+PlayIntegrity" : "PlayIntegrity") + ").");
        } catch (Exception e) {
            // Fail open, deliberately: App Check is a cost-raising attestation
            // layer, not the app's only line of defense (Firestore/Storage rules
            // and server-side auth still apply). A provider-install failure must
            // not crash app startup for every user on a device/Play Services
            // combination that cannot attest.
            Log.w(TAG, "App Check provider install failed (non-fatal): " + e.getMessage());
        }

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

        // Firestore offline cache: 100 MB on normal devices, 50 MB on low-RAM devices
        // (e.g. POCO C51, 2 GB RAM — ActivityManager.getMemoryClass() ≤ 128 MB).
        // Reducing the cache on low-RAM frees ~50 MB of internal storage that is
        // more valuable for SQLCipher WAL files and B2 media cache.
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            boolean isLowRam = am != null
                    && (am.isLowRamDevice() || am.getMemoryClass() <= 128);
            long firestoreCacheBytes = isLowRam
                    ? 50L * 1024 * 1024   // 50 MB for POCO C51 / 2–3 GB class
                    : 100L * 1024 * 1024; // 100 MB for well-provisioned devices
            FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder()
                            .setSizeBytes(firestoreCacheBytes)
                            .build())
                    .build();
            FirebaseFirestore.getInstance().setFirestoreSettings(settings);
        } catch (Exception e) {
            Log.w(TAG, "Firestore persistence setup failed (may already be configured): " + e.getMessage());
        }

        // Pre-warm the TCP + TLS connection to B2 so the first upload/download
        // reuses a pooled socket instead of paying the full ~900-1400 ms handshake.
        B2StorageHelper.warmConnection();

        // Warm up Room/SQLCipher and run the libsignal JNI diagnostic on a single
        // background thread so the first chat open does not block the main thread.
        // Combining both tasks into one thread avoids the overhead of creating two
        // separate OS-level threads for sequential work that has no parallelism benefit.
        new Thread(() -> {
            // Step 1: open Room/SQLCipher so the first chat screen is instant.
            try {
                AppDatabase.getInstance(getApplicationContext());
                Log.i(TAG, "Room/SQLCipher warm-up complete.");
            } catch (Exception e) {
                Log.w(TAG, "Room warm-up failed (non-fatal): " + e.getMessage());
            }
            // Step 2: confirm libsignal JNI is wired up correctly end-to-end.
            try {
                Curve.generateKeyPair();
                Log.i(TAG, "libsignal JNI check PASSED — Curve.generateKeyPair() succeeded.");
            } catch (Throwable e) {
                Log.e(TAG, "libsignal JNI check FAILED — identity generation will crash. "
                        + "Check that libsignal-android AAR is correctly packaged in the APK "
                        + "and that the device ABI is supported (arm64-v8a / armeabi-v7a / x86_64).", e);
            }
        }, "startup-warmup").start();

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

    }

    /**
     * Release Glide's in-memory caches when the OS asks the app to yield RAM.
     * This is especially important on low-RAM devices (e.g. POCO C51, 2 GB) where
     * multiple apps compete for a tight heap budget.  We clear on MODERATE or worse;
     * on UI_HIDDEN we just trim Glide's pool without a full clear.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            Glide.get(this).clearMemory();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Glide.get(this).trimMemory(level);
        }
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build();
    }
}
