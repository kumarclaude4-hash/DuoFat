package com.duoshield.app.db;

import android.content.SharedPreferences;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.duoshield.app.util.B2StorageHelper;
import com.duoshield.app.util.FirebaseCostGuard;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.List;

/**
 * Periodic WorkManager job that deletes expired messages from both the local
 * Room database and the Firestore conversation sub-collection.
 *
 * <p>Deletion is driven by per-message expiry: {@code message.expiresAt} — an absolute
 * epoch-millisecond deadline written at send time by
 * {@code ChatMediaActivity.sendMessage()} and {@code MessageBuilder}.
 * A value of {@code 0} means "never expire".
 *
 * <p>Runs every 15 minutes while any conversation has disappearing messages enabled
 * (scheduled by {@link com.duoshield.app.util.SelfDestructScheduler}).
 *
 * <p>F43 fix: A legacy global-TTL mechanism has been removed. It was dead code
 * whose controlling preference was never written by any live UI path, and it posed
 * a risk of silently deleting messages that should not have been deleted.
 */
public class SelfDestructWorker extends Worker {

    private static final String TAG         = "SelfDestructWorker";
    private static final int    BATCH_LIMIT = 100;

    public SelfDestructWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);

        String conversationId = prefs.getString("conversation_id", null);
        if (conversationId == null) {
            Log.d(TAG, "No conversation — nothing to delete.");
            return Result.success();
        }

        long now = System.currentTimeMillis();

        try {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());

            // Delete Room rows whose expiresAt deadline has passed.
            // expiresAt is an absolute timestamp set at send time; 0 means "never".
            db.messageDao().deleteExpired(now);
            Log.d(TAG, "Room: deleted messages with expiresAt ≤ " + now);

            // Mirror the deletion in Firestore so both sides stay in sync.
            deleteExpiredFromFirestore(conversationId, now);

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "SelfDestructWorker failed — will retry", e);
            return Result.retry();
        }
    }

    /**
     * Batch-deletes Firestore messages whose {@code expiresAt} is non-zero
     * and earlier than {@code now}.
     *
     * <p>A 30-second timeout is applied to each {@code Tasks.await()} call so
     * that a slow or offline network cannot block the WorkManager thread pool
     * indefinitely (BUG-T05).
     */
    private void deleteExpiredFromFirestore(String conversationId, long now) throws Exception {
        FirebaseFirestore fs = FirebaseFirestore.getInstance();
        QuerySnapshot snap = Tasks.await(
                fs.collection("chats").document(conversationId)
                  .collection("messages")
                  .whereGreaterThan("expiresAt", 0L)
                  .whereLessThan("expiresAt", now)
                  .get(),
                30, java.util.concurrent.TimeUnit.SECONDS
        );
        FirebaseCostGuard.getInstance(getApplicationContext()).recordReads(snap.size());
        commitBatchDelete(fs, snap, "expiresAt-expired");
    }

    private void commitBatchDelete(FirebaseFirestore fs,
                                   QuerySnapshot snap,
                                   String label) throws Exception {
        List<DocumentSnapshot> docs = snap.getDocuments();
        if (docs.isEmpty()) {
            Log.d(TAG, "Firestore [" + label + "]: no messages to delete.");
            return;
        }

        int total = 0, batchCount = 0;
        WriteBatch batch = fs.batch();

        for (DocumentSnapshot doc : docs) {
            // Delete associated B2 media blob before removing the Firestore doc
            // so the encrypted file does not linger after expiry (BUG-SD01).
            // F10 fix: guard with isOwnedB2Path() so an attacker-crafted "path" value
            // pointing at another conversation's media cannot trigger arbitrary deletions.
            String mediaPath = doc.getString("path");
            if (B2StorageHelper.isOwnedB2Path(mediaPath, doc.getReference()
                    .getParent().getParent().getId())) {
                try {
                    B2StorageHelper.deleteFile(mediaPath);
                } catch (Exception e) {
                    Log.w(TAG, "B2 delete failed for path=" + mediaPath
                            + " — Firestore doc will still be deleted.", e);
                }
            } else if (mediaPath != null && !mediaPath.isEmpty()) {
                Log.w(TAG, "F10: refusing to delete B2 path '" + mediaPath
                        + "' — not owned by this conversation.");
            }

            batch.delete(doc.getReference());
            batchCount++;
            total++;
            if (batchCount == BATCH_LIMIT) {
                Tasks.await(batch.commit(), 30, java.util.concurrent.TimeUnit.SECONDS);
                Log.d(TAG, "Firestore [" + label + "]: committed batch of " + batchCount);
                batch = fs.batch();
                batchCount = 0;
            }
        }
        if (batchCount > 0) Tasks.await(batch.commit(), 30, java.util.concurrent.TimeUnit.SECONDS);
        FirebaseCostGuard.getInstance(getApplicationContext()).recordDeletes(total);
        Log.d(TAG, "Firestore [" + label + "]: deleted " + total + " message(s).");
    }
}
