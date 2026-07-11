package com.duoshield.app.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;

public class AppearanceNotificationsSettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    private SwitchCompat switchNotifications;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_appearance_notifications_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        switchNotifications = findViewById(R.id.switchNotifications);

        // ── Restore saved state ───────────────────────────────────────────────
        switchNotifications.setChecked(prefs.getBoolean("notifications_enabled", true));

        // ── Notifications switch ──────────────────────────────────────────────
        switchNotifications.setOnCheckedChangeListener((b, c) ->
            prefs.edit().putBoolean("notifications_enabled", c).apply());
    }
}
