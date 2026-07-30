# Restore-From-Seed Correctness Audit — 2026-07-30

**Scope:** Line-by-line review of the account-restore path — `RestoreFromSeedActivity.java`
(client) plus the server endpoints it depends on, `/mintToken` and `/migrateUid`
(`server/index.js`). This goes deeper than `COMPATIBILITY_AUDIT_2026-07.md` (2026-07-14),
which covered device/build compatibility and general auth-flow silent failures but did not
walk this file step by step.

**Trigger:** restore is the one path that can misroute a user's identity or silently mix
accounts' data on a shared device. A bug here is rare (needs a specific device history) but
severe (wrong messages/contacts shown to the wrong identity), so it warranted a dedicated pass.

---

## Critical finding — fixed

### The "switch identity" wipe guard could never fire (BUG-D-RESTORE02)

**File:** `RestoreFromSeedActivity.java`

The code already had a guard (`wipeStaleLocalIdentityIfSwitching`, added for `BUG-D-RESTORE01`)
intended to wipe a previous account's local Room DB and media cache before restoring a
*different* identity on the same device — necessary because none of the local tables
(`messages`, `contacts`, `groups`, …) are scoped by owner UID.

The guard worked by reading `KEY_USER_ID` from `SharedPreferences` and comparing it to the
identity being restored. The bug: **Step F** (storing the recovered identity) unconditionally
overwrote `KEY_USER_ID` with the *new* identity several steps *before* **Step G2** called the
guard. By the time the guard ran, it was reading back the value it had just written, so
`existingUserId` was always equal to `incomingUserId` — the switch could never be detected and
the wipe never ran.

**Real-world impact:** on a device where a previous account was left in place by an auto
sign-out (which intentionally does *not* wipe local data, so the same user's session resumes
quickly), restoring a *different* account would silently leave the old account's messages and
contacts mixed into the new account's conversation list.

**Fix:** capture `existingUserId` once, at the very top of `restoreOnBackground()`, before any
write touches `KEY_USER_ID`, and thread that captured value into
`wipeStaleLocalIdentityIfSwitching(existingUserId, incomingUserId)` instead of letting it
re-read (now-overwritten) prefs. Verified with a full `:app:compileDebugJavaWithJavac` build.

---

## Verified correct (no action needed)

- **Client/server hash agreement.** Client hashes the raw Signal identity public key bytes
  (`sha256Hex(pubKeyBytes)`). Server's `sha256hex(identityPubKeyHex)` looked suspicious at first
  glance (it takes the *hex string* the client sent) but it decodes the hex back to raw bytes
  (`Buffer.from(hexStr, "hex")`) before hashing — so both sides hash the same raw bytes. No
  mismatch.
- **Account-ID / mnemonic binding.** `derivedUserId` (from the seed) must equal the user-entered
  Account ID before any network call is made — a stolen seed alone cannot restore an account
  without also knowing the Account ID, and a mistyped phrase that still passes BIP39 checksum
  cannot coincidentally derive the right Account ID (128-bit entropy).
- **UID authentication check.** After `signInWithCustomToken()`, the code re-verifies the live
  Firebase UID equals `derivedUserId` and signs out + aborts if not, before marking
  `restoreSessionEstablished`.
- **Legacy-account migration detection.** `/mintToken` only writes `identities/{userId}.uid` on
  first creation (new accounts) and never touches it again on subsequent verifies — so for a
  legacy record (created by the old `signInAnonymously()` flow) the stored `uid` correctly stays
  the old anonymous UID until `/migrateUid` completes. The client's `oldUid != currentUid` check
  reads this correctly and only triggers migration when there's real legacy data to move.
- **`/migrateUid` ordering and idempotency.** Chats/groups/backups are copied before the
  `identities/{userId}.uid` completion marker is written, and the old `users/{oldUid}` doc is
  deleted only *after* that marker — so a retry after a partial failure is always safe, and a
  second call after successful migration is a correctly-detected no-op.
- **Rollback semantics.** `rollbackFailedRestore()` only reverts local auth/identity state; it
  never needs to restore data it wiped, because remote backups are untouched and a retry with
  the same phrase re-runs the whole restore (including re-wiping/re-populating Room) from
  scratch.

---

## Conclusion

One severe-but-narrow bug found and fixed (`BUG-D-RESTORE02`). Everything else touching
identity resolution, migration, and rollback in the restore path checked out. The audit itself
lives in this file; the code fix and its rationale are documented in
`RestoreFromSeedActivity.java` at the call site and inside `wipeStaleLocalIdentityIfSwitching()`.
