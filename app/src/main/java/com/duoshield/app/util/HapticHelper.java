package com.duoshield.app.util;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

public class HapticHelper {

    public static void wrongPin(Context ctx)  { vibrate(ctx, new long[]{0, 80, 60, 80, 60, 80}); }
    public static void longPress(Context ctx) { vibrate(ctx, new long[]{0, 50}); }
    public static void send(Context ctx)      { vibrate(ctx, new long[]{0, 30}); }
    public static void reaction(Context ctx)  { vibrate(ctx, new long[]{0, 20, 30, 20}); }

    private static void vibrate(Context ctx, long[] pattern) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) vm.getDefaultVibrator()
                    .vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                    } else {
                        v.vibrate(pattern, -1);
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
