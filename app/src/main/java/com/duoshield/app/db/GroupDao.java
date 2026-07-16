package com.duoshield.app.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.duoshield.app.models.Group;
import com.duoshield.app.models.GroupMember;
import java.util.List;

@Dao
public interface GroupDao {

    // ── Group ─────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertGroup(Group group);

    @Query("SELECT * FROM groups ORDER BY lastMessageTs DESC")
    List<Group> getAllGroups();

    @Query("SELECT * FROM groups WHERE id = :groupId LIMIT 1")
    Group getGroupById(String groupId);

    @Query("UPDATE groups SET groupKey = :groupKey WHERE id = :groupId")
    void updateGroupKey(String groupId, String groupKey);

    @Query("UPDATE groups SET lastMessage = :preview, lastMessageTs = :ts WHERE id = :groupId")
    void updateLastMessage(String groupId, String preview, long ts);

    @Query("DELETE FROM groups WHERE id = :groupId")
    void deleteGroup(String groupId);

    // ── GroupMember ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMember(GroupMember member);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMembers(List<GroupMember> members);

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY joinedAt ASC")
    List<GroupMember> getMembersOf(String groupId);

    @Query("SELECT memberUid FROM group_members WHERE groupId = :groupId")
    List<String> getMemberUidsOf(String groupId);

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    void deleteMembersOf(String groupId);

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND memberUid = :uid")
    void deleteMember(String groupId, String uid);
}
