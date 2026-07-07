package com.duoshield.app.notifications;

import android.content.Context;
import android.content.SharedPreferences;

public class NotificationHelper {

    public static final String CHANNEL_ID = NotificationStyler.CH_MESSAGES;

    public static void createChannel(Context ctx) {
        NotificationStyler.createChannels(ctx);
    }

    /**
     * Shows a notification for an incoming message.
     *
     * @param chatId     Firestore chat document ID from the FCM payload; used to open
     *                   the correct conversation when the notification is tapped.
     *                   Falls back to the SharedPreferences conversation_id when null.
     * @param partnerUid UID of the sender (from FCM payload "senderUid"); used to pass
     *                   partner_uid to ChatMediaActivity so it can load the right chat.
     *                   Falls back to the SharedPreferences partner_uid when null.
     */
    public static void showNotification(Context ctx, String title, String body,
                                        String chatId, String partnerUid) {
        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String myUid  = prefs.getString("my_uid", "");

        // Prefer the chatId from the FCM payload; fall back to the last-used conversation.
        String convId = (chatId != null && !chatId.isEmpty())
                ? chatId : prefs.getString("conversation_id", "");

        // Prefer the partner from the FCM payload; fall back to prefs.
        String partner = (partnerUid != null && !partnerUid.isEmpty())
                ? partnerUid : prefs.getString("partner_uid", "");

        int count = prefs.getInt("badge_count", 0) + 1;
        prefs.edit().putInt("badge_count", count).apply();
        NotificationStyler.showMessage(ctx, title, body, convId, partner, myUid, count);
    }

    /** Legacy overload — used by callers that don't have FCM payload data. */
    public static void showNotification(Context ctx, String title, String body) {
        showNotification(ctx, title, body, null, null);
    }
}
