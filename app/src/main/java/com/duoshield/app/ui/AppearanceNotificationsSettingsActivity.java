package com.duoshield.app.ui;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
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
import com.duoshield.app.util.ChatCustomizationHelper;
import com.duoshield.app.util.ChatThemeHelper;

public class AppearanceNotificationsSettingsActivity extends BaseActivity {

    private SharedPreferences prefs;
    private SwitchCompat switchNotifications;
    private TextView textLanguageSummary;
    private String[] languageTags;
    private String[] languageLabels;

    /** Ring view for the currently-selected background-theme swatch. */
    private FrameLayout selectedThemeRing;
    /** Ring view for the currently-selected mine-bubble colour swatch. */
    private FrameLayout selectedMineRing;
    /** Ring view for the currently-selected theirs-bubble colour swatch. */
    private FrameLayout selectedTheirsRing;

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
        languageTags   = getResources().getStringArray(R.array.language_tags);
        languageLabels = getResources().getStringArray(R.array.language_labels);

        // ── Restore saved state ───────────────────────────────────────────────
        switchNotifications.setChecked(prefs.getBoolean("notifications_enabled", true));
        updateLanguageSummary();

        // ── Populate all pickers ──────────────────────────────────────────────
        setupThemePicker();
        setupBubbleStylePicker();
        setupBubbleColorPicker();
        setupTextSizePicker();

        // ── Notifications switch ──────────────────────────────────────────────
        switchNotifications.setOnCheckedChangeListener((b, c) ->
                prefs.edit().putBoolean("notifications_enabled", c).apply());

        findViewById(R.id.rowLanguage).setOnClickListener(v -> showLanguagePicker());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1 · CHAT BACKGROUND THEME PICKER
    // ══════════════════════════════════════════════════════════════════════════

