package com.duoshield.app.ui;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.util.B2StorageHelper;
import com.google.android.material.button.MaterialButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StorageDiagnosticsActivity extends BaseActivity {

    private static final int COLOR_OK      = 0xFF6BBF8A;
    private static final int COLOR_ERROR   = 0xFFD96A7C;
    private static final int COLOR_NEUTRAL = 0xFF9A8FB0;

    private TextView       tvStatusLabel, tvLatency, tvBucket, tvRegion,
                           tvEndpoint, tvKeyId, tvCredentials,
                           tvErrorDetail, labelErrorDetail;
    private View           statusDot, cardError;
    private ProgressBar    progressTest;
    private MaterialButton btnRunTest;

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Defense in depth: this screen exposes raw B2 bucket names, key IDs, and
        // endpoints for local testing only. Never allow it to run in a release
        // build even if something else manages to launch it (see UX audit #5).
        if (!com.duoshield.app.BuildConfig.DEBUG) {
            finish();
            return;
        }
        setContentView(R.layout.activity_storage_diagnostics);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvStatusLabel    = findViewById(R.id.tvStatusLabel);
        tvLatency        = findViewById(R.id.tvLatency);
        tvBucket         = findViewById(R.id.tvBucket);
        tvRegion         = findViewById(R.id.tvRegion);
        tvEndpoint       = findViewById(R.id.tvEndpoint);
        tvKeyId          = findViewById(R.id.tvKeyId);
        tvCredentials    = findViewById(R.id.tvCredentials);
        tvErrorDetail    = findViewById(R.id.tvErrorDetail);
        labelErrorDetail = findViewById(R.id.labelErrorDetail);
        statusDot        = findViewById(R.id.statusDot);
        cardError        = findViewById(R.id.cardError);
        progressTest     = findViewById(R.id.progressTest);
        btnRunTest       = findViewById(R.id.btnRunTest);

        populateStaticConfig();

        btnRunTest.setOnClickListener(v -> runTest());

        runTest();
    }

    private void populateStaticConfig() {
        String workerUrl = B2StorageHelper.getWorkerUrl();
        boolean usingWorker = !workerUrl.isEmpty();

        if (usingWorker) {
            tvBucket.setText("(managed by Cloudflare R2 + B2 Worker)");
            tvRegion.setText("(managed by Worker cron)");
            tvEndpoint.setText(workerUrl);
        } else {
            tvBucket.setText(B2StorageHelper.getBucket());
            tvRegion.setText(B2StorageHelper.getRegion());
            tvEndpoint.setText(B2StorageHelper.getEndpoint());
        }
        tvKeyId.setText(B2StorageHelper.getMaskedKeyId());

        if (usingWorker) {
            tvCredentials.setText("Cloudflare Worker configured ✓ (R2 hot → B2 cold, 180-day purge)");
            tvCredentials.setTextColor(COLOR_OK);
        } else if (B2StorageHelper.areCredentialsConfigured()) {
            tvCredentials.setText("Configured ✓");
            tvCredentials.setTextColor(COLOR_OK);
        } else {
            tvCredentials.setText("Missing — set WORKER_URL or B2_KEY_ID + B2_APPLICATION_KEY");
            tvCredentials.setTextColor(COLOR_ERROR);
        }
    }

    private void runTest() {
        if (isFinishing() || isDestroyed()) return;

        btnRunTest.setEnabled(false);
        btnRunTest.setText("Testing…");
        tvStatusLabel.setText("Connecting to Backblaze B2…");
        tvStatusLabel.setTextColor(COLOR_NEUTRAL);
        tvLatency.setText("");
        statusDot.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(COLOR_NEUTRAL));
        progressTest.setVisibility(View.VISIBLE);
        cardError.setVisibility(View.GONE);
        labelErrorDetail.setVisibility(View.GONE);

        executor.execute(() -> {
            String[] result = B2StorageHelper.testConnectionTimed();
            String errorMsg   = result[0];
            long   latencyMs  = Long.parseLong(result[1]);

            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;

                progressTest.setVisibility(View.GONE);
                btnRunTest.setEnabled(true);
                btnRunTest.setText("Run Test");
                tvLatency.setText(latencyMs + " ms");

                if (errorMsg == null) {
                    tvStatusLabel.setText("Connected — bucket reachable");
                    tvStatusLabel.setTextColor(COLOR_OK);
                    statusDot.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(COLOR_OK));
                } else {
                    tvStatusLabel.setText("Connection failed");
                    tvStatusLabel.setTextColor(COLOR_ERROR);
                    statusDot.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(COLOR_ERROR));
                    tvErrorDetail.setText(errorMsg);
                    cardError.setVisibility(View.VISIBLE);
                    labelErrorDetail.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
