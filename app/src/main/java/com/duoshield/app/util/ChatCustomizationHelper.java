package com.duoshield.app.util;

import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;

/**
 * Central helper for per-user chat bubble and font-size customization.
 *
 * <p>All settings are stored in {@code duoshield_prefs}. Changing any pref
 * takes effect the next time the chat screen is (re-)resumed, which calls
 * {@link com.duoshield.app.ui.MessageAdapter#notifyBubbleStyleChanged()}.
 *
 * <h3>Prefs managed here</h3>
 * <ul>
 *   <li>{@link #PREF_BUBBLE_MINE_COLOR}   – ARGB int, default {@link #DEFAULT_MINE_COLOR}</li>
 *   <li>{@link #PREF_BUBBLE_THEIRS_COLOR} – ARGB int, default {@link #DEFAULT_THEIRS_COLOR}</li>
 *   <li>{@link #PREF_BUBBLE_STYLE}        – one of {@link #STYLE_ROUNDED}/{@link #STYLE_CLASSIC}/{@link #STYLE_SHARP}</li>
 *   <li>{@link #PREF_MSG_FONT_SIZE}       – one of {@link #FONT_SMALL}/{@link #FONT_MEDIUM}/{@link #FONT_LARGE}</li>
 * </ul>
 */
public final class ChatCustomizationHelper {

    // ── Pref keys ────────────────────────────────────────────────────────────
    public static final String PREF_BUBBLE_MINE_COLOR   = "bubble_mine_color";
    public static final String PREF_BUBBLE_THEIRS_COLOR = "bubble_theirs_color";
    public static final String PREF_BUBBLE_STYLE        = "bubble_style";
    public static final String PREF_MSG_FONT_SIZE       = "msg_font_size";

    // ── Bubble styles ────────────────────────────────────────────────────────
    /** Large radius (20 dp), subtle TL→BR gradient — the default modern look. */
    public static final String STYLE_ROUNDED = "rounded";
    /** Medium radius (10 dp), solid colour with hairline border. */
    public static final String STYLE_CLASSIC = "classic";
    /** Minimal radius (4 dp), flat solid — no gradient, no border. */
    public static final String STYLE_SHARP   = "sharp";

    // ── Font sizes ───────────────────────────────────────────────────────────
    public static final String FONT_SMALL  = "small";   // 13 sp
    public static final String FONT_MEDIUM = "medium";  // 15 sp  (default)
    public static final String FONT_LARGE  = "large";   // 17 sp

    // ── Default colours ──────────────────────────────────────────────────────
    public static final int DEFAULT_MINE_COLOR   = 0xFF2A2045;
    public static final int DEFAULT_THEIRS_COLOR = 0xFF24202E;

    // ── Mine bubble colour presets (10) ──────────────────────────────────────
    public static final int[] MINE_COLORS = {
        0xFF2A2045,  // Purple   (default)
        0xFF1A2845,  // Blue
        0xFF0A2832,  // Teal
        0xFF0D2618,  // Emerald
        0xFF2A1020,  // Rose
        0xFF1A1850,  // Indigo
        0xFF2A0C0C,  // Crimson
        0xFF261808,  // Amber
        0xFF241830,  // Mauve
        0xFF1C1C24,  // Graphite
    };

    public static final String[] MINE_COLOR_NAMES = {
        "Purple", "Blue", "Teal", "Emerald", "Rose",
        "Indigo", "Crimson", "Amber", "Mauve", "Graphite",
    };

    // ── Their bubble colour presets (6) ──────────────────────────────────────
    public static final int[] THEIRS_COLORS = {
        0xFF24202E,  // Default
        0xFF1A1822,  // Midnight
        0xFF1A1E2C,  // Steel
        0xFF141E1A,  // Forest
        0xFF201E1A,  // Warm
        0xFF1C1C24,  // Charcoal
    };

    public static final String[] THEIRS_COLOR_NAMES = {
        "Default", "Midnight", "Steel", "Forest", "Warm", "Charcoal",
    };

    private ChatCustomizationHelper() {}

    // ── Accessors ────────────────────────────────────────────────────────────

    public static int getMineColor(SharedPreferences p) {
        return p.getInt(PREF_BUBBLE_MINE_COLOR, DEFAULT_MINE_COLOR);
    }

    public static int getTheirsColor(SharedPreferences p) {
        return p.getInt(PREF_BUBBLE_THEIRS_COLOR, DEFAULT_THEIRS_COLOR);
    }

    public static String getBubbleStyle(SharedPreferences p) {
        return p.getString(PREF_BUBBLE_STYLE, STYLE_ROUNDED);
    }

