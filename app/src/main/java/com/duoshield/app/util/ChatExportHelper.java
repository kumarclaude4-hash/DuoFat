package com.duoshield.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Contact;
import com.duoshield.app.models.GroupMember;
import com.duoshield.app.models.Message;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports a full conversation (1:1 or group) to a single ZIP archive:
 *
 * <pre>
 * DuoShield_Export_&lt;ts&gt;.zip
 *  ├─ Chat.txt                        (full plain-text transcript)
 *  ├─ Voice Messages/
 *  │   ├─ Me/...
 *  │   └─ &lt;Other sender&gt;/...
 *  ├─ Photos/
 *  │   ├─ Me/...
 *  │   └─ &lt;Other sender&gt;/...
 *  └─ Videos/
 *      ├─ Me/...
 *      └─ &lt;Other sender&gt;/...
 * </pre>
 *
 * Media is optional (user choice at export time) and, for groups, is split
 * into one sub-folder per sender rather than just two — the same per-sender
 * separation rule applies regardless of chat type. Category folders are only
 * created when they actually contain at least one file.
 */
public final class ChatExportHelper {

    private static final String TAG = "ChatExportHelper";

    private ChatExportHelper() {}

    // ── Entry point ──────────────────────────────────────────────────────────

    /**
     * Shows the unencrypted-export warning with an "include media" checkbox,
     * then kicks off the export on confirmation.
     *
     * @param partnerUid required for 1:1 chats (used to resolve the other
     *                   participant's display name); pass {@code null} for groups.
     */
    public static void showExportDialog(Activity activity, String conversationId,
                                         String partnerUid, boolean isGroup) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (24 * dp);
        container.setPadding(padH, (int) (4 * dp), padH, 0);

        CheckBox includeMediaBox = new CheckBox(activity);
        includeMediaBox.setText("Include photos, videos & voice messages");
        includeMediaBox.setChecked(true);
        container.addView(includeMediaBox);

