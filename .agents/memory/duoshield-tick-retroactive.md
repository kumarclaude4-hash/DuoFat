---
name: DuoShield retroactive blue-tick fix
description: How sender sees blue ticks on older messages not inside the Firestore startAfter() window
---

## Rule
`listenForMessages()` uses `startAfter(latestKnownTimestamp)`, so MODIFIED events for older messages never reach the sender when partner marks them read.

**Fix (implemented):** `DeliveryReceiptHelper.markRead()` writes `last_read_<myUid>` (ServerTimestamp) to the conversation doc. `listenForConvUpdates()` on the sender detects `last_read_<partnerUid>` changes via `lastPartnerReadMs` guard and retroactively marks all sent messages with `timestamp <= lastReadMs` as "read" in adapter + Room.

**Why:** Firestore `startAfter()` query only fires MODIFIED events for docs inside the query window — old docs are forever invisible to the message listener.

**How to apply:** Any new tick-related feature must check whether it needs a conv-doc signal for out-of-window messages.
