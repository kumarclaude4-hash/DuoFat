package com.duoshield.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.duoshield.app.models.OutboxMessage;
import java.util.List;

@Dao
public interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(OutboxMessage message);

    @Query("SELECT * FROM outbox_messages WHERE nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    List<OutboxMessage> getReady(long now, int limit);

    @Query("UPDATE outbox_messages SET attemptCount = :attempts, nextAttemptAt = :nextAt, lastError = :error WHERE id = :id")
    void recordFailure(String id, int attempts, long nextAt, String error);

    @Query("DELETE FROM outbox_messages WHERE id = :id")
    void delete(String id);

    @Query("SELECT COUNT(*) FROM outbox_messages")
    int count();
}
