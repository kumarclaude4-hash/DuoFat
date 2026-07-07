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
}
