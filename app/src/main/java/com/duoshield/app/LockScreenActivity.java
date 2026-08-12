package com.duoshield.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
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
 *   <li>Any wrong PIN: "Incorrect PIN" error with shake animation. There is no
 *       attempt limit — an exact PIN B match is the only signal that triggers
 *       {@link DuressManager#performLogout(android.content.Context)}. A repeated-
 *       wrong-guess fallback was considered and explicitly removed: it created
 *       false-positive lockouts (typos, kids, a drunk owner) with no upside, since
 *       duress is meant to be a single deliberate, unambiguous signal.</li>
 * </ul>
 *
 * Input is now handled by an on-screen custom numpad — no system keyboard needed.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String PREFS_NAME     = "duoshield_prefs";
    private static final long   AUTO_SUBMIT_DEBOUNCE_MS = 600L;

    // UI refs
    private PinDotsView      pinDotsView;
    private TextView         tvError;
    private Button           btnUnlock;   // kept GONE; ButtonPressAnimator still attaches
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

        // S08-H2: honor the "Allow screenshots" preference (secure by default) on the
        // PIN-entry screen instead of unconditionally allowing screenshots here too.
        BaseActivity.applyScreenshotSecurity(this);

        setContentView(R.layout.activity_lock_screen);

        pinDotsView         = findViewById(R.id.pinDotsView);
        tvError             = findViewById(R.id.tvError);
        btnUnlock           = findViewById(R.id.btnUnlock);
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

        // Hidden unlock button (GONE in layout — kept so ButtonPressAnimator doesn't NPE)
        ButtonPressAnimator.attach(btnUnlock);
        btnUnlock.setOnClickListener(v -> checkPin());

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

    // ── Scan animation ──────────────────────────────────────────────────────

    private void showScanAnim() {
        ivLockShield.setVisibility(View.GONE);
        fingerprintScanView.setVisibility(View.VISIBLE);
    }

    private void hideScanAnim() {
        fingerprintScanView.setVisibility(View.GONE);
        ivLockShield.setVisibility(View.VISIBLE);
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

        // S06-L5: a persisted, exponential-backoff delay between attempts, so an
        // accidental keypad mash (child, pocket, curious colleague) cannot realistically
        // reach the secondary code's keyspace and trigger an unrecoverable duress wipe.
        // This only gates how soon the next attempt may be *submitted* — a deliberate,
        // correct entry is still accepted immediately whenever it happens.
        long remainingMs = PinManager.getLockoutRemainingMs(this);
        if (remainingMs > 0) {
            long secs = (remainingMs + 999) / 1000;
            tvError.setText("Too many attempts. Try again in " + secs + "s.");
            tvError.setVisibility(View.VISIBLE);
            pinBuffer.setLength(0);
            pinDotsView.setFilledCount(0);
            return;
        }

        isVerifying = true;
        setInputEnabled(false);
        tvError.setText("Verifying…");
        tvError.setVisibility(View.VISIBLE);
        showScanAnim();

        new Thread(() -> {
            // Both hashes are always computed, regardless of outcome. A short-circuit
            // here (skip PinManager.verifyPin() once isDuressPin() matches) would make
            // matching the duress PIN measurably faster than matching the real PIN —
            // an observable timing side-channel that could tip off an attacker watching
            // for exactly this kind of asymmetry.
            boolean duress  = DuressManager.isDuressPin(this, entered);
            boolean real    = PinManager.verifyPin(this, entered);
            boolean correct = real && !duress;

            runOnUiThread(() -> {
                if (duress) {
                    PinManager.clearFailedAttempts(this);
                    // The entered PIN is captured here — the plaintext never leaves this
                    // call stack — so DuressManager can promote it to the primary/device
                    // gate PIN before anything is wiped (S06-C1's promote-and-rotate).
                    DuressManager.performLogout(this, entered);
                    return;
                }

                isVerifying = false;
                setInputEnabled(true);
                tvError.setVisibility(View.INVISIBLE);
                hideScanAnim();

                if (correct) {
                    PinManager.clearFailedAttempts(this);
                    unlock();
                } else {
                    PinManager.recordFailedAttempt(this);
                    handleWrongPin();
                }
            });
        }).start();
    }

    private void handleWrongPin() {
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
