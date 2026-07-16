package com.duoshield.app;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.github.chrisbanes.photoview.PhotoView;
import com.duoshield.app.util.B2StorageHelper;


import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.concurrent.Executors;

public class FullScreenImageActivity extends BaseActivity {

    public static final String EXTRA_URL       = "image_url";
    public static final String EXTRA_MEDIA_KEY = "media_key";

    private PhotoView   photoView;
    private ProgressBar progressBar;
    private String      imageUrl;
    private String      mediaKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen_image);

        photoView   = findViewById(R.id.photo_view);
        progressBar = findViewById(R.id.progress_bar);
        imageUrl    = getIntent().getStringExtra(EXTRA_URL);
        mediaKey    = getIntent().getStringExtra(EXTRA_MEDIA_KEY);

        ImageButton btnClose = findViewById(R.id.btn_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());

        ImageButton btnSave = findViewById(R.id.btn_save);
        if (btnSave != null) btnSave.setOnClickListener(v -> saveImageToGallery());

        ImageButton btnShare = findViewById(R.id.btn_share);
        if (btnShare != null) btnShare.setOnClickListener(v -> shareImage());

        if (imageUrl != null && photoView != null) loadImageIntoViewer();
    }

    // ── Image loading (decrypt-first for B2 / Supabase) ───────────────────────

    private void showProgress(boolean show) {
        if (progressBar != null) progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Decodes bytes to a Bitmap and hands it to PhotoView directly.
     *  Glide.load(byte[]) triggers PhotoView layout bugs on some devices. */
    private void displayBytes(byte[] plainBytes) {
        if (isDestroyed() || isFinishing()) return;
        Bitmap bmp = BitmapFactory.decodeByteArray(plainBytes, 0, plainBytes.length);
        showProgress(false);
        if (bmp != null) {
            photoView.setImageBitmap(bmp);
        } else {
            // fallback — let Glide try (may still work for some formats)
            Glide.with(FullScreenImageActivity.this).load(plainBytes).into(photoView);
        }
    }

    // Timeout handler: if neither onLoaded nor onError fires within 30 s,
    // clear the spinner so the screen isn't stuck indefinitely.
    private final android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable loadTimeoutRunnable = () -> {
        if (isDestroyed() || isFinishing()) return;
        showProgress(false);
        Toast.makeText(this, "Image is taking too long to load. Check your connection.", Toast.LENGTH_LONG).show();
    };

    private void loadImageIntoViewer() {
        showProgress(true);
        timeoutHandler.postDelayed(loadTimeoutRunnable, 30_000);

        if (B2StorageHelper.isB2Path(imageUrl)) {
            if (mediaKey != null && !mediaKey.isEmpty()) {
                // Encrypted media (chat photos / videos)
                B2StorageHelper.loadMedia(this, imageUrl, mediaKey, new B2StorageHelper.MediaCallback() {
                    @Override public void onLoaded(byte[] plainBytes) {
                        timeoutHandler.removeCallbacks(loadTimeoutRunnable);
                        displayBytes(plainBytes);
                    }
                    @Override public void onError(Exception e) {
                        timeoutHandler.removeCallbacks(loadTimeoutRunnable);
                        if (isDestroyed() || isFinishing()) return;
                        showProgress(false);
                        android.util.Log.e("FullScreenImage", "B2 media load error", e);
                        Toast.makeText(FullScreenImageActivity.this,
                                "Failed to load image — " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                // Avatar (SigV4 authenticated, not AES-encrypted — use loadAvatarBytes)
                B2StorageHelper.loadAvatarBytes(imageUrl, new B2StorageHelper.MediaCallback() {
                    @Override public void onLoaded(byte[] bytes) {
                        timeoutHandler.removeCallbacks(loadTimeoutRunnable);
                        displayBytes(bytes);
                    }
                    @Override public void onError(Exception e) {
                        timeoutHandler.removeCallbacks(loadTimeoutRunnable);
                        if (isDestroyed() || isFinishing()) return;
                        showProgress(false);
                        android.util.Log.e("FullScreenImage", "B2 avatar load error", e);
                        Toast.makeText(FullScreenImageActivity.this,
                                "Could not load photo", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } else {
            // Local file:// URI or legacy plain HTTPS URL — Glide handles both
            timeoutHandler.removeCallbacks(loadTimeoutRunnable);
            showProgress(false);
            Glide.with(this).load(
                imageUrl.startsWith("file://") ? android.net.Uri.parse(imageUrl) : imageUrl
            ).into(photoView);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timeoutHandler.removeCallbacks(loadTimeoutRunnable);
    }

    // ── Save to gallery ───────────────────────────────────────────────────────

    private void saveImageToGallery() {
        if (imageUrl == null) return;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();

        if (B2StorageHelper.isB2Path(imageUrl)) {
            B2StorageHelper.loadMedia(this, imageUrl, mediaKey, new B2StorageHelper.MediaCallback() {
                @Override public void onLoaded(byte[] plainBytes) {
                    Executors.newSingleThreadExecutor().execute(() -> writeImageToGallery(plainBytes));
                }
                @Override public void onError(Exception e) {
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() -> Toast.makeText(FullScreenImageActivity.this,
                            "Failed to decrypt image", Toast.LENGTH_SHORT).show());
                }
            });
        } else {
            // Legacy public URL
            Executors.newSingleThreadExecutor().execute(() -> {
                try (InputStream in = new URL(imageUrl).openStream()) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
                    writeImageToGallery(baos.toByteArray());
                } catch (Exception e) {
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() -> Toast.makeText(FullScreenImageActivity.this,
                            "Save failed", Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private void writeImageToGallery(byte[] plainBytes) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "duoshield_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DuoShield");
            }
            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out != null) {
                    try { out.write(plainBytes); } finally { out.close(); }
                }
                // F37 fix: record the URI so wipe/duress logout can delete it from the gallery.
                com.duoshield.app.util.MediaStoreWipeHelper.recordUri(
                        FullScreenImageActivity.this, uri);
            }
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing())
                    Toast.makeText(FullScreenImageActivity.this,
                            "Saved to gallery", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing())
                    Toast.makeText(FullScreenImageActivity.this,
                            "Save failed", Toast.LENGTH_SHORT).show();
            });
        }
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    private void shareImage() {
        if (imageUrl == null) return;
        Toast.makeText(this, "Preparing image…", Toast.LENGTH_SHORT).show();

        if (B2StorageHelper.isB2Path(imageUrl)) {
            B2StorageHelper.loadMedia(this, imageUrl, mediaKey, new B2StorageHelper.MediaCallback() {
                @Override public void onLoaded(byte[] plainBytes) {
                    if (isDestroyed() || isFinishing()) return;
                    com.duoshield.app.util.SecureShareHelper.shareImage(
                            FullScreenImageActivity.this, plainBytes);
                }
                @Override public void onError(Exception e) {
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() -> Toast.makeText(FullScreenImageActivity.this,
                            "Share failed", Toast.LENGTH_SHORT).show());
                }
            });
        } else {
            // Legacy public URL
            Executors.newSingleThreadExecutor().execute(() -> {
                try (InputStream in = new URL(imageUrl).openStream()) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
                    final byte[] bytes = baos.toByteArray();
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() ->
                            com.duoshield.app.util.SecureShareHelper.shareImage(
                                    FullScreenImageActivity.this, bytes));
                } catch (Exception e) {
                    if (isDestroyed() || isFinishing()) return;
                    runOnUiThread(() -> Toast.makeText(FullScreenImageActivity.this,
                            "Share failed", Toast.LENGTH_SHORT).show());
                }
            });
        }
    }
}
