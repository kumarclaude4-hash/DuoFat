package com.duoshield.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.backup.BackupManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BackupStorageSettingsActivity extends BaseActivity {

    private TextView tvLastBackup, tvBackupCount, tvBackupHealth;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup_storage_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

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
                                tvB2Status.setTextColor(0xFF6BBF8A);
                            } else {
                                tvB2Status.setText("✗ " + err.split("\n")[0]);
                                tvB2Status.setTextColor(0xFFD96A7C);
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
            if (com.duoshield.app.BuildConfig.DEBUG) {
                btnB2Details.setOnClickListener(v ->
                        startActivity(new android.content.Intent(this, StorageDiagnosticsActivity.class)));
            } else {
                btnB2Details.setVisibility(View.GONE);
            }
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

        // ── Local cache size ──────────────────────────────────────────────────
        TextView tvCacheSize = findViewById(R.id.tvCacheSize);
        com.google.android.material.button.MaterialButton btnClearCache =
                findViewById(R.id.btnClearCache);
        if (tvCacheSize != null) {
            bgExecutor.execute(() -> {
                String label = com.duoshield.app.util.MediaSizeEstimator.getCacheSizeLabel(this);
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) tvCacheSize.setText("Local cache: " + label);
                });
            });
        }
        if (btnClearCache != null) {
            btnClearCache.setOnClickListener(v -> {
                btnClearCache.setEnabled(false);
                btnClearCache.setText("Clearing…");
                bgExecutor.execute(() -> {
                    clearDirRecursive(getCacheDir());
                    String label = com.duoshield.app.util.MediaSizeEstimator.getCacheSizeLabel(this);
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        btnClearCache.setEnabled(true);
                        btnClearCache.setText("Clear cache");
                        if (tvCacheSize != null) tvCacheSize.setText("Local cache: " + label);
                        Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show();
                    });
                });
            });
        }

        loadBackupStatus();
    }

    private void clearDirRecursive(java.io.File dir) {
        if (dir == null) return;
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) clearDirRecursive(f);
            else f.delete();
        }
    }

    private void loadBackupStatus() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            if (tvLastBackup   != null) tvLastBackup  .setText("Last backup: Not signed in");
            if (tvBackupCount  != null) tvBackupCount .setText("Messages backed up: —");
            if (tvBackupHealth != null) tvBackupHealth.setVisibility(View.GONE);
            return;
        }
        BackupManager.loadMeta(user.getUid(), (lastTs, count) -> runOnUiThread(() -> {
            if (tvLastBackup == null || tvBackupCount == null) return;
            if (lastTs < 0) {
                tvLastBackup .setText("Last backup: Unable to reach server");
                tvBackupCount.setText("Messages backed up: —");
                if (tvBackupHealth != null) tvBackupHealth.setVisibility(View.GONE);
            } else if (lastTs == 0) {
                tvLastBackup .setText("Last backup: Never");
                tvBackupCount.setText("Messages backed up: 0");
                if (tvBackupHealth != null) tvBackupHealth.setVisibility(View.GONE);
            } else {
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("MMM d 'at' h:mm a",
                                java.util.Locale.getDefault());
                tvLastBackup .setText("Last backup: " + sdf.format(new java.util.Date(lastTs)));
                tvBackupCount.setText("Messages backed up: " + count);
            }
        }));

        bgExecutor.execute(() -> {
            int unsynced = BackupManager.getUnsyncedCount(this);
            runOnUiThread(() -> {
                if (tvBackupHealth == null) return;
                if (unsynced < 0) {
                    tvBackupHealth.setVisibility(View.GONE);
                } else if (unsynced == 0) {
                    tvBackupHealth.setVisibility(View.VISIBLE);
                    tvBackupHealth.setText("✓ All messages backed up");
                    tvBackupHealth.setTextColor(0xFF6BBF8A);
                } else {
                    tvBackupHealth.setVisibility(View.VISIBLE);
                    tvBackupHealth.setText(unsynced + " message" + (unsynced == 1 ? "" : "s")
                            + " pending sync");
                    tvBackupHealth.setTextColor(0xFFE7B15D);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdown();
    }
}
