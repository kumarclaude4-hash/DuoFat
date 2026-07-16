package com.duoshield.app.models;

public class Conversation {
    public String id;
    public String partnerName;
    public String partnerUid;
    public String lastMessage;
    public long   lastMessageTs;
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
        return c;
    }
}
