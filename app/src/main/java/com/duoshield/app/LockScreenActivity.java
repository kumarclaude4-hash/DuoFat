package com.duoshield.app;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.duoshield.app.security.BiometricHelper;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.ui.FingerprintScanView;
import com.duoshield.app.ui.PinDotsView;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.ButtonPressAnimator;
import com.duoshield.app.util.HapticHelper;
import com.duoshield.app.util.PinManager;

/**
 * Lock screen shown after the background timeout.
 *
 * <h3>PIN-failure policy</h3>
 * <ul>
 *   <li>Attempts 1–4: "Incorrect PIN" error with shake animation.</li>
 *   <li>Attempt 5 (or duress PIN): {@link DuressManager#performLogout(android.content.Context)}.</li>
 * </ul>
 *
 * Input is now handled by an on-screen custom numpad — no system keyboard needed.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String PREFS_NAME     = "duoshield_prefs";
    private static final String PREFS_SECURITY = "duoshield_security_prefs";
    private static final String KEY_FAIL_COUNT = "pin_fail_count";
    private static final int    MAX_ATTEMPTS   = 5;
    private static final long   AUTO_SUBMIT_DEBOUNCE_MS = 600L;

    // UI refs
    private PinDotsView      pinDotsView;
    private TextView         tvError;
    private Button           btnUnlock;   // kept GONE; ButtonPressAnimator still attaches
    private View             btnBiometric;
    private FingerprintScanView fingerprintScanView;
    private ImageView        ivLockShield;

    // Custom numpad keys (0–9) + backspace
    private final View[] digitKeys = new View[10];
    private View keyBackspace;

    /** Actual length of the user's PIN — read from PinManager on create. */
    private int pinLength = 6;

    /** Accumulated PIN digits — never leaves this Activity. */
    private StringBuilder pinBuffer = new StringBuilder(6);

    private boolean isVerifying = false;
    private final Handler autoSubmitHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingAutoSubmit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean allowScreenshots = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("app_screenshot_enabled", false);
        if (allowScreenshots) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        setContentView(R.layout.activity_lock_screen);

        pinDotsView         = findViewById(R.id.pinDotsView);
        tvError             = findViewById(R.id.tvError);
        btnUnlock           = findViewById(R.id.btnUnlock);
        btnBiometric        = findViewById(R.id.btnBiometric);
        fingerprintScanView = findViewById(R.id.fingerprintScanView);
        ivLockShield        = findViewById(R.id.ivLockShield);

        // If no PIN has been set yet, skip lock entirely
        if (!PinManager.hasPinSet(this)) {
            AppLockManager.onAppForegrounded(this);
            finish();
            return;
        }

        // Read the actual PIN length so dots and buffer match what the user set
        pinLength = PinManager.getPinLength(this);
        pinBuffer = new StringBuilder(pinLength);
        pinDotsView.setMaxCount(pinLength);

        // Light-mode dot colours for the lavender lock screen
        pinDotsView.setColors(
                getResources().getColor(R.color.ds_accent_deep, null),
                getResources().getColor(R.color.ls_dot_empty, null));

        // Biometric
        boolean bioEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("biometric_enabled", false);
        if (bioEnabled && BiometricHelper.isAvailable(this)) {
            btnBiometric.setVisibility(View.VISIBLE);
            showBiometric();
        }

        // Hidden unlock button (GONE in layout — kept so ButtonPressAnimator doesn't NPE)
        ButtonPressAnimator.attach(btnUnlock);
        btnUnlock.setOnClickListener(v -> checkPin());

        btnBiometric.setOnClickListener(v -> showBiometric());

        // ── Wire numpad keys ──────────────────────────────────────────────
        digitKeys[1] = findViewById(R.id.key1);
        digitKeys[2] = findViewById(R.id.key2);
        digitKeys[3] = findViewById(R.id.key3);
        digitKeys[4] = findViewById(R.id.key4);
        digitKeys[5] = findViewById(R.id.key5);
        digitKeys[6] = findViewById(R.id.key6);
        digitKeys[7] = findViewById(R.id.key7);
        digitKeys[8] = findViewById(R.id.key8);
        digitKeys[9] = findViewById(R.id.key9);
        digitKeys[0] = findViewById(R.id.key0);
        keyBackspace  = findViewById(R.id.keyBackspace);

        for (int digit = 0; digit <= 9; digit++) {
            final int d = digit;
            if (digitKeys[d] != null) {
                digitKeys[d].setOnClickListener(v -> onDigitPressed(d));
            }
        }
        keyBackspace.setOnClickListener(v -> onBackspacePressed());
    }

    // ── Numpad input ──────────────────────────────────────────────────────

    private void onDigitPressed(int digit) {
        if (isVerifying || pinBuffer.length() >= pinLength) return;
        HapticHelper.lightPress(this);
        pinBuffer.append(digit);
        int len = pinBuffer.length();
        pinDotsView.setFilledCount(len);
        cancelPendingAutoSubmit();
        if (len >= pinLength) {
            // Exact PIN length reached — submit immediately
            checkPin();
        }
    }

    private void onBackspacePressed() {
        if (isVerifying || pinBuffer.length() == 0) return;
        cancelPendingAutoSubmit();
        pinBuffer.deleteCharAt(pinBuffer.length() - 1);
        pinDotsView.setFilledCount(pinBuffer.length());
        tvError.setVisibility(View.INVISIBLE);
    }

    private void cancelPendingAutoSubmit() {
        if (pendingAutoSubmit != null) {
            autoSubmitHandler.removeCallbacks(pendingAutoSubmit);
            pendingAutoSubmit = null;
        }
    }

    // ── Biometric / scan animation ────────────────────────────────────────

    private void showScanAnim() {
        ivLockShield.setVisibility(View.GONE);
        fingerprintScanView.setVisibility(View.VISIBLE);
    }

    private void hideScanAnim() {
        fingerprintScanView.setVisibility(View.GONE);
        ivLockShield.setVisibility(View.VISIBLE);
    }

    private void showBiometric() {
        showScanAnim();
        BiometricHelper.authenticate(this, new BiometricHelper.AuthCallback() {
            @Override public void onSuccess() {
                getSharedPreferences(PREFS_SECURITY, MODE_PRIVATE)
                        .edit().putInt(KEY_FAIL_COUNT, 0).apply();
                hideScanAnim();
                unlock();
            }
            @Override public void onFailure() {
                hideScanAnim();
            }
        });
    }

    // ── PIN verification ──────────────────────────────────────────────────

    private void checkPin() {
        cancelPendingAutoSubmit();
        if (isVerifying) return;

        String entered = pinBuffer.toString();
        if (entered.isEmpty()) {
            tvError.setText("Please enter your PIN.");
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        isVerifying = true;
        setInputEnabled(false);
        tvError.setText("Verifying…");
        tvError.setVisibility(View.VISIBLE);
        showScanAnim();

        new Thread(() -> {
            boolean duress  = DuressManager.isDuressPin(this, entered);
            boolean correct = !duress && PinManager.verifyPin(this, entered);

            runOnUiThread(() -> {
                if (duress) {
                    DuressManager.performLogout(this);
                    return;
                }

                isVerifying = false;
                setInputEnabled(true);
                tvError.setVisibility(View.INVISIBLE);
                hideScanAnim();

                if (correct) {
                    getSharedPreferences(PREFS_SECURITY, MODE_PRIVATE)
                            .edit().putInt(KEY_FAIL_COUNT, 0).apply();
                    unlock();
                } else {
                    handleWrongPin();
                }
            });
        }).start();
    }

    private void handleWrongPin() {
        SharedPreferences prefs = getSharedPreferences(PREFS_SECURITY, MODE_PRIVATE);
        int failCount = prefs.getInt(KEY_FAIL_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_FAIL_COUNT, failCount).apply();

        if (failCount >= MAX_ATTEMPTS) {
            DuressManager.performLogout(this);
            return;
        }

        HapticHelper.wrongPin(this);
        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        pinDotsView.startAnimation(shake);

        // Clear the buffer
        pinBuffer.setLength(0);
        pinDotsView.setFilledCount(0);

        tvError.setText(getString(R.string.wrong_pin));
        tvError.setVisibility(View.VISIBLE);
    }

    private void setInputEnabled(boolean enabled) {
        btnUnlock.setEnabled(enabled);
        btnBiometric.setEnabled(enabled);
        keyBackspace.setEnabled(enabled);
        for (int i = 0; i <= 9; i++) {
            if (digitKeys[i] != null) digitKeys[i].setEnabled(enabled);
        }
    }

    private void unlock() {
        BaseActivity.lockScreenActive = false;
        AppLockManager.onAppForegrounded(this);
        finish();
    }

    // Intentionally does NOT call super.onBackPressed() — the lock screen must block
    // back navigation until the user authenticates, so the default finish() behavior
    // is deliberately suppressed here.
    @SuppressLint("MissingSuperCall")
    @Override public void onBackPressed() {
        // Block back — user must authenticate
    }
}
