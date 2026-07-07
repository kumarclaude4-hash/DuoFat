package com.duoshield.app.notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fired when the user swipes a message notification away without tapping it.
 *
 * <p>Deliberately does NOT mark messages as read — the user dismissed the
 * notification without opening the chat, so the sender should NOT receive
 * blue ticks. Only the local badge count is cleared.
 */
public class NotificationDismissReceiver extends BroadcastReceiver {

    public static final String EXTRA_CONV_ID = "conv_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Just clear the badge for this conversation — no read receipt.
        String convId = intent.getStringExtra(EXTRA_CONV_ID);
        if (convId != null) {
            // Decrement badge count for this conversation only.
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
            int current = prefs.getInt("badge_count", 0);
            if (current > 0) {
                prefs.edit().putInt("badge_count", current - 1).apply();
            }
        }
    }
}
