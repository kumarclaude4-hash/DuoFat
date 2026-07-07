package com.duoshield.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.duoshield.app.models.SessionEvent;
import java.util.List;

@Dao
public interface SessionEventDao {

    @Insert
    void insert(SessionEvent event);

    @Query("SELECT * FROM session_events ORDER BY timestamp DESC LIMIT 200")
    List<SessionEvent> getAll();

    @Query("DELETE FROM session_events")
    void deleteAll();
}
