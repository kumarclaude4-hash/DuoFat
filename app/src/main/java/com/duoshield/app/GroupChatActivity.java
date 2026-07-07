package com.duoshield.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.crypto.GroupCipherHelper;
import com.duoshield.app.crypto.signal.SignalCipherHelper;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Group;
import com.duoshield.app.models.GroupMember;
import com.duoshield.app.models.Message;
import com.duoshield.app.ui.MessageAdapter;
import com.duoshield.app.util.FirebaseCostGuard;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Group chat screen.
 *
 * <p>Encryption: messages are encrypted with a shared AES-256-GCM group key
 * ({@link GroupCipherHelper}). The group key is stored locally in Room
 * ({@code Group.groupKey}). If absent (newly added member), it is fetched from
 * Firestore {@code /groups/{id}/keys/{myUid}} and decrypted via the creator's
 * Signal session before being persisted locally.
 *
 * <p>Messages are written to Firestore {@code /groups/{id}/messages} and stored
 * in the local {@code messages} Room table using the {@code groupId} as their
 * {@code conversationId}, so all existing message queries work unchanged.
 *
 * <p>Architecture rules observed:
 * <ul>
 *   <li>Extends {@link BaseActivity} for app-lock integration.</li>
 *   <li>Single Firestore listener — attached in {@code onStart()}, removed in
 *       {@code onStop()}.</li>
 *   <li>{@link MessageAdapter} with DiffUtil drives the RecyclerView.</li>
 *   <li>{@link FirebaseCostGuard} records all reads, writes, and deletes.</li>
 * </ul>
 */
public class GroupChatActivity extends BaseActivity {

    private static final String TAG = "GroupChatActivity";

    // ── State ─────────────────────────────────────────────────────────────────
    private String groupId;
    private String groupName;
    private String myUid;
    /**
     * Written from the background executor (key decryption) and read from the
     * Firestore listener (main thread). {@code volatile} ensures visibility without
     * a full synchronization block (BUG-G03).
     */
    private volatile String groupKey;
    private String creatorUid;
    private List<String> memberUids = new ArrayList<>();

    // ── Firebase ──────────────────────────────────────────────────────────────
    private FirebaseFirestore  db;
    private ListenerRegistration msgListener;
    private com.google.firebase.Timestamp latestKnownTimestamp;

    // ── UI ────────────────────────────────────────────────────────────────────
    private RecyclerView  recyclerView;
    private MessageAdapter adapter;
    private EditText      etMessage;

    // ── Local DB ──────────────────────────────────────────────────────────────
    private AppDatabase   localDb;
    private ExecutorService executor;

