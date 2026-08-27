package com.duoshield.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface CallHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(CallRecord record);

    @Query("SELECT * FROM call_history ORDER BY startedAt DESC")
    List<CallRecord> getAll();

    @Query("SELECT * FROM call_history WHERE partnerId = :partnerId ORDER BY startedAt DESC")
    List<CallRecord> getByPartnerId(String partnerId);

    @Query("SELECT COUNT(*) FROM call_history WHERE partnerId = :partnerId")
    int countByPartnerId(String partnerId);

    @Query("SELECT SUM(durationSeconds) FROM call_history WHERE partnerId = :partnerId AND outcome = 'ANSWERED'")
    int totalDurationByPartnerId(String partnerId);

    @Query("UPDATE call_history SET recordingPath = :path WHERE id = :id")
    void setRecordingPath(String id, String path);

    @Query("DELETE FROM call_history WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM call_history")
    void deleteAll();
}
