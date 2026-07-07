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

    private EditText                 svSearch;
    private RecyclerView             recyclerView;
    private View                     tvEmpty;
    private LinearProgressIndicator  progress;
    private SearchResultsAdapter     adapter;
    private String                   conversationId;

    private final Handler   debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable        debounceRunnable;
    private static final int DEBOUNCE_MS = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message_search);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Search Messages");
        }
        if (toolbar != null) toolbar.setNavigationOnClickListener(v -> finish());

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        conversationId = prefs.getString("conversation_id", null);
        String myUid       = prefs.getString("my_uid", null);
        String partnerName = prefs.getString("partner_name", null);

        svSearch     = findViewById(R.id.sv_search);
        recyclerView = findViewById(R.id.rv_results);
        tvEmpty      = findViewById(R.id.tv_empty);
        progress     = findViewById(R.id.progress);

        adapter = new SearchResultsAdapter(new ArrayList<>());
        adapter.setUids(myUid, partnerName);
        adapter.setOnResultClickListener(msg -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_MSG_ID, msg.getId());
            setResult(RESULT_OK, result);
            finish();
        });

        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);
        }

        if (svSearch != null) {
            svSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int i, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    String q = s.toString().trim();
                    if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
                    if (q.length() < 2) {
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
            svSearch.requestFocus();
        }
    }

    private void runSearch(String query) {
        if (conversationId == null) return;
        if (progress != null) progress.setVisibility(View.VISIBLE);
        adapter.setQuery(query);
        SearchHelper.runSearch(this, conversationId, query, results -> runOnUiThread(() -> {
            if (progress != null) progress.setVisibility(View.GONE);
            adapter.setMessages(results);
            if (tvEmpty != null) tvEmpty.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
        }));
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        debounceHandler.removeCallbacksAndMessages(null);
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }
}
