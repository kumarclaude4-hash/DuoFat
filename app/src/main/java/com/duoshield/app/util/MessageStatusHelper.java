package com.duoshield.app.util;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import com.duoshield.app.R;
import com.duoshield.app.models.Message;

public class MessageStatusHelper {

    // Brand Lavender accent — matches the app accent color (#9A81FF)
    private static final int COLOR_READ      = 0xFF9A81FF;
    // Muted blue-grey for delivered (✓✓ grey)
    private static final int COLOR_DELIVERED = 0xFFC8C2D8;
    // Light grey for sent (✓)
    private static final int COLOR_SENT      = 0xFF9A8FB0;

    public static void bind(ImageView tick, Message msg, String myUid) {
        if (tick == null || msg == null || myUid == null) return;
        // Only show ticks on messages the local user sent
        if (!myUid.equals(msg.getSender())) {
            tick.setVisibility(View.GONE);
            return;
        }
        String status = msg.getStatus();
        if ("uploading".equals(status)) {
            // The voice bubble is already visible, but it is not a delivered message yet.
            tick.setVisibility(View.GONE);
            return;
        }
        tick.setVisibility(View.VISIBLE);
        if ("read".equals(status)) {
            // ✓✓ cyan — partner opened the chat and saw the message
            tick.setImageResource(R.drawable.ic_done_all);
            tick.setImageTintList(ColorStateList.valueOf(COLOR_READ));
        } else if ("delivered".equals(status) || msg.isDelivered()) {
            // ✓✓ grey — partner device received it via FCM
            tick.setImageResource(R.drawable.ic_done_all);
            tick.setImageTintList(ColorStateList.valueOf(COLOR_DELIVERED));
        } else if ("failed".equals(status)) {
            // ! red — message failed to send
            tick.setImageResource(android.R.drawable.stat_notify_error);
            tick.setImageTintList(ColorStateList.valueOf(0xFFD96A7C));
        } else {
            // ✓ light grey — sent to Firestore, partner not yet received ("sent", "pending", null)
            tick.setImageResource(R.drawable.ic_done);
            tick.setImageTintList(ColorStateList.valueOf(COLOR_SENT));
        }
    }
}
