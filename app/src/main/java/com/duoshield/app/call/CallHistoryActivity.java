package com.duoshield.app.call;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.CallRecord;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CallHistoryActivity extends BaseActivity {

    private CallHistoryAdapter adapter;
    private TextView           tvEmpty;
    private ExecutorService    executor;
    private AppDatabase        db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call_history);

        Toolbar toolbar = findViewById(R.id.callHistoryToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Calls");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvEmpty = findViewById(R.id.tvEmptyCallHistory);
        RecyclerView rv = findViewById(R.id.rvCallHistory);
        rv.setLayoutManager(new LinearLayoutManager(this));

        adapter = new CallHistoryAdapter(record -> showDeleteDialog(record));
        rv.setAdapter(adapter);

        db = AppDatabase.getInstance(this);
        executor = Executors.newSingleThreadExecutor();

        loadHistory();
    }

    private void loadHistory() {
        executor.execute(() -> {
            List<CallRecord> records = db.callHistoryDao().getAll();
            runOnUiThread(() -> {
                adapter.setItems(records);
                tvEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void showDeleteDialog(CallRecord record) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete entry")
                .setMessage("Remove this call from your history?")
                .setPositiveButton("Delete", (d, w) -> {
                    executor.execute(() -> {
                        db.callHistoryDao().deleteById(record.id);
                        loadHistory();
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, 1, Menu.NONE, "Clear all")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Clear call history")
                    .setMessage("All call history will be deleted. This cannot be undone.")
                    .setPositiveButton("Clear all", (d, w) -> {
                        executor.execute(() -> {
                            db.callHistoryDao().deleteAll();
                            loadHistory();
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }
}
