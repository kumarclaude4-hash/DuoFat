package com.duoshield.app.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Represents a single call entry in the local call history log.
 * Never transmitted — local-only Room table.
 */
@Entity(tableName = "call_history")
public class CallRecord {

    public static final String DIRECTION_OUTGOING = "OUTGOING";
    public static final String DIRECTION_INCOMING = "INCOMING";

    public static final String OUTCOME_ANSWERED = "ANSWERED";
    public static final String OUTCOME_MISSED   = "MISSED";
    public static final String OUTCOME_DECLINED = "DECLINED";
    public static final String OUTCOME_FAILED   = "FAILED";

    @PrimaryKey
    @NonNull
    public String id = "";

    @NonNull public String partnerId      = "";
    @NonNull public String partnerName    = "";
    public boolean         isVideo        = false;
    @NonNull public String direction      = DIRECTION_OUTGOING;
    @NonNull public String outcome        = OUTCOME_ANSWERED;
    public long            startedAt      = 0L;
    public int             durationSeconds = 0;

    /**
     * Absolute path to the encrypted call-recording file for this call, or {@code null} if this
     * call was not recorded. The file lives under {@code filesDir/call_recordings/} and is
     * AES-256-GCM sealed with {@link #recordingKey}. Local-only — never uploaded or transmitted.
     */
    @Nullable public String recordingPath = null;

    /**
     * Base64 AES-256 key for {@link #recordingPath}. Stored inside this row, which is itself
     * inside the SQLCipher-encrypted database, so the key is protected at rest. {@code null}
     * when there is no recording.
     */
    @Nullable public String recordingKey = null;

    /** Duration of the recording in seconds. {@code 0} when there is no recording. */
    public int recordingDurationSeconds = 0;
}
