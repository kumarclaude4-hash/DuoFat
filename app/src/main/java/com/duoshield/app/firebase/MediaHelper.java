package com.duoshield.app.firebase;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.duoshield.app.util.B2StorageHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Media helper — B2 storage.
 * Uploads media files and returns storage paths.
 * Note: ChatMediaActivity performs its own encrypted uploads directly via
 * B2StorageHelper.encryptForUpload().
 */
public class MediaHelper {

    private static final String TAG = "MediaHelper";

    public interface UploadCallback {
        void onSuccess(String storagePath);
        void onFailure(Exception e);
    }

    /**
     * Callback for encrypted uploads.  Returns both the storage path and the
     * per-file AES-256-GCM key (Base64) needed for decryption (BUG-S02).
     */
    public interface EncryptedUploadCallback {
        void onSuccess(String storagePath, String mediaKeyBase64);
        void onFailure(Exception e);
    }

    /**
     * Uploads a file from {@code fileUri} to B2.
     *
     * <p>BUG-S02 fix: bytes are AES-256-GCM encrypted before upload via
     * {@link B2StorageHelper#encryptForUpload(byte[])}.  The resulting
     * {@code mediaKey} must be stored in the Firestore message doc so the
     * recipient can decrypt.
     *
     * @param ctx      Used to open the URI.
     * @param fileUri  Source URI (image or video).
     * @param path     Storage path, e.g. {@code "media/chatId/uuid.jpg"}.
     * @param mimeType MIME type of the source file.
     * @param cb       Callback delivering path + mediaKey on success.
     */
    public void uploadMedia(Context ctx, Uri fileUri, String path,
                            String mimeType, EncryptedUploadCallback cb) {
        new Thread(() -> {
            try {
                byte[] plain = readUri(ctx, fileUri);
                B2StorageHelper.EncryptedMedia em =
                        B2StorageHelper.encryptForUpload(plain);
                String stored = B2StorageHelper.uploadFile(em.data, path, mimeType, null);
                cb.onSuccess(stored, em.keyBase64);
            } catch (Exception e) {
                Log.w(TAG, "uploadMedia failed: " + e.getMessage());
                cb.onFailure(e);
            }
        }, "media-upload").start();
    }

    /**
     * @deprecated Use {@link #uploadMedia(Context, Uri, String, String, EncryptedUploadCallback)}
     *             which encrypts before upload. This overload is kept for binary compatibility
     *             only and will throw {@link UnsupportedOperationException} at runtime.
     */
    @Deprecated
    public void uploadMedia(Context ctx, Uri fileUri, String path,
                            String mimeType, UploadCallback cb) {
        cb.onFailure(new UnsupportedOperationException(
            "uploadMedia with UploadCallback is deprecated — use EncryptedUploadCallback " +
            "to receive the mediaKey required for decryption (BUG-S02)."));
    }

    /**
     * Resolves, downloads, and returns media bytes for {@code path}.
     * Delivers bytes on the main thread via {@code cb}.
     * Pass the message's mediaKey to enable decryption.
     */
    public void loadMedia(Context ctx, String path, String mediaKey,
                          B2StorageHelper.MediaCallback cb) {
        B2StorageHelper.loadMedia(ctx, path, mediaKey, cb);
    }

    private static byte[] readUri(Context ctx, Uri uri) throws java.io.IOException {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) throw new java.io.IOException("Cannot open: " + uri);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } finally { is.close(); }
    }
}
