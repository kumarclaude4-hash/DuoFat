package com.duoshield.app.db;

import androidx.annotation.NonNull;
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
     * Absolute path to the local {@code .m4a} audio recording of this call, or {@code null} when
     * the call was not recorded. Nullable on purpose: "not recorded" and "recording deleted" are
     * both represented by NULL, and the history UI only shows a play affordance when this is set.
     *
     * <p>Never transmitted — the file lives in app-private external storage and the path is only
     * meaningful on this device.
     */
    public String          recordingPath   = null;
}
