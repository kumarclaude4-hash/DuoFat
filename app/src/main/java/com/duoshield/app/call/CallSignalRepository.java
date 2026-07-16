package com.duoshield.app.call;

import android.util.Log;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.HashMap;
import java.util.Map;

/**
 * Thin Firestore wrapper for all {@code calls/{callId}} reads and writes.
 * Isolates signaling plumbing from call logic.
 */
public class CallSignalRepository {

    private static final String TAG = "CallSignalRepo";
    private static final String COLLECTION = "calls";

    private final FirebaseFirestore db;

    public CallSignalRepository() {
        this.db = FirebaseFirestore.getInstance();
    }

    public DocumentReference callRef(String callId) {
        return db.collection(COLLECTION).document(callId);
    }

    public DocumentReference callerCandidatesRef(String callId) {
        return db.collection(COLLECTION).document(callId)
                .collection("callerCandidates").document();
    }

    public DocumentReference calleeCandidatesRef(String callId) {
        return db.collection(COLLECTION).document(callId)
                .collection("calleeCandidates").document();
    }

    public void createCallDoc(String callId, String callerId, String calleeId,
                              String type, String offerSdp, String chatId, OnCompleteCallback cb) {
        Map<String, Object> data = new HashMap<>();
        data.put("callerId", callerId);
        data.put("calleeId", calleeId);
        data.put("type", type);
        data.put("status", "ringing");
        data.put("chatId", chatId != null ? chatId : "");   // F6: bilateral contact gate
        data.put("createdAt", FieldValue.serverTimestamp());
        data.put("endedAt", null);
        data.put("endReason", null);

        Map<String, Object> offer = new HashMap<>();
        offer.put("sdp", offerSdp);
        offer.put("type", "offer");
        data.put("offer", offer);

        callRef(callId).set(data)
                .addOnSuccessListener(v -> { if (cb != null) cb.onSuccess(); })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "createCallDoc failed", e);
                    if (cb != null) cb.onFailure(e);
                });
    }

    public void writeAnswer(String callId, String answerSdp, OnCompleteCallback cb) {
        Map<String, Object> update = new HashMap<>();
        Map<String, Object> answer = new HashMap<>();
        answer.put("sdp", answerSdp);
        answer.put("type", "answer");
        update.put("answer", answer);
        update.put("status", "accepted");

        callRef(callId).update(update)
                .addOnSuccessListener(v -> { if (cb != null) cb.onSuccess(); })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "writeAnswer failed", e);
                    if (cb != null) cb.onFailure(e);
                });
    }

    public void writeStatus(String callId, String status, String endReason) {
        Map<String, Object> update = new HashMap<>();
        update.put("status", status);
        if (endReason != null) {
            update.put("endReason", endReason);
            update.put("endedAt", FieldValue.serverTimestamp());
        }
        callRef(callId).update(update)
                .addOnFailureListener(e -> Log.w(TAG, "writeStatus failed (non-fatal): " + e.getMessage()));
    }

    public void addCallerCandidate(String callId, String candidateJson,
                                   String sdpMid, int sdpMLineIndex) {
        Map<String, Object> data = new HashMap<>();
        data.put("candidate", candidateJson);
        data.put("sdpMid", sdpMid);
        data.put("sdpMLineIndex", sdpMLineIndex);
        callerCandidatesRef(callId).set(data)
                .addOnFailureListener(e -> Log.w(TAG, "addCallerCandidate failed: " + e.getMessage()));
    }

    public void addCalleeCandidate(String callId, String candidateJson,
                                   String sdpMid, int sdpMLineIndex) {
        Map<String, Object> data = new HashMap<>();
        data.put("candidate", candidateJson);
        data.put("sdpMid", sdpMid);
        data.put("sdpMLineIndex", sdpMLineIndex);
        calleeCandidatesRef(callId).set(data)
                .addOnFailureListener(e -> Log.w(TAG, "addCalleeCandidate failed: " + e.getMessage()));
    }

    public ListenerRegistration listenToCall(String callId, EventListener<com.google.firebase.firestore.DocumentSnapshot> listener) {
        return callRef(callId).addSnapshotListener(listener);
    }

    public ListenerRegistration listenToCallerCandidates(String callId,
            EventListener<com.google.firebase.firestore.QuerySnapshot> listener) {
        return db.collection(COLLECTION).document(callId)
                .collection("callerCandidates").addSnapshotListener(listener);
    }

    public ListenerRegistration listenToCalleeCandidates(String callId,
            EventListener<com.google.firebase.firestore.QuerySnapshot> listener) {
        return db.collection(COLLECTION).document(callId)
                .collection("calleeCandidates").addSnapshotListener(listener);
    }

    // ── ICE restart signaling ─────────────────────────────────────────────────

    /**
     * Writes a new offer SDP into the call doc for an ICE restart.
     * The callee's snapshot listener picks this up and calls {@code setRemoteDescription}
     * followed by {@code createAnswer}.
     */
    public void writeRestartOffer(String callId, String offerSdp) {
        Map<String, Object> restartOffer = new HashMap<>();
        restartOffer.put("sdp",  offerSdp);
        restartOffer.put("type", "offer");
        restartOffer.put("ts",   System.currentTimeMillis());

        Map<String, Object> update = new HashMap<>();
        update.put("restartOffer", restartOffer);
        update.put("iceRestartRequested", false);

        callRef(callId).update(update)
                .addOnFailureListener(e -> Log.w(TAG, "writeRestartOffer failed: " + e.getMessage()));
    }

    /**
     * Sets the {@code iceRestartRequested} flag so the caller knows the callee
     * is waiting for a new offer.
     */
    public void requestIceRestart(String callId) {
        callRef(callId).update("iceRestartRequested", true)
                .addOnFailureListener(e -> Log.w(TAG, "requestIceRestart failed: " + e.getMessage()));
    }

    /** Clears the ICE restart flag after the callee has acted on it. */
    public void clearIceRestartFlag(String callId) {
        callRef(callId).update("iceRestartRequested", false)
                .addOnFailureListener(e -> Log.w(TAG, "clearIceRestartFlag failed: " + e.getMessage()));
    }

    /**
     * Deletes the call doc AND all three known subcollections:
     * callerCandidates, calleeCandidates, chat.
     */
    public void deleteCallDoc(String callId) {
        String[] subcollections = {"callerCandidates", "calleeCandidates", "chat"};
        for (String sub : subcollections) {
            db.collection(COLLECTION).document(callId).collection(sub).get()
                    .addOnSuccessListener(snap -> {
                        for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
                            doc.getReference().delete();
                        }
                    });
        }
        callRef(callId).delete()
                .addOnFailureListener(e -> Log.w(TAG, "deleteCallDoc failed (non-fatal): " + e.getMessage()));
    }

    public interface OnCompleteCallback {
        void onSuccess();
        void onFailure(Exception e);
    }
}
