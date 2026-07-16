package com.duoshield.app.util;

import android.content.Context;
import com.duoshield.app.util.FirebaseCostGuard;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.HashMap;
import java.util.Map;

public class ConversationMetaUpdater {

    private static final String TAG = "ConversationMetaUpdater";

    public static void update(Context ctx, String convId, String senderUid,
                              String recipientUid, String preview) {
        if (convId == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("lastMessage", preview != null ? preview : "");
        data.put("lastMessageTs", FieldValue.serverTimestamp());
        data.put("lastSenderId",  senderUid);
        if (recipientUid != null) {
            data.put("unread_" + recipientUid, FieldValue.increment(1));
        }
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener(v -> FirebaseCostGuard.getInstance(ctx).recordWrites(1));
    }
}
