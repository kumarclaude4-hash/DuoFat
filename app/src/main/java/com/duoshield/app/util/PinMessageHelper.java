package com.duoshield.app.util;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class PinMessageHelper {
    public static void pin(String convId, String msgId, String preview) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("id",      msgId);
        entry.put("preview", preview);
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .update("pinnedMessages", FieldValue.arrayUnion(entry))
            .addOnFailureListener(e -> {
                Map<String, Object> init = new HashMap<>();
                init.put("pinnedMessages", Arrays.asList(entry));
                FirebaseFirestore.getInstance()
                    .collection("chats").document(convId)
                    .set(init, SetOptions.merge());
            });
    }

    public static void unpin(String convId, Map<String, Object> entry) {
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .update("pinnedMessages", FieldValue.arrayRemove(entry));
    }
}
