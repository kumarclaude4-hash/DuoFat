---
name: DuoShield ChatMediaActivity patterns
description: Key patterns in ChatMediaActivity for message dedup, Firestore cost control, and Room-first loading
---

## knownIds HashSet (F-07 fix)
`ChatMediaActivity` maintains `Set<String> knownIds` (HashSet). All message IDs loaded from Room or received via Firestore are added to this set.

**Why:** The old `exists()` check iterated the full adapter list for every incoming message — O(n²) on re-attach for long conversations. HashSet lookup is O(1).

**How to apply:** Always `knownIds.add(id)` when adding a message to the adapter. Always `knownIds.contains(id)` to check for duplicates. Never iterate the adapter list for existence checks.

## latestKnownTimestamp + Firestore startAfter (F-07 fix)
`latestKnownTimestamp` tracks the highest message timestamp seen locally (Room + Firestore). When `listenForMessages()` re-attaches (on `onStart()`), it calls `q.startAfter(new Date(latestKnownTimestamp))` so Firestore only delivers messages newer than what we already have.

**Why:** Without `startAfter`, every app foreground triggers a full conversation re-read from Firestore — O(n) reads per foreground, burning quota fast.

**How to apply:** Update `latestKnownTimestamp` whenever a message with a newer timestamp arrives (from Room load or Firestore). Never reset it to 0 between foreground/background cycles.

## Room-first loading
On `listenForMessages()` with an empty `knownIds`, Room messages are loaded first (background thread → `messageDao().getMessages(convId)`), adapter populated, then Firestore listener attached with `startAfter`.

## MainActivity pre-auth gate (F-04 fix)
`MainActivity.route()` gates the FCM token Firestore upload behind `!AppLockManager.shouldLock(this)`. If the app is locked (PIN set, backgrounded > 3 min), no Firestore writes happen until after the user authenticates.

**Why:** Writing to Firestore before PIN authentication leaks presence — a privacy concern for DuoShield's threat model (plausible deniability, duress scenarios).
