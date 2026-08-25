package com.duoshield.app.util;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Central policy for decorative, purely-cosmetic animations: the matrix rain, orbiting-key lock,
 * signal pulses, decrypt grid, cipher/typing dots and fingerprint scan.
 *
 * <p>Every one of those views runs a continuous per-frame redraw loop that, until now, ignored
 * {@link DevicePerformanceTier}. On a budget SoC such as the MediaTek Helio P35 (Poco C51 —
 * in-order Cortex-A53 cores, PowerVR GE8320 GPU) those uncapped loops are the single biggest
 * source of jank, heat and battery drain, and they sit on exactly the screens the user meets
 * first: splash, lock, pairing and restore.
 *
 * <p>The rest of the app (Glide config, RecyclerView cache/prefetch, call quality ladder) already
 * consults the tier. This class extends the same discipline to the animation layer.
 *
 * <p>Policy:
 * <ul>
 *   <li><b>LOW</b> tier &rarr; cap decorative redraws to ~20&nbsp;fps, and fully disable the
 *       heaviest full-screen effect (matrix rain).</li>
 *   <li><b>MID / HIGH</b> &rarr; full frame rate.</li>
 *   <li><b>OS "remove animations"</b> (Animator duration scale == 0, i.e. the accessibility /
 *       battery-saver reduce-motion setting) &rarr; render a single static frame on every tier.</li>
 * </ul>
 *
 * <p>{@link DevicePerformanceTier#effectiveTier(Context)} already demotes a thermally throttled
 * device one step, so a hot MID phone is automatically treated as LOW here without extra work.
 *
 * <p>All queries are cheap: the tier is resolved once and cached, and the frame-pacing gate is a
 * single monotonic-clock read with no allocation, safe to call from an animation update listener.
 */
public final class MotionBudget {

    private MotionBudget() {}

    /** ~20&nbsp;fps cap on LOW-tier devices. {@code 0} means "draw every frame" (no throttle). */
    private static final long LOW_FRAME_INTERVAL_MS = 50L;

    /**
     * True when the user has asked the OS to remove animations (Developer Options "Animator
     * duration scale = off", or the system battery-saver / reduce-motion path sets it to 0).
     * Honoured on every tier: if the user opted out of motion, decorative loops stay static.
     */
    public static boolean animationsDisabledBySystem(Context context) {
        if (context == null) return false;
        try {
            float scale = Settings.Global.getFloat(
                    context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale == 0f;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Minimum milliseconds between decorative redraws for this device. {@code 0} means unthrottled
     * (draw at the display refresh rate). Resolve this once when a view attaches, not per frame.
     */
    public static long frameIntervalMs(Context context) {
        if (context == null) return 0L;
        return DevicePerformanceTier.effectiveTier(context) == DevicePerformanceTier.LOW
                ? LOW_FRAME_INTERVAL_MS
                : 0L;
    }

    /**
     * True when the heaviest full-screen effect (the matrix rain) should not run at all: either
     * the device is LOW tier, or the user has disabled animations system-wide.
     */
    public static boolean disableHeavyDecoration(Context context) {
        return animationsDisabledBySystem(context)
                || (context != null
                    && DevicePerformanceTier.effectiveTier(context) == DevicePerformanceTier.LOW);
    }

    /**
     * True when a decorative view should render a single static frame instead of animating,
     * regardless of tier. Currently driven solely by the OS reduce-motion setting.
     */
    public static boolean staticOnly(Context context) {
        return animationsDisabledBySystem(context);
    }

    /**
     * Frame-pacing gate for {@code ValueAnimator}-driven views. Returns {@code true} when enough
     * time has elapsed since {@code lastFrameUptimeMs} to justify another {@code invalidate()} at
     * this device's budget. The animator keeps computing its value every tick; only the redraw
     * (the GPU-bound part) is throttled, so motion stays smooth in value while cheap in pixels.
     *
     * @param intervalMs        the budget from {@link #frameIntervalMs(Context)}, cached on attach
     * @param lastFrameUptimeMs {@link SystemClock#uptimeMillis()} of the last committed redraw
     */
    public static boolean shouldDrawFrame(long intervalMs, long lastFrameUptimeMs) {
        if (intervalMs <= 0L) return true;
        return SystemClock.uptimeMillis() - lastFrameUptimeMs >= intervalMs;
    }
}
