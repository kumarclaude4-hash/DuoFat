package com.duoshield.app.notifications;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DuoShieldMessagingService extends FirebaseMessagingService {

    private static final String TAG = "DuoShieldFCM";

    /**
     * Dedup set — prevents showing two notifications for the same messageId if two
     * pushes arrive within the same process lifetime.  Bounded at 100 entries.
     */
    private static final Set<String> shownMessageIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void onNewToken(@NonNull String token) {
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();
        uploadTokenWithRetry(token, 0);
    }

    private void uploadTokenWithRetry(String token, int attempt) {
        String myUid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if (myUid == null) {
            SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
            myUid = prefs.getString("my_uid", null);
        }
        if (myUid != null && !myUid.isEmpty()) {
            FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(myUid)
                    .set(Collections.singletonMap("fcmToken", token), SetOptions.merge());
        } else if (attempt < 3) {
            long delayMs = 2000L * (1L << attempt);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> uploadTokenWithRetry(token, attempt + 1), delayMs);
        } else {
            Log.w(TAG, "onNewToken: uid still null after 3 retries — token not uploaded yet");
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        // ── 0. Handle call_invite separately before message flow ─────────────
        String msgType = remoteMessage.getData().get("type");
        if ("call_invite".equals(msgType)) {
            handleCallInvite(remoteMessage.getData());
            return;
        }

        // ── 1. Device-level delivery acknowledgement ──────────────────────────
        String chatId    = remoteMessage.getData().get("chatId");
        String messageId = remoteMessage.getData().get("messageId");
        if (chatId != null && !chatId.isEmpty()
                && messageId != null && !messageId.isEmpty()) {
            acknowledgeDelivery(chatId, messageId);
        }

        // ── 2. Dedup: skip notification if already shown for this messageId ──
        if (messageId != null && !messageId.isEmpty()) {
            if (shownMessageIds.size() > 100) shownMessageIds.clear();
            if (!shownMessageIds.add(messageId)) {
                Log.d(TAG, "Duplicate FCM push for " + messageId + " — notification suppressed");
                return;
            }
        }

        // ── 3. Show notification (if enabled) ────────────────────────────────
        boolean notificationsEnabled = prefs.getBoolean("notifications_enabled", true);
        if (!notificationsEnabled) return;

        // Sender name from data payload (set by push server from Firestore displayName).
        // Falls back to "DuoShield" for privacy if not present.
        String senderName = remoteMessage.getData().get("senderName");
        String title = (senderName != null && !senderName.isEmpty()) ? senderName : "DuoShield";

        String body = "New encrypted message";
        // Prefer data body (push server always sets it)
        String dataBody = remoteMessage.getData().get("body");
        if (dataBody != null && !dataBody.isEmpty()) body = dataBody;
        // Only use notification block body as last resort
        if (remoteMessage.getNotification() != null) {
            String nb = remoteMessage.getNotification().getBody();
            if (nb != null && !nb.isEmpty() && body.equals("New encrypted message")) body = nb;
        }

        String senderUid = remoteMessage.getData().get("senderUid");
        NotificationHelper.showNotification(this, title, body, chatId, senderUid);
    }

    private void handleCallInvite(Map<String, String> data) {
        String callId     = data.get("callId");
        String callerId   = data.get("callerId");
        String callerName = data.get("callerName");
        String isVideoStr = data.get("isVideo");
        boolean isVideo   = "true".equals(isVideoStr);

        if (callId == null || callerId == null) {
            Log.w(TAG, "call_invite missing callId or callerId — ignored");
            return;
        }
        if (callerName == null || callerName.isEmpty()) callerName = "DuoShield";

        // Show full-screen intent notification (works from background/killed state)
        NotificationHelper.createChannel(this);
        NotificationStyler.showIncomingCall(this, callerName, callId, callerId, isVideo);

        // FIX #6: Removed dead CALL_INVITE LocalBroadcast — no receiver was ever registered
        // for "com.duoshield.app.CALL_INVITE", so the broadcast was silently discarded
        // on every incoming call. The full-screen intent above covers all entry points.

        Log.d(TAG, "call_invite handled: callId=" + callId + " caller=" + callerName);
    }

    private void acknowledgeDelivery(String chatId, String messageId) {
        // The FCM data payload can still land after the recipient has already opened
        // the chat and marked the message "read" (e.g. the push was delayed, or the
        // chat was foregrounded from a different trigger). An unconditional
        // update({"status":"delivered"}) here would clobber that "read" status back
        // down to "delivered", which is what caused the sender's tick to silently
        // revert / never show real-time read receipts on one side. Guard the write
        // in a transaction so delivery ACKs can only ever move the status forward.
        com.google.firebase.firestore.DocumentReference ref = FirebaseFirestore.getInstance()
                .collection("chats").document(chatId)
                .collection("messages").document(messageId);

        FirebaseFirestore.getInstance().runTransaction(txn -> {
            com.google.firebase.firestore.DocumentSnapshot snap = txn.get(ref);
            String currentStatus = snap.getString("status");
            if ("read".equals(currentStatus) || "delivered".equals(currentStatus)) {
                return null; // already at or past "delivered" — never downgrade
            }
            Map<String, Object> update = new HashMap<>();
            update.put("status", "delivered");
            update.put("deliveredAt", FieldValue.serverTimestamp());
            txn.update(ref, update);
            return null;
        })
                .addOnSuccessListener(v ->
                        Log.d(TAG, "Delivery ACK written: " + messageId))
                .addOnFailureListener(e ->
                        Log.w(TAG, "Delivery ACK failed (non-critical): " + e.getMessage()));
    }
}
