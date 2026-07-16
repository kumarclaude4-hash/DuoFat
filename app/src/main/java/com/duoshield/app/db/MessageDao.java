package com.duoshield.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;
import com.duoshield.app.models.Message;

@Dao
public interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(Message message);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<Message> messages);

    @Update
    void updateMessage(Message message);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    List<Message> getMessages(String conversationId);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    List<Message> getMessagesPage(String conversationId, int limit, int offset);

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    Message getMessageById(String messageId);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND (status = 'pending' OR status = 'failed' OR status IS NULL) AND sender = :myUid")
    List<Message> getUndeliveredMessages(String conversationId, String myUid);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    List<Message> searchMessages(String conversationId, String query);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    List<Message> getLatestMessages(String conversationId, int limit);

    @Query("UPDATE messages SET seen = 1, status = 'read' WHERE conversationId = :conversationId AND sender != :myUid")
    void markAllRead(String conversationId, String myUid);

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    void updateStatus(String messageId, String status);

    // Used by EditMessageHelper to keep the local Room row in sync after the
    // sender edits a message (so the correct text survives an app restart).
    @Query("UPDATE messages SET text = :text WHERE id = :messageId")
    void updateText(String messageId, String text);

    // F42 fix: persist a full tombstone when a message is deleted-for-everyone.
    // Clears text, mediaUrl, mediaType, and mediaKey; sets isDeleted=1 so the
    // row survives app restart and renders as "⛔ Message deleted" on reload.
    @Query("UPDATE messages SET text = '\u26d4 Message deleted', mediaUrl = NULL, mediaType = NULL, mediaKey = NULL, isDeleted = 1 WHERE id = :messageId")
    void markTombstone(String messageId);

    @Query("UPDATE messages SET starred = :starred WHERE id = :messageId")
    void updateStarred(String messageId, boolean starred);

    @Query("UPDATE messages SET isDeleted = 1 WHERE id = :messageId")
    void deleteMessage(String messageId);

    @Query("SELECT * FROM messages WHERE isDeleted = 0 ORDER BY timestamp ASC")
    List<Message> getAllActiveMessages();

    @Query("SELECT * FROM messages WHERE isDeleted = 0 AND timestamp > :sinceTs ORDER BY timestamp ASC")
    List<Message> getMessagesSince(long sinceTs);

    @Query("SELECT * FROM messages WHERE isDeleted = 0 AND timestamp > :sinceTs ORDER BY timestamp DESC")
    List<Message> getMessagesSinceDesc(long sinceTs);

    // Bug 16 fix: renamed parameter from :now to :currentTime to make the intent
    // explicit — callers must pass System.currentTimeMillis(), not an age-based cutoff.
    @Query("DELETE FROM messages WHERE expiresAt > 0 AND expiresAt < :currentTime")
    void deleteExpired(long currentTime);

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    void deleteAll(String conversationId);

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    List<Message> getAllMessages();
}
