package com.duoshield.app.call;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Periodic WorkManager job that purges stale {@code calls/*} Firestore documents
 * older than 24 hours with status in {@code ["ringing","missed","timeout"]}.
 *
 * <p>Protects against clients that crash mid-call and never write a terminal status.
 * Scheduled once at app startup; safe to enqueue multiple times (unique tag).
 */
public class CallCleanupWorker extends Worker {

    private static final String TAG = "CallCleanupWorker";
    static final String WORK_TAG = "call_cleanup_periodic";

    private static final List<String> STALE_STATUSES =
            Arrays.asList("ringing", "missed", "timeout");
    private static final long STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L;

    public CallCleanupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            Log.d(TAG, "Not authenticated — skipping cleanup");
            return Result.success();
        }

        try {
            long cutoffMs = System.currentTimeMillis() - STALE_THRESHOLD_MS;
            com.google.firebase.firestore.Query query = FirebaseFirestore.getInstance()
                    .collection("calls")
                    .whereLessThan("createdAt", new Date(cutoffMs));

            com.google.firebase.firestore.QuerySnapshot snap =
                    Tasks.await(query.get(), 30, TimeUnit.SECONDS);

            int deleted = 0;
            for (QueryDocumentSnapshot doc : snap) {
                String status = doc.getString("status");
                if (status != null && STALE_STATUSES.contains(status)) {
                    deleteDocAndSubcollections(doc.getId());
                    deleted++;
                }
            }
            Log.d(TAG, "Call cleanup complete — deleted " + deleted + " stale docs");
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Call cleanup failed — will retry next cycle: " + e.getMessage());
            return Result.retry();
        }
    }

    private void deleteDocAndSubcollections(String callId) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String[] subcollections = {"callerCandidates", "calleeCandidates"};
        for (String sub : subcollections) {
            db.collection("calls").document(callId).collection(sub).get()
                    .addOnSuccessListener(snap -> {
                        for (com.google.firebase.firestore.DocumentSnapshot d : snap.getDocuments()) {
                            d.getReference().delete();
                        }
                    });
        }
        db.collection("calls").document(callId).delete();
    }

    public static void scheduleIfNeeded(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                CallCleanupWorker.class, 12, TimeUnit.HOURS)
                .addTag(WORK_TAG)
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_TAG,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                req);
        Log.d(TAG, "Call cleanup scheduled (12h periodic)");
    }
}
