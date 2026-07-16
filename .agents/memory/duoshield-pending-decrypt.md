---
name: DuoShield pending decrypt queue
description: ChatMediaActivity pattern for messages arriving before ECDH key is ready
---

## Rule
Messages that arrive in `listenForMessages()` before the ECDH shared key is available must NOT be saved to Room with placeholder text. They go into `pendingDecryptQueue` (raw ciphertext, isEncrypted=true) and are retried via `retryPendingDecryption()` once the key is ready.

## Why
The old code saved `"[Waiting for encryption key…]"` and `"[Decryption failed]"` strings to Room with `isEncrypted=false`. The `exists` check in the Firestore listener skips any message already in the adapter by id, so the placeholder was never replaced — it persisted permanently across sessions. This was the proximate cause of the "decryption keeps failing" user complaint even after the ECDH race was fixed.

## How to apply
- `shouldPersist = false` for any message whose `displayText` is a placeholder
- `pendingDecryptQueue.add(pending)` where `pending.text = rawCiphertext`, `pending.isEncrypted = true`
- `retryPendingDecryption()` must be called at the end of EVERY success path in `reEnsureEcdhKey()`:
  - "key already good" branch (existingKey != null && storedPartnerPub matches)
  - executor `finally` block runOnUiThread after `listenForMessages()`
- `retryPendingDecryption()` checks `getSharedKey()` is non-null before iterating; on success calls `adapter.updateMessage()` then `saveToRoom()`.
