package com.duoshield.app.call;

import android.content.Context;
import android.util.Log;

import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.CallRecord;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the whole journey of a finished call recording: temp file in the cache dir → durable file in
 * app-private external storage → {@code recordingPath} on the matching {@link CallRecord} row.
 *
 * <h3>Why this is app-scoped rather than living in {@code CallActivity}</h3>
 * The two events that must meet here arrive from different places and in a genuinely
 * unpredictable order:
 * <ul>
 *   <li><b>The file</b> arrives from {@code CallAudioRecorder}'s encoder thread, which flushes
 *       asynchronously — a few hundred ms after the user (or the hangup) stopped recording.</li>
 *   <li><b>The row id</b> is created by {@code CallActivity} the instant the call reaches
 *       {@code ENDED}, which is immediately followed by {@code finish()}.</li>
 * </ul>
 * So on a hangup-while-recording the Activity is already destroyed by the time the file is ready.
 * Anything Activity-scoped would drop the recording on exactly the path users hit most often
 * (just hang up, never press stop). This class holds the pending half of the pair until the other
 * half shows up, keyed on nothing more than "there is only ever one call in flight".
 *
 * <h3>Ordering</h3>
 * Both entry points run on the same single-thread executor, so the two halves can never interleave
 * and the "who arrived first" check needs no extra locking. The file move also happens on that
 * thread, keeping disk I/O off the main thread.
 *
 * <p>Recordings are never transmitted. They live in app-private external storage, which is removed
 * with the app and is not indexed by MediaStore, so recordings never surface in the device gallery
 * or a file picker.
 */
public final class CallRecordingStore {

    private static final String TAG     = "CallRecordingStore";
    private static final String SUBDIR  = "call_recordings";

    private static CallRecordingStore instance;

    private final Context         appCtx;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** A stored file with no row to attach it to yet. */
    private String pendingPath;
    /** A row id whose recording is still being flushed. */
    private String pendingRecordId;

    private CallRecordingStore(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public static synchronized CallRecordingStore get(Context ctx) {
        if (instance == null) instance = new CallRecordingStore(ctx);
        return instance;
    }

    // ── Pairing ───────────────────────────────────────────────────────────────

    /**
     * Clears any half-pair left over from a previous call. Called when a new call starts so a
     * recording can never be attached to the wrong call's history row.
     *
     * <p>A leftover {@link #pendingPath} means the recording finished but its row never appeared
     * (the call died before {@code ENDED} could be handled). Nothing will ever claim that file, so
     * it is deleted here rather than left to accumulate.
     */
    public void beginCall() {
        io.execute(() -> {
            if (pendingPath != null) {
                Log.w(TAG, "discarding orphaned recording from previous call");
                deleteQuietly(new File(pendingPath));
                pendingPath = null;
            }
            pendingRecordId = null;
        });
    }

    /**
     * Hands over a freshly finalised temp recording. The file is moved into durable storage and
     * attached to the call's history row as soon as that row exists.
     */
    public void onRecordingFinished(String tempPath) {
        if (tempPath == null) return;
        io.execute(() -> {
            String stored = moveIntoStorage(new File(tempPath));
            if (stored == null) return;
            if (pendingRecordId != null) {
                String id = pendingRecordId;
                pendingRecordId = null;
                attach(id, stored);
            } else {
                pendingPath = stored;
            }
        });
    }

    /**
     * Announces that the history row for the just-ended call has been written. If the recording is
     * already stored it is attached now; otherwise the id waits for the encoder to finish.
     */
    public void onCallRecordSaved(String recordId) {
        if (recordId == null) return;
        io.execute(() -> {
            if (pendingPath != null) {
                String path = pendingPath;
                pendingPath = null;
                attach(recordId, path);
            } else {
                pendingRecordId = recordId;
            }
        });
    }

    private void attach(String recordId, String storedPath) {
        try {
            AppDatabase.getInstance(appCtx).callHistoryDao().setRecordingPath(recordId, storedPath);
        } catch (Exception e) {
            // The row is gone (user cleared history while the encoder was flushing). Without a row
            // the file is unreachable from the UI, so drop it instead of leaking it.
            Log.w(TAG, "could not attach recording, deleting: " + e.getMessage());
            deleteQuietly(new File(storedPath));
        }
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    /** App-private external storage, falling back to internal storage if no volume is mounted. */
    public static File storageDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, SUBDIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Moves {@code temp} out of the cache dir into durable storage, returning the new absolute
     * path or {@code null} on failure.
     *
     * <p>The move matters: the source lives in {@code getCacheDir()}, which Android is free to
     * purge under storage pressure. A rename is attempted first and a copy is used as the fallback,
     * because rename fails when cache and external files sit on different volumes.
     */
    private String moveIntoStorage(File temp) {
        if (!temp.exists() || temp.length() == 0) {
            Log.w(TAG, "recording temp file missing or empty");
            deleteQuietly(temp);
            return null;
        }
        File dest = new File(storageDir(appCtx), temp.getName());
        if (temp.renameTo(dest)) return dest.getAbsolutePath();

        try (FileInputStream in = new FileInputStream(temp);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        } catch (IOException e) {
            Log.e(TAG, "failed to store recording", e);
            deleteQuietly(dest);
            deleteQuietly(temp);
            return null;
        }
        deleteQuietly(temp);
        return dest.getAbsolutePath();
    }

    // ── Deletion ──────────────────────────────────────────────────────────────

    /**
     * Deletes the recording belonging to a single history row, if it has one. Call before removing
     * the row, while the path is still readable.
     */
    public static void deleteRecordingFor(CallRecord record) {
        if (record != null && record.recordingPath != null) {
            deleteQuietly(new File(record.recordingPath));
        }
    }

    /**
     * Deletes every stored recording. Used by "Clear all", where the rows go away in one statement
     * and there is no per-row opportunity to clean up the files behind them.
     */
    public static void deleteAllRecordings(Context ctx) {
        File[] files = storageDir(ctx).listFiles();
        if (files == null) return;
        for (File f : files) deleteQuietly(f);
    }

    /** Deletes recordings whose history row no longer exists. */
    public static void pruneOrphans(Context ctx, List<CallRecord> allRecords) {
        File[] files = storageDir(ctx).listFiles();
        if (files == null || files.length == 0) return;
        for (File f : files) {
            boolean referenced = false;
            for (CallRecord r : allRecords) {
                if (f.getAbsolutePath().equals(r.recordingPath)) { referenced = true; break; }
            }
            if (!referenced) deleteQuietly(f);
        }
    }

    private static void deleteQuietly(File f) {
        try {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Exception ignored) {}
    }
}
