package com.duoshield.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import androidx.core.content.FileProvider;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Exports a conversation to a UTF-8 plain-text file (WhatsApp-style format).
 *
 * <p><b>F15 fix:</b> the previous implementation used {@link android.graphics.pdf.PdfDocument}
 * to write plaintext message bodies into a PDF, which (a) embedded the partial
 * conversation ID as a visible header, (b) used partial sender UIDs as identifiers,
 * and (c) produced a file that appears more "official" than it is.  This replacement
 * writes a simple line-per-message {@code .txt} file, removes the conversation-ID
 * header, and uses "Me" / short-UID labels to reduce unnecessary metadata exposure.
 */
public class ExportHelper {

    /**
     * Shows a prominent warning dialog before exporting the chat to a plain-text file.
     *
     * <p>Callers should always prefer this method over {@link #exportToText(Context, String)}
     * so that the user sees the unencrypted-export warning every time.
     *
     * @param activity The foreground Activity used to display the dialog.
     * @param convId   Firestore conversation ID to export.
     */
    public static void exportToPdfWithConfirmation(Activity activity, String convId) {
        new MaterialAlertDialogBuilder(activity)
            .setTitle("Export Chat — Unencrypted")
            .setMessage(
                "This creates a plain-text file of your full chat history.\n\n"
                + "⚠️  The file is NOT encrypted. Any app or person you share "
                + "it with can read every message.\n\n"
                + "The file will be removed from your device after sharing.")
            .setPositiveButton("Export", (d, w) -> exportToText(activity, convId))
            .setNegativeButton("Cancel", null)
            .show();
    }

    /**
     * @deprecated Kept for binary compatibility. Delegates to {@link #exportToText}.
     */
    @Deprecated
    public static void exportToPdf(Context ctx, String convId) {
        exportToText(ctx, convId);
    }

    /**
     * Exports the conversation to a UTF-8 {@code .txt} file and launches the
     * system share sheet.  Runs on a background thread.
     *
     * <p>Line format:
     * <pre>{@code [2026-07-16, 14:32] Me: Hello there}</pre>
     * <pre>{@code [2026-07-16, 14:33] a1b2c3d4: Hi!}</pre>
     *
     * @param ctx    Any {@link Context}; the export runs on a background thread.
     * @param convId Firestore conversation ID whose messages should be exported.
     */
    public static void exportToText(Context ctx, String convId) {
        new Thread(() -> {
            List<Message> msgs = AppDatabase.getInstance(ctx)
                                            .messageDao().getMessages(convId);

            String myUid = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE)
                              .getString("my_uid", null);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US);
            sdf.setTimeZone(TimeZone.getDefault());

            File outFile = new File(ctx.getCacheDir(),
                "duoshield_export_" + System.currentTimeMillis() + ".txt");

            try (FileWriter fw = new FileWriter(outFile)) {
                fw.write("DuoShield Chat Export\n");
                fw.write("Exported: " + sdf.format(new Date()) + "\n");
                fw.write("─────────────────────────────────────────────────\n");
                for (Message m : msgs) {
                    String time   = sdf.format(new Date(m.getTimestamp()));
                    String sender = deriveSenderLabel(m.getSender(), myUid);
                    String text   = MessageLabelHelper.describePlain(m)
                                  + (m.isEdited() ? " (edited)" : "");
                    fw.write("[" + time + "] " + sender + ": " + text + "\n");
                }
            } catch (IOException e) {
                Log.e("ExportHelper", "Text export failed for conv=" + convId, e);
                return;
            }

            try {
                Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".provider", outFile);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(Intent.createChooser(intent, "Export Chat")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.e("ExportHelper", "Share intent failed for conv=" + convId, e);
            }
        }).start();
    }

    /**
     * Returns a short, human-readable sender label.
     *
     * <ul>
     *   <li>{@code "Me"} when {@code senderUid} equals the local user's UID.</li>
     *   <li>The first 8 characters of the sender UID otherwise (enough for the
     *       recipient to cross-reference, but not the full internal identifier).</li>
     * </ul>
     */
    private static String deriveSenderLabel(String senderUid, String myUid) {
        if (senderUid == null) return "Unknown";
        if (senderUid.equals(myUid)) return "Me";
        return senderUid.substring(0, Math.min(8, senderUid.length()));
    }
}
