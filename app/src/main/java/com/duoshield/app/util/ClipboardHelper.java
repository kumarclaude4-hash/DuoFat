package com.duoshield.app.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

public class ClipboardHelper {
    private static final long CLEAR_DELAY = 90_000L;

    /**
     * Copies {@code text} to the clipboard and schedules a clear after 90 seconds.
     *
     * <p>Returns a {@link Runnable} that the caller MUST post to
     * {@code Handler.removeCallbacks()} when the Activity pauses, so the deferred
     * clear still fires but the callback does not reference a dead Activity context
     * (BUG-CL01).  Typical usage:
     * <pre>
     *   private Runnable clipboardClear;
     *
     *   void onCopy(String text) {
     *       if (clipboardClear != null) clearHandler.removeCallbacks(clipboardClear);
     *       clipboardClear = ClipboardHelper.copy(this, text);
     *   }
     *
     *   &#64;Override protected void onPause() {
     *       super.onPause();
     *       if (clipboardClear != null) {
     *           clearHandler.removeCallbacks(clipboardClear);
     *           clipboardClear.run(); // clear immediately on background
     *       }
     *   }
     * </pre>
     *
     * @return the scheduled clear runnable — callers should cancel it on {@code onPause()}.
     */
    public static Runnable copy(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return () -> {};
        ClipData clip = ClipData.newPlainText("message", text);
        cm.setPrimaryClip(clip);
        Runnable clear = () -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip();
            } else if (cm.hasPrimaryClip()) {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        };
        new Handler(Looper.getMainLooper()).postDelayed(clear, CLEAR_DELAY);
        return clear;
    }
}
