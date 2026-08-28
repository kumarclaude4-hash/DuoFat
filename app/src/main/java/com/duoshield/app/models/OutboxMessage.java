package com.duoshield.app.models;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Durable Firestore write queue for already-encrypted outgoing messages.
 * Plaintext never belongs in this table; the sender's Room Message row is the local display copy.
 */
@Entity(tableName = "outbox_messages")
public class OutboxMessage {
    @PrimaryKey
    @NonNull public String id;
    public String conversationId;
    public String recipientUid;
    public String senderUid;
    public String ciphertext;
    public int sigType;
    public String messageType;
    public String replyToId;
    public String replyPreview;
    public long expiresAt;
    public long createdAt;
    public int attemptCount;
    public long nextAttemptAt;
    public String lastError;
    /** Already-uploaded encrypted object metadata; never plaintext media. */
    public String mediaPath;
    public String mediaKey;
    public String thumbnail;
    public String caption;
    public boolean chunked;
    public String waveformJson;
    public int durationMs;

    public OutboxMessage(@NonNull String id, String conversationId, String recipientUid,
                         String senderUid, String ciphertext, int sigType, String messageType,
                         String replyToId, String replyPreview, long expiresAt, long createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.recipientUid = recipientUid;
        this.senderUid = senderUid;
        this.ciphertext = ciphertext;
        this.sigType = sigType;
        this.messageType = messageType;
        this.replyToId = replyToId;
        this.replyPreview = replyPreview;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public void setMediaMetadata(String mediaPath, String mediaKey, String thumbnail,
                                 String caption, boolean chunked) {
        this.mediaPath = mediaPath;
        this.mediaKey = mediaKey;
        this.thumbnail = thumbnail;
        this.caption = caption;
        this.chunked = chunked;
    }

    public void setVoiceMetadata(String waveformJson, int durationMs) {
        this.waveformJson = waveformJson;
        this.durationMs = durationMs;
    }
}