        new MaterialAlertDialogBuilder(activity)
            .setTitle("Export Chat — Unencrypted")
            .setMessage(
                "This creates a ZIP file with your full chat as a text file, and "
                + "optionally the media that was sent, sorted into folders by sender.\n\n"
                + "⚠️  Nothing in the ZIP is encrypted. Anyone you share it with can "
                + "read every message and view every media file.")
            .setView(container)
            .setPositiveButton("Export", (d, w) ->
                exportChat(activity, conversationId, partnerUid, includeMediaBox.isChecked(), isGroup))
            .setNegativeButton("Cancel", null)
            .show();
    }

    /** Runs the export on a background thread and shares the resulting ZIP when done. */
    public static void exportChat(Context ctx, String conversationId, String partnerUid,
                                   boolean includeMedia, boolean isGroup) {
        Context appCtx = ctx.getApplicationContext();
        Toast.makeText(ctx, "Preparing export…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                doExport(appCtx, conversationId, partnerUid, includeMedia, isGroup);
            } catch (Exception e) {
                Log.e(TAG, "Chat export failed for conv=" + conversationId, e);
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(appCtx, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ── Core export ──────────────────────────────────────────────────────────

    private static void doExport(Context ctx, String conversationId, String partnerUid,
                                  boolean includeMedia, boolean isGroup) throws IOException {
        AppDatabase db = AppDatabase.getInstance(ctx);
        List<Message> msgs = db.messageDao().getMessages(conversationId);

        SharedPreferences prefs = ctx.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);
        String myUid = prefs.getString("my_uid", null);

        Map<String, String> senderLabel = buildSenderLabels(db, conversationId, myUid, partnerUid, isGroup);

        File workDir = new File(ctx.getCacheDir(), "chat_export_" + System.currentTimeMillis());
        deleteRecursive(workDir);
        if (!workDir.mkdirs()) throw new IOException("Could not create export working directory");

        writeTranscript(new File(workDir, "Chat.txt"), msgs, senderLabel);

        int saved = 0, failed = 0;
        if (includeMedia) {
            for (Message m : msgs) {
                if (m.isDeleted()) continue;
                String sender = senderLabel.get(m.getSender());
                if (sender == null) sender = sanitize(shortUid(m.getSender()));
                String type = m.getMediaType();

                if ("album".equals(type) && m.getMediaItems() != null) {
                    try {
                        JSONArray arr = new JSONArray(m.getMediaItems());
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.getJSONObject(i);
                            String itemType = item.optString("type", "image");
                            boolean isVideo = "video".equals(itemType);
                            String category = isVideo ? "Videos" : "Photos";
                            String ext = isVideo ? ".mp4" : ".jpg";
                            boolean ok = saveMediaFile(workDir, category, sender,
                                item.optString("path", null), item.optString("key", null),
                                m.getId() + "_" + i + ext);
                            if (ok) saved++; else failed++;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "album mediaItems parse failed for msg=" + m.getId(), e);
                        failed++;
                    }
                } else if ("voice".equals(type)) {
                    if (saveMediaFile(workDir, "Voice Messages", sender,
                            m.getMediaUrl(), m.getMediaKey(), m.getId() + ".m4a")) saved++; else failed++;
                } else if ("image".equals(type)) {
                    if (saveMediaFile(workDir, "Photos", sender,
                            m.getMediaUrl(), m.getMediaKey(), m.getId() + ".jpg")) saved++; else failed++;
                } else if ("video".equals(type)) {
                    if (saveMediaFile(workDir, "Videos", sender,
                            m.getMediaUrl(), m.getMediaKey(), m.getId() + ".mp4")) saved++; else failed++;
                }
            }
        }

        File zipFile = new File(ctx.getCacheDir(), "DuoShield_Export_" + System.currentTimeMillis() + ".zip");
        zipDirectory(workDir, zipFile);
        deleteRecursive(workDir);

        int finalSaved = saved, finalFailed = failed;
        new Handler(Looper.getMainLooper()).post(() -> {
            shareZip(ctx, zipFile);
            String msg = includeMedia
                ? "Export ready — " + finalSaved + " media file(s) included"
                  + (finalFailed > 0 ? ", " + finalFailed + " failed" : "")
                : "Export ready";
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        });
    }

    // ── Sender → folder/label resolution ────────────────────────────────────

    private static Map<String, String> buildSenderLabels(AppDatabase db, String conversationId,
                                                           String myUid, String partnerUid, boolean isGroup) {
        Map<String, String> map = new HashMap<>();
        if (myUid != null) map.put(myUid, "Me");

        if (isGroup) {
            List<GroupMember> members = db.groupDao().getMembersOf(conversationId);
            for (GroupMember gm : members) {
                if (myUid != null && myUid.equals(gm.memberUid)) continue;
                map.put(gm.memberUid, labelFor(gm.displayName, gm.memberUid));
            }
        } else if (partnerUid != null) {
            Contact c = db.contactDao().getByUid(partnerUid);
            String name = (c != null) ? c.displayName : null;
            map.put(partnerUid, labelFor(name, partnerUid));
        }
        return map;
    }

    /** "DisplayName (a1b2c3d4)" when a name is known, otherwise just the short UID. */
    private static String labelFor(String displayName, String uid) {
        String uidShort = shortUid(uid);
        String label = (displayName != null && !displayName.trim().isEmpty())
            ? displayName.trim() + " (" + uidShort + ")"
            : uidShort;
        return sanitize(label);
    }

    private static String shortUid(String uid) {
        if (uid == null) return "unknown";
        return uid.substring(0, Math.min(8, uid.length()));
    }

    /** Strips characters that are illegal in file/folder names on Android's filesystem. */
    private static String sanitize(String name) {
        if (name == null) return "Unknown";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "Unknown" : cleaned;
    }

    // ── Transcript ───────────────────────────────────────────────────────────

    private static void writeTranscript(File outFile, List<Message> msgs,
                                         Map<String, String> senderLabel) throws IOException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd, HH:mm", Locale.US);
        sdf.setTimeZone(TimeZone.getDefault());

        try (FileWriter fw = new FileWriter(outFile)) {
            fw.write("DuoShield Chat Export\n");
            fw.write("Exported: " + sdf.format(new Date()) + "\n");
            fw.write("─────────────────────────────────────────────────\n");
            for (Message m : msgs) {
                if (m.isDeleted()) continue;
                String time   = sdf.format(new Date(m.getTimestamp()));
                String sender = senderLabel.getOrDefault(m.getSender(), shortUid(m.getSender()));
                String text   = MessageLabelHelper.describePlain(m) + (m.isEdited() ? " (edited)" : "");
                fw.write("[" + time + "] " + sender + ": " + text + "\n");
            }
        }
    }

    // ── Media download + save ───────────────────────────────────────────────

    /**
     * Downloads (or reuses the in-memory cache for) one encrypted media item and
     * writes the decrypted bytes to {@code workDir/category/sender/fileName}.
     * Returns {@code false} without throwing on any failure so a single bad file
     * never aborts the whole export.
     */
    private static boolean saveMediaFile(File workDir, String category, String sender,
                                          String b2Path, String keyBase64, String fileName) {
        if (b2Path == null || b2Path.isEmpty() || !B2StorageHelper.isB2Path(b2Path)) return false;
        try {
            byte[] bytes = B2StorageHelper.getCached(b2Path);
            if (bytes == null) {
                byte[] raw = B2StorageHelper.downloadFile(b2Path);
                bytes = B2StorageHelper.decryptAfterDownload(raw, keyBase64);
            }
            File dir = new File(new File(workDir, category), sender);
            if (!dir.exists() && !dir.mkdirs()) return false;
            File out = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to export media " + b2Path, e);
            return false;
        }
    }

    // ── Zip + share ──────────────────────────────────────────────────────────

    private static void zipDirectory(File sourceDir, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            zipRecursive(sourceDir, sourceDir, zos);
        }
    }

    private static void zipRecursive(File root, File current, ZipOutputStream zos) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                zipRecursive(root, f, zos);
                continue;
            }
            String relPath = root.toURI().relativize(f.toURI()).getPath();
            zos.putNextEntry(new ZipEntry(relPath));
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) zos.write(buf, 0, n);
            }
            zos.closeEntry();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private static void shareZip(Context ctx, File zipFile) {
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".provider", zipFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(Intent.createChooser(intent, "Export Chat")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.e(TAG, "Share intent failed for " + zipFile, e);
            Toast.makeText(ctx, "Export saved but couldn't open the share sheet.", Toast.LENGTH_LONG).show();
        }
    }
}
