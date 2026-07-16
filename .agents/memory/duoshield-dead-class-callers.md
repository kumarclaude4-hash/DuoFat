---
name: DuoShield dead-class caller cleanup (Part 3.6)
description: Rules for handling deleted crypto classes and the patterns used to fix their last live callers
---

## Rule
After deleting a class, grep for every live (non-comment) import and call site before declaring it gone. Comments mentioning deleted class names are harmless.

## What was fixed (Part 3.6)

### CryptoInitializer — fully purged from live code
- `MainActivity.route()`: removed `ensureKeyExists()` call + ecPublicKey upload; now only uploads fcmToken via `Collections.singletonMap`.
- `ChatMediaActivity` (line ~863 in listenForMessages, line ~1498 in retryPendingDecryption): both legacy-ECDH `sigType==0` branches replaced — show `"[Legacy message — not decryptable]"`, mark resolved (no more retry-queue loops).
- `UploadHelper.java`: deleted entirely (zero callers, contained the last two `CryptoInitializer.getSharedKey()` calls).

### EncryptionHelper — fully purged from live code
- `ConversationListActivity`: removed import + decrypt call. `lastMessage` field in the `chats/{chatId}` Firestore doc is **PLAINTEXT** (≤80 chars) written by `ConversationMetaUpdater.update()`; no decryption ever needed in ConversationListActivity.

### EncryptionHelper in EditMessageHelper
- Rewrote to use `SignalCipherHelper.encrypt(ctx, partnerUid, newText)` on a background thread.
- Signature is now `editMessage(ctx, convId, messageId, partnerUid, newText)`.
- Still dead code (zero callers) but compiles correctly with correct Signal signature.

## Key lessons

1. **sigType==0 handling**: When a legacy ciphertext with no sigType arrives and the decrypt class is gone, the correct pattern is: `displayText = "[Legacy message — not decryptable]"; shouldPersist = false;` in the listener, and `resolved.add(id); continue;` in the retry loop. Do NOT re-queue it.

2. **ConversationMetaUpdater stores PLAINTEXT**: `lastMessage` in the `chats/{chatId}` doc is always plaintext preview. Never attempt to decrypt it in ConversationListActivity.

3. **Remaining dead method**: `ChatMediaActivity.anyFailedWithEcdhKey()` has no callers after Part 3.6. It uses only standard Java types — compiles fine. Safe to delete in a future cleanup.

**Why:** CryptoHelper/CryptoInitializer/EncryptionHelper were deleted as part of the Signal Protocol migration (Part 3.5). Callers must be updated before the deletion or they cause compile errors on the next build.
