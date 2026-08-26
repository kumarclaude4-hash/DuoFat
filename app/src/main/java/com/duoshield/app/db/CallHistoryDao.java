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

    @Query("DELETE FROM call_history WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM call_history")
    void deleteAll();

    /**
     * Attaches a finished recording to an existing call-history row. Called after the encoder
     * drains and the file has been encrypted, which can be either side of the row's own insert
     * (a mid-call stop finalises before the row exists; an end-of-call stop after it), so a
     * missing row is simply a no-op — the insert path picks up the pending values instead.
     */
    @Query("UPDATE call_history SET recordingPath = :path, recordingKey = :key, "
            + "recordingDurationSeconds = :durationSeconds WHERE id = :id")
    void updateRecording(String id, String path, String key, int durationSeconds);

    /** The recording file path for one row, or {@code null}. Used to delete the file on row delete. */
    @Query("SELECT recordingPath FROM call_history WHERE id = :id")
    String getRecordingPathById(String id);

    /** Every non-null recording path — used to purge orphaned files when history is cleared. */
    @Query("SELECT recordingPath FROM call_history WHERE recordingPath IS NOT NULL")
    List<String> getAllRecordingPaths();
}
