package com.duoshield.app.call;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.CallRecord;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CallHistoryActivity extends BaseActivity {

    private static final String TAG = "CallHistoryActivity";

    private CallHistoryAdapter adapter;
    private View               tvEmpty;
    private ExecutorService    executor;
    private AppDatabase        db;

    /** The MediaPlayer for the currently playing recording, or {@code null} when nothing plays. */
    private MediaPlayer player;

    /** Id of the record currently playing, or {@code null}. */
    private String playingRecordId;

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

        adapter = new CallHistoryAdapter(this::showDeleteDialog, this::togglePlayback);
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

    // ── Recording playback ────────────────────────────────────────────────────

    /**
     * Starts this row's recording, or stops it if it is already the one playing. Only one
     * recording plays at a time, so starting a second one stops the first.
     */
    private void togglePlayback(CallRecord record) {
        if (record.id.equals(playingRecordId)) {
            stopPlayback();
            return;
        }
        stopPlayback();

        File f = record.recordingPath == null ? null : new File(record.recordingPath);
        if (f == null || !f.exists()) {
            // The file vanished after the list was bound. Repaint so the button disappears
            // instead of failing again on the next tap.
            Toast.makeText(this, "Recording is no longer available", Toast.LENGTH_SHORT).show();
            loadHistory();
            return;
        }

        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> stopPlayback());
            player.setOnErrorListener((mp, what, extra) -> {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Could not play recording", Toast.LENGTH_SHORT).show();
                    stopPlayback();
                });
                return true;
            });
            player.prepare();
            player.start();

            playingRecordId = record.id;
            adapter.setPlayingRecordId(playingRecordId);
        } catch (Exception e) {
            Log.e(TAG, "playback failed", e);
            Toast.makeText(this, "Could not play recording", Toast.LENGTH_SHORT).show();
            stopPlayback();
        }
    }

    /** Releases the player and clears the playing indicator. Safe to call when nothing plays. */
    private void stopPlayback() {
        if (player != null) {
            try {
                player.reset();   // reset() before release() so a mid-prepare player can't throw
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
        if (playingRecordId != null) {
            playingRecordId = null;
            adapter.setPlayingRecordId(null);
        }
    }

    private void showDeleteDialog(CallRecord record) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete entry")
                .setMessage(record.recordingPath != null
                        ? "Remove this call from your history? The recording will be deleted too."
                        : "Remove this call from your history?")
                .setPositiveButton("Delete", (d, w) -> {
                    // Stop first: deleting the file under a running player leaves it playing from
                    // an unlinked descriptor, with a stop button on a row that no longer exists.
                    if (record.id.equals(playingRecordId)) stopPlayback();
                    executor.execute(() -> {
                        // Delete the file before the row, while the path is still readable.
                        CallRecordingStore.deleteRecordingFor(record);
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
