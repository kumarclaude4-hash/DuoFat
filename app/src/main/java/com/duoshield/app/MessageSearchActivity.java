package com.duoshield.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.util.SearchHelper;
import java.util.ArrayList;

public class MessageSearchActivity extends BaseActivity {

    public static final String EXTRA_MSG_ID = "msg_id";
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_GLOBAL_SEARCH = "global_search";
    /**
     * Dedicated "Starred Messages" mode (Settings → Starred Messages), like WhatsApp's
     * standalone starred list: locked to the STARRED filter, filter chips hidden, and the
     * list loads immediately with no query needed. Always pass this together with
     * {@link #EXTRA_GLOBAL_SEARCH} = true.
     */
    public static final String EXTRA_STARRED_ONLY = "starred_only";

    private EditText                 svSearch;
    private RecyclerView             recyclerView;
    private View                     tvEmpty;
    private LinearProgressIndicator  progress;
    private SearchResultsAdapter     adapter;
    private String                   conversationId;
    private String                   myUid;
    private SearchHelper.Filter      activeFilter = SearchHelper.Filter.ALL;
    private boolean                  globalSearch;
    private boolean                  starredOnly;

    private final Handler   debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable        debounceRunnable;
    private static final int DEBOUNCE_MS = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_search);

        // Starred-only mode is always a form of global (cross-conversation) search.
        starredOnly  = getIntent().getBooleanExtra(EXTRA_STARRED_ONLY, false);
        globalSearch = starredOnly || getIntent().getBooleanExtra(EXTRA_GLOBAL_SEARCH, false);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(starredOnly ? "Starred Messages" : "Search Messages");
        }
        if (toolbar != null) toolbar.setNavigationOnClickListener(v -> finish());

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        conversationId = globalSearch ? null : prefs.getString("conversation_id", null);
        myUid       = prefs.getString("my_uid", null);
        String partnerName = globalSearch ? "Conversation" : prefs.getString("partner_name", null);

        svSearch     = findViewById(R.id.sv_search);
        recyclerView = findViewById(R.id.rv_results);
        tvEmpty      = findViewById(R.id.tv_empty);
        progress     = findViewById(R.id.progress);

        adapter = new SearchResultsAdapter(new ArrayList<>());
        adapter.setUids(myUid, partnerName);
        // In cross-conversation lists each row shows which chat it belongs to.
        adapter.setGlobalMode(globalSearch);
        adapter.setOnResultClickListener(msg -> {
            if (globalSearch) {
                openGlobalResult(msg);
                return;
            }
            Intent result = new Intent();
            result.putExtra(EXTRA_MSG_ID, msg.getId());
            result.putExtra(EXTRA_CONVERSATION_ID, msg.getConversationId());
            setResult(RESULT_OK, result);
            finish();
        });

        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);
        }
        if (starredOnly) {
            // Scope is fixed to starred, so the chip row is meaningless here.
            activeFilter = SearchHelper.Filter.STARRED;
            View filterScroll = findViewById(R.id.filter_scroll);
            if (filterScroll != null) filterScroll.setVisibility(View.GONE);
            if (svSearch != null) svSearch.setHint("Search starred messages");
        } else {
            setupFilters();
        }

        if (svSearch != null) {
            svSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int i, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String q = s.toString().trim();
                    if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
                    if (q.length() < 2) {
                        // In starred mode an empty/short query means "show the full starred list",
                        // not "clear results" — the STARRED filter drives the results on its own.
                        if (starredOnly) {
                            adapter.setQuery("");
                            debounceRunnable = () -> runSearch("");
                            debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
                            return;
                        }
                        adapter.setQuery("");
                        adapter.setMessages(new ArrayList<>());
                        if (tvEmpty  != null) tvEmpty.setVisibility(View.GONE);
                        if (progress != null) progress.setVisibility(View.GONE);
                        return;
                    }
                    debounceRunnable = () -> runSearch(q);
                    debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
                }
            });
            // WhatsApp's starred list lands as a calm list, so skip the auto-focused keyboard there.
            if (!starredOnly) svSearch.requestFocus();
        }

        // Load the full starred list immediately, no typing required.
        if (starredOnly) runSearch("");
    }

    private void runSearch(String query) {
        if (conversationId == null && !globalSearch) return;
        if (progress != null) progress.setVisibility(View.VISIBLE);
        adapter.setQuery(query);
        SearchHelper.runSearch(this, conversationId, query, activeFilter, myUid,
                results -> {
            // This callback runs on SearchHelper's background thread, so resolving each
            // result's chat name from Room here keeps the point-queries off the UI thread.
            final java.util.Map<String, String> names =
                    globalSearch ? resolveConversationNames(results) : null;
            runOnUiThread(() -> {
                if (progress != null) progress.setVisibility(View.GONE);
                if (names != null) adapter.setConversationNames(names);
                adapter.setMessages(results);
                if (tvEmpty != null) tvEmpty.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
            });
                });
    }

    /**
     * Resolves each distinct conversationId in {@code results} to a display name using local Room
     * data only (no network): groups via {@link com.duoshield.app.db.GroupDao}, otherwise the
     * contact matched by conversationId. Falls back to "Unknown chat".
     */
    private java.util.Map<String, String> resolveConversationNames(
            java.util.List<com.duoshield.app.models.Message> results) {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        if (results == null || results.isEmpty()) return names;
        com.duoshield.app.db.AppDatabase db = com.duoshield.app.db.AppDatabase.getInstance(this);
        for (com.duoshield.app.models.Message m : results) {
            String id = m.getConversationId();
            if (id == null || id.isEmpty() || names.containsKey(id)) continue;
            String name = null;
            com.duoshield.app.models.Group group = db.groupDao().getGroupById(id);
            if (group != null && group.name != null && !group.name.isEmpty()) {
                name = group.name;
            } else {
                com.duoshield.app.models.Contact contact = db.contactDao().getByConversationId(id);
                if (contact != null && contact.displayName != null
                        && !contact.displayName.isEmpty()) {
                    name = contact.displayName;
                }
            }
            names.put(id, name != null ? name : "Unknown chat");
        }
        return names;
    }

    private void openGlobalResult(com.duoshield.app.models.Message message) {
        String id = message.getConversationId();
        if (id == null || id.isEmpty()) return;
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("groups").document(id).get()
                .addOnSuccessListener(group -> {
                    Intent intent;
                    if (group.exists()) {
                        intent = new Intent(this, GroupChatActivity.class);
                        intent.putExtra("group_id", id);
                    } else {
                        intent = new Intent(this, ChatMediaActivity.class);
                        intent.putExtra("conversation_id", id);
                        resolveDirectPartnerAndOpen(intent, id);
                        return;
                    }
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(e ->
                        android.widget.Toast.makeText(this, "Could not open conversation", android.widget.Toast.LENGTH_SHORT).show());
    }

    private void resolveDirectPartnerAndOpen(Intent intent, String conversationId) {
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("chats").document(conversationId).get()
                .addOnSuccessListener(chat -> {
                    Object value = chat.get("participants");
                    if (!(value instanceof java.util.List)) return;
                    String partner = null;
                    for (Object item : (java.util.List<?>) value) {
                        if (item != null && !myUid.equals(String.valueOf(item))) {
                            partner = String.valueOf(item);
                            break;
                        }
                    }
                    if (partner == null) return;
                    intent.putExtra("partner_uid", partner);
                    startActivity(intent);
                    finish();
                });
    }

    private void setupFilters() {
        com.google.android.material.chip.ChipGroup group = findViewById(R.id.search_filters);
        if (group == null) return;
        group.setOnCheckedStateChangeListener((chipGroup, checkedIds) -> {
            if (checkedIds == null || checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.filter_media) activeFilter = SearchHelper.Filter.MEDIA;
            else if (id == R.id.filter_links) activeFilter = SearchHelper.Filter.LINKS;
            else if (id == R.id.filter_files) activeFilter = SearchHelper.Filter.FILES;
            else if (id == R.id.filter_starred) activeFilter = SearchHelper.Filter.STARRED;
            else if (id == R.id.filter_unread) activeFilter = SearchHelper.Filter.UNREAD;
            else activeFilter = SearchHelper.Filter.ALL;
            if (svSearch != null && svSearch.getText().toString().trim().length() >= 2) {
                runSearch(svSearch.getText().toString().trim());
            }
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        debounceHandler.removeCallbacksAndMessages(null);
        SearchHelper.clearSearch();
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }
}
