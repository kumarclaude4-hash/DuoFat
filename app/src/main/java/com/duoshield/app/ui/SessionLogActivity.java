package com.duoshield.app.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.SessionEvent;
import com.duoshield.app.util.SessionLogger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class SessionLogActivity extends BaseActivity {

    private RecyclerView rvSessionLog;
    private TextView     tvSessionEmpty;
    private Adapter      adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // FLAG_SECURE is applied conditionally by BaseActivity.onCreate() via app_screenshot_enabled.
        setContentView(R.layout.activity_session_log);

        Toolbar toolbar = findViewById(R.id.sessionLogToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvSessionEmpty = findViewById(R.id.tvSessionEmpty);
        rvSessionLog   = findViewById(R.id.rvSessionLog);
        rvSessionLog.setLayoutManager(new LinearLayoutManager(this));

        adapter = new Adapter(new ArrayList<>());
        rvSessionLog.setAdapter(adapter);

        findViewById(R.id.btnClearLog).setOnClickListener(v -> confirmClearLog());

        loadEvents();
    }

    private void loadEvents() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<SessionEvent> events;
            try {
                events = AppDatabase.getInstance(getApplicationContext())
                        .sessionEventDao().getAll();
            } catch (Exception e) {
                events = new ArrayList<>();
            }
            final List<SessionEvent> result = events;
            runOnUiThread(() -> {
                adapter.setEvents(result);
                tvSessionEmpty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                rvSessionLog.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void confirmClearLog() {
        new AlertDialog.Builder(this)
            .setTitle("Clear Session Log")
            .setMessage("Delete all recorded session events? This cannot be undone.")
            .setPositiveButton("Clear", (d, w) -> clearLog())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void clearLog() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                AppDatabase.getInstance(getApplicationContext())
                        .sessionEventDao().deleteAll();
            } catch (Exception e) {
                android.util.Log.e("SessionLog", "Failed to clear session log", e);
            }
            runOnUiThread(() -> {
                adapter.setEvents(new ArrayList<>());
                tvSessionEmpty.setVisibility(View.VISIBLE);
                rvSessionLog.setVisibility(View.GONE);
                Toast.makeText(this, "Session log cleared.", Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ── RecyclerView adapter ──────────────────────────────────────────────────

    private static class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        private static final SimpleDateFormat SDF =
                new SimpleDateFormat("dd MMM yyyy  HH:mm:ss", Locale.getDefault());

        private List<SessionEvent> events;

        Adapter(List<SessionEvent> events) { this.events = events; }

        void setEvents(List<SessionEvent> events) {
            this.events = events;
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_session_event, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            SessionEvent e = events.get(pos);

            // Resolve human-readable label and indicator colour per event type
            String label;
            int    color;
            switch (e.eventType == null ? "" : e.eventType) {
                case SessionLogger.SIGN_IN:
                    label = "Signed in";
                    color = Color.parseColor("#4CAF50"); // green
                    break;
                case SessionLogger.SIGN_OUT:
                    label = "Signed out";
                    color = Color.parseColor("#9E9E9E"); // grey
                    break;
                case SessionLogger.AUTO_SIGN_OUT:
                    label = "Auto sign-out (inactivity)";
                    color = Color.parseColor("#FF9800"); // amber
                    break;
                case SessionLogger.DURESS_LOGOUT:
                    // F35 fix: Render any legacy DURESS_LOGOUT row identically to a normal
                    // sign-out. New duress logouts no longer write this event type (they write
                    // SIGN_OUT via DuressManager.performLogout → SessionLogger.logSync), but
                    // rows that existed before this fix must not betray the user.
                    label = "Signed out";
                    color = Color.parseColor("#9E9E9E"); // grey — same as SIGN_OUT
                    break;
                default:
                    label = e.eventType != null ? e.eventType : "Unknown";
                    color = Color.parseColor("#607D8B"); // blue-grey
            }

            h.tvEventType.setText(label);
            h.tvTimestamp.setText(SDF.format(new Date(e.timestamp)));
            h.tvDevice.setText(e.deviceModel + "  ·  " + e.androidVersion);
            h.viewIndicator.setBackgroundColor(color);
        }

        @Override public int getItemCount() { return events.size(); }

        static class VH extends RecyclerView.ViewHolder {
            View     viewIndicator;
            TextView tvEventType, tvTimestamp, tvDevice;
            VH(View v) {
                super(v);
                viewIndicator = v.findViewById(R.id.viewIndicator);
                tvEventType   = v.findViewById(R.id.tvEventType);
                tvTimestamp   = v.findViewById(R.id.tvTimestamp);
                tvDevice      = v.findViewById(R.id.tvDevice);
            }
        }
    }
}
