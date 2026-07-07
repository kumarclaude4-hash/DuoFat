---
name: DuoShield ConversationMetaUpdater signature
description: lastMessage written as PLAINTEXT preview — EncryptionHelper is deleted; no encryption in this path
---

**Current behavior:** `ConversationMetaUpdater.update()` writes a **plaintext** preview string (≤80 chars) to the `lastMessage` field in the `chats/{chatId}` Firestore doc. It does NOT encrypt.

**Why:** `EncryptionHelper` was deleted in Part 3.5 (Signal migration). The `lastMessage` field is used only for the conversation list preview; it is a short plaintext snippet. `ConversationListActivity` reads it directly as a string — no decryption call needed or correct.

**How to apply:**
- Never add a decryption call in `ConversationListActivity` for `lastMessage` — it is already plaintext.
- Never add an `EncryptionHelper` or `SignalCipherHelper` call in `ConversationMetaUpdater` — the preview is intentionally plaintext.
- Any new caller (MessageBuilder, ChatMediaActivity, etc.) passes a plain String preview, not ciphertext.
