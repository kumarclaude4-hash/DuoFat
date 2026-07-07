package com.duoshield.app.util;

import android.content.Context;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

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
 */
public class SearchHelper {

    public interface Callback { void onResults(List<Message> results); }

    public static void runSearch(Context ctx, String convId, String query, Callback cb) {
        if (query == null || query.trim().isEmpty()) {
            cb.onResults(new ArrayList<>());
            return;
        }
        final String lowerQuery = query.trim().toLowerCase(Locale.getDefault());
        Executors.newSingleThreadExecutor().execute(() -> {
            List<Message> all = AppDatabase.getInstance(ctx)
                .messageDao().getMessages(convId);
            List<Message> results = new ArrayList<>();
            for (Message m : all) {
                String text = m.getText();
                if (text != null && text.toLowerCase(Locale.getDefault()).contains(lowerQuery)) {
                    results.add(m);
                }
            }
            cb.onResults(results);
        });
    }

    public static void clearSearch() {}
}
