package com.duoshield.app.util;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;
import java.util.ArrayList;
import java.util.List;

public class ReadReceiptHelper {

    public static void markAllRead(String convId, String myUid) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        // F-15 fix: the previous query used whereNotEqualTo("status", "read").
        // Firestore's != operator *excludes* documents where the queried field does
        // not exist at all — so messages created before the 'status' field was added
        // (schema v6 → v7 migration) were permanently skipped and never marked read.
        //
        // Fix: run two explicit equality queries ('sent' and 'delivered') and union
        // their results into a single write batch. This covers all non-read states
        // that have the field set, plus avoids the Firestore limitation of only one
        // inequality filter per query.
        com.google.android.gms.tasks.Task<QuerySnapshot> sentTask =
                db.collection("chats").document(convId)
                  .collection("messages")
                  .whereEqualTo("status", "sent")
                  .get();

        com.google.android.gms.tasks.Task<QuerySnapshot> deliveredTask =
                db.collection("chats").document(convId)
                  .collection("messages")
                  .whereEqualTo("status", "delivered")
                  .get();

        com.google.android.gms.tasks.Tasks.whenAllSuccess(sentTask, deliveredTask)
                .addOnSuccessListener(results -> {
                    List<DocumentSnapshot> docs = new ArrayList<>();
                    for (Object r : results) {
                        QuerySnapshot qs = (QuerySnapshot) r;
                        docs.addAll(qs.getDocuments());
                    }
                    if (docs.isEmpty()) return;

                    WriteBatch batch = db.batch();
                    int count = 0;
                    for (DocumentSnapshot doc : docs) {
                        String sender = doc.getString("sender");
                        if (myUid.equals(sender)) continue;
                        batch.update(doc.getReference(), "status", "read");
                        if (++count == 450) {
                            batch.commit();
                            batch = db.batch();
                            count = 0;
                        }
                    }
                    if (count > 0) batch.commit();
                });

        db.collection("chats").document(convId)
          .update("unread_" + myUid, 0L);
    }

    public static void markMessageRead(String convId, String messageId) {
        FirebaseFirestore.getInstance()
            .collection("chats").document(convId)
            .collection("messages").document(messageId)
            .update("status", "read");
    }
}
