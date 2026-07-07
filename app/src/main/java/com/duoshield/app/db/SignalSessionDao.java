package com.duoshield.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.duoshield.app.models.SignalSessionRecord;

import java.util.List;

/**
 * Room DAO for {@link SignalSessionRecord}.
 *
 * All methods are synchronous — callers must run them on a background thread
 * (e.g. inside the executor used by {@code SignalSessionManager}).
 */
@Dao
public interface SignalSessionDao {

    /** Load the session for a specific remote address, or null if none exists. */
    @Query("SELECT * FROM signal_sessions WHERE address = :address LIMIT 1")
    SignalSessionRecord load(String address);

    /** Persist (insert or replace) a session record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void store(SignalSessionRecord record);

    /** Delete the session for a specific address. */
    @Query("DELETE FROM signal_sessions WHERE address = :address")
    void delete(String address);

    /**
     * Delete all sessions for a given user name (uid), regardless of device ID.
     * Used when a user resets or re-pairs.
     */
    @Query("DELETE FROM signal_sessions WHERE address LIKE :namePrefix || '.%'")
    void deleteAllForName(String namePrefix);

    /** Delete ALL session records — used by {@code DuressManager.performLogout()}. */
    @Query("DELETE FROM signal_sessions")
    void deleteAll();

    /** Check whether a session exists for a given address. */
    @Query("SELECT COUNT(*) FROM signal_sessions WHERE address = :address")
    int count(String address);

    /**
     * Return all device-ID suffixes for a given user name.
     * e.g. for uid "abc", returns ["abc.1"] → device IDs can be parsed from these.
     */
    @Query("SELECT address FROM signal_sessions WHERE address LIKE :namePrefix || '.%'")
    List<String> getAddressesForName(String namePrefix);
}
