package com.duoshield.app.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.os.LocaleListCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;

public class AppearanceNotificationsSettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    private SwitchCompat switchNotifications;
    private TextView textLanguageSummary;
    private String[] languageTags;
    private String[] languageLabels;

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
        textLanguageSummary = findViewById(R.id.textLanguageSummary);
        languageTags = getResources().getStringArray(R.array.language_tags);
        languageLabels = getResources().getStringArray(R.array.language_labels);

        // ── Restore saved state ───────────────────────────────────────────────
        switchNotifications.setChecked(prefs.getBoolean("notifications_enabled", true));
        updateLanguageSummary();

        // ── Notifications switch ──────────────────────────────────────────────
        switchNotifications.setOnCheckedChangeListener((b, c) ->
            prefs.edit().putBoolean("notifications_enabled", c).apply());

        findViewById(R.id.rowLanguage).setOnClickListener(v -> showLanguagePicker());
    }

    private void showLanguagePicker() {
        String currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        int checkedItem = 0; // System default
        for (int i = 1; i < languageTags.length; i++) {
            if (languageTags[i].equalsIgnoreCase(currentTags)) {
                checkedItem = i;
                break;
            }
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_language)
            .setSingleChoiceItems(languageLabels, checkedItem, (dialog, which) -> {
                // AppCompat persists this automatically and recreates activities with the
                // selected resource configuration. An empty locale list follows the device.
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(languageTags[which]));
                dialog.dismiss();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void updateLanguageSummary() {
        String currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        int selected = 0;
        for (int i = 1; i < languageTags.length; i++) {
            if (languageTags[i].equalsIgnoreCase(currentTags)) {
                selected = i;
                break;
            }
        }
        textLanguageSummary.setText(languageLabels[selected]);
    }
}
