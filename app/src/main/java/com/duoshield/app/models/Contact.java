package com.duoshield.app.models;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A paired contact — someone this device has successfully paired with.
 * Populated by {@link com.duoshield.app.contacts.ContactManager#addContact}
 * so that {@link com.duoshield.app.ui.CreateGroupActivity} can offer a contact
 * picker when creating group conversations.
 */
@Entity(tableName = "contacts")
public class Contact {

    @PrimaryKey
    @NonNull
    public String uid;

    public String displayName;

    /** Deterministic SHA-256 chatId (same key used in chats/{chatId}). */
    public String conversationId;

    public String avatarUrl;

    public Contact() {
        uid = "";
    }

    public Contact(@NonNull String uid, String displayName, String conversationId) {
        this.uid            = uid;
        this.displayName    = displayName;
        this.conversationId = conversationId;
    }
}
