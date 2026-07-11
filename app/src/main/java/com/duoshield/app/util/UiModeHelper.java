package com.duoshield.app.util;

import android.app.Activity;
import android.content.Context;

import com.duoshield.app.R;

public class UiModeHelper {

    private UiModeHelper() {}

    /**
     * Applies the app's single theme.
     * Must be called as the VERY FIRST statement in Activity.onCreate(),
     * before super.onCreate(), so that AppCompatActivity picks up the theme.
     */
    public static void applyTheme(Activity activity) {
        activity.setTheme(R.style.Theme_DuoShield_Classic);
    }
}
