package com.duoshield.app.call.watch;

import android.content.Context;
import android.util.Log;

import com.duoshield.app.util.FirebaseCostGuard;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.Map;

/**
 * Thin Firestore wrapper for the single Watch Together state document at
 * {@code calls/{callId}/watch/state}.
 *
 * <p>Deliberately shaped like {@link com.duoshield.app.call.CallSignalRepository}: it
 * isolates Firestore plumbing from playback logic, exposes one listener registration for
 * the caller to own, and never throws — signaling failures are logged and non-fatal so a
 * Watch Together problem can never tear down an active WebRTC call.
 *
 * <p><strong>Why a single document rather than an event subcollection.</strong> One doc
 * means one listener, one write per control action, and no unbounded growth to sweep. It
 * also makes join/rejoin trivial: the newest snapshot <em>is</em> the full session state,
 * so a participant who backgrounds and returns simply re-reads it and re-syncs. An
 * append-only event log would need replay and periodic pruning for no benefit here.
 *
 * <p><strong>Write budget.</strong> Writes happen on discrete user actions (start, play,
 * pause, seek, rate, stop) plus a host heartbeat capped at one write per
 * {@link WatchTogetherState#HEARTBEAT_INTERVAL_MS}. That is well inside the project's
 * Firestore cost envelope.
 */
public class WatchTogetherRepository {

    private static final String TAG = "WatchTogetherRepo";

    private static final String CALLS_COLLECTION = "calls";
    /** Subcollection name; also swept by {@code CallSignalRepository.deleteCallDoc}. */
    public static final String WATCH_COLLECTION = "watch";
    /** Fixed document ID — there is exactly one Watch Together state per call. */
    public static final String STATE_DOC = "state";

    private final FirebaseFirestore db;

    /**
     * Firestore cost gate (project rule #2). Every read/write below is gated by this
     * guard so a runaway Watch Together session can never blow the daily Firebase budget.
     */
    private final FirebaseCostGuard guard;

    /** Monotonic sequence for writes made by this device during this session. */
    private long localSeq = 0L;

    /**
     * @param context any Context; only the application context is retained via
     *                {@link FirebaseCostGuard#getInstance(Context)}.
     */
    public WatchTogetherRepository(Context context) {
        this.db = FirebaseFirestore.getInstance();
        this.guard = FirebaseCostGuard.getInstance(context);
    }

    /** Reference to {@code calls/{callId}/watch/state}. */
    public DocumentReference stateRef(String callId) {
        return db.collection(CALLS_COLLECTION).document(callId)
                .collection(WATCH_COLLECTION).document(STATE_DOC);
    }

    /**
     * Attaches the single snapshot listener for the Watch Together state.
     *
     * <p>The caller owns the returned registration and MUST remove it in {@code onStop()}
     * / {@code onDestroy()}, per the project's one-listener-per-screen rule.
     */
    public ListenerRegistration listenToState(String callId,
                                              EventListener<DocumentSnapshot> listener) {
        // The attach itself triggers one initial read of the single state doc. Snapshot
        // updates thereafter are also single-doc reads; the guard bounds the attach and
        // the caller keeps exactly one listener alive (project rule #3).
        if (guard.canRead(1)) {
            guard.recordReads(1);
        }
        return stateRef(callId).addSnapshotListener(listener);
    }

    /**
     * Raises {@link #localSeq} above any sequence already observed remotely, so a write
     * made by this device is never discarded as stale by the other participant.
     *
     * <p>Call this whenever a remote state is applied.
     */
    public void observeRemoteSeq(long remoteSeq) {
        if (remoteSeq > localSeq) localSeq = remoteSeq;
    }

    /** Next sequence number to stamp on an outgoing write. */
    public long nextSeq() {
        return ++localSeq;
    }

    /**
     * Writes the full state document, stamping {@code seq}, {@code updatedAtMs},
     * {@code lastAction} and {@code lastActionBy}.
     *
     * <p>Uses {@code set()} rather than {@code update()} so the very first write creates
     * the document and every later write leaves it fully populated — a follower can then
     * rely on all fields being present.
     *
     * <p>Gated by {@link FirebaseCostGuard}: if the daily write budget is exhausted the
     * sync write is dropped (and this returns {@code false}) rather than throwing — a
     * Watch Together problem must never tear down the underlying call.
     *
     * @param state  the desired state; mutated in place with the stamped metadata only
     *               when the write is actually issued.
     * @param action one of the {@code WatchTogetherState.ACTION_*} labels.
     * @param myUid  the acting participant's uid.
     * @return {@code true} when the write was issued; {@code false} when it was skipped
     *         (null args or cost budget exhausted). The caller should only treat its
     *         local state as authoritative when this returns {@code true}.
     */
    public boolean writeState(String callId, WatchTogetherState state, String action, String myUid) {
        if (callId == null || state == null) {
            Log.w(TAG, "writeState skipped — null callId or state");
            return false;
        }

        if (!guard.canWrite(1)) {
            Log.w(TAG, "writeState(" + action + ") dropped — Firestore write budget exhausted");
            return false;
        }

        state.seq          = nextSeq();
        state.updatedAtMs  = System.currentTimeMillis();
        state.lastAction   = action;
        state.lastActionBy = myUid;

        Map<String, Object> payload = state.toMap();
        guard.recordWrites(1);
        stateRef(callId).set(payload)
                .addOnFailureListener(e ->
                        Log.w(TAG, "writeState(" + action + ") failed (non-fatal): " + e.getMessage()));
        return true;
    }

    /**
     * One-shot read of the current state, used when joining or rejoining a call so a
     * participant immediately picks up a session that started while they were away.
     *
     * <p>The snapshot listener also delivers this, so treat it as an optimization rather
     * than the primary path.
     */
    public void fetchState(String callId, OnStateCallback cb) {
        if (callId == null || cb == null) return;

        if (!guard.canRead(1)) {
            Log.w(TAG, "fetchState dropped — Firestore read budget exhausted");
            cb.onState(null);
            return;
        }
        guard.recordReads(1);
        stateRef(callId).get()
                .addOnSuccessListener(snap -> {
                    if (snap != null && snap.exists()) {
                        cb.onState(WatchTogetherState.fromMap(snap.getData()));
                    } else {
                        cb.onState(null);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "fetchState failed (non-fatal): " + e.getMessage());
                    cb.onState(null);
                });
    }

    /**
     * Ends the session by writing {@code active = false} rather than deleting the doc.
     *
     * <p>Deleting would race with the other participant's listener and could leave their
     * player open with no state to reconcile against. The document is removed for real
     * when the call ends, by {@code CallSignalRepository.deleteCallDoc}.
     */
    public void endSession(String callId, String myUid) {
        WatchTogetherState ended = new WatchTogetherState();
        ended.active  = false;
        ended.playing = false;
        writeState(callId, ended, WatchTogetherState.ACTION_STOP, myUid);
    }

    /** Callback for {@link #fetchState}. {@code state} is null when no session exists. */
    public interface OnStateCallback {
        void onState(WatchTogetherState state);
    }
}
