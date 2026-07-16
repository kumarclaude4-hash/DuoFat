---
name: DuoShield delivery/read status race guard
description: Pattern for preventing FCM delivery ACKs from downgrading a message's read status
---

Both the client (`DuoShieldMessagingService.acknowledgeDelivery`) and server
(`server/index.js` `markDelivered`) write `status: "delivered"` when a push is
processed. If the recipient already opened the chat and marked the message
"read" before the ACK write lands (a real race — pushes can arrive after the
chat was already foregrounded), an unconditional `update()` stomps "read" back
down to "delivered" and the sender's tick never shows read in real time.

**Why:** status transitions must be monotonic (sent → delivered → read, never
backward). Any future write path that sets `status` on a message doc needs the
same guard.

**How to apply:** wrap the write in a Firestore transaction, read current
`status` first, and no-op if it's already `"delivered"` or `"read"`. Apply this
pattern to *any* new code that writes message `status`, not just delivery ACKs.
