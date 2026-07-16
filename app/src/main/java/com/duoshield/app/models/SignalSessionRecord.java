package com.duoshield.app.models;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room entity that persists a Double Ratchet session.
 *
 * Primary key format: "{firebaseUid}.{deviceId}" — e.g. "abc123.1".
 * DuoShield is single-device per user so deviceId is always 1.
 *
 * sessionData holds the raw bytes returned by
 * {@code org.signal.libsignal.protocol.state.SessionRecord#serialize()}.
 * These bytes encode the full Double Ratchet state (chain keys, message keys,
 * root key, ratchet position) and must be updated after every sent/received message.
 */
@Entity(tableName = "signal_sessions")
public class SignalSessionRecord {

    /** "{uid}.{deviceId}" — serves as the unique address for a remote party's device. */
    @PrimaryKey
    @NonNull
    public String address = "";

    /** Serialised {@code SessionRecord} bytes — updated on every ratchet step. */
    @NonNull
    @ColumnInfo(name = "session_data")
    public byte[] sessionData = new byte[0];

    /** Wall-clock time of the last write, for debugging and future key-rotation checks. */
    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    public SignalSessionRecord() {}

    public SignalSessionRecord(@NonNull String address,
                               @NonNull byte[] sessionData,
                               long updatedAt) {
        this.address     = address;
        this.sessionData = sessionData;
        this.updatedAt   = updatedAt;
    }
}
