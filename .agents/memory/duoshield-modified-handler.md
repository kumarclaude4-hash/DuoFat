---
name: DuoShield MODIFIED handler edit decryption
description: Firestore MODIFIED events for edited messages must re-decrypt; EditMessageHelper must sync Room
---

## Rule
The Firestore `listenForMessages()` MODIFIED handler must handle three update types: reaction updates, status updates, AND edited message text updates. Failing to handle the third causes partner-edited messages to be permanently stale.

## What was wrong (B-3)
Original MODIFIED handler only read `reaction` and `status`. When the partner edited a message, Firestore fired MODIFIED with new ciphertext in `text` and `edited: true`. The handler ignored both fields — partner saw old plaintext forever.

Separately, `EditMessageHelper.editMessage()` updated Firestore but never updated Room, so after restart the sender also saw old plaintext (Room cache served stale text, and MODIFIED listener didn't fix it).

## Fix

### ChatMediaActivity MODIFIED handler
When `edited == true` AND `isEncrypted == true` AND `sender != myUid`:
- Decrypt `text`/`sigType` on `dbExecutor` using `SignalCipherHelper.decrypt()`
- `runOnUiThread` → `adapter.updateMessage(id, m -> { m.setText(plain); m.setEncrypted(false); })`
- `messageDao().updateText(id, plain)` — persists to Room

Own edits are **not** re-decrypted here — `showEditDialog()` already updates the adapter with plaintext, and `EditMessageHelper` persists to Room via `addOnSuccessListener`.

### EditMessageHelper
After `FirebaseFirestore...update(updates)`:
```java
.addOnSuccessListener(v ->
    AppDatabase.getInstance(ctx).messageDao().updateText(messageId, newText));
```

### MessageDao
```java
@Query("UPDATE messages SET text = :text WHERE id = :messageId")
void updateText(String messageId, String text);
```

**Why:** Room is the offline cache. If it's stale for edited messages, every app restart shows the old text. The Firestore MODIFIED event is the only window to fix it for the recipient.
