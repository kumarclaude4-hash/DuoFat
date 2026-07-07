package com.duoshield.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.duoshield.app.BaseActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.duoshield.app.crypto.signal.SignalKeyManager;
import com.google.firebase.firestore.FirebaseFirestore;
import com.duoshield.app.util.B2StorageHelper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import com.duoshield.app.R;
import com.duoshield.app.backup.BackupManager;
import com.duoshield.app.db.AppDatabase;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.duoshield.app.security.BiometricHelper;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.FirebaseCostGuard;
import com.duoshield.app.util.SecurePrefs;
import com.duoshield.app.util.SelfDestructScheduler;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends BaseActivity {

    // ── Auto sign-out options ─────────────────────────────────────────────────
    private static final long[]   SIGNOUT_MS  = {0, 3_600_000L, 28_800_000L, 86_400_000L, 604_800_000L};
    private static final String[] SIGNOUT_LBL = {"Never", "1 hour", "8 hours", "24 hours", "7 days"};

    // ── Lock timeout options ──────────────────────────────────────────────────
    private static final long[]   LOCK_MS  = {0L, 30_000L, 60_000L, 3 * 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L};
    private static final String[] LOCK_LBL = {"Immediately", "30 seconds", "1 minute", "3 minutes", "5 minutes", "15 minutes", "30 minutes"};

    private SharedPreferences prefs;
    private SwitchCompat      switchNotifications, switchBiometric, switchDarkMode, switchDuress,
                              switchAppScreenshot, switchSanctuaryMode, switchShakeLock;
    private LinearLayout      layoutDuressSection, layoutDuressContent, layoutDuressForm, layoutDuressActive;
    private LinearLayout      layoutPinInputs, layoutPinSet;
    private EditText          etDuressPin, etNewPin, etConfirmPin;
    private TextView          tvPinStatus, tvAutoSignOutSub, tvLockTimeoutSub;
    private TextView          tvLastBackup, tvBackupCount, tvBackupHealth;
    private Button            btnAutoSignOut, btnLockTimeout;
    private ImageView         ivProfilePhoto;
    private TextView          tvProfileDisplayName;

    private ActivityResultLauncher<String> pickPhotoLauncher;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);

        // Register the profile photo picker before setContentView / super lifecycle
        pickPhotoLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadProfilePhoto(uri); });

        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        // ── Find views ────────────────────────────────────────────────────────

        // Account ID section
        TextView tvAccountId        = findViewById(R.id.tvAccountId);
        Button   btnCopyAccountId   = findViewById(R.id.btnCopyAccountId);
        Button   btnShareAccountId  = findViewById(R.id.btnShareAccountId);
        TextView tvDisplayName      = findViewById(R.id.tvDisplayName);

        if (tvAccountId != null) {
            String accountId = SignalKeyManager.getAccountId(this);
            tvAccountId.setText(accountId != null ? accountId : "Keys not yet generated");
        }

        if (tvDisplayName != null) {
            String name = prefs.getString("my_display_name", null);
            tvDisplayName.setText(name != null && !name.isEmpty() ? name : "—");
        }

        // ── Profile photo + name card ─────────────────────────────────────────
        ivProfilePhoto       = findViewById(R.id.ivProfilePhoto);
        tvProfileDisplayName = findViewById(R.id.tvProfileDisplayName);
        View btnChangePhoto  = findViewById(R.id.btnChangePhoto);
        View layoutProfileNameRow = findViewById(R.id.layoutProfileNameRow);

        // Populate name
        String myName = prefs.getString("my_display_name", null);
        if (tvProfileDisplayName != null)
            tvProfileDisplayName.setText(myName != null && !myName.isEmpty() ? myName : "—");

        // Load profile photo if stored
        String photoUrl = prefs.getString("my_photo_url", null);
        if (ivProfilePhoto != null && photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(this)
                    .load(photoUrl)
                    .transform(new CircleCrop())
                    .placeholder(R.drawable.ic_person)
                    .into(ivProfilePhoto);
        }

        if (layoutProfileNameRow != null)
            layoutProfileNameRow.setOnClickListener(v -> showNameEditDialog());
        if (btnChangePhoto != null)
            btnChangePhoto.setOnClickListener(v -> pickPhotoLauncher.launch("image/*"));

        if (btnCopyAccountId != null) {
            btnCopyAccountId.setOnClickListener(v -> {
                String accountId = SignalKeyManager.getAccountId(this);
                if (accountId != null) {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("account_id", accountId));
                        Toast.makeText(this, R.string.settings_id_copied, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (btnShareAccountId != null) {
            btnShareAccountId.setOnClickListener(v -> {
                String accountId = SignalKeyManager.getAccountId(this);
                if (accountId != null) {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_TEXT, accountId);
                    startActivity(Intent.createChooser(shareIntent, "Share Account ID"));
                }
            });
        }

        switchNotifications   = findViewById(R.id.switchNotifications);
        switchBiometric       = findViewById(R.id.switchBiometric);
        switchDarkMode        = findViewById(R.id.switchDarkMode);
        switchDuress          = findViewById(R.id.switchDuress);
        switchAppScreenshot   = findViewById(R.id.switchAppScreenshot);
        switchSanctuaryMode   = findViewById(R.id.switchSanctuaryMode);
        switchShakeLock       = findViewById(R.id.switchShakeLock);
        layoutDuressSection = findViewById(R.id.layoutDuressSection);
        layoutDuressContent = findViewById(R.id.layoutDuressContent);
        layoutDuressForm    = findViewById(R.id.layoutDuressForm);
        layoutDuressActive  = findViewById(R.id.layoutDuressActive);
        layoutPinInputs     = findViewById(R.id.layoutPinInputs);
        layoutPinSet        = findViewById(R.id.layoutPinSet);
        etDuressPin         = findViewById(R.id.etDuressPin);
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
        Button btnSetDuressPin  = findViewById(R.id.btnSetDuressPin);
        Button btnUnpair        = findViewById(R.id.btnUnpair);

        // ── Restore saved state ───────────────────────────────────────────────
        switchNotifications.setChecked(prefs.getBoolean("notifications_enabled", true));
        switchBiometric.setChecked(prefs.getBoolean("biometric_enabled", false));
        switchDarkMode.setChecked(prefs.getBoolean("dark_mode", false));
        if (switchAppScreenshot != null)
            switchAppScreenshot.setChecked(prefs.getBoolean("app_screenshot_enabled", false));
        if (switchShakeLock != null)
            switchShakeLock.setChecked(prefs.getBoolean("shake_to_lock_enabled", false));
        updateAutoSignOutLabel();
        updateLockTimeoutLabel();
        refreshPinStatus();

        // ── Duress toggle ─────────────────────────────────────────────────────
        // Always start unchecked — the section is hidden entirely when a duress
        // PIN is active, so the toggle state is irrelevant while it is saved.
        switchDuress.setChecked(false);
        layoutDuressContent.setVisibility(View.GONE);
        refreshDuressState(); // hides entire section if hasDuressPin()

        switchDuress.setOnCheckedChangeListener((btn, checked) -> {
            if (checked && !PinManager.hasPinSet(this)) {
                switchDuress.setChecked(false);
                Toast.makeText(this,
                    "Set an app PIN first before enabling Duress PIN.",
                    Toast.LENGTH_LONG).show();
                return;
            }
            // Only controls the form visibility — the active-badge path is never
            // reached via the toggle because the section is hidden when a PIN is saved.
            layoutDuressContent.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (!checked && etDuressPin != null) etDuressPin.setText("");
        });

        // ── App PIN ───────────────────────────────────────────────────────────
        btnSetPin.setOnClickListener(v -> saveAppPin());
        if (btnClearPin      != null) btnClearPin.setOnClickListener(v -> confirmClearPin());
        if (btnChangePinMode != null) btnChangePinMode.setOnClickListener(v -> enterChangePinMode());
        if (btnCancelPinForm != null) btnCancelPinForm.setOnClickListener(v -> refreshPinStatus());

        // ── Duress PIN ────────────────────────────────────────────────────────
        if (btnSetDuressPin != null) btnSetDuressPin.setOnClickListener(v -> saveDuressPin());

        // ── Biometric ─────────────────────────────────────────────────────────
        switchBiometric.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !PinManager.hasPinSet(this)) {
                switchBiometric.setChecked(false);
                Toast.makeText(this,
                    "Set an app PIN first before enabling biometric lock.",
                    Toast.LENGTH_LONG).show();
                return;
            }
            if (checked && !BiometricHelper.isAvailable(this)) {
                switchBiometric.setChecked(false);
                Toast.makeText(this,
                    "No biometric enrolled on this device. Enroll fingerprint or face in system Settings first.",
                    Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putBoolean("biometric_enabled", checked).apply();
        });

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

        // ── Other switches ────────────────────────────────────────────────────
        switchNotifications.setOnCheckedChangeListener((b, c) ->
            prefs.edit().putBoolean("notifications_enabled", c).apply());

        // Screenshot toggle — requires the app PIN to change (security gate).
        if (switchAppScreenshot != null) attachScreenshotListener();

        switchDarkMode.setOnCheckedChangeListener((b, c) -> {
            prefs.edit().putBoolean("dark_mode", c).apply();
            AppCompatDelegate.setDefaultNightMode(
                c ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });

        // ── Sanctuary Mode ────────────────────────────────────────────────────
        if (switchSanctuaryMode != null) {
            switchSanctuaryMode.setChecked(
                    com.duoshield.app.util.UiModeHelper.isSanctuary(this));
            switchSanctuaryMode.setOnCheckedChangeListener((b, checked) -> {
                com.duoshield.app.util.UiModeHelper.setMode(this,
                        checked
                        ? com.duoshield.app.util.UiModeHelper.MODE_SANCTUARY
                        : com.duoshield.app.util.UiModeHelper.MODE_CLASSIC);
                recreate();
            });
        }

        // ── Auto sign-out ─────────────────────────────────────────────────────
        btnAutoSignOut.setOnClickListener(v -> showAutoSignOutPicker());

        // ── Lock timeout ──────────────────────────────────────────────────────
        if (btnLockTimeout != null) btnLockTimeout.setOnClickListener(v -> showLockTimeoutPicker());

        // ── Session log ───────────────────────────────────────────────────────
        Button btnViewSessionLog = findViewById(R.id.btnViewSessionLog);
        if (btnViewSessionLog != null)
            btnViewSessionLog.setOnClickListener(v ->
                startActivity(new Intent(this, SessionLogActivity.class)));

        // ── Unpair ────────────────────────────────────────────────────────────
        btnUnpair.setOnClickListener(v -> confirmUnpair());

        // ── B2 connection test ────────────────────────────────────────────────
        TextView tvB2Status = findViewById(R.id.tvB2Status);
        com.google.android.material.button.MaterialButton btnTestB2 =
                findViewById(R.id.btnTestB2);
        if (btnTestB2 != null) {
            btnTestB2.setOnClickListener(v -> {
                btnTestB2.setEnabled(false);
                btnTestB2.setText("Testing…");
                if (tvB2Status != null) tvB2Status.setText("Connecting to Backblaze B2…");
                bgExecutor.execute(() -> {
                    String err = com.duoshield.app.util.B2StorageHelper.testConnection();
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        btnTestB2.setEnabled(true);
                        btnTestB2.setText("Test");
                        if (tvB2Status != null) {
                            if (err == null) {
                                tvB2Status.setText("✓ B2 connection OK — bucket reachable");
                                tvB2Status.setTextColor(0xFF4CAF50);
                            } else {
                                tvB2Status.setText("✗ " + err.split("\n")[0]);
                                tvB2Status.setTextColor(0xFFFF5252);
                            }
                        }
                    });
                });
            });
        }

        // ── B2 diagnostics deep-link ──────────────────────────────────────────
        com.google.android.material.button.MaterialButton btnB2Details =
                findViewById(R.id.btnB2Details);
        if (btnB2Details != null) {
            btnB2Details.setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, StorageDiagnosticsActivity.class)));
        }

        // ── Cloud Backup ──────────────────────────────────────────────────────
        tvLastBackup   = findViewById(R.id.tvLastBackup);
        tvBackupCount  = findViewById(R.id.tvBackupCount);
        tvBackupHealth = findViewById(R.id.tvBackupHealth);
        com.google.android.material.button.MaterialButton btnSyncBackup =
                findViewById(R.id.btnSyncBackup);

        if (btnSyncBackup != null) {
            btnSyncBackup.setOnClickListener(v -> {
                btnSyncBackup.setEnabled(false);
                btnSyncBackup.setText("Syncing…");
                BackupManager.syncAll(this, result -> runOnUiThread(() -> {
                    btnSyncBackup.setEnabled(true);
                    btnSyncBackup.setText("Sync now");
                    if (result.total == 0) {
                        Toast.makeText(this, R.string.settings_backup_none, Toast.LENGTH_SHORT).show();
                    } else if (result.failed == 0) {
                        Toast.makeText(this, "Backed up " + result.written + " messages ✓", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Backed up " + result.written + "/" + result.total
                                + " (" + result.failed + " failed)", Toast.LENGTH_LONG).show();
                    }
                    loadBackupStatus();
                }));
            });
        }

        loadBackupStatus();
    }

    private void loadBackupStatus() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            if (tvLastBackup   != null) tvLastBackup  .setText("Last backup: Not signed in");
            if (tvBackupCount  != null) tvBackupCount .setText("Messages backed up: —");
            if (tvBackupHealth != null) tvBackupHealth.setVisibility(android.view.View.GONE);
            return;
        }
        BackupManager.loadMeta(user.getUid(), (lastTs, count) -> runOnUiThread(() -> {
            if (tvLastBackup == null || tvBackupCount == null) return;
            if (lastTs < 0) {
                tvLastBackup .setText("Last backup: Unable to reach server");
                tvBackupCount.setText("Messages backed up: —");
                if (tvBackupHealth != null) tvBackupHealth.setVisibility(android.view.View.GONE);
            } else if (lastTs == 0) {
                tvLastBackup .setText("Last backup: Never");
                tvBackupCount.setText("Messages backed up: 0");
                if (tvBackupHealth != null) tvBackupHealth.setVisibility(android.view.View.GONE);
            } else {
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("MMM d 'at' h:mm a",
                                java.util.Locale.getDefault());
                tvLastBackup .setText("Last backup: " + sdf.format(new java.util.Date(lastTs)));
                tvBackupCount.setText("Messages backed up: " + count);
            }
        }));

        // Backup health: count unsynced messages on a background thread
        bgExecutor.execute(() -> {
            int unsynced = BackupManager.getUnsyncedCount(this);
            runOnUiThread(() -> {
                if (tvBackupHealth == null) return;
                if (unsynced < 0) {
                    tvBackupHealth.setVisibility(android.view.View.GONE);
                } else if (unsynced == 0) {
                    tvBackupHealth.setVisibility(android.view.View.VISIBLE);
                    tvBackupHealth.setText("✓ All messages backed up");
                    tvBackupHealth.setTextColor(0xFF4CAF50); // green
                } else {
                    tvBackupHealth.setVisibility(android.view.View.VISIBLE);
                    tvBackupHealth.setText(unsynced + " message" + (unsynced == 1 ? "" : "s")
                            + " pending sync");
                    tvBackupHealth.setTextColor(0xFFFFA726); // amber
                }
            });
        });
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
                    // Clash — PIN was NOT saved, so re-read storage (no async write
                    // in flight) to restore the correct state (already-set or empty).
                    refreshPinStatus();
                    Toast.makeText(this,
                        "App PIN cannot be the same as your duress PIN.",
                        Toast.LENGTH_LONG).show();
                } else {
                    // Success — directly apply "PIN is set" UI state.
                    // Do NOT call refreshPinStatus() here: PinManager.setPin() uses
                    // apply() (async), so hasPinSet() might still read the old state
                    // before EncryptedSharedPreferences flushes the in-memory update.
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
            // Clearing the app PIN also erases the duress PIN — the user must
            // prove they know the duress PIN before we allow this, so someone
            // who only knows the app PIN cannot silently disable the duress feature.
            promptDuressPin(() ->
                promptCurrentPin("Enter your app PIN to confirm", this::doClearPin));
        } else {
            promptCurrentPin("Enter your current PIN to clear it", this::doClearPin);
        }
    }

    private void doClearPin() {
        PinManager.clearPin(this);
        prefs.edit()
             .putBoolean("biometric_enabled", false)
             .putBoolean("shake_to_lock_enabled", false)
             .apply();
        switchBiometric.setChecked(false);
        if (switchShakeLock != null) switchShakeLock.setChecked(false);
        DuressManager.clearDuressPin(this);
        if (switchDuress != null) switchDuress.setChecked(false);
        if (layoutDuressContent != null) layoutDuressContent.setVisibility(View.GONE);
        if (etDuressPin != null) etDuressPin.setText("");
        // Directly apply "no PIN" state — clearPin() uses apply() (async)
        applyPinUiState(false);
        refreshDuressState(); // section becomes visible again (duress PIN now cleared)
        Toast.makeText(this, R.string.settings_pin_cleared, Toast.LENGTH_SHORT).show();
    }

    /**
     * Prompts the user to enter their duress PIN and calls {@code onVerified} only
     * if the entered value matches the stored hash. Used before any operation that
     * would clear or disable the duress feature.
     */
    private void promptDuressPin(Runnable onVerified) {
        EditText etEntry = new EditText(this);
        etEntry.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etEntry.setHint("Duress PIN");
        etEntry.setMaxLines(1);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(etEntry);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Verify Duress PIN")
                .setMessage("Clearing your app PIN will also remove the duress PIN.\nEnter your duress PIN to confirm.")
                .setView(container)
                .setPositiveButton("Confirm", (d, w) -> {
                    String entered = etEntry.getText().toString().trim();
                    bgExecutor.execute(() -> {
                        boolean ok = DuressManager.isDuressPin(this, entered);
                        runOnUiThread(() -> {
                            if (ok) onVerified.run();
                            else Toast.makeText(this, "Incorrect duress PIN.", Toast.LENGTH_SHORT).show();
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

    /**
     * Re-reads the PIN state from storage and refreshes the UI.
     * Safe to call from onCreate() when no async write is in flight.
     * After doSavePin() / doClearPin(), call applyPinUiState() instead
     * to avoid reading a value that hasn't been flushed yet by apply().
     */
    private void refreshPinStatus() {
        applyPinUiState(PinManager.hasPinSet(this));
    }

    /**
     * Directly applies the PIN UI state without reading from storage.
     * Use this after setPin() / clearPin() to guarantee the correct
     * state is shown even before EncryptedSharedPreferences completes
     * its async disk flush.
     */
    private void applyPinUiState(boolean pinSet) {
        if (tvPinStatus == null) return;
        tvPinStatus.setText(pinSet ? "✓  App PIN is set" : "No PIN set — lock screen is disabled");
        tvPinStatus.setTextColor(getResources().getColor(
            pinSet ? R.color.online_green : R.color.text_hint, null));

        // Toggle between the "set" state (Change/Clear buttons) and the form (input fields)
        if (layoutPinInputs != null) {
            layoutPinInputs.setVisibility(pinSet ? View.GONE : View.VISIBLE);
        }
        if (layoutPinSet != null) {
            layoutPinSet.setVisibility(pinSet ? View.VISIBLE : View.GONE);
        }
        // Hide the cancel button whenever we return to base state
        Button cancel = findViewById(R.id.btnCancelPinForm);
        if (cancel != null) cancel.setVisibility(View.GONE);
        // Clear inputs
        if (etNewPin != null) etNewPin.setText("");
        if (etConfirmPin != null) etConfirmPin.setText("");
    }

    private void enterChangePinMode() {
        // Show the input form (in "change" mode) with a Cancel button
        if (layoutPinInputs != null) layoutPinInputs.setVisibility(View.VISIBLE);
        if (layoutPinSet    != null) layoutPinSet.setVisibility(View.GONE);
        Button cancel = findViewById(R.id.btnCancelPinForm);
        if (cancel != null) cancel.setVisibility(View.VISIBLE);
        if (etNewPin != null) etNewPin.requestFocus();
    }

    // ── Duress PIN logic ──────────────────────────────────────────────────────

    private void saveDuressPin() {
        String pin = etDuressPin.getText().toString().trim();
        if (pin.length() < 4 || pin.length() > 6) {
            Toast.makeText(this, "Duress PIN must be 4–6 digits.", Toast.LENGTH_SHORT).show();
            return;
        }

        Button btnSetDuressPin = findViewById(R.id.btnSetDuressPin);
        if (btnSetDuressPin != null) btnSetDuressPin.setEnabled(false);

        bgExecutor.execute(() -> {
            boolean clashWithAppPin = PinManager.verifyPin(this, pin);
            if (!clashWithAppPin) DuressManager.setDuressPin(this, pin);

            runOnUiThread(() -> {
                if (btnSetDuressPin != null) btnSetDuressPin.setEnabled(true);
                if (clashWithAppPin) {
                    Toast.makeText(this,
                        "Duress PIN cannot match your app PIN.",
                        Toast.LENGTH_LONG).show();
                } else {
                    etDuressPin.setText("");
                    Toast.makeText(this, "Duress PIN set. Keep it secret.", Toast.LENGTH_SHORT).show();
                    // Hide the form — show the "active" badge instead. The user
                    // cannot re-enter or overwrite a duress PIN once it is set;
                    // they must disable and re-enable the toggle to change it.
                    refreshDuressState();
                }
            });
        });
    }

    // ── Profile name edit ─────────────────────────────────────────────────────

    private void showNameEditDialog() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setHint("Your display name");
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        String current = prefs.getString("my_display_name", "");
        et.setText(current);
        et.setSelection(et.getText().length());

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        container.setPadding(pad * 2, pad, pad * 2, 0);
        container.addView(et);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Change Display Name")
                .setView(container)
                .setPositiveButton("Save", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Name cannot be empty.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString("my_display_name", name).apply();
                    if (tvProfileDisplayName != null) tvProfileDisplayName.setText(name);
                    // Also update the hidden legacy tvDisplayName if present
                    TextView legacy = findViewById(R.id.tvDisplayName);
                    if (legacy != null) legacy.setText(name);
                    // Persist to Firestore so other users/devices see the new name
                    saveNameToFirestore(name);
                    Toast.makeText(this, "Name updated.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Writes the local user's new photo URL into every active conversation doc they participate in,
     * stored as {@code partnerPhotoUrl_<partnerUid>} so the partner's conversation list shows the
     * updated avatar without waiting for them to open a chat.
     */
    private void propagatePhotoToConversations(String myUid, String photoUrl) {
        FirebaseFirestore.getInstance()
                .collection("chats")
                .whereArrayContains("participants", myUid)
                .get()
                .addOnSuccessListener(snap -> {
                    for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
                        java.util.List<String> participants =
                                (java.util.List<String>) doc.get("participants");
                        if (participants == null) continue;
                        String partnerUid = null;
                        for (String p : participants) {
                            if (p != null && !p.equals(myUid)) { partnerUid = p; break; }
                        }
                        if (partnerUid == null) continue;
                        // partnerPhotoUrl_<partnerUid> = MY photo URL (from the partner's perspective,
                        // I am their partner — so they read partnerPhotoUrl_<theirUid> = my URL).
                        doc.getReference()
                           .update("partnerPhotoUrl_" + partnerUid, photoUrl)
                           .addOnFailureListener(e ->
                               android.util.Log.w("Settings", "propagatePhoto non-critical: " + e.getMessage()));
                    }
                })
                .addOnFailureListener(e ->
                        android.util.Log.w("Settings", "propagatePhoto query failed (non-critical): " + e.getMessage()));
    }

    private void saveNameToFirestore(String name) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .set(Collections.singletonMap("displayName", name),
                        com.google.firebase.firestore.SetOptions.merge())
                .addOnFailureListener(e ->
                        android.util.Log.w("Settings", "displayName Firestore write failed", e));
    }

    // ── Profile photo upload (B2 — bypasses Firebase Storage rules entirely) ──

    private void uploadProfilePhoto(Uri uri) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Not signed in.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Uploading photo…", Toast.LENGTH_SHORT).show();
        bgExecutor.execute(() -> {
            try {
                byte[] plain = readUriBytes(uri);
                if (plain == null || plain.length == 0) throw new Exception("Empty file");
                String objectKey = "avatars/" + user.getUid() + "_" + System.currentTimeMillis() + ".jpg";
                String b2Path = B2StorageHelper.uploadFile(plain, objectKey, "image/jpeg", null);
                String url = B2StorageHelper.toPublicUrl(b2Path);
                runOnUiThread(() -> onPhotoUploaded(url, user));
            } catch (Exception e) {
                android.util.Log.e("Settings", "Photo upload failed", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void onPhotoUploaded(String url, FirebaseUser user) {
        prefs.edit().putString("my_photo_url", url).apply();
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .set(Collections.singletonMap("photoUrl", url),
                        com.google.firebase.firestore.SetOptions.merge());
        propagatePhotoToConversations(user.getUid(), url);
        if (ivProfilePhoto != null && !isDestroyed() && !isFinishing()) {
            Glide.with(this).load(url).transform(new CircleCrop()).into(ivProfilePhoto);
        }
        Toast.makeText(this, "Photo updated!", Toast.LENGTH_SHORT).show();
    }

    private byte[] readUriBytes(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buf = new byte[32_768];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            android.util.Log.e("Settings", "readUriBytes failed", e);
            return null;
        }
    }

    // ── Screenshot toggle helpers ─────────────────────────────────────────────

    /**
     * Guards against the listener re-entering itself when we call
     * {@code setChecked()} programmatically inside the PIN success callback.
     */
    private boolean screenshotListenerGuard = false;

    /**
     * Attaches (or re-attaches) the PIN-gated listener to {@link #switchAppScreenshot}.
     * Uses {@link #screenshotListenerGuard} to prevent the programmatic
     * {@code setChecked()} inside the PIN success path from re-triggering the listener.
     */
    private void attachScreenshotListener() {
        if (switchAppScreenshot == null) return;
        switchAppScreenshot.setOnCheckedChangeListener((btn, checked) -> {
            // Ignore events that we ourselves triggered to update the switch visual.
            if (screenshotListenerGuard) return;

            if (!PinManager.hasPinSet(this)) {
                // No PIN set — allow freely so users aren't locked out.
                prefs.edit().putBoolean("app_screenshot_enabled", checked).apply();
                applyScreenshotFlag(checked);
                return;
            }

            // Visually revert the switch until the PIN is confirmed.
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

    /** Applies or removes FLAG_SECURE for this activity based on the toggle value. */
    private void applyScreenshotFlag(boolean allow) {
        if (allow) {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    // ── Duress state helper ───────────────────────────────────────────────────

    /**
     * Hides or reveals the <em>entire</em> Duress PIN section based on whether a
     * duress PIN is currently saved:
     * <ul>
     *   <li>PIN saved  → {@code layoutDuressSection} is {@code GONE} — no trace visible</li>
     *   <li>No PIN     → {@code layoutDuressSection} is {@code VISIBLE} — user can set one</li>
     * </ul>
     * Call after any event that might change the duress PIN state (initial load,
     * save success, app-PIN clear).
     */
    private void refreshDuressState() {
        if (layoutDuressSection == null) return;
        boolean pinSaved = DuressManager.hasDuressPin(this);
        // When a duress PIN is active the entire section vanishes — no toggle,
        // no label, no evidence that the feature exists.
        layoutDuressSection.setVisibility(pinSaved ? View.GONE : View.VISIBLE);
    }

    // ── Lock timeout ──────────────────────────────────────────────────────────

    private void showLockTimeoutPicker() {
        long current = prefs.getLong(AppLockManager.KEY_LOCK_TIMEOUT_MS, AppLockManager.DEFAULT_LOCK_TIMEOUT);
        int checked = 3; // default index = "3 minutes" (index 3 after adding "Immediately" at 0)
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

    // ── Unpair ────────────────────────────────────────────────────────────────

    private void confirmUnpair() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Unpair Device")
            .setMessage("This will remove all pairing data and delete all local messages. This cannot be undone.")
            .setPositiveButton("Unpair", (dialog, which) -> unpairDevice())
            .setNegativeButton("Cancel", null).show();
    }

    private void unpairDevice() {
        SelfDestructScheduler.cancel(getApplicationContext());
        bgExecutor.execute(() -> {
            try {
                // 0. Back up contacts BEFORE wiping Room DB so they survive a re-pair.
                com.google.firebase.auth.FirebaseUser fu =
                        com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                String uid = (fu != null) ? fu.getUid() : prefs.getString("my_uid", null);
                if (uid != null) {
                    java.util.List<com.duoshield.app.models.Contact> contacts =
                            AppDatabase.getInstance(getApplicationContext()).contactDao().getAll();
                    com.duoshield.app.util.ContactBackupHelper.backup(
                            getApplicationContext(), uid, contacts);
                }
            } catch (Exception e) {
                android.util.Log.w("Settings", "unpair: contact backup failed (non-fatal)", e);
            }
            try {
                // 1. Close and delete the entire Room database (includes all messages,
                //    signal sessions, prekeys, identity records, session log).
                //    clearInstance() must precede deleteDatabase() (BUG-SET01).
                AppDatabase.clearInstance();
                getApplicationContext().deleteDatabase("duoshield_db");
            } catch (Exception e) {
                android.util.Log.e("Settings", "unpair: DB deletion failed (non-fatal)", e);
            }
            try {
                // 2. Wipe EncryptedSharedPreferences (Signal identity key, prekeys,
                //    shared ECDH key, registration ID).  Leaving these in place would
                //    allow the next paired user to inherit the current identity (BUG-SET01).
                SecurePrefs.get(getApplicationContext()).edit().clear().commit();
            } catch (Exception e) {
                android.util.Log.e("Settings", "unpair: SecurePrefs clear failed (non-fatal)", e);
            }
            try {
                // 3. Delete cache directory to remove any temp media or export files.
                deleteDir(getApplicationContext().getCacheDir());
            } catch (Exception e) {
                android.util.Log.w("Settings", "unpair: cache deletion failed (non-fatal)", e);
            }
            runOnUiThread(() -> {
                // 4. Clear plain SharedPreferences last so conversation_id etc. are gone.
                prefs.edit().clear().apply();
                Toast.makeText(this, "Device unpaired.", Toast.LENGTH_SHORT).show();
                Intent i = new Intent(this, AddContactActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i); finish();
            });
        });
    }

    private static void deleteDir(java.io.File dir) {
        if (dir == null || !dir.exists()) return;
        java.io.File[] files = dir.listFiles();
        if (files != null) for (java.io.File f : files) deleteDir(f);
        dir.delete();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdown();
    }
}
