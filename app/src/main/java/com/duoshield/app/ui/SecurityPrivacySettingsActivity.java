package com.duoshield.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.PinManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SecurityPrivacySettingsActivity extends BaseActivity {

    // ── Auto sign-out options ─────────────────────────────────────────────────
    private static final long[]   SIGNOUT_MS  = {0, 3_600_000L, 28_800_000L, 86_400_000L, 604_800_000L};
    private static final String[] SIGNOUT_LBL = {"Never", "1 hour", "8 hours", "24 hours", "7 days"};

    // ── Lock timeout options ──────────────────────────────────────────────────
    private static final long[]   LOCK_MS  = {0L, 30_000L, 60_000L, 3 * 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L};
    private static final String[] LOCK_LBL = {"Immediately", "30 seconds", "1 minute", "3 minutes", "5 minutes", "15 minutes", "30 minutes"};

    private SharedPreferences prefs;
    private SwitchCompat      switchAppScreenshot, switchShakeLock, switchRelayOnlyCalls;
    private LinearLayout      rowManageUnlockCodes;
    private LinearLayout      layoutPinInputs, layoutPinSet;
    private EditText          etNewPin, etConfirmPin;
    private TextView          tvPinStatus, tvAutoSignOutSub, tvLockTimeoutSub;
    private Button            btnAutoSignOut, btnLockTimeout;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_security_privacy_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        switchAppScreenshot  = findViewById(R.id.switchAppScreenshot);
        switchShakeLock      = findViewById(R.id.switchShakeLock);
        switchRelayOnlyCalls = findViewById(R.id.switchRelayOnlyCalls);
        layoutPinInputs     = findViewById(R.id.layoutPinInputs);
        layoutPinSet        = findViewById(R.id.layoutPinSet);
        rowManageUnlockCodes = findViewById(R.id.rowManageUnlockCodes);
        etNewPin            = findViewById(R.id.etNewPin);
        etConfirmPin        = findViewById(R.id.etConfirmPin);
        tvPinStatus         = findViewById(R.id.tvPinStatus);
        btnAutoSignOut      = findViewById(R.id.btnAutoSignOut);
        tvAutoSignOutSub    = findViewById(R.id.tvAutoSignOutSub);
        btnLockTimeout      = findViewById(R.id.btnLockTimeout);
        tvLockTimeoutSub    = findViewById(R.id.tvLockTimeoutSub);

        Button btnSetPin        = findViewById(R.id.btnSetPin);
        Button btnCancelPinForm = findViewById(R.id.btnCancelPinForm);
        Button btnClearPin      = findViewById(R.id.btnClearPin);
        Button btnChangePinMode = findViewById(R.id.btnChangePinMode);

        // ── Restore saved state ───────────────────────────────────────────────
        if (switchAppScreenshot != null)
            switchAppScreenshot.setChecked(prefs.getBoolean("app_screenshot_enabled", false));
        if (switchShakeLock != null)
            switchShakeLock.setChecked(prefs.getBoolean("shake_to_lock_enabled", false));
        if (switchRelayOnlyCalls != null)
            switchRelayOnlyCalls.setChecked(prefs.getBoolean("relay_only_calls_enabled", false));
        updateAutoSignOutLabel();
        updateLockTimeoutLabel();
        refreshPinStatus();

        // "Manage unlock codes" row
        if (rowManageUnlockCodes != null) {
            rowManageUnlockCodes.setOnClickListener(v ->
                startActivity(new Intent(this, ManageUnlockCodesActivity.class)));
        }

        // ── App PIN ───────────────────────────────────────────────────────────
        if (btnSetPin != null) btnSetPin.setOnClickListener(v -> saveAppPin());
        if (btnClearPin      != null) btnClearPin.setOnClickListener(v -> confirmClearPin());
        if (btnChangePinMode != null) btnChangePinMode.setOnClickListener(v -> enterChangePinMode());
        if (btnCancelPinForm != null) btnCancelPinForm.setOnClickListener(v -> refreshPinStatus());

        // ── Shake to lock ─────────────────────────────────────────────────────
        if (switchShakeLock != null) {
            switchShakeLock.setOnCheckedChangeListener((btn, checked) -> {
                if (checked && !PinManager.hasPinSet(this)) {
                    switchShakeLock.setChecked(false);
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("App PIN required")
                            .setMessage("Shake to lock only works when an app PIN is set. Set a PIN now to use this feature.")
                            .setPositiveButton("Set PIN", (d, w) -> {
                                d.dismiss();
                                if (etNewPin != null) {
                                    etNewPin.requestFocus();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return;
                }
                prefs.edit().putBoolean("shake_to_lock_enabled", checked).apply();
            });
        }

        // Screenshot toggle — requires the app PIN to change (security gate).
        if (switchAppScreenshot != null) attachScreenshotListener();

        // ── Relay-only calls (F8 fix) ────────────────────────────────────────
        // Forces WebRTC to use only TURN relay candidates so the call partner
        // never learns this device's IP address. See CallManager.createPeerConnection().
        if (switchRelayOnlyCalls != null) {
            switchRelayOnlyCalls.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean("relay_only_calls_enabled", checked).apply());
        }

        // ── Auto sign-out ─────────────────────────────────────────────────────
        if (btnAutoSignOut != null) btnAutoSignOut.setOnClickListener(v -> showAutoSignOutPicker());

        // ── Lock timeout ──────────────────────────────────────────────────────
        if (btnLockTimeout != null) btnLockTimeout.setOnClickListener(v -> showLockTimeoutPicker());
    }

    // ── App PIN logic ─────────────────────────────────────────────────────────

    private void saveAppPin() {
        String pin     = etNewPin.getText().toString().trim();
        String confirm = etConfirmPin.getText().toString().trim();

        if (pin.length() < 4 || pin.length() > 6) {
            Toast.makeText(this, "PIN must be 4–6 digits.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!pin.equals(confirm)) {
            Toast.makeText(this, "PINs don't match. Try again.", Toast.LENGTH_SHORT).show();
            etConfirmPin.setText("");
            return;
        }

        if (PinManager.hasPinSet(this)) {
            promptCurrentPin("Enter your current PIN to change it", () -> doSavePin(pin));
        } else {
            doSavePin(pin);
        }
    }

    private void doSavePin(String pin) {
        Button btnSetPin = findViewById(R.id.btnSetPin);
        if (btnSetPin != null) btnSetPin.setEnabled(false);
        tvPinStatus.setText("Saving PIN…");

        bgExecutor.execute(() -> {
            boolean clashWithDuress = DuressManager.isDuressPin(this, pin);
            if (!clashWithDuress) PinManager.setPin(this, pin);

            runOnUiThread(() -> {
                if (btnSetPin != null) btnSetPin.setEnabled(true);
                if (clashWithDuress) {
                    refreshPinStatus();
                    Toast.makeText(this,
                        "This PIN is already in use as another unlock code. Choose a different one.",
                        Toast.LENGTH_LONG).show();
                } else {
                    if (etNewPin != null) etNewPin.setText("");
                    if (etConfirmPin != null) etConfirmPin.setText("");
                    applyPinUiState(true);
                    Toast.makeText(this, R.string.settings_pin_set, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void confirmClearPin() {
        if (!PinManager.hasPinSet(this)) {
            Toast.makeText(this, R.string.settings_pin_no_pin, Toast.LENGTH_SHORT).show();
            return;
        }
        if (DuressManager.hasDuressPin(this)) {
            promptOtherUnlockCode(() ->
                promptCurrentPin("Enter your app PIN to confirm", this::doClearPin));
        } else {
            promptCurrentPin("Enter your current PIN to clear it", this::doClearPin);
        }
    }

    private void doClearPin() {
        PinManager.clearPin(this);
        prefs.edit()
             .putBoolean("shake_to_lock_enabled", false)
             .apply();
        if (switchShakeLock != null) switchShakeLock.setChecked(false);
        DuressManager.clearDuressPin(this);
        applyPinUiState(false);
        Toast.makeText(this, R.string.settings_pin_cleared, Toast.LENGTH_SHORT).show();
    }

    private void promptOtherUnlockCode(Runnable onVerified) {
        EditText etEntry = new EditText(this);
        etEntry.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etEntry.setHint("Other unlock code");
        etEntry.setMaxLines(1);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(etEntry);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Confirm your other code")
                .setMessage("You have more than one unlock code configured. Enter the other one to continue.")
                .setView(container)
                .setPositiveButton("Confirm", (d, w) -> {
                    String entered = etEntry.getText().toString().trim();
                    bgExecutor.execute(() -> {
                        boolean ok = DuressManager.isDuressPin(this, entered);
                        runOnUiThread(() -> {
                            if (ok) onVerified.run();
                            else Toast.makeText(this, "Incorrect code.", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptCurrentPin(String title, Runnable onVerified) {
        EditText etCurrent = new EditText(this);
        etCurrent.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etCurrent.setHint("Current PIN");
        etCurrent.setMaxLines(1);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(etCurrent);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(container)
                .setPositiveButton("Confirm", (d, w) -> {
                    String entered = etCurrent.getText().toString().trim();
                    bgExecutor.execute(() -> {
                        boolean ok = PinManager.verifyPin(this, entered);
                        runOnUiThread(() -> {
                            if (ok) onVerified.run();
                            else Toast.makeText(this, "Incorrect PIN.", Toast.LENGTH_SHORT).show();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshPinStatus() {
        applyPinUiState(PinManager.hasPinSet(this));
    }

    private void applyPinUiState(boolean pinSet) {
        if (tvPinStatus == null) return;
        tvPinStatus.setText(pinSet ? "✓  App PIN is set" : "No PIN set — lock screen is disabled");
        tvPinStatus.setTextColor(getResources().getColor(
            pinSet ? R.color.online_green : R.color.text_hint, null));

        if (layoutPinInputs != null) {
            layoutPinInputs.setVisibility(pinSet ? View.GONE : View.VISIBLE);
        }
        if (layoutPinSet != null) {
            layoutPinSet.setVisibility(pinSet ? View.VISIBLE : View.GONE);
        }
        if (rowManageUnlockCodes != null) {
            rowManageUnlockCodes.setVisibility(pinSet ? View.VISIBLE : View.GONE);
        }
        Button cancel = findViewById(R.id.btnCancelPinForm);
        if (cancel != null) cancel.setVisibility(View.GONE);
        if (etNewPin != null) etNewPin.setText("");
        if (etConfirmPin != null) etConfirmPin.setText("");
    }

    private void enterChangePinMode() {
        if (layoutPinInputs != null) layoutPinInputs.setVisibility(View.VISIBLE);
        if (layoutPinSet    != null) layoutPinSet.setVisibility(View.GONE);
        Button cancel = findViewById(R.id.btnCancelPinForm);
        if (cancel != null) cancel.setVisibility(View.VISIBLE);
        if (etNewPin != null) etNewPin.requestFocus();
    }

    // ── Screenshot toggle helpers ─────────────────────────────────────────────

    private boolean screenshotListenerGuard = false;

    private void attachScreenshotListener() {
        if (switchAppScreenshot == null) return;
        switchAppScreenshot.setOnCheckedChangeListener((btn, checked) -> {
            if (screenshotListenerGuard) return;

            if (!PinManager.hasPinSet(this)) {
                prefs.edit().putBoolean("app_screenshot_enabled", checked).apply();
                applyScreenshotFlag(checked);
                return;
            }

            screenshotListenerGuard = true;
            switchAppScreenshot.setChecked(!checked);
            screenshotListenerGuard = false;

            final boolean desired = checked;
            promptCurrentPin(
                checked ? "Enable screenshots" : "Disable screenshots",
                () -> {
                    screenshotListenerGuard = true;
                    switchAppScreenshot.setChecked(desired);
                    screenshotListenerGuard = false;
                    prefs.edit().putBoolean("app_screenshot_enabled", desired).apply();
                    applyScreenshotFlag(desired);
                });
        });
    }

    private void applyScreenshotFlag(boolean allow) {
        // Screenshots are always enabled app-wide; FLAG_SECURE is never applied.
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }

    // ── Lock timeout ──────────────────────────────────────────────────────────

    private void showLockTimeoutPicker() {
        long current = prefs.getLong(AppLockManager.KEY_LOCK_TIMEOUT_MS, AppLockManager.DEFAULT_LOCK_TIMEOUT);
        int checked = 3;
        for (int i = 0; i < LOCK_MS.length; i++) {
            if (LOCK_MS[i] == current) { checked = i; break; }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Lock screen after")
            .setSingleChoiceItems(LOCK_LBL, checked, (d, which) -> {
                prefs.edit().putLong(AppLockManager.KEY_LOCK_TIMEOUT_MS, LOCK_MS[which]).apply();
                updateLockTimeoutLabel();
                d.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void updateLockTimeoutLabel() {
        if (btnLockTimeout == null) return;
        long ms = prefs.getLong(AppLockManager.KEY_LOCK_TIMEOUT_MS, AppLockManager.DEFAULT_LOCK_TIMEOUT);
        String label = "3 minutes";
        for (int i = 0; i < LOCK_MS.length; i++) {
            if (LOCK_MS[i] == ms) { label = LOCK_LBL[i]; break; }
        }
        btnLockTimeout.setText(label);
        if (tvLockTimeoutSub != null) {
            if (ms == 0L) {
                tvLockTimeoutSub.setText("Lock screen will appear as soon as you leave the app.");
            } else {
                tvLockTimeoutSub.setText("Lock screen will appear after " + label + " in the background.");
            }
        }
    }

    // ── Auto sign-out ─────────────────────────────────────────────────────────

    private void showAutoSignOutPicker() {
        long current = prefs.getLong("auto_signout_ms", 0);
        int checked = 0;
        for (int i = 0; i < SIGNOUT_MS.length; i++) {
            if (SIGNOUT_MS[i] == current) { checked = i; break; }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Auto sign-out")
            .setSingleChoiceItems(SIGNOUT_LBL, checked, (d, which) -> {
                prefs.edit().putLong("auto_signout_ms", SIGNOUT_MS[which]).apply();
                updateAutoSignOutLabel();
                d.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void updateAutoSignOutLabel() {
        if (btnAutoSignOut == null) return;
        long ms = prefs.getLong("auto_signout_ms", 0);
        String label = "Never";
        for (int i = 0; i < SIGNOUT_MS.length; i++) {
            if (SIGNOUT_MS[i] == ms) { label = SIGNOUT_LBL[i]; break; }
        }
        btnAutoSignOut.setText(label);
        if (tvAutoSignOutSub != null) {
            tvAutoSignOutSub.setText(ms <= 0
                ? "You will not be signed out automatically."
                : "You will be signed out after " + label + " of inactivity.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdown();
    }
}
