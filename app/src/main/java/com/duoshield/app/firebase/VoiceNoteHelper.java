package com.duoshield.app.firebase;

import android.util.Log;

import com.duoshield.app.util.B2StorageHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * Voice-note helper — B2 storage.
 * Returns storage paths.
 * Note: ChatMediaActivity performs its own encrypted uploads directly via
 * B2StorageHelper.encryptForUpload().
 */
public class VoiceNoteHelper {

    private static final String TAG = "VoiceNoteHelper";

    public interface UploadCallback {
        void onSuccess(String storagePath);
        void onFailure(Exception e);
    }

    /**
     * @deprecated Use {@code ChatMediaActivity.uploadVoiceNote()} which encrypts before
     *             uploading via {@link B2StorageHelper#encryptForUpload(byte[])}.
     *             This overload is retained for binary compatibility only and will throw
     *             {@link UnsupportedOperationException} at runtime to prevent accidental
     *             unencrypted uploads.
     */
    @Deprecated
    public void uploadVoiceNote(String filePath, String convId, UploadCallback cb) {
        cb.onFailure(new UnsupportedOperationException(
            "uploadVoiceNote(UploadCallback) is deprecated — use ChatMediaActivity.uploadVoiceNote() " +
            "which encrypts the voice note before upload and stores the mediaKey in Firestore."));
    }

    /**
     * Resolves, downloads, and returns voice note bytes for {@code path}.
     * Delivers bytes on the main thread.
     * Pass the message's mediaKey to enable decryption.
     */
    public void loadVoiceNote(android.content.Context ctx, String path, String mediaKey,
                              B2StorageHelper.MediaCallback cb) {
        B2StorageHelper.loadMedia(ctx, path, mediaKey, cb);
    }

    private static byte[] readFile(File f) throws java.io.IOException {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }
}