    public static float getMsgFontSizeSp(SharedPreferences p) {
        switch (p.getString(PREF_MSG_FONT_SIZE, FONT_MEDIUM)) {
            case FONT_SMALL: return 13f;
            case FONT_LARGE: return 17f;
            default:         return 15f;
        }
    }

    /**
     * Font size (sp) for compact metadata: timestamps and voice-note duration.
     * Scales proportionally with the user's chosen message font size.
     */
    public static float getMetaFontSizeSp(SharedPreferences p) {
        switch (p.getString(PREF_MSG_FONT_SIZE, FONT_MEDIUM)) {
            case FONT_SMALL: return 9.5f;
            case FONT_LARGE: return 12f;
            default:         return 10.5f;
        }
    }

    /**
     * Font size (sp) for media/voice captions (photo, video, album).
     * Scales proportionally with the user's chosen message font size.
     */
    public static float getCaptionFontSizeSp(SharedPreferences p) {
        switch (p.getString(PREF_MSG_FONT_SIZE, FONT_MEDIUM)) {
            case FONT_SMALL: return 12.5f;
            case FONT_LARGE: return 16.5f;
            default:         return 14.5f;
        }
    }

    // ── Drawable builders ────────────────────────────────────────────────────

    /**
     * Build a ready-to-use bubble {@link GradientDrawable} from stored prefs.
     *
     * @param isMine  {@code true} for sender/mine bubbles.
     * @param prefs   {@code duoshield_prefs} SharedPreferences instance.
     * @param density {@code context.getResources().getDisplayMetrics().density}
     */
    public static GradientDrawable buildBubble(boolean isMine, SharedPreferences prefs,
                                               float density) {
        return buildBubble(isMine, prefs, density, true);
    }

    /**
     * Variant that lets the caller drop the bubble "tail" for grouped continuation
     * messages (WhatsApp/Telegram style): only the first bubble of a same-sender
     * cluster keeps its tail; the rest are fully rounded on that corner.
     */
    public static GradientDrawable buildBubble(boolean isMine, SharedPreferences prefs,
                                               float density, boolean showTail) {
        int    color = isMine ? getMineColor(prefs) : getTheirsColor(prefs);
        String style = getBubbleStyle(prefs);
        return buildBubbleDrawable(color, style, isMine, density, showTail);
    }

    /**
     * Build a bubble drawable from explicit parameters.
     * Useful for live previews in the settings screen.
     */
    public static GradientDrawable buildBubbleDrawable(int color, String style,
                                                       boolean isMine, float dp) {
        return buildBubbleDrawable(color, style, isMine, dp, true);
    }

    public static GradientDrawable buildBubbleDrawable(int color, String style,
                                                       boolean isMine, float dp,
                                                       boolean showTail) {
        // Corner radii array: [TL-x,TL-y, TR-x,TR-y, BR-x,BR-y, BL-x,BL-y]
        float[] radii;
        switch (style) {
            case STYLE_SHARP: {
                float r = 4 * dp;
                radii = new float[]{r,r, r,r, r,r, r,r};
                break;
            }
            case STYLE_CLASSIC: {
                float big = 10 * dp, sm = 4 * dp;
                // Mine: tail at top-right → TL big, TR small
                // Theirs: tail at top-left → TL small, TR big
                float tail = showTail ? sm : big;
                radii = isMine
                        ? new float[]{big,big, tail,tail, big,big, big,big}
                        : new float[]{tail,tail, big,big, big,big, big,big};
                break;
            }
            default: { // STYLE_ROUNDED
                float big = 20 * dp, sm = 5 * dp;
                float tail = showTail ? sm : big;
                radii = isMine
                        ? new float[]{big,big, tail,tail, big,big, big,big}
                        : new float[]{tail,tail, big,big, big,big, big,big};
            }
        }

        GradientDrawable d;
        if (STYLE_ROUNDED.equals(style)) {
            // Subtle two-stop gradient for depth
            d = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{color, darken(color, 0.72f)});
        } else {
            d = new GradientDrawable();
            d.setColor(color);
        }
        d.setCornerRadii(radii);

        // Translucent border on rounded/classic; none on sharp
        if (!STYLE_SHARP.equals(style)) {
            d.setStroke((int) dp, 0x22FFFFFF);
        }

        return d;
    }

    /**
     * Build a small circular swatch drawable for the colour picker UI.
     *
     * @param color   swatch fill colour (ARGB int)
     * @param density screen density
     */
    public static GradientDrawable buildSwatchCircle(int color, float density) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke((int) density, 0x33FFFFFF);
        return d;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static int darken(int color, float f) {
        int r = Math.min(255, (int)(((color >> 16) & 0xFF) * f));
        int g = Math.min(255, (int)(((color >>  8) & 0xFF) * f));
        int b = Math.min(255, (int)( (color        & 0xFF) * f));
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
}