    private void setupThemePicker() {
        LinearLayout row = findViewById(R.id.rowThemePicker);
        row.removeAllViews();
        selectedThemeRing = null;

        float dp = density();
        String current = prefs.getString(ChatThemeHelper.PREF_KEY, ChatThemeHelper.THEME_DEFAULT);

        // { themeId, label, gradientStartColor, gradientEndColor }
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

        int outerSz = (int)(72 * dp);
        int ringPad = (int)( 3 * dp);

        for (Object[] t : themes) {
            String themeId = (String)  t[0];
            String label   = (String)  t[1];
            int    startC  = (Integer) t[2];
            int    endC    = (Integer) t[3];

            LinearLayout item = makeItemContainer(dp, 10);

            FrameLayout ring = makeRingFrame(outerSz, ringPad);
            GradientDrawable ringDrw = makeRingDrawable(17 * dp, dp);

            // Swatch card
            android.view.View swatch = new android.view.View(this);
            swatch.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            GradientDrawable grad = (startC == endC)
                    ? solidRounded(startC, 15 * dp)
                    : gradientRounded(startC, endC, 15 * dp);
            grad.setStroke((int)(0.75f * dp), 0x26FFFFFF);
            swatch.setBackground(grad);
            ring.addView(swatch);

            if (themeId.equals(current)) { ring.setBackground(ringDrw); selectedThemeRing = ring; }
            item.addView(ring);
            item.addView(makeLabel(label, dp));
            row.addView(item);

            final String fId = themeId; final FrameLayout fRing = ring;
            final GradientDrawable fDrw = ringDrw;
            item.setOnClickListener(v -> {
                prefs.edit().putString(ChatThemeHelper.PREF_KEY, fId).apply();
                if (selectedThemeRing != null) selectedThemeRing.setBackground(null);
                fRing.setBackground(fDrw); selectedThemeRing = fRing;
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2 · BUBBLE STYLE CHIPS
    // ══════════════════════════════════════════════════════════════════════════

    private void setupBubbleStylePicker() {
        LinearLayout row = findViewById(R.id.rowBubbleStylePicker);
        row.removeAllViews();

        float dp = density();
        String current = prefs.getString(
                ChatCustomizationHelper.PREF_BUBBLE_STYLE, ChatCustomizationHelper.STYLE_ROUNDED);

        String[] ids    = { ChatCustomizationHelper.STYLE_ROUNDED,
                            ChatCustomizationHelper.STYLE_CLASSIC,
                            ChatCustomizationHelper.STYLE_SHARP };
        String[] labels = { "Rounded", "Classic", "Sharp" };

        setupChipRow(row, ids, labels,
                ChatCustomizationHelper.PREF_BUBBLE_STYLE, current, dp);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3 · BUBBLE COLOUR PICKERS (mine + theirs)
    // ══════════════════════════════════════════════════════════════════════════

    private void setupBubbleColorPicker() {
        float dp = density();

        // Mine
        int curMine = prefs.getInt(
                ChatCustomizationHelper.PREF_BUBBLE_MINE_COLOR,
                ChatCustomizationHelper.DEFAULT_MINE_COLOR);
        LinearLayout mineRow = findViewById(R.id.rowMineBubblePicker);
        mineRow.removeAllViews();
        selectedMineRing = buildColorSwatchRow(mineRow,
                ChatCustomizationHelper.MINE_COLORS,
                ChatCustomizationHelper.MINE_COLOR_NAMES,
                curMine,
                ChatCustomizationHelper.PREF_BUBBLE_MINE_COLOR,
                true, dp);

        // Theirs
        int curTheirs = prefs.getInt(
                ChatCustomizationHelper.PREF_BUBBLE_THEIRS_COLOR,
                ChatCustomizationHelper.DEFAULT_THEIRS_COLOR);
        LinearLayout theirsRow = findViewById(R.id.rowTheirsBubblePicker);
        theirsRow.removeAllViews();
        selectedTheirsRing = buildColorSwatchRow(theirsRow,
                ChatCustomizationHelper.THEIRS_COLORS,
                ChatCustomizationHelper.THEIRS_COLOR_NAMES,
                curTheirs,
                ChatCustomizationHelper.PREF_BUBBLE_THEIRS_COLOR,
                false, dp);
    }

    /**
     * Adds colour swatch items to {@code row} and returns the ring FrameLayout
     * that is initially selected.
     */
    private FrameLayout buildColorSwatchRow(LinearLayout row,
            int[] colors, String[] names, int currentColor,
            String prefKey, boolean isMineRow, float dp) {

        int outerSz = (int)(52 * dp);
        int ringPad = (int)( 3 * dp);
        FrameLayout[] rings = new FrameLayout[colors.length];
        FrameLayout initialSelected = null;

        for (int i = 0; i < colors.length; i++) {
            int    color = colors[i];
            String name  = names[i];

            LinearLayout item = makeItemContainer(dp, 10);

            FrameLayout ring = makeRingFrame(outerSz, ringPad);
            // Circular ring: corner radius = half the outer diameter
            GradientDrawable ringDrw = makeRingDrawable(outerSz / 2f, dp);
            rings[i] = ring;

            // Circle swatch
            android.view.View swatch = new android.view.View(this);
            swatch.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            swatch.setBackground(
                    ChatCustomizationHelper.buildSwatchCircle(color, dp));
            ring.addView(swatch);

            if (color == currentColor) { ring.setBackground(ringDrw); initialSelected = ring; }
            item.addView(ring);
            item.addView(makeLabel(name, dp));
            row.addView(item);

            final int     fColor  = color;
            final FrameLayout fRing = ring;
            final GradientDrawable fDrw  = ringDrw;
            final FrameLayout[] fRings   = rings;
            item.setOnClickListener(v -> {
                prefs.edit().putInt(prefKey, fColor).apply();
                for (FrameLayout r : fRings) if (r != null) r.setBackground(null);
                fRing.setBackground(fDrw);
                if (isMineRow)   selectedMineRing   = fRing;
                else             selectedTheirsRing = fRing;
            });
        }
        return initialSelected;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4 · TEXT SIZE CHIPS
    // ══════════════════════════════════════════════════════════════════════════

    private void setupTextSizePicker() {
        LinearLayout row = findViewById(R.id.rowTextSizePicker);
        row.removeAllViews();

        float dp = density();
        String current = prefs.getString(
                ChatCustomizationHelper.PREF_MSG_FONT_SIZE, ChatCustomizationHelper.FONT_MEDIUM);

        String[] ids    = { ChatCustomizationHelper.FONT_SMALL,
                            ChatCustomizationHelper.FONT_MEDIUM,
                            ChatCustomizationHelper.FONT_LARGE };
        String[] labels = { "Aa  Small", "Aa  Medium", "Aa  Large" };

        setupChipRow(row, ids, labels,
                ChatCustomizationHelper.PREF_MSG_FONT_SIZE, current, dp);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LANGUAGE
    // ══════════════════════════════════════════════════════════════════════════

    private void showLanguagePicker() {
        String currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        int checkedItem = 0;
        for (int i = 1; i < languageTags.length; i++) {
            if (languageTags[i].equalsIgnoreCase(currentTags)) { checkedItem = i; break; }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_language)
                .setSingleChoiceItems(languageLabels, checkedItem, (dialog, which) -> {
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
            if (languageTags[i].equalsIgnoreCase(currentTags)) { selected = i; break; }
        }
        textLanguageSummary.setText(languageLabels[selected]);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SHARED UI HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Builds a horizontal row of pill/chip TextViews for a single-choice preference.
     *
     * @param row          target container (already cleared by the caller)
     * @param ids          pref values for each chip
     * @param labels       display labels for each chip
     * @param prefKey      SharedPrefs key to read/write
     * @param currentValue currently saved value
     * @param dp           screen density
     */
    private void setupChipRow(LinearLayout row,
                               String[] ids, String[] labels,
                               String prefKey, String currentValue, float dp) {
        final TextView[] chips = new TextView[ids.length];
        for (int i = 0; i < ids.length; i++) {
            final String val = ids[i];
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding((int)(18*dp), (int)(9*dp), (int)(18*dp), (int)(9*dp));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int)(10 * dp));
            chip.setLayoutParams(lp);
            applyChipState(chip, val.equals(currentValue), dp);
            chips[i] = chip;

            final String[] fIds = ids;
            final TextView[] fChips = chips;
            chip.setOnClickListener(v -> {
                prefs.edit().putString(prefKey, val).apply();
                for (int j = 0; j < fChips.length; j++) {
                    applyChipState(fChips[j], fIds[j].equals(val), dp);
                }
            });
            row.addView(chip);
        }
    }

    private void applyChipState(TextView chip, boolean selected, float dp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(20 * dp);
        if (selected) {
            bg.setColor(0xFF9A81FF);
            chip.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0xFF2D2938);
            bg.setStroke((int) dp, 0xFF9A81FF);
            chip.setTextColor(0xFFC8C2D8);
        }
        chip.setBackground(bg);
    }

    /** Generic vertical item container (LinearLayout WRAP/WRAP, centred, with end margin). */
    private LinearLayout makeItemContainer(float dp, int marginEndDp) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClickable(true);
        item.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int)(marginEndDp * dp));
        item.setLayoutParams(lp);
        return item;
    }

    /** FrameLayout used as the selection-ring outer container. */
    private FrameLayout makeRingFrame(int outerSizePx, int paddingPx) {
        FrameLayout f = new FrameLayout(this);
        // Use LinearLayout.LayoutParams because the ring is added to a LinearLayout (item).
        f.setLayoutParams(new LinearLayout.LayoutParams(outerSizePx, outerSizePx));
        f.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
        return f;
    }

    /** Accent-coloured ring border drawable (transparent fill). */
    private GradientDrawable makeRingDrawable(float cornerRadius, float dp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(cornerRadius);
        d.setStroke((int)(2.5f * dp), 0xFF9A81FF);
        d.setColor(Color.TRANSPARENT);
        return d;
    }

    /** Small centred label TextView placed below a swatch. */
    private TextView makeLabel(String text, float dp) {
        TextView tv = new TextView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int)(5 * dp);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextColor(0xFFC8C2D8);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private GradientDrawable solidRounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable gradientRounded(int start, int end, float radius) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        d.setCornerRadius(radius);
        return d;
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }
}
