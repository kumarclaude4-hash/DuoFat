---
name: DuoShield group conversations
description: Group conversation architecture — AES-256-GCM shared key, Signal-encrypted key distribution, Room v12 schema, new activities
---

## Group key model

- Creator generates a 32-byte AES-256 group key via `GroupCipherHelper.generateGroupKey()`
- Key is encrypted **per-member** using `SignalCipherHelper.encrypt(ctx, memberUid, groupKey)` — requires an existing Signal session (only paired contacts can be added)
- Encrypted keys stored at `/groups/{id}/keys/{memberUid}` with fields: `encryptedKey`, `sigType`, `senderUid`
- Members decrypt on first `GroupChatActivity` open: `SignalCipherHelper.decrypt(ctx, creatorUid, encryptedKey, sigType)` → stored in Room `groups.groupKey`
- Group messages use `GroupCipherHelper.encrypt/decrypt(text, groupKeyB64)` — NOT Signal Double Ratchet

**Why:** Signal sessions are 1-to-1 (paired contacts). Using Signal to encrypt the group key leverages existing authenticated channels for bootstrap. Subsequent messages use the shared AES key directly for simplicity and performance.

## Room v12 schema

Tables added in `MIGRATION_11_12`:
- `contacts(uid PK, displayName, conversationId, avatarUrl)` — populated by `PairingManager.finalizeConnection()`; drives `CreateGroupActivity` member picker
- `groups(id PK, name, avatarUrl, createdBy, createdAt, groupKey, lastMessage, lastMessageTs)` — `groupKey` is null until fetched+decrypted from Firestore
- `group_members(groupId+memberUid PK, displayName, joinedAt)`

Next migration is v12 → v13. Never use `fallbackToDestructiveMigration()`.

## Conversation list integration

- `Conversation.fromGroup(Group g)` creates a `Conversation` with `isGroup=true` and `groupId` set
- `ConversationListActivity.loadGroupsFromRoom()` runs on a background thread every `onStart()`; merges into `allConversations` sorted by `lastMessageTs` desc via `mergeAndFilter()`
- `openChat(Conversation conv)` routes to `GroupChatActivity` when `conv.isGroup == true`, else `ChatMediaActivity`
- Popup menu item id=5 "New Group" → `CreateGroupActivity`

## FCM for group messages

Group messages nudge `/groups/{id}` `lastActivity` field. A Cloud Function listening to that collection fans FCM out to all members. This avoids each device needing service-account credentials or knowing all members' FCM tokens.

**Why:** Replicating the existing `notifyPartner()` pattern (which also just nudges a Firestore field) keeps FCM logic server-side and consistent.

## Architecture checklist (all followed)

- `GroupChatActivity` and `CreateGroupActivity` extend `BaseActivity` ✅
- Single Firestore listener per screen (null-guarded, attached `onStart`, removed `onStop`) ✅
- `MessageAdapter` with `DiffUtil` drives RecyclerView ✅
- `FirebaseCostGuard.canWrite/recordWrites` on every Firestore write ✅
- `groupKey` null check gates the Firestore listener start ✅
- `knownIds` `HashSet<String>` prevents duplicate message display ✅