    // ── Dedup guard ───────────────────────────────────────────────────────────
    private final Set<String> knownIds = new HashSet<>();

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.duoshield.app.util.UiModeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_chat);

        db      = FirebaseFirestore.getInstance();
        localDb = AppDatabase.getInstance(this);
        executor = Executors.newSingleThreadExecutor();

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        myUid   = prefs.getString("my_uid", null);
        groupId = getIntent().getStringExtra("group_id");

        if (groupId == null || myUid == null) { finish(); return; }

        // FLAG_SECURE is applied globally in BaseActivity.onCreate()
        // based on the "app_screenshot_enabled" preference.

        // ── Views ──────────────────────────────────────────────────────────
        TextView tvGroupName   = findViewById(R.id.tv_group_name);
        TextView tvMemberCount = findViewById(R.id.tv_member_count);
        recyclerView           = findViewById(R.id.recycler_messages);
        etMessage              = findViewById(R.id.et_message);
        ImageView btnBack      = findViewById(R.id.btn_back);
        ImageView btnSend      = findViewById(R.id.btn_send);

        btnBack.setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> trySend());

        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                btnSend.setAlpha(s.toString().trim().isEmpty() ? 0.4f : 1.0f);
            }
        });

        adapter = new MessageAdapter(new java.util.ArrayList<>(), myUid, null, null, this::retryMessage);
        LinearLayoutManager groupLlm = new LinearLayoutManager(this);
        groupLlm.setStackFromEnd(true);
        groupLlm.setInitialPrefetchItemCount(12);
        recyclerView.setLayoutManager(groupLlm);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setAdapter(adapter);

        // ── Load group from Room, init group key ───────────────────────────
        executor.execute(() -> {
            Group g = localDb.groupDao().getGroupById(groupId);
            if (g == null) { runOnUiThread(this::finish); return; }

            groupName  = g.name;
            groupKey   = g.groupKey;
            creatorUid = g.createdBy;
            memberUids = localDb.groupDao().getMemberUidsOf(groupId);

            runOnUiThread(() -> {
                tvGroupName.setText(groupName);
                int memberCount = memberUids.size();
                tvMemberCount.setText(memberCount + " member" + (memberCount == 1 ? "" : "s"));
            });

            if (groupKey == null) {
                // Fetch encrypted key from Firestore and decrypt with Signal
                fetchGroupKey();
            } else {
                seedFromRoom();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (groupKey != null && msgListener == null) listenForMessages();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (msgListener != null) { msgListener.remove(); msgListener = null; }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Group key retrieval
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called when no local group key is stored. Fetches the Signal-encrypted
     * group key from Firestore, decrypts it, and saves it to Room.
     */
    private void fetchGroupKey() {
        FirebaseCostGuard guard = FirebaseCostGuard.getInstance(this);
        if (!guard.canRead(1)) {
            runOnUiThread(() ->
                Toast.makeText(this, "Read quota reached — try again tomorrow",
                        Toast.LENGTH_LONG).show());
            return;
        }
        db.collection("groups").document(groupId)
          .collection("keys").document(myUid)
          .get()
          .addOnSuccessListener(snap -> {
              guard.recordReads(1);
              if (!snap.exists()) {
                  Toast.makeText(this, "Group key not found — ask the creator to re-add you",
                          Toast.LENGTH_LONG).show();
                  return;
              }
              String encryptedKey = snap.getString("encryptedKey");
              Object sigTypeObj   = snap.get("sigType");
              String sender       = snap.getString("senderUid");
              if (encryptedKey == null || sigTypeObj == null || sender == null) {
                  Toast.makeText(this, "Malformed group key doc", Toast.LENGTH_SHORT).show();
                  return;
              }
              // Defense in depth: even though the Firestore rule now restricts writes
              // to this collection to the group's creator, also verify the claimed
              // senderUid actually matches the locally-cached creatorUid before ever
              // decrypting/trusting it. This guards against a stale/misconfigured rule
              // or a future regression re-opening write access to other members.
              if (creatorUid != null && !creatorUid.equals(sender)) {
                  Log.w(TAG, "Group key doc senderUid (" + sender + ") does not match "
                          + "group creator (" + creatorUid + ") — refusing to trust it");
                  Toast.makeText(this,
                          "Group key came from an unexpected sender — refusing for safety",
                          Toast.LENGTH_LONG).show();
                  return;
              }
              // Firestore returns numeric fields as Long on Android; guard against
              // other Number subtypes to avoid ClassCastException (BUG-D05).
              int sigType = sigTypeObj instanceof Number
                      ? ((Number) sigTypeObj).intValue() : 2;
              executor.execute(() -> {
                  try {
                      String decrypted = SignalCipherHelper.decrypt(
                              this, sender, encryptedKey, sigType);
                      groupKey = decrypted;
                      localDb.groupDao().updateGroupKey(groupId, groupKey);
                      seedFromRoom();
                  } catch (Exception e) {
                      Log.e(TAG, "Failed to decrypt group key", e);
                      runOnUiThread(() ->
                          Toast.makeText(this, "Could not decrypt group key", Toast.LENGTH_LONG).show());
                  }
              });
          })
          .addOnFailureListener(e ->
              Toast.makeText(this, "Failed to fetch group key", Toast.LENGTH_SHORT).show());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Message seeding (Room cache)
    // ═════════════════════════════════════════════════════════════════════════

    private void seedFromRoom() {
        executor.execute(() -> {
            List<Message> cached = localDb.messageDao().getMessages(groupId);
            for (Message m : cached) knownIds.add(m.getId());
            if (!cached.isEmpty()) {
                long lastTs = cached.get(cached.size() - 1).getTimestamp();
                // We use a placeholder timestamp; real latestKnownTimestamp set from Firestore
                Log.d(TAG, "Seeded " + cached.size() + " messages from Room");
            }
            runOnUiThread(() -> {
                adapter.setMessages(cached);
                scrollToBottom();
                // Now attach the Firestore listener
                if (msgListener == null) listenForMessages();
            });
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Firestore listener
    // ═════════════════════════════════════════════════════════════════════════

    private void listenForMessages() {
        if (groupKey == null || msgListener != null) return;

        Query q = db.collection("groups").document(groupId)
                    .collection("messages")
                    .orderBy("timestamp", Query.Direction.ASCENDING);
        if (latestKnownTimestamp != null) {
            q = q.startAfter(latestKnownTimestamp);
        }

        msgListener = q.addSnapshotListener((snaps, e) -> {
            if (e != null) { Log.e(TAG, "Message listener error", e); return; }
            if (snaps == null || snaps.isEmpty()) return;

            FirebaseCostGuard.getInstance(this).recordReads(snaps.size());

            for (DocumentChange dc : snaps.getDocumentChanges()) {
                if (dc.getType() != DocumentChange.Type.ADDED) continue;
                com.google.firebase.firestore.DocumentSnapshot doc = dc.getDocument();

                String id     = doc.getString("id");
                String sender = doc.getString("sender");
                String cipher = doc.getString("text");
                Object ts     = doc.get("timestamp");

                if (id == null || sender == null || cipher == null) continue;
                if (knownIds.contains(id)) continue;
                knownIds.add(id);

                // Update latest timestamp for next listener re-attach
                if (ts instanceof com.google.firebase.Timestamp) {
                    com.google.firebase.Timestamp fts = (com.google.firebase.Timestamp) ts;
                    if (latestKnownTimestamp == null ||
                            fts.compareTo(latestKnownTimestamp) > 0) {
                        latestKnownTimestamp = fts;
                    }
                }

                long tsMs = ts instanceof com.google.firebase.Timestamp
                    ? ((com.google.firebase.Timestamp) ts).toDate().getTime()
                    : System.currentTimeMillis();

                // Own optimistic inserts are already in knownIds (added at send time).
                // The knownIds.contains(id) guard above (line ~284) already covers this —
                // no second check needed here (BUG-G04/U04 dead-code removal).

                String plain;
                try {
                    Boolean isEncrypted = doc.getBoolean("isEncrypted");
                    plain = (Boolean.TRUE.equals(isEncrypted))
                        ? GroupCipherHelper.decrypt(cipher, groupKey)
                        : cipher;
                } catch (Exception ex) {
                    Log.e(TAG, "Decrypt failed for msg " + id, ex);
                    plain = "[Decryption failed]";
                }

                final Message msg = new Message(id, groupId, sender, plain, tsMs, false);
                adapter.appendMessage(msg);
                scrollToBottom();

                // Persist decrypted message to Room
                final Message toRoom = new Message(id, groupId, sender, plain, tsMs, false);
                executor.execute(() -> {
                    try { localDb.messageDao().insert(toRoom); }
                    catch (Exception ex) { Log.w(TAG, "Room insert conflict for " + id); }
                });
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Send message
    // ═════════════════════════════════════════════════════════════════════════

    private void trySend() {
        if (groupKey == null) {
            Toast.makeText(this, "Group key not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;

        etMessage.setText("");

        String msgId = UUID.randomUUID().toString();
        long   now   = System.currentTimeMillis();

        // ── Optimistic UI ──────────────────────────────────────────────────
        Message optimistic = new Message(msgId, groupId, myUid, text, now, false);
        optimistic.setStatus("pending");
        adapter.appendMessage(optimistic);
        knownIds.add(msgId);
        scrollToBottom();

        // ── Encrypt + write to Firestore ───────────────────────────────────
        executor.execute(() -> {
            String cipher;
            try {
                cipher = GroupCipherHelper.encrypt(text, groupKey);
            } catch (Exception ex) {
                Log.e(TAG, "Group encrypt failed", ex);
                runOnUiThread(() -> {
                    optimistic.setStatus("failed");
                    adapter.updateMessage(msgId, m -> m.setStatus("failed"));
                    executor.execute(() -> localDb.messageDao().insert(optimistic));
                    Toast.makeText(this, "Encryption failed. Tap to retry.", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            Map<String, Object> doc = new HashMap<>();
            doc.put("id",          msgId);
            doc.put("sender",      myUid);
            doc.put("text",        cipher);
            doc.put("isEncrypted", true);
            doc.put("type",        "text");
            doc.put("status",      "sent");
            doc.put("timestamp",   FieldValue.serverTimestamp());

            FirebaseCostGuard guard = FirebaseCostGuard.getInstance(this);
            if (!guard.canWrite(1)) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Write quota reached — try again tomorrow", Toast.LENGTH_LONG).show());
                return;
            }
            db.collection("groups").document(groupId)
              .collection("messages").document(msgId)
              .set(doc)
              .addOnSuccessListener(v -> {
                  guard.recordWrites(1);
                  adapter.updateMessage(msgId, m -> m.setStatus("sent"));

                  // Persist to Room
                  Message stored = new Message(msgId, groupId, myUid, text, now, false);
                  stored.setStatus("sent");
                  executor.execute(() -> localDb.messageDao().insert(stored));

                  // Update group lastMessage in Room
                  String preview = text.length() > 80 ? text.substring(0, 80) : text;
                  executor.execute(() ->
                      localDb.groupDao().updateLastMessage(groupId, preview, now));

                  // Nudge Firestore group doc so the Cloud Function sends FCM
                  db.collection("groups").document(groupId)
                    .update("lastActivity", FieldValue.serverTimestamp())
                    .addOnFailureListener(ex ->
                        Log.w(TAG, "nudge failed (non-critical): " + ex.getMessage()));
              })
              .addOnFailureListener(ex -> {
                  runOnUiThread(() -> {
                      optimistic.setStatus("failed");
                      adapter.updateMessage(msgId, m -> m.setStatus("failed"));
                      executor.execute(() -> localDb.messageDao().insert(optimistic));
                      Toast.makeText(this, "Failed to send. Tap to retry.",
                              Toast.LENGTH_SHORT).show();
                  });
              });
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private void retryMessage(Message msg) {
        adapter.removeMessage(msg.getId());
        knownIds.remove(msg.getId());
        etMessage.setText(msg.getText());
        trySend();
    }

    private void scrollToBottom() {
        int last = adapter.getItemCount() - 1;
        if (last >= 0) recyclerView.scrollToPosition(last);
    }
}
