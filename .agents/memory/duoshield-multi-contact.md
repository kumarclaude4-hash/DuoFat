---
name: DuoShield multi-contact model
description: PairingActivity/PairingManager replaced by AddContactActivity/ContactManager; ConversationListActivity is now multi-conversation; ChatMediaActivity reads IDs from Intent extras.
---

## Rule
PairingActivity and PairingManager are deleted. Never reference them. Use AddContactActivity and ContactManager instead.

## Key changes
- `contacts/ContactManager.java` — replaces PairingManager; callback returns `(chatId, partnerUid, displayName)`; does NOT write is_paired/conversation_id/partner_uid to SharedPrefs
- `ui/AddContactActivity.java` — replaces PairingActivity; registered in AndroidManifest; navigates to ChatMediaActivity via Intent extras on success
- `ConversationListActivity` — listener is now `db.collection("chats").whereArrayContains("participants", myUid)` (all chats, not one); `conversationId` field removed; `openChat()` passes `conversation_id` + `partner_uid` as Intent extras
- `ChatMediaActivity.setupChat()` — reads `conversation_id` + `partner_uid` from Intent extras first; falls back to SharedPrefs for back-compat with pre-existing users

## Firestore rule requirement
`whereArrayContains("participants", myUid)` requires:
  `request.auth.uid in resource.data.participants` on the chats collection.

**Why:** Single-partner pairing via SharedPrefs couldn't scale to multiple contacts. The new model derives chatId deterministically (same SHA-256 logic) and stores all conversations in Firestore, loaded dynamically.

**How to apply:** Any new screen that opens a 1-to-1 chat must pass `conversation_id` and `partner_uid` as Intent extras to ChatMediaActivity — never rely on SharedPrefs for routing.
