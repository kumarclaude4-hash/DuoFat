package com.duoshield.app.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Shares decrypted media bytes with other apps via {@code ACTION_SEND}.
 *
 * <p>The previous implementation re-downloaded the Supabase blob (which is
 * AES-GCM ciphertext) and shared the raw ciphertext as a "JPEG".  Recipients
 * received an undecodable file, and expired signed URLs caused silent failures
 * (BUG-E02).
 *
 * <p>Callers must now pass already-decrypted bytes from memory.  This
 * eliminates the HTTP round-trip, the signed-URL race, and the ciphertext-
 * sharing bug.  The {@code share_*.jpg} temp file is removed by
 * {@link TempFileCleaner} (BUG-SD02 fix also included in Wave-1).
 */
public class SecureShareHelper {

    public static void shareImage(Context ctx, byte[] decryptedBytes) {
        new Thread(() -> {
            File out = new File(ctx.getCacheDir(), "share_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(decryptedBytes);
            } catch (Exception e) {
                android.util.Log.e("SecureShareHelper", "Failed to write share temp file", e);
                return;
            }
            try {
                Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".provider", out);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/jpeg");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(intent, "Share Image")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                android.util.Log.e("SecureShareHelper", "Failed to launch share chooser", e);
            }
        }).start();
    }
}
