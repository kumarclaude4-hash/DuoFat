package com.duoshield.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.util.SecurePrefs;
import com.duoshield.app.util.SelfDestructScheduler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DangerZoneSettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_danger_zone_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        // ── Unpair ────────────────────────────────────────────────────────────
        com.google.android.material.button.MaterialButton btnUnpair =
                findViewById(R.id.btnUnpair);
        if (btnUnpair != null) btnUnpair.setOnClickListener(v -> confirmUnpair());
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
                //    NOTE: account-scoped file only — the device-level PIN gate lives in
                //    its own isolated file (SecurePrefs.getDeviceGate()) and must survive
                //    this wipe by design; see PinManager's class javadoc.
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdown();
    }
}
