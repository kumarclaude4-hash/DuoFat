package com.duoshield.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;

import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.ShakeDetector;
import com.google.firebase.auth.FirebaseAuth;

public class BaseActivity extends AppCompatActivity {

    private static final String TAG = "BaseActivity";

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

    /**
     * SharedPreferences key for the user-facing "Allow screenshots" toggle in
     * SecurityPrivacySettingsActivity. Read here (not just there) so every
     * BaseActivity-derived screen actually enforces the choice — see S08-H2.
     */
    private static final String KEY_APP_SCREENSHOT_ENABLED = "app_screenshot_enabled";

    /**
     * Applies (or lifts) {@code FLAG_SECURE} on {@code activity}'s window based on the
     * {@code app_screenshot_enabled} preference.
     *
     * <p>S08-H2 fix: every one of {@link #onCreate}, {@link MainActivity#onCreate},
     * {@link LockScreenActivity#onCreate}, and
     * {@link com.duoshield.app.ui.SecurityPrivacySettingsActivity#applyScreenshotFlag}
     * unconditionally called {@code clearFlags(FLAG_SECURE)} — screenshots, screen
     * recording, and the recents-list thumbnail were <em>always</em> allowed app-wide,
     * regardless of what the "Allow screenshots" toggle said, and regardless of its
     * secure-by-default value ({@code false} — see the {@code getBoolean} default in
     * SecurityPrivacySettingsActivity). This is the single point BaseActivity-derived
     * activities now route through, matching what GroupChatActivity's and
     * ChatMediaActivity's onResume() comments already claimed was happening globally.
     *
     * <p>Default is secure (FLAG_SECURE applied) when the preference has never been
     * set, matching the settings screen's own default.
     */
    static void applyScreenshotSecurity(android.app.Activity activity) {
        boolean screenshotsAllowed = activity
                .getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_APP_SCREENSHOT_ENABLED, false);
        if (screenshotsAllowed) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyScreenshotSecurity(this);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Increment the activity reference count (BUG-D09).
        AppLockManager.onActivityStarted();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // 0. Firebase session check — only redirect to SignInActivity when we are
        //    SURE the sign-out was intentional (explicit_signout flag is set).
        //    Transient null currentUser (SDK re-init, token refresh) is silently ignored.
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            boolean wasExplicit = prefs.getBoolean(KEY_EXPLICIT_SIGNOUT, false);
            Log.d(TAG, getClass().getSimpleName() + ".onStart: currentUser=null"
                    + "  explicit=" + wasExplicit);
            if (wasExplicit) {
                Log.i(TAG, getClass().getSimpleName()
                        + ": intentional sign-out → redirecting to SignInActivity");
                prefs.edit().remove(KEY_EXPLICIT_SIGNOUT).apply();
                Intent intent = new Intent(this, SignInActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
                return;
            }
            // Not an intentional sign-out — Firebase SDK may still be restoring
            // its session.  Do NOT redirect; preserve bgTs for lock/sign-out timing.
            return;
        }

        // Valid session — clear any stale explicit_signout flag.
        prefs.edit().remove(KEY_EXPLICIT_SIGNOUT).apply();

        // 1. Auto sign-out — kills Firebase session after prolonged inactivity.
        if (AppLockManager.shouldAutoSignOut(this)) {
            Log.i(TAG, getClass().getSimpleName() + ": auto sign-out threshold exceeded → SignIn");
            // Only schedule the de-registration if there actually was a signed-in
            // session to de-register. FcmUnregisterWorker takes no uid and no bearer
            // token (S06-H2): FirebaseMessaging.deleteToken() acts on this device's own
            // FCM registration and handles its own auth, so nothing needs to be captured
            // before signOut() below runs. See FcmUnregisterWorker's javadoc.
            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                com.duoshield.app.util.FcmUnregisterWorker
                        .enqueue(getApplicationContext());
            }
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
                Log.d(TAG, getClass().getSimpleName() + ": lock timeout exceeded → LockScreenActivity");
                lockScreenActive = true;
                Intent intent = new Intent(this, LockScreenActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        } else {
            AppLockManager.onAppForegrounded(this);

            // 3. Keep the duress lock credential warm (S06-H3).
            //
            // This is the ONLY caller of maintainLockCredential(), and without it the
            // whole offline branch of the S06-H3 fix is inert: performLogout() reads a
            // warm nonce out of PendingLockStore at trigger time, and if nothing ever
            // put one there, an offline duress trigger records an intent with a null
            // token, drainPendingLockIntent() finds nothing to send, and the account is
            // never locked — silently, which is the attacker's win condition. The nonce
            // fetch cannot happen on the duress path itself, because by then the app is
            // offline and (moments later) signed out.
            //
            // Deliberately placed in this else-branch: reaching it means there is a
            // valid session AND the app is genuinely foregrounded and unlocked, which is
            // exactly the "ordinary online foreground operation" the method's javadoc
            // requires. It self-throttles (no-ops if the warm nonce is under 12h old),
            // no-ops when signed out or offline, and does its network I/O on its own
            // background thread, so calling it from onStart() adds no main-thread work.
            try {
                com.duoshield.app.security.DuressManager
                        .maintainLockCredential(getApplicationContext());
            } catch (Exception e) {
                // Never let credential upkeep break navigation into a screen.
                Log.w(TAG, "Lock credential upkeep skipped: " + e.getMessage());
            }
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
