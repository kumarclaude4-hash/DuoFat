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
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import com.duoshield.app.util.B2StorageHelper;
import com.duoshield.app.util.ChunkedMediaDataSource;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executors;

public class MediaViewerActivity extends BaseActivity {

    public static final String EXTRA_URL       = "media_url";
    public static final String EXTRA_MEDIA_KEY = "media_key";
    /**
     * Whether the object named by {@link #EXTRA_URL} is stored in the chunked v2 format.
     *
     * <p>Carried as an intent extra rather than sniffed off the object's leading version byte:
     * the byte is attacker-influenced, and letting it select the decrypt path would mean a
     * remote party choosing which crypto code runs. The flag originates on the message document
     * that named the object, so it is as trustworthy as the media key beside it. Defaults to
     * false, which is the truth for every object written before this format existed.
     */
    public static final String EXTRA_CHUNKED  = "media_chunked";

    private ExoPlayer   player;
    private PlayerView  playerView;
    private ProgressBar progressBar;
    private String      mediaRef;
    private String      mediaKey;
    private boolean     chunked;
    /**
     * Decrypted scratch file backing the player, deleted in {@link #onDestroy()}.
     *
     * <p>The previous implementation relied on {@code File.deleteOnExit()}, which only runs on
     * an orderly JVM shutdown — a process the OS kills to reclaim memory (the common fate of a
     * backgrounded app on a low-RAM device) never fires it, so decrypted video plaintext
     * accumulated in the cache directory indefinitely. Deleting on activity teardown is both
     * reliable and appropriate for an app whose whole premise is that plaintext does not sit
     * at rest.
     */
    private File        playbackFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);

        playerView  = findViewById(R.id.player_view);
        progressBar = findViewById(R.id.progress);
        mediaRef    = getIntent().getStringExtra(EXTRA_URL);
        mediaKey    = getIntent().getStringExtra(EXTRA_MEDIA_KEY);
        chunked     = getIntent().getBooleanExtra(EXTRA_CHUNKED, false);

        ImageButton btnClose    = findViewById(R.id.btn_close);
        ImageButton btnDownload = findViewById(R.id.btn_download);

        if (btnClose    != null) btnClose.setOnClickListener(v -> finish());
        if (btnDownload != null) btnDownload.setOnClickListener(v -> saveVideoToGallery());

        if (mediaRef != null) loadAndPlay();
    }

    // ── Load + play ───────────────────────────────────────────────────────────

    /**
     * Resolves the media to a decrypted file on disk and hands that file to ExoPlayer.
     *
     * <p>Uses {@link B2StorageHelper#loadMediaToFile} rather than
     * {@link B2StorageHelper#loadMedia}: playback needs a file, and the old path allocated the
     * encrypted blob plus the full decrypted plaintext in memory only to then write that
     * plaintext to a temp file anyway. Peak memory was 2–3× the video size to reach a state
     * that is simply "a file in the cache dir", which is a guaranteed OOM for a large video on
     * a low-RAM device. The streaming path holds a 256 KB buffer instead, so playback memory is
     * now flat regardless of file size.
     */
    private void loadAndPlay() {
        if (B2StorageHelper.isB2Path(mediaRef) && chunked) {
            // Chunked v2 object: hand ExoPlayer a DataSource that decrypts 1 MiB at a time and
            // let it pull. Nothing is written to disk, so there is no playbackFile to clean up
            // and the first frame renders after one header read plus one chunk rather than
            // after the whole object has landed and been decrypted end to end.
            showProgress(true);
            initChunkedPlayer();
            return;
        }
        if (B2StorageHelper.isB2Path(mediaRef)) {
            showProgress(true);
            File dest;
            try {
                dest = File.createTempFile("vid_view_", ".mp4", getCacheDir());
            } catch (Exception e) {
                showProgress(false);
                Toast.makeText(this, "Failed to prepare video", Toast.LENGTH_SHORT).show();
                return;
            }
            playbackFile = dest;
            B2StorageHelper.loadMediaToFile(this, mediaRef, mediaKey, dest,
                    new B2StorageHelper.FileCallback() {
                        @Override public void onReady(File plainFile) {
                            if (isDestroyed() || isFinishing()) return;
                            showProgress(false);
                            initPlayer(Uri.fromFile(plainFile));
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

    private void initPlayer(Uri uri) {
        if (isDestroyed() || isFinishing()) return;
        player = new ExoPlayer.Builder(this).build();
        if (playerView != null) playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.play();
    }

    /**
     * Builds a player that streams straight out of the encrypted object.
     *
     * <p>{@link ProgressiveMediaSource} drives a {@link ChunkedMediaDataSource} through the
     * standard extractor pipeline, so seeking needs no special handling here: the extractor
     * reopens the source at the requested plaintext position and the data source turns that
     * position into a chunk index arithmetically. The {@code duoshield-chunked://} URI is inert
     * — the factory reads the B2 path and media key from its own constructor arguments, and the
     * URI exists only because {@link MediaItem} requires one.
     *
     * <p>Marked {@code @UnstableApi} rather than opting in per call: {@code MediaSource},
     * {@code ProgressiveMediaSource} and {@code setMediaSource} are all unstable in media3
     * 1.3.1, and containing that to the one method that touches them keeps the rest of the
     * activity on the stable surface.
     */
    @UnstableApi
    private void initChunkedPlayer() {
        if (isDestroyed() || isFinishing()) return;
        player = new ExoPlayer.Builder(this).build();
        if (playerView != null) playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                // The spinner has to be dismissed by playback state rather than by a download
                // completing, because in streaming mode there is no download that completes.
                if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                    showProgress(false);
                } else if (state == Player.STATE_BUFFERING) {
                    showProgress(true);
                }
            }
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                // A failed chunk tag surfaces here. The object is either tampered with or
                // truncated; either way there is no partial playback to fall back to, because
                // no chunk is ever exposed before its tag verifies.
                showProgress(false);
                if (isDestroyed() || isFinishing()) return;
                Toast.makeText(MediaViewerActivity.this,
                        "Failed to play video", Toast.LENGTH_SHORT).show();
            }
        });
        // Opaque (scheme:ssp) rather than hierarchical, so the B2 path's own "b2:" prefix cannot
        // be misread as an authority by anything that inspects the URI along the way.
        MediaItem item = MediaItem.fromUri(
                Uri.fromParts(ChunkedMediaDataSource.SCHEME, mediaRef, null));
        player.setMediaSource(new ProgressiveMediaSource.Factory(
                new ChunkedMediaDataSource.Factory(mediaRef, mediaKey)).createMediaSource(item));
        player.prepare();
        player.play();
    }

    // ── Download to gallery ───────────────────────────────────────────────────

    /**
     * Saves the video to the gallery by streaming a plaintext file into the MediaStore.
     *
     * <p>When the video is already playing, {@link #playbackFile} holds the decrypted bytes,
     * so the save reuses it instead of re-downloading and re-decrypting the whole thing —
     * pressing download on a video you are watching is now disk-to-disk only. Otherwise the
     * file is streamed down to a scratch file first. Either way the plaintext is copied to the
     * MediaStore in 256 KB chunks and never held in memory as one array.
     */
    private void saveVideoToGallery() {
        if (mediaRef == null) return;
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show();

        if (!B2StorageHelper.isB2Path(mediaRef)) {
            Toast.makeText(this, "Cannot save this video format", Toast.LENGTH_SHORT).show();
            return;
        }

        File ready = playbackFile;
        if (ready != null && ready.exists() && ready.length() > 0) {
            Executors.newSingleThreadExecutor().execute(() -> writeVideoToGallery(ready, false));
            return;
        }

        File scratch;
        try {
            scratch = File.createTempFile("vid_save_", ".mp4", getCacheDir());
        } catch (Exception e) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show();
            return;
        }
        // Saving is the one operation that genuinely needs every byte, so it does a full
        // download + decrypt even in streaming mode — there is no playback file to reuse
        // because streaming never wrote one. The chunked flag still has to be threaded through
        // so the decrypt loop matches the format the object is actually in.
        B2StorageHelper.loadMediaToFile(this, mediaRef, mediaKey, scratch, chunked,
                new B2StorageHelper.FileCallback() {
                    @Override public void onReady(File plainFile) {
                        Executors.newSingleThreadExecutor()
                                .execute(() -> writeVideoToGallery(plainFile, true));
                    }
                    @Override public void onError(Exception e) {
                        //noinspection ResultOfMethodCallIgnored
                        scratch.delete();
                        if (isDestroyed() || isFinishing()) return;
                        Toast.makeText(MediaViewerActivity.this,
                                "Download failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /**
     * Copies {@code plainFile} into the gallery. Runs on a background thread.
     *
     * @param deleteAfter whether {@code plainFile} is a throwaway scratch file this method
     *                    owns. The playback file is still in use by ExoPlayer, so it is left
     *                    for {@link #onDestroy()} to clean up.
     */
    private void writeVideoToGallery(File plainFile, boolean deleteAfter) {
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
                try (InputStream in = new FileInputStream(plainFile);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("MediaStore stream unavailable");
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
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
        } finally {
            if (deleteAfter) {
                //noinspection ResultOfMethodCallIgnored
                plainFile.delete();
            }
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
        if (playbackFile != null) {
            //noinspection ResultOfMethodCallIgnored
            playbackFile.delete();
            playbackFile = null;
        }
    }
}
