package com.duoshield.app.util;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.HashMap;
import java.util.Map;

public class MuteHelper {
    public static void mute(String convId, String myUid, boolean muted) {
        if (convId == null) return;
        Map<String, Object> d = new HashMap<>();
        d.put("muted_" + myUid, muted);
        FirebaseFirestore.getInstance().collection("chats").document(convId)
            .set(d, SetOptions.merge());
    }
}
