package com.duoshield.app.models;

import androidx.annotation.NonNull;
import androidx.room.Entity;

/**
 * A member of a {@link Group}.
 * Composite primary key: (groupId, memberUid).
 */
@Entity(
    tableName  = "group_members",
    primaryKeys = {"groupId", "memberUid"}
)
public class GroupMember {

    @NonNull
    public String groupId;

    @NonNull
    public String memberUid;

    public String displayName;

    public long joinedAt;

    public GroupMember() {
        groupId   = "";
        memberUid = "";
    }

    public GroupMember(@NonNull String groupId, @NonNull String memberUid,
                       String displayName) {
        this.groupId     = groupId;
        this.memberUid   = memberUid;
        this.displayName = displayName;
        this.joinedAt    = System.currentTimeMillis();
    }
}
