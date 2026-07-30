package com.duoshield.app.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.os.LocaleListCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.util.ChatThemeHelper;

public class AppearanceNotificationsSettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    private SwitchCompat switchNotifications;
    private TextView textLanguageSummary;
    private String[] languageTags;
    private String[] languageLabels;

    /** Tracks the ring FrameLayout for the currently selected swatch. */
    private FrameLayout selectedRingView;

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

        // ── Chat background theme picker ──────────────────────────────────────
        setupThemePicker();

        // ── Notifications switch ──────────────────────────────────────────────
        switchNotifications.setOnCheckedChangeListener((b, c) ->
            prefs.edit().putBoolean("notifications_enabled", c).apply());

        findViewById(R.id.rowLanguage).setOnClickListener(v -> showLanguagePicker());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // THEME PICKER
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Populates the horizontal swatch picker in the Chat Background section.
     * Each swatch is a rounded gradient card with the theme name below it.
     * Tapping a swatch saves the theme to prefs and shows an accent ring.
     */
    private void setupThemePicker() {
        LinearLayout row = findViewById(R.id.rowThemePicker);
        row.removeAllViews();
        selectedRingView = null;

        float dp = getResources().getDisplayMetrics().density;
        String current = prefs.getString(ChatThemeHelper.PREF_KEY, ChatThemeHelper.THEME_DEFAULT);

        // Each entry: { themeId, label, gradientStartColor, gradientEndColor }
        // Colors are ARGB ints. For solid swatches startColor == endColor.
        Object[][] themes = {
            { ChatThemeHelper.THEME_DEFAULT,  "Default",  0xFF191620, 0xFF191620 },
            { ChatThemeHelper.THEME_MIDNIGHT, "Midnight", 0xFF0B0C18, 0xFF1C1240 },
            { ChatThemeHelper.THEME_OCEAN,    "Ocean",    0xFF040E1C, 0xFF0A2032 },
            { ChatThemeHelper.THEME_FOREST,   "Forest",   0xFF071210, 0xFF0D2018 },
            { ChatThemeHelper.THEME_DUSK,     "Dusk",     0xFF120810, 0xFF200E1E },
            { ChatThemeHelper.THEME_STEEL,    "Steel",    0xFF080D1A, 0xFF122032 },
            { ChatThemeHelper.THEME_ASH,      "Ash",      0xFF0C0C10, 0xFF181820 },
            { ChatThemeHelper.THEME_NOIR,     "Noir",     0xFF000000, 0xFF0A0A0C },
            { ChatThemeHelper.THEME_AURORA,   "Aurora",   0xFF030E14, 0xFF082820 },
            { ChatThemeHelper.THEME_EMBER,    "Ember",    0xFF100904, 0xFF201608 },
            { ChatThemeHelper.THEME_LAVENDER, "Lavender", 0xFF0E0C1C, 0xFF201A3C },
            { ChatThemeHelper.THEME_SLATE,    "Slate",    0xFF08090F, 0xFF101620 },
        };

        int swatchSize  = (int)(66 * dp);   // card width & height
        int outerSize   = (int)(72 * dp);   // ring frame (3dp ring padding each side)
        int ringPad     = (int)( 3 * dp);
        float cardRadius = 15 * dp;
        float ringRadius = 18 * dp;

        for (Object[] t : themes) {
            String themeId = (String)  t[0];
            String label   = (String)  t[1];
            int    startC  = (Integer) t[2];
            int    endC    = (Integer) t[3];
            boolean isSolid = startC == endC;

            // ── Root item (vertical, centred) ──────────────────────────────────
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setClickable(true);
            item.setFocusable(true);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            itemLp.setMarginEnd((int)(10 * dp));
            item.setLayoutParams(itemLp);

            // ── Ring frame (FrameLayout with padding = ring gap) ───────────────
            FrameLayout ringFrame = new FrameLayout(this);
            FrameLayout.LayoutParams rfLp = new FrameLayout.LayoutParams(outerSize, outerSize);
            ringFrame.setLayoutParams(rfLp);
            ringFrame.setPadding(ringPad, ringPad, ringPad, ringPad);

            // Accent ring drawable (shown only when selected)
            GradientDrawable ringDrawable = new GradientDrawable();
            ringDrawable.setShape(GradientDrawable.RECTANGLE);
            ringDrawable.setCornerRadius(ringRadius);
            ringDrawable.setStroke((int)(2.5f * dp), 0xFF9A81FF);
            ringDrawable.setColor(Color.TRANSPARENT);

            if (themeId.equals(current)) {
                ringFrame.setBackground(ringDrawable);
                selectedRingView = ringFrame;
            }

            // ── Swatch card ────────────────────────────────────────────────────
            android.widget.FrameLayout.LayoutParams swatchLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);

            android.view.View swatch = new android.view.View(this);
            swatch.setLayoutParams(swatchLp);

            GradientDrawable grad;
            if (isSolid) {
                grad = new GradientDrawable();
                grad.setShape(GradientDrawable.RECTANGLE);
                grad.setColor(startC);
            } else {
                grad = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[]{ startC, endC });
            }
            grad.setCornerRadius(cardRadius);

            // Subtle inner border so dark-on-dark themes have a visible edge
            grad.setStroke((int)(0.75f * dp), 0x26FFFFFF);
            swatch.setBackground(grad);

            ringFrame.addView(swatch);

            // ── Label ─────────────────────────────────────────────────────────
            TextView labelView = new TextView(this);
            LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lblLp.topMargin = (int)(5 * dp);
            labelView.setLayoutParams(lblLp);
            labelView.setText(label);
            labelView.setTextColor(0xFFC8C2D8);
            labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
            labelView.setGravity(Gravity.CENTER);

            item.addView(ringFrame);
            item.addView(labelView);
            row.addView(item);

            // ── Click: save & update ring ──────────────────────────────────────
            final String       finalThemeId    = themeId;
            final FrameLayout  finalRingFrame  = ringFrame;
            final GradientDrawable finalRingDrw = ringDrawable;

            item.setOnClickListener(v -> {
                prefs.edit().putString(ChatThemeHelper.PREF_KEY, finalThemeId).apply();
                if (selectedRingView != null) selectedRingView.setBackground(null);
                finalRingFrame.setBackground(finalRingDrw);
                selectedRingView = finalRingFrame;
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LANGUAGE
    // ══════════════════════════════════════════════════════════════════════════

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
