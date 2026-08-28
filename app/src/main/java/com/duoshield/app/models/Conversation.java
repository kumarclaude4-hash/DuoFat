package com.duoshield.app.models;

public class Conversation {
    public String id;
    public String partnerName;
    public String partnerUid;
    public String lastMessage;
    public long   lastMessageTs;
    /**
     * Timestamp the conversation list sorts on, newest first.
     *
     * <p>Separate from {@link #lastMessageTs} because that field is 0 for a chat with no messages
     * yet (and briefly for a message whose {@code serverTimestamp()} has not resolved). Sorting on
     * it directly left every such chat tied at 0, so the list fell back to Firestore's arbitrary
     * document order instead of keeping the newest conversation on top. Populated by the list
     * screen — see {@code ConversationListActivity.resolveSortTs}.
     */
    public long   sortTs;
    public int    unreadCount;
    public boolean isTyping;
    public boolean isOnline;
    public long   lastSeen;
    public String avatarUrl;
    public boolean isMuted;

    /** True when this entry represents a group conversation. */
    public boolean isGroup;

    /** Non-null only when {@code isGroup == true}. */
    public String groupId;

    public Conversation() {}

    public Conversation(String id, String partnerName, String partnerUid) {
        this.id = id;
        this.partnerName = partnerName;
        this.partnerUid  = partnerUid;
    }

    /** Convenience constructor for group conversations. */
    public static Conversation fromGroup(com.duoshield.app.models.Group g) {
        Conversation c = new Conversation();
        c.id            = g.id;
        c.groupId       = g.id;
        c.isGroup       = true;
        c.partnerName   = g.name;
        c.lastMessage   = g.lastMessage;
        c.lastMessageTs = g.lastMessageTs;
        // A freshly created group has no messages, so fall back to when it was created —
        // otherwise it sorts at 0 and lands at the bottom of the list instead of the top.
        c.sortTs        = g.lastMessageTs > 0 ? g.lastMessageTs : g.createdAt;
        return c;
    }
}
