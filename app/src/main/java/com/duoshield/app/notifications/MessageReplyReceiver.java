package com.duoshield.app.notifications;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import com.duoshield.app.MainActivity;
import com.duoshield.app.R;
import com.duoshield.app.util.MessageBuilder;
/**
 * Sends an inline-reply notification action using Signal Protocol encryption.
 * notification reply. In a killed-state reply scenario the key may not be in
 * memory, and MessageBuilder falls back to plaintext (isEncrypted = false).
 *
 * If the key is unavailable, we show a failure notification prompting the
 * user to open the app to reply securely instead of sending in plaintext.
 */
public class MessageReplyReceiver extends BroadcastReceiver {

    public static final String KEY_REPLY_TEXT    = "reply_text";
    public static final String EXTRA_CONV_ID     = "conv_id";
    public static final String EXTRA_MY_UID      = "my_uid";
    /** F13 fix: partnerUid is now explicitly included in the reply PendingIntent. */
    public static final String EXTRA_PARTNER_UID = "partner_uid";

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
        if (remoteInput == null) return;

        CharSequence replyText = remoteInput.getCharSequence(KEY_REPLY_TEXT);
        if (replyText == null || replyText.toString().trim().isEmpty()) return;

        String convId = intent.getStringExtra(EXTRA_CONV_ID);
        String myUid  = intent.getStringExtra(EXTRA_MY_UID);
        if (convId == null || myUid == null) return;

        // F13 fix: prefer the partnerUid baked into the intent; fall back to SharedPrefs only
        // when the intent was created before this fix was applied (legacy notifications).
        String partnerUid = intent.getStringExtra(EXTRA_PARTNER_UID);
        if (partnerUid == null) {
            SharedPreferences prefs =
                    context.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
            // null default — empty string would be passed to MessageBuilder as a valid address
            partnerUid = prefs.getString("partner_uid", null);
        }

        // If Signal keys aren't loaded (app killed), we cannot encrypt the reply.
        // Show a notification prompting the user to open the app instead of silently
        // dropping the message (showOpenAppNotification was previously dead code).
        if (partnerUid == null || !com.duoshield.app.crypto.signal.SignalKeyManager.isInitialized(context)) {
            showOpenAppNotification(context);
            return;
        }

        MessageBuilder.sendTextMessage(context, convId, myUid, partnerUid,
            replyText.toString().trim(), null, null);

        NotificationManagerCompat.from(context).cancel(MarkReadReceiver.NOTIF_ID);
    }

    /**
     * Shows a notification telling the user to open the app to reply securely.
     * This replaces the inline-reply action so they know the reply was NOT sent.
     */
    private void showOpenAppNotification(Context context) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle("Reply not sent")
            .setContentText("Open DuoShield to reply securely — encryption key not loaded.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi);

        // API 33+ requires POST_NOTIFICATIONS at runtime; a receiver can't prompt for it,
        // so just skip showing the notification if it was never granted (or was revoked).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context)
                .notify(MarkReadReceiver.NOTIF_ID + 1, builder.build());
        }
    }
}
