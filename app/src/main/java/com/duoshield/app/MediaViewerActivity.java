package com.duoshield.app;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.duoshield.app.util.B2StorageHelper;


import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;

public class MediaViewerActivity extends BaseActivity {

    public static final String EXTRA_URL       = "media_url";
    public static final String EXTRA_MEDIA_KEY = "media_key";

    private ExoPlayer   player;
    private PlayerView  playerView;
    private ProgressBar progressBar;
    private String      mediaRef;
    private String      mediaKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);

        playerView  = findViewById(R.id.player_view);
        progressBar = findViewById(R.id.progress);
        mediaRef    = getIntent().getStringExtra(EXTRA_URL);
        mediaKey    = getIntent().getStringExtra(EXTRA_MEDIA_KEY);

        ImageButton btnClose    = findViewById(R.id.btn_close);
        ImageButton btnDownload = findViewById(R.id.btn_download);

        if (btnClose    != null) btnClose.setOnClickListener(v -> finish());
        if (btnDownload != null) btnDownload.setOnClickListener(v -> saveVideoToGallery());

        if (mediaRef != null) loadAndPlay();
    }

    // ── Load + play ───────────────────────────────────────────────────────────

    private void loadAndPlay() {
        if (B2StorageHelper.isB2Path(mediaRef)) {
            showProgress(true);
            B2StorageHelper.loadMedia(this, mediaRef, mediaKey, new B2StorageHelper.MediaCallback() {
                @Override public void onLoaded(byte[] plainBytes) {
                    if (isDestroyed() || isFinishing()) return;
                    writeTempAndPlay(plainBytes);
                }
                @Override public void onError(Exception e) {
                    if (isDestroyed() || isFinishing()) return;
                    showProgress(false);
                    Toast.makeText(MediaViewerActivity.this,
                            "Failed to load video", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            // Legacy public URL — play directly
            showProgress(false);
            initPlayer(Uri.parse(mediaRef));
        }
    }

    private void writeTempAndPlay(byte[] plainBytes) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File tmp = File.createTempFile("vid_view_", ".mp4", getCacheDir());
                try (FileOutputStream fos = new FileOutputStream(tmp)) { fos.write(plainBytes); }
                tmp.deleteOnExit();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    showProgress(false);
                    initPlayer(Uri.fromFile(tmp));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    showProgress(false);
                    Toast.makeText(MediaViewerActivity.this,
                            "Failed to prepare video", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void initPlayer(Uri uri) {
        if (isDestroyed() || isFinishing()) return;
        player = new ExoPlayer.Builder(this).build();
        if (playerView != null) playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.play();
    }

    // ── Download to gallery ───────────────────────────────────────────────────

    private void saveVideoToGallery() {
        if (mediaRef == null) return;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();

        if (B2StorageHelper.isB2Path(mediaRef)) {
            B2StorageHelper.loadMedia(this, mediaRef, mediaKey, new B2StorageHelper.MediaCallback() {
                @Override public void onLoaded(byte[] plainBytes) {
                    Executors.newSingleThreadExecutor().execute(() -> writeVideoToGallery(plainBytes));
                }
                @Override public void onError(Exception e) {
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() -> Toast.makeText(MediaViewerActivity.this,
                            "Download failed", Toast.LENGTH_SHORT).show());
                }
            });
        } else {
            Toast.makeText(this, "Cannot save this video format", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeVideoToGallery(byte[] bytes) {
        try {
            ContentValues values = new ContentValues();
            values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    "duoshield_" + System.currentTimeMillis() + ".mp4");
            values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/DuoShield");
            }
            Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out != null) {
                    try { out.write(bytes); } finally { out.close(); }
                }
                // F37 fix: record the URI so wipe/duress logout can delete it from the gallery.
                com.duoshield.app.util.MediaStoreWipeHelper.recordUri(
                        MediaViewerActivity.this, uri);
            }
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing())
                    Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing())
                    Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
            });
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private void showProgress(boolean show) {
        if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override protected void onPause()   { super.onPause(); if (player != null) player.pause(); }
    @Override protected void onResume()  { super.onResume(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
    }
}
