package com.duoshield.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.ShakeDetector;
import com.google.firebase.auth.FirebaseAuth;

public class BaseActivity extends AppCompatActivity {

    /**
     * SharedPreferences key set to {@code true} before any intentional Firebase sign-out
     * (duress logout, auto sign-out timer, explicit wipe).  BaseActivity reads this flag
     * in onStart() to distinguish an intentional sign-out from a transient Firebase
     * token state (e.g. app process restart while the SDK re-initialises).
     *
     * <p>Cleared by BaseActivity when currentUser is non-null (valid session) and by
     * RestoreFromSeedActivity after a successful restore.</p>
     */
    public static final String KEY_EXPLICIT_SIGNOUT = "explicit_signout";
    private static final String PREFS_NAME          = "duoshield_prefs";

    /**
     * Prevents stacking multiple LockScreenActivity instances.
     * Set to {@code true} just before starting LockScreenActivity; cleared back
     * to {@code false} in {@link LockScreenActivity#unlock()} before finish().
     */
    public static volatile boolean lockScreenActive = false;

    private ShakeDetector shakeDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Apply or clear FLAG_SECURE based on the global screenshot preference.
        // Default: screenshots BLOCKED (pref absent → FLAG_SECURE on), matching the
        // "off" default already shown in SettingsActivity's switch (F20 fix — the two
        // were previously inverted, so a fresh install silently allowed screenshots
        // despite Settings showing the toggle as off).
        boolean allowScreenshots = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getBoolean("app_screenshot_enabled", false);
        if (allowScreenshots) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Increment the activity reference count (BUG-D09).
        AppLockManager.onActivityStarted();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 0. Firebase session check — only redirect to SignInActivity when we are
        //    SURE the sign-out was intentional (explicit_signout flag is set).
        //    Transient null currentUser (SDK re-init, token refresh) is silently ignored
        //    so the user is never unexpectedly kicked out after backgrounding the app.
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            boolean wasExplicit = prefs.getBoolean(KEY_EXPLICIT_SIGNOUT, false);
            if (wasExplicit) {
                prefs.edit().remove(KEY_EXPLICIT_SIGNOUT).apply();
                Intent intent = new Intent(this, SignInActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            }
            // Not an intentional sign-out — Firebase SDK may still be restoring its
            // session. Do NOT clear bgTs here; preserve it so that the lock/auto-
            // sign-out check fires correctly on the next onStart() once Firebase
            // has fully restored the auth state.
            return;
        }

        // Valid session — clear any stale explicit_signout flag.
        prefs.edit().remove(KEY_EXPLICIT_SIGNOUT).apply();

        // 1. Auto sign-out — kills Firebase session after prolonged inactivity.
        if (AppLockManager.shouldAutoSignOut(this)) {
            // Clear conversation-linked prefs so a subsequent "Create Account" flow
            // doesn't route straight back to the old conversation (BUG-D-AS01).
            prefs.edit()
                 .putBoolean(KEY_EXPLICIT_SIGNOUT, true)
                 .putBoolean("signed_out_reason_inactivity", true)
                 .remove("is_paired")
                 .remove("conversation_id")
                 .remove("partner_uid")
                 .remove("ecdh_shared_key")
                 .remove("disappear_ms")
                 .apply();
            try { FirebaseAuth.getInstance().signOut(); } catch (Exception ignored) {}
            Intent intent = new Intent(this, SignInActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }

        // 2. PIN lock — shows lock screen after background timeout.
        if (AppLockManager.shouldLock(this)) {
            if (!lockScreenActive) {
                lockScreenActive = true;
                Intent intent = new Intent(this, LockScreenActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        } else {
            AppLockManager.onAppForegrounded(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean shakeEnabled = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getBoolean("shake_to_lock_enabled", false);
        if (shakeEnabled && PinManager.hasPinSet(this)) {
            shakeDetector = new ShakeDetector(this, this::onShakeToLock);
            shakeDetector.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shakeDetector != null) {
            shakeDetector.stop();
            shakeDetector = null;
        }
    }

    private void onShakeToLock() {
        if (!PinManager.hasPinSet(this)) return;
        if (lockScreenActive) return;
        lockScreenActive = true;
        Intent intent = new Intent(this, LockScreenActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLockManager.onActivityStopped(this);
    }

    protected void navigateTo(Class<?> dest) {
        startActivity(new Intent(this, dest));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    protected void navigateBack() {
        finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
