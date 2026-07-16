---
name: DuoShield MessageBuilder id fix
description: MessageBuilder Firestore doc must include "id" field or listenForMessages silently drops the message
---

## Rule
Every Firestore message document written by `MessageBuilder.sendTextMessage()` must include `doc.put("id", msgId)`.

## Why
`ChatMediaActivity.listenForMessages()` gates ALL processing on `if (id != null)`. Without the `"id"` field, the document is written to Firestore (costs a write quota unit) but never displayed to either party and never saved to Room. This silently swallowed notification quick-replies and forwarded messages.

## How to apply
Always check that `doc.put("id", msgId)` is present in any code path that writes a message document to Firestore. The main `sendMessage()` / `sendMediaMessage()` paths in ChatMediaActivity already include it — only MessageBuilder was missing it.

## Encryption guard
`MessageBuilder.sendTextMessage()` now also aborts if `CryptoInitializer.getSharedKey()` is null OR if `CryptoHelper.encrypt()` returns null — never sends plaintext. All callers (MessageReplyReceiver, ForwardMessageHelper) inherit this protection automatically.
