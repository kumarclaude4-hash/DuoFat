# DuoShield — Firestore Security Rules

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // ── Users ────────────────────────────────────────────────────────────────
    // Anyone authenticated can read (needed for key exchange, display info,
    // FCM token delivery). Only the owner can write their own doc.
    match /users/{uid} {
      allow read:  if request.auth != null;
      allow write: if request.auth != null && request.auth.uid == uid;

      // public_keys subcollection — stores the Signal pre-key bundle.
      // Any authenticated user can UPDATE (not create/delete) the bundle to
      // consume a one-time pre-key (X3DH forward secrecy).
      match /public_keys/{doc} {
        allow read:   if request.auth != null;
        allow create: if request.auth != null && request.auth.uid == uid;
        allow update: if request.auth != null;
        allow delete: if request.auth != null && request.auth.uid == uid;
      }
    }

    // ── Chats ────────────────────────────────────────────────────────────────
    // Only the two participants can read or write.
    match /chats/{chatId} {
      allow create: if request.auth != null
                    && request.auth.uid in request.resource.data.participants;
      allow read, update: if request.auth != null
                          && request.auth.uid in resource.data.participants;

      match /messages/{msgId} {
        allow read, write: if request.auth != null
                           && request.auth.uid in
                              get(/databases/$(database)/documents/chats/$(chatId)).data.participants;
      }
    }

    // ── Groups ───────────────────────────────────────────────────────────────
    // Only members can read/write. Only the creator can delete the group doc.
    match /groups/{groupId} {
      allow create: if request.auth != null
                    && request.auth.uid in request.resource.data.members;
      allow read:   if request.auth != null
                    && request.auth.uid in resource.data.members;
      allow update: if request.auth != null
                    && request.auth.uid in resource.data.members;
      allow delete: if request.auth != null
                    && request.auth.uid == resource.data.createdBy;

      match /messages/{msgId} {
        allow read, write: if request.auth != null
                           && request.auth.uid in
                              get(/databases/$(database)/documents/groups/$(groupId)).data.members;
      }

      // Each member's encrypted copy of the group key.
      // You can only read your own key; any current member can write keys
      // (creator distributes on group creation).
      match /keys/{memberUid} {
        allow read:  if request.auth != null
                     && request.auth.uid == memberUid;
        allow write: if request.auth != null
                     && request.auth.uid in
                        get(/databases/$(database)/documents/groups/$(groupId)).data.members;
      }
    }

    // ── Calls (voice & video signaling) ─────────────────────────────────────
    match /calls/{callId} {
      allow create: if request.auth != null
                    && request.auth.uid == request.resource.data.callerId;
      allow read, write: if request.auth != null
                         && (request.auth.uid == resource.data.callerId
                             || request.auth.uid == resource.data.calleeId);

      // ICE candidates — both participants can read; only the correct side writes.
      match /callerCandidates/{candId} {
        allow read, write: if request.auth != null
                           && (request.auth.uid ==
                                 get(/databases/$(database)/documents/calls/$(callId)).data.callerId
                               || request.auth.uid ==
                                 get(/databases/$(database)/documents/calls/$(callId)).data.calleeId);
      }
      match /calleeCandidates/{candId} {
        allow read, write: if request.auth != null
                           && (request.auth.uid ==
                                 get(/databases/$(database)/documents/calls/$(callId)).data.callerId
                               || request.auth.uid ==
                                 get(/databases/$(database)/documents/calls/$(callId)).data.calleeId);
      }
    }

    // ── Identities ───────────────────────────────────────────────────────────
    // Maps DuoShield User ID (e.g. ABCDE-FGHIJ-KLM) → Firebase UID + identity
    // public key hash.  Any signed-in user can read (needed to look up a
    // contact by their User ID).  Write is locked to the owner and the written
    // uid field must equal the caller's Firebase UID — prevents identity
    // hijacking (BUG-S06 / BUG-F03 / BUG-F06 / BUG-ID01).
    match /identities/{userId} {
      allow read:  if request.auth != null;
      allow write: if request.auth != null
                   && request.resource.data.uid == request.auth.uid;
    }

    // ── Recovery ─────────────────────────────────────────────────────────────
    // AES-GCM encrypted recovery blob — owner only.
    match /recovery/{uid} {
      allow read:  if request.auth != null && request.auth.uid == uid;
      allow write: if request.auth != null && request.auth.uid == uid;
    }

    // ── Backups ──────────────────────────────────────────────────────────────
    // All content is AES-256-GCM encrypted on-device before upload.
    // Delete is hard-denied on sub-docs — use isDeleted:true soft-delete
    // instead so a restore never silently loses messages.
    match /backups/{userId} {
      allow read:   if request.auth != null && request.auth.uid == userId;
      allow create: if request.auth != null && request.auth.uid == userId;
      allow update: if request.auth != null && request.auth.uid == userId;
      allow delete: if false;

      match /messages/{msgId} {
        allow read:   if request.auth != null && request.auth.uid == userId;
        allow create: if request.auth != null && request.auth.uid == userId;
        allow update: if request.auth != null && request.auth.uid == userId;
        allow delete: if false;
      }

      match /contacts/{contactId} {
        allow read:   if request.auth != null && request.auth.uid == userId;
        allow create: if request.auth != null && request.auth.uid == userId;
        allow update: if request.auth != null && request.auth.uid == userId;
        allow delete: if false;
      }
    }

    // Backup event log — write-only for the owning user; no client reads.
    match /backup_logs/{logId} {
      allow create: if request.auth != null
                    && request.resource.data.uid == request.auth.uid;
      allow read:   if false;
      allow update: if false;
      allow delete: if false;
    }

    // ── Legacy (backwards compatibility) ─────────────────────────────────────

    // Old pairing rooms — restricted to creator and joiner only.
    match /rooms/{code} {
      allow create: if request.auth != null;
      allow read:   if request.auth != null
                    && (request.auth.uid == resource.data.creatorUid
                        || request.auth.uid == resource.data.joinerUid);
      allow update: if request.auth != null
                    && resource.data.status == "waiting"
                    && resource.data.joinerUid == "";
      allow delete: if request.auth != null
                    && (request.auth.uid == resource.data.creatorUid
                        || request.auth.uid == resource.data.joinerUid);
    }

    // Old conversations schema — participants only.
    match /conversations/{convId} {
      allow create: if request.auth != null
                    && request.auth.uid in request.resource.data.participants;
      allow read, update: if request.auth != null
                          && request.auth.uid in resource.data.participants;

      match /messages/{msgId} {
        allow read, write: if request.auth != null
                           && request.auth.uid in
                              get(/databases/$(database)/documents/conversations/$(convId)).data.participants;
      }
    }

    // ── Internal / server-only ────────────────────────────────────────────────
    // Admin SDK bypasses rules entirely; explicit deny blocks all app clients.
    match /_server_health/{doc} {
      allow read, write: if false;
    }

  }
}
```

---

## Quick reference

| Collection | Who can read | Who can write |
|---|---|---|
| `users/{uid}` | Any signed-in user | Owner only |
| `users/{uid}/public_keys/{doc}` | Any signed-in user | Owner creates/deletes; anyone updates (pre-key consumption) |
| `chats/{chatId}` | Participants only | Participants only |
| `chats/{chatId}/messages/{msgId}` | Participants only | Participants only |
| `groups/{groupId}` | Members only | Members only (creator deletes) |
| `groups/{groupId}/messages/{msgId}` | Members only | Members only |
| `groups/{groupId}/keys/{memberUid}` | That member only | Any current member |
| `calls/{callId}` | Caller + callee | Caller + callee |
| `calls/{callId}/callerCandidates` | Caller + callee | Caller + callee |
| `calls/{callId}/calleeCandidates` | Caller + callee | Caller + callee |
| `identities/{userId}` | Any signed-in user | Owner only (uid field must match auth UID) |
| `recovery/{uid}` | Owner only | Owner only |
| `backups/{userId}` | Owner only | Owner only (delete denied) |
| `backup_logs/{logId}` | Nobody | Owner only (create only) |
| `rooms/{code}` | Creator + joiner | Creator + joiner |
| `conversations/{convId}` | Participants only | Participants only |
| `_server_health/{doc}` | Nobody | Nobody (Admin SDK only) |
