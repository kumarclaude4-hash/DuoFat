package com.duoshield.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import com.duoshield.app.R;

/**
 * Central utility for applying per-chat background themes.
 *
 * <p>The selected theme is stored as a string in {@code duoshield_prefs} under the key
 * {@link #PREF_KEY}. ChatMediaActivity and GroupChatActivity both call
 * {@link #apply(View, SharedPreferences)} after the RecyclerView is ready.
 *
 * <p>Theme IDs are the {@code THEME_*} constants below. Adding a new theme requires:
 * <ol>
 *   <li>A gradient drawable named {@code bg_chat_<id>.xml} in res/drawable.</li>
 *   <li>A new {@code THEME_*} constant here.</li>
 *   <li>A new {@code case} in {@link #apply} and {@link #drawableRes}.</li>
 *   <li>A new entry in the themes array in AppearanceNotificationsSettingsActivity.</li>
 * </ol>
 */
public final class ChatThemeHelper {

    /** SharedPrefs key for the active chat background theme. */
    public static final String PREF_KEY = "chat_theme";

    /** Theme IDs ─ must match the values set in AppearanceNotificationsSettingsActivity. */
    public static final String THEME_DEFAULT  = "default";   // uses the layout's @color/background
    public static final String THEME_MIDNIGHT = "midnight";  // deep space indigo
    public static final String THEME_OCEAN    = "ocean";     // abyssal navy
    public static final String THEME_FOREST   = "forest";    // ancient dark forest
    public static final String THEME_DUSK     = "dusk";      // velvet rose-noir
    public static final String THEME_STEEL    = "steel";     // cold blue-steel
    public static final String THEME_ASH      = "ash";       // carbon near-black
    public static final String THEME_NOIR     = "noir";      // pure black
    public static final String THEME_AURORA   = "aurora";    // deep arctic teal
    public static final String THEME_EMBER    = "ember";     // warm amber charcoal
    public static final String THEME_LAVENDER = "lavender";  // deep violet mist
    public static final String THEME_SLATE    = "slate";     // cool blue-grey

    private ChatThemeHelper() {}

    /**
     * Apply the currently selected theme to {@code chatBackground}.
     * Must be called on the main thread. Safe to call multiple times (e.g. onResume).
     *
     * <p>The chat theme is applied <em>on top of</em> any color wallpaper stored under
     * {@code wallpaper_type}: if a theme other than "default" is chosen, it takes
     * precedence and the old color-wallpaper dialog is ignored.
     *
     * @param chatBackground the RecyclerView (or root view) whose background should be painted.
     * @param prefs          {@code duoshield_prefs} SharedPreferences instance.
     */
    public static void apply(View chatBackground, SharedPreferences prefs) {
        if (chatBackground == null || prefs == null) return;
        String theme = prefs.getString(PREF_KEY, THEME_DEFAULT);
        switch (theme) {
            case THEME_MIDNIGHT: chatBackground.setBackgroundResource(R.drawable.bg_chat_midnight); break;
            case THEME_OCEAN:    chatBackground.setBackgroundResource(R.drawable.bg_chat_ocean);    break;
            case THEME_FOREST:   chatBackground.setBackgroundResource(R.drawable.bg_chat_forest);   break;
            case THEME_DUSK:     chatBackground.setBackgroundResource(R.drawable.bg_chat_dusk);     break;
            case THEME_STEEL:    chatBackground.setBackgroundResource(R.drawable.bg_chat_steel);    break;
            case THEME_ASH:      chatBackground.setBackgroundResource(R.drawable.bg_chat_ash);      break;
            case THEME_NOIR:     chatBackground.setBackgroundResource(R.drawable.bg_chat_noir);     break;
            case THEME_AURORA:   chatBackground.setBackgroundResource(R.drawable.bg_chat_aurora);   break;
            case THEME_EMBER:    chatBackground.setBackgroundResource(R.drawable.bg_chat_ember);    break;
            case THEME_LAVENDER: chatBackground.setBackgroundResource(R.drawable.bg_chat_lavender); break;
            case THEME_SLATE:    chatBackground.setBackgroundResource(R.drawable.bg_chat_slate);    break;
            default:             chatBackground.setBackground(null);                                break;
        }
    }

    /** Returns the drawable resource id for a given theme id, or 0 for default. */
    public static int drawableRes(String themeId) {
        switch (themeId) {
            case THEME_MIDNIGHT: return R.drawable.bg_chat_midnight;
            case THEME_OCEAN:    return R.drawable.bg_chat_ocean;
            case THEME_FOREST:   return R.drawable.bg_chat_forest;
            case THEME_DUSK:     return R.drawable.bg_chat_dusk;
            case THEME_STEEL:    return R.drawable.bg_chat_steel;
            case THEME_ASH:      return R.drawable.bg_chat_ash;
            case THEME_NOIR:     return R.drawable.bg_chat_noir;
            case THEME_AURORA:   return R.drawable.bg_chat_aurora;
            case THEME_EMBER:    return R.drawable.bg_chat_ember;
            case THEME_LAVENDER: return R.drawable.bg_chat_lavender;
            case THEME_SLATE:    return R.drawable.bg_chat_slate;
            default:             return R.drawable.bg_chat_default_solid;
        }
    }
}
