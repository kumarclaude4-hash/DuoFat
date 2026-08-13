package com.duoshield.app.util;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

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
        markSensitive(clip);
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

    /**
     * Flags {@code clip} as sensitive via {@link ClipDescription#EXTRA_IS_SENSITIVE}
     * on API 33+ (Android 13), so the system suppresses the "text copied" content
     * preview toast and clipboard-history/cloud-sync apps treat the value as
     * sensitive (S08-L2). This is metadata on the {@link ClipDescription} only — it
     * does not change what gets written or who can read the live primary clip, so
     * call sites that schedule their own clear (e.g. {@link #copy}) must still do so;
     * this does not replace that.
     *
     * <p>Every call site in this app that puts a message, key fingerprint, Account
     * ID, or other non-public value on the clipboard MUST call this before
     * {@code ClipboardManager.setPrimaryClip(clip)}.
     */
    public static void markSensitive(ClipData clip) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
    }
}
