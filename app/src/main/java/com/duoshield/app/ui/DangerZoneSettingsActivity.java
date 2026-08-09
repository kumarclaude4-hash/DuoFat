package com.duoshield.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
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
        bgExecutor.execute(() -> {
            // Canonical local erasure — the SAME routine used by "Wipe & Exit" and the
            // duress-PIN logout, running in UNPAIR mode. See WipeHelper for the full
            // ordered step list. UNPAIR mode backs contacts up first so they survive
            // re-pairing.
            //
            // Do NOT re-inline erasure steps here. This path previously maintained its
            // own copy of the sequence and had fallen behind in four places: it never
            // cleared the decrypted-media disk cache (filesDir/b2_cache), never deleted
            // gallery-saved media, never cleared pin_fail_count, and never signed out of
            // Firebase — leaving a usable session token behind after "unpairing".
            // Any new erasure step belongs in WipeHelper.eraseLocalData().
            com.duoshield.app.util.WipeHelper.eraseLocalData(
                    getApplicationContext(),
                    com.duoshield.app.util.WipeHelper.WipeMode.UNPAIR);

            runOnUiThread(() -> {
                Toast.makeText(this, "Device unpaired.", Toast.LENGTH_SHORT).show();
                Intent i = new Intent(this, AddContactActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i); finish();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdown();
    }
}
