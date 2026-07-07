package com.duoshield.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.duoshield.app.R;

/**
 * Manages the Sanctuary / Classic UI mode toggle.
 *
 * Mode is persisted in "duoshield_prefs" under key "ui_mode".
 * Default is "sanctuary" (premium design system).
 *
 * Usage pattern in Activity.onCreate():
 *   UiModeHelper.applyTheme(this);   // ← FIRST line, before super.onCreate()
 *   super.onCreate(savedInstanceState);
 */
public class UiModeHelper {

    public static final String KEY_UI_MODE    = "ui_mode";
    public static final String MODE_SANCTUARY = "sanctuary";
    public static final String MODE_CLASSIC   = "classic";

    private static final String PREFS_NAME = "duoshield_prefs";

    private UiModeHelper() {}

    /** Returns true if Sanctuary (premium) mode is active (the default). */
    public static boolean isSanctuary(Context ctx) {
        SharedPreferences prefs =
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return !MODE_CLASSIC.equals(prefs.getString(KEY_UI_MODE, MODE_SANCTUARY));
    }

    /** Persists the selected mode to SharedPreferences. */
    public static void setMode(Context ctx, String mode) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
           .edit()
           .putString(KEY_UI_MODE, mode)
           .apply();
    }

    /**
     * Applies the correct theme to the activity.
     * Must be called as the VERY FIRST statement in Activity.onCreate(),
     * before super.onCreate(), so that AppCompatActivity picks up the theme.
     *
     * Only call this on activities that participate in the Sanctuary/Classic toggle
     * (chat screens, conversation list, settings). Do NOT call on activities that
     * already have a special manifest theme (FullScreen, Splash).
     */
    public static void applyTheme(Activity activity) {
        if (isSanctuary(activity)) {
            activity.setTheme(R.style.Theme_DuoShield_Sanctuary);
        } else {
            activity.setTheme(R.style.Theme_DuoShield_Classic);
        }
    }
}
