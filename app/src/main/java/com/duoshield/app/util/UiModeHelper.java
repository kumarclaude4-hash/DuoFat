package com.duoshield.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.duoshield.app.R;

/**
 * The "Sanctuary Mode" premium/classic UI toggle has been removed — the app
 * now always uses the single Classic design. This class is kept only so
 * existing {@code applyTheme(this)} call sites at the top of every
 * Activity.onCreate() don't need to be touched one-by-one.
 */
public class UiModeHelper {

    private UiModeHelper() {}

    /** Always false now — Sanctuary Mode has been removed. */
    public static boolean isSanctuary(Context ctx) {
        return false;
    }

    /**
     * Applies the app's single theme.
     * Must be called as the VERY FIRST statement in Activity.onCreate(),
     * before super.onCreate(), so that AppCompatActivity picks up the theme.
     */
    public static void applyTheme(Activity activity) {
        activity.setTheme(R.style.Theme_DuoShield_Classic);
    }
}
