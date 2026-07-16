package com.duoshield.app.call;

/** Ephemeral in-call chat message. Not persisted to Room; cleared when the call ends. */
public class InCallChatMessage {

    public final String id;
    public final String senderId;
    public final String text;
    public final long   timestamp;
    public final boolean isMine;

    public InCallChatMessage(String id, String senderId, String text,
                             long timestamp, boolean isMine) {
        this.id        = id;
        this.senderId  = senderId;
        this.text      = text;
        this.timestamp = timestamp;
        this.isMine    = isMine;
    }
}
