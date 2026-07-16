package com.duoshield.app.util;

import android.content.Context;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * In-memory full-text search over locally-stored messages.
 *
 * <p>Previously, {@link com.duoshield.app.db.MessageDao#searchMessages} ran a
 * SQL {@code LIKE} query directly on the {@code messages.text} column.
 * For Signal-encrypted conversations the column stores ciphertext, so the
 * {@code LIKE} never matched any plaintext query — search returned zero results
 * for every Signal-encrypted message (BUG-DB01).
 *
 * <p>The fix loads all messages for the conversation into memory and filters
 * in Java.  This correctly searches whatever text is stored in the column
 * (plaintext for sent messages and any already-decrypted received messages)
 * without risking SQL injection via the query string.
 *
 * <p>A single shared executor is reused across calls (no thread leak).
 * Each new search cancels any in-progress one, so rapid keystrokes don't
 * queue up stale searches that arrive out-of-order.
 */
public class SearchHelper {

    public interface Callback { void onResults(List<Message> results); }

    /** Single-thread executor reused for all searches — never recreated. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    /** Future for the last submitted search; cancelled when a newer one arrives. */
    private static volatile Future<?> lastFuture;

    public static void runSearch(Context ctx, String convId, String query, Callback cb) {
        if (query == null || query.trim().isEmpty()) {
            cb.onResults(new ArrayList<>());
            return;
        }
        final String lowerQuery = query.trim().toLowerCase(Locale.getDefault());

        // Cancel previous search so stale results don't overwrite fresher ones
        Future<?> prev = lastFuture;
        if (prev != null && !prev.isDone()) prev.cancel(true);

        lastFuture = EXECUTOR.submit(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            List<Message> all = AppDatabase.getInstance(ctx)
                    .messageDao().getMessages(convId);
            if (Thread.currentThread().isInterrupted()) return;
            List<Message> results = new ArrayList<>();
            for (Message m : all) {
                if (Thread.currentThread().isInterrupted()) return;
                String text = m.getText();
                if (text != null && text.toLowerCase(Locale.getDefault()).contains(lowerQuery)) {
                    results.add(m);
                }
            }
            if (!Thread.currentThread().isInterrupted()) cb.onResults(results);
        });
    }

    public static void clearSearch() {}
}
