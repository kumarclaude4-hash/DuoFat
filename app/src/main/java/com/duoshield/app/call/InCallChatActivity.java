package com.duoshield.app.call;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.R;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ephemeral in-call chat screen.
 *
 * <p>Messages are stored in {@code calls/{callId}/chat} and are <strong>not</strong> persisted
 * to Room — they disappear when the call document is swept by the scheduled self-destruct
 * Cloud Function. This mirrors the "Messages won't be saved when the call ends" notice shown
 * in the UI (matching the design reference).
 *
 * <p>Uses {@link AppCompatActivity} (not BaseActivity) because:
 * <ul>
 *   <li>The user is already authenticated — they passed the full call setup flow.</li>
 *   <li>Messages are ephemeral; there is no persistent sensitive data at risk.</li>
 *   <li>Triggering the app-lock redirect mid-call would disrupt an active call.</li>
 * </ul>
 */
public class InCallChatActivity extends AppCompatActivity {

    private static final String TAG = "InCallChatActivity";

    /** Intent extras — set by CallActivity when opening this screen. */
    public static final String EXTRA_CALL_ID      = "incall_call_id";
    public static final String EXTRA_MY_UID       = "incall_my_uid";
    public static final String EXTRA_PARTNER_NAME = "incall_partner_name";

    private String callId;
    private String myUid;
    private String partnerName;

    private RecyclerView rvMessages;
    private EditText     etMessage;

    private final List<InCallChatMessage> messages = new ArrayList<>();
    private InCallChatAdapter adapter;
    private ListenerRegistration chatListener;

    /** Backoff state for re-attaching a listener that Firestore rejected (see listenForMessages). */
    private final android.os.Handler retryHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private int listenAttempts = 0;
    private static final int MAX_LISTEN_ATTEMPTS = 8;

    /**
     * Closes this screen the moment the call it belongs to ends.
     *
     * <p>This chat is launched on top of {@link CallActivity} and outlives it: when the call
     * ended, {@code CallActivity} finished itself and this screen stayed in the foreground on
     * top of a call that no longer existed — still showing a live-looking composer, and still
     * listening to {@code calls/{callId}/chat}, a subcollection that is about to be swept.
     * Anything typed after that point would be written into a dying document and never
     * delivered, with no indication to the user that the call was over.
     *
     * <p>{@link CallActivity#broadcastCallEnded()} is the single sender, and it fires from every
     * terminal path (hangup, decline, timeout, remote hangup, ICE failure). Package-scoped, so
     * it cannot be spoofed by another app.
     */
    private final BroadcastReceiver callEndedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isFinishing()) return;
            Toast.makeText(InCallChatActivity.this,
                    "Call ended — chat closed", Toast.LENGTH_SHORT).show();
            finish();
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incall_chat);

        callId      = getIntent().getStringExtra(EXTRA_CALL_ID);
        myUid       = getIntent().getStringExtra(EXTRA_MY_UID);
        partnerName = getIntent().getStringExtra(EXTRA_PARTNER_NAME);
        if (partnerName == null) partnerName = "Unknown";

        if (callId == null || myUid == null) {
            Log.e(TAG, "Missing callId or myUid — closing in-call chat");
            finish();
            return;
        }

        bindViews();
        listenForMessages();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        retryHandler.removeCallbacksAndMessages(null);
        if (chatListener != null) { chatListener.remove(); chatListener = null; }
    }

    // ── View setup ────────────────────────────────────────────────────────────

    private void bindViews() {
        rvMessages = findViewById(R.id.rvInCallMessages);
        etMessage  = findViewById(R.id.etInCallMessage);

        // RecyclerView — stack from end so newest messages appear at the bottom
        adapter = new InCallChatAdapter(messages, partnerName);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        rvMessages.setLayoutManager(llm);
        rvMessages.setAdapter(adapter);

        // Header navigation
        View btnMinimize = findViewById(R.id.btnMinimizeChat);
        View btnClose    = findViewById(R.id.btnCloseChat);
        if (btnMinimize != null) btnMinimize.setOnClickListener(v -> finish());
        if (btnClose    != null) btnClose.setOnClickListener(v -> finish());

        // Emoji button — opens the soft keyboard so the user can switch to emoji panel
        ImageView btnEmoji = findViewById(R.id.btnEmoji);
        if (btnEmoji != null) {
            btnEmoji.setOnClickListener(v -> {
                etMessage.requestFocus();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etMessage, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }

        // Send button
        ImageView btnSend = findViewById(R.id.btnSendInCall);
        if (btnSend != null) btnSend.setOnClickListener(v -> sendMessage());

        // IME "Send" action key
        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    // ── Firestore ─────────────────────────────────────────────────────────────

    private void sendMessage() {
        if (etMessage == null) return;
        String text = etMessage.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;

        etMessage.setText("");

        Map<String, Object> doc = new HashMap<>();
        doc.put("senderId", myUid);
        doc.put("text", text);
        doc.put("ts", System.currentTimeMillis());

        FirebaseFirestore.getInstance()
                .collection("calls").document(callId)
                .collection("chat")
                .add(doc)
                .addOnFailureListener(e -> Log.w(TAG, "send failed", e));
    }

    private void listenForMessages() {
        if (chatListener != null) return;
        chatListener = FirebaseFirestore.getInstance()
                .collection("calls").document(callId)
                .collection("chat")
                .orderBy("ts", Query.Direction.ASCENDING)
                .addSnapshotListener((snapshots, e) -> {
                    if (e != null) {
                        // A rejected listener is dead for good in Firestore. Opening this
                        // screen in the first moment of a call can race the creation of the
                        // parent calls/{callId} document that the security rule resolves,
                        // which used to leave the thread permanently empty on that device.
                        // Drop the dead registration and re-attach with backoff.
                        Log.w(TAG, "listener error — re-attaching (attempt "
                                + listenAttempts + ")", e);
                        if (chatListener != null) { chatListener.remove(); chatListener = null; }
                        if (isFinishing() || isDestroyed()) return;
                        if (listenAttempts >= MAX_LISTEN_ATTEMPTS) {
                            Log.e(TAG, "in-call chat listener gave up after "
                                    + listenAttempts + " attempts");
                            return;
                        }
                        long delay = Math.min(600L * (1L << listenAttempts), 4_000L);
                        listenAttempts++;
                        retryHandler.postDelayed(this::listenForMessages, delay);
                        return;
                    }
                    if (snapshots == null) return;
                    listenAttempts = 0;

                    List<InCallChatMessage> updated = new ArrayList<>();
                    for (DocumentSnapshot ds : snapshots.getDocuments()) {
                        String senderId = ds.getString("senderId");
                        String text     = ds.getString("text");
                        Long   ts       = ds.getLong("ts");
                        if (TextUtils.isEmpty(text) || senderId == null) continue;
                        updated.add(new InCallChatMessage(
                                ds.getId(), senderId, text,
                                ts != null ? ts : 0L,
                                senderId.equals(myUid)));
                    }

                    runOnUiThread(() -> {
                        messages.clear();
                        messages.addAll(updated);
                        adapter.notifyDataSetChanged();
                        if (!messages.isEmpty()) {
                            rvMessages.scrollToPosition(messages.size() - 1);
                        }
                    });
                });
    }
}
