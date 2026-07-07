package com.duoshield.app.util;

import com.google.firebase.firestore.FirebaseFirestore;

public class UnreadCountHelper {
    public static void reset(String convId, String myUid) {
        if (convId == null || myUid == null) return;
        FirebaseFirestore.getInstance().collection("chats").document(convId)
            .update("unread_" + myUid, 0L);
    }
}
