package com.duoshield.app.models;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A group conversation.
 *
 * <p>Group messages are stored in the shared {@code messages} Room table using
 * {@code groupId} as their {@code conversationId}, so all existing message
 * queries work without modification.
 *
 * <p>Encryption: all group messages are encrypted with a shared AES-256-GCM key
 * ({@code groupKey}, Base64) generated at group creation time. The key is
 * distributed to each member via their Signal session and stored locally here.
 * Firestore mirrors the encrypted key per member at
 * {@code /groups/{id}/keys/{uid}}.
 */
@Entity(tableName = "groups")
public class Group {

    @PrimaryKey
    @NonNull
    public String id;

    @NonNull
    public String name;

    public String avatarUrl;

    @NonNull
    public String createdBy;

    public long createdAt;

    /** Base64-encoded AES-256 group key (32 bytes). Null until key is received. */
    public String groupKey;

    /** Plaintext preview of the last message (≤80 chars). */
    public String lastMessage;

    public long lastMessageTs;

    public Group() {
        id        = "";
        name      = "";
        createdBy = "";
    }

    public Group(@NonNull String id, @NonNull String name, @NonNull String createdBy) {
        this.id        = id;
        this.name      = name;
        this.createdBy = createdBy;
        this.createdAt = System.currentTimeMillis();
    }
}
