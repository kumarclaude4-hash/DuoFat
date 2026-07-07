---
name: DuoShield prekey rotation
description: How Signal one-time pre-key replenishment works — trigger, thresholds, ID tracking, Firestore update strategy.
---

## Rule
When the local one-time pre-key pool drops below 10, automatically generate 25 new keys, store private bytes in SecurePrefs, and upload public bytes to Firestore using `arrayUnion`.

**Why:** The X3DH handshake requires a fresh one-time pre-key from the recipient's Firestore bundle. If the pool empties, new sessions fall back to no OTP key (reduced forward secrecy). Replenishment must happen silently with zero user friction.

**How to apply:**
- `SignalPreKeyRefresher.checkAndReplenish(ctx)` is the sole entry point. It is called from `SignalKeyManager.consumePreKey()` after every key removal.
- An `AtomicBoolean sRunning` gate prevents concurrent replenishment threads.
- Pre-key IDs are monotonically increasing. The current high-water mark is stored in SecurePrefs under `signal_prekey_next_id` (seeded to 51 on first key generation). `getAndIncrementNextPreKeyId(ctx, count)` reads, advances, and persists atomically within one thread (the gate ensures single-threaded execution).
- IDs wrap at 0xFFFFFF per the Signal spec.
- Firestore upload uses `FieldValue.arrayUnion(publicEntries.toArray())` — adds new entries without displacing keys the partner hasn't fetched yet.
- Local SecurePrefs write happens **before** the Firestore upload. A Firestore failure is logged as a warning; the local pool is still correct. The next replenishment cycle will re-attempt.
- Legacy devices without `signal_prekey_next_id` fall back to `51` — same safe starting point, no Room migration needed.

## Key constants
- Threshold: 10 remaining → replenish
- Batch: 25 new keys per cycle
- Max ID: 0xFFFFFF (24-bit Signal spec)
- SecurePrefs key for counter: `signal_prekey_next_id`

## Files
- `crypto/signal/SignalPreKeyRefresher.java` — new class; all replenishment logic
- `crypto/signal/SignalKeyManager.java` — `getPreKeyCount()`, `getAndIncrementNextPreKeyId()`, `storeNewPreKeys()`, `KEY_PREKEY_NEXT_ID`; `consumePreKey()` now calls `checkAndReplenish()`
