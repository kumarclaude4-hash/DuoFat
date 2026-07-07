package com.duoshield.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.duoshield.app.security.BiometricHelper;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.HapticHelper;
import com.duoshield.app.util.PinManager;

/**
 * Lock screen shown after the 3-minute background timeout.
 *
 * <h3>PIN-failure policy (Phase 2)</h3>
 * <ul>
 *   <li>Attempts 1–4: "Incorrect PIN" error with shake animation.</li>
 *   <li>Attempt 5 (or duress PIN): {@link DuressManager#performLogout(android.content.Context)}
 *       — silent identity wipe + navigate to SignIn. No announcement, no backoff timer,
 *       no decoy-chat redirect.</li>
 * </ul>
 *
 * <p>The fail counter lives in {@code duoshield_prefs} under {@code "pin_fail_count"}.
 * It is cleared on successful unlock. It is also cleared automatically when
 * {@code performLogout()} wipes {@code duoshield_prefs}.
 *
 * <p>{@link com.duoshield.app.util.WipeHelper#wipeAll(android.content.Context)} is NOT
 * triggered from here. Full data wipe is only reachable via the voluntary "Wipe & Exit"
 * menu item.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String PREFS_NAME     = "duoshield_prefs";
    /**
     * Fail counter lives in a SEPARATE SharedPreferences file that is NOT cleared
     * by DuressManager.performLogout() (which only clears "duoshield_prefs").
     * This prevents a rooted user from resetting the counter to get fresh attempts
     * without triggering the duress action (BUG-DR02).
     */
    private static final String PREFS_SECURITY = "duoshield_security_prefs";
    private static final String KEY_FAIL_COUNT = "pin_fail_count";
    private static final int    MAX_ATTEMPTS   = 5; // 5th wrong attempt → performLogout()

    private EditText etPin;
    private TextView tvError;
    private Button   btnUnlock, btnBiometric;
    /** Guard against multiple concurrent PBKDF2 threads on rapid button taps (BUG-U01). */
    private boolean isVerifying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Default: screenshots BLOCKED (pref absent → FLAG_SECURE on) — see F20 fix note
        // in BaseActivity.onCreate() for the full rationale.
        boolean allowScreenshots = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getBoolean("app_screenshot_enabled", false);
        if (allowScreenshots) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        setContentView(R.layout.activity_lock_screen);

        etPin        = findViewById(R.id.etPin);
        tvError      = findViewById(R.id.tvError);
        btnUnlock    = findViewById(R.id.btnUnlock);
        btnBiometric = findViewById(R.id.btnBiometric);

        // If no app PIN has been set yet, skip the lock screen entirely
        if (!PinManager.hasPinSet(this)) {
            AppLockManager.onAppForegrounded(this);
            finish();
            return;
        }

        // Only show biometric button if enabled and hardware is enrolled
        boolean bioEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("biometric_enabled", false);
        if (bioEnabled && BiometricHelper.isAvailable(this)) {
            btnBiometric.setVisibility(View.VISIBLE);
            showBiometric();
        } else {
            btnBiometric.setVisibility(View.GONE);
        }

        btnUnlock.setOnClickListener(v -> checkPin());
        btnBiometric.setOnClickListener(v -> showBiometric());

        etPin.setOnEditorActionListener((v, actionId, event) -> {
            checkPin();
            return true;
        });
    }

    private void showBiometric() {
        BiometricHelper.authenticate(this, new BiometricHelper.AuthCallback() {
            @Override public void onSuccess() {
                // F31 fix: Reset the PIN fail counter on ANY successful authentication,
                // not only a correctly typed PIN. Without this, accumulated wrong-PIN
                // entries from before a biometric success remain counted, allowing the
                // 5-strike duress trigger to fire on an unrelated future typo.
                getSharedPreferences(PREFS_SECURITY, MODE_PRIVATE)
                        .edit().putInt(KEY_FAIL_COUNT, 0).apply();
                unlock();
            }
            @Override public void onFailure() { etPin.requestFocus(); }
        });
    }

    private void checkPin() {
        // Guard against multiple concurrent PBKDF2 threads caused by rapid button taps (BUG-U01).
        if (isVerifying) return;

        String entered = etPin.getText().toString().trim();
        if (entered.isEmpty()) {
            tvError.setText("Please enter your PIN.");
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        isVerifying = true;
        // Disable UI while PBKDF2 runs on background thread (310K iterations ≈ 3–8 s)
        setInputEnabled(false);
        tvError.setText("Verifying…");
        tvError.setVisibility(View.VISIBLE);

        new Thread(() -> {
            // Both calls are PBKDF2 — must NOT run on the UI thread
            boolean duress  = DuressManager.isDuressPin(this, entered);
            boolean correct = !duress && PinManager.verifyPin(this, entered);

            runOnUiThread(() -> {
                if (duress) {
                    // Duress PIN entered — keep UI frozen on "Verifying…" for plausible
                    // deniability. To an observer, the app appears to be processing the PIN
                    // normally before transitioning to the sign-in screen.
                    // isVerifying stays true and input stays disabled intentionally.
                    DuressManager.performLogout(this);
                    return;
                }

                isVerifying = false;
                setInputEnabled(true);
                tvError.setVisibility(View.GONE);

                if (correct) {
                    // Reset fail counter on successful unlock
                    getSharedPreferences(PREFS_SECURITY, MODE_PRIVATE)
                        .edit().putInt(KEY_FAIL_COUNT, 0).apply();
                    unlock();
                } else {
                    handleWrongPin();
                }
            });
        }).start();
    }

    /**
     * Handles a wrong-PIN attempt.
     *
     * <ul>
     *   <li>Attempts 1–4: shake + "Incorrect PIN" error.</li>
     *   <li>Attempt 5+: {@link DuressManager#performLogout(android.content.Context)} silently.</li>
     * </ul>
     *
     * <p>No backoff timer. No decoy-chat redirect. No data wipe from this path.
     */
    private void handleWrongPin() {
        // Use PREFS_SECURITY so the counter survives a DuressManager.performLogout()
        // which only clears "duoshield_prefs" (BUG-DR02).
        SharedPreferences prefs = getSharedPreferences(PREFS_SECURITY, MODE_PRIVATE);
        int failCount = prefs.getInt(KEY_FAIL_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_FAIL_COUNT, failCount).apply();

        if (failCount >= MAX_ATTEMPTS) {
            // 5th wrong attempt — silent identity logout (same action as duress PIN)
            DuressManager.performLogout(this);
            return;
        }

        // Attempts 1–4: show shake and generic error only
        HapticHelper.wrongPin(this);
        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        etPin.startAnimation(shake);
        etPin.setText("");

        tvError.setText("Incorrect PIN");
        tvError.setVisibility(View.VISIBLE);
    }

    private void setInputEnabled(boolean enabled) {
        btnUnlock.setEnabled(enabled);
        btnBiometric.setEnabled(enabled);
        etPin.setEnabled(enabled);
    }

    private void unlock() {
        // Clear the lock-screen flag BEFORE foregrounding so BaseActivity.onStop()
        // does not set bgTs while we transition back to the chat activity.
        BaseActivity.lockScreenActive = false;
        AppLockManager.onAppForegrounded(this);
        finish();
    }

    @Override public void onBackPressed() {
        // Block back — user must authenticate
    }
}
