# SESSION-S3-17 — Android backup & group crypto

**Lane:** AND (verify BLOCKED → S3-19b); `S07-H3` additionally has a RULES half
(verify BLOCKED → S3-15b)
**Status:** COMPLETE for what this environment can do — all 7 scoped findings
land **Partial**. Implementation had already landed on this branch before this
documentation-and-verification pass started; this session's job was to
independently re-verify every change against current source (not trust the
prior commit messages), run the available structural checks, and close out
the tracker/session/index/start-here documentation.
**Model:** Opus 5
**Sequencing note:** this is the plan's actual next unstarted session — no
out-of-order pickup this time. `S3-15` and `S3-16` (the two sessions ahead of
this one) are both closed per their own session logs and `START_HERE.md`'s
chain state before this session began.

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

> S3-17 — Android backup & group crypto · lane AND + RULES (verify BLOCKED)
> Findings: `S07-H2` (unkeyed SHA-256 plaintext oracle in backup docs →
> keyed/removed), `S07-H3` (group message AAD, not rules-only attribution),
> `S07-M2` (trust keyed on immutable identity, not mutable Firebase uid),
> `S07-M3` (bring backup metadata inside the AEAD), `S07-L1` (creator check
> fail-closed on null `creatorUid`), `S07-L4`/`S10-N2` (session deser no
> silent fresh-session substitute; stop logging peer uid to release logcat).
> Exit: source-reviewed; AND rows pending S3-19b, the `S07-H3` rules half
> pending S3-15b.

Read in full before touching anything: `START_HERE.md`, `SESSION_INDEX.md`,
`SESSION_PROTOCOL.md`, `ROUND3_REMEDIATION_PLAN.md`, and the `BUG_TRACKER.md`
rows for all seven findings. Per protocol §3 ("source beats tracker"), every
tracker claim below was independently re-verified against current source —
including re-reading the already-landed implementation commits diff-by-diff,
not just trusting their commit messages — before any documentation was
written.

## State found at session start

`git log --oneline -8` showed the implementation already on this branch,
three commits deep, on top of the merge that closed S3-16/S3-15:

- `46627a2` — `feat: enhance encryption and backup integrity with GCM tags
  and HMACs` (touches `BackupCryptoHelper.java`, `BackupManager.java`,
  `GroupCipherHelper.java`, `GroupChatActivity.java`)
- `443820e` — `fix: enhance sender validation and AAD reconstruction in
  GroupChatActivity` (touches `GroupChatActivity.java`,
  `DuoShieldSignalStore.java`)
- `ad703c9` — `feat: add HMAC integrity tests and associated data (AAD)
  validation` (adds `BackupRoundTripTest.java` additions,
  `GroupCipherHelperAadTest.java`)

`git status` was clean — nothing uncommitted. `BUG_TRACKER.md` still showed
all seven findings as `Open` (S07-M2/M3/L1/L4, S10-N2) or `Open`/`Verified`
(S07-H2/H3) — the tracker had not yet been updated to reflect the landed
work. This session's job was therefore verification + documentation, not
new implementation.

## Source verification performed (before writing any documentation)

Every claim below was checked by reading the actual diff and/or current file
content, not inferred from commit messages:

- **`BackupCryptoHelper.java`** (`git show 46627a2` full diff read): confirmed
  new `computeHmac`/`verifyHmac` (HMAC-SHA256, HKDF-derived key with a
  distinct `info` string `DUOSHIELD_BACKUP_HMAC_V1` from the AES-GCM key,
  constant-time `MessageDigest.isEqual` compare); confirmed legacy
  `computeChecksum`/`verifyChecksum` kept but `@Deprecated` with a javadoc
  explaining why (offline guess-confirmation oracle); confirmed new
  `buildAad(docId, conversationId, compressed)` and AAD-aware
  `encryptCompressed`/`decryptCompressed` overloads that call
  `Cipher.updateAAD`.
- **`BackupManager.java`** (`git show 46627a2` full diff read): confirmed the
  HMAC/AAD helpers are actually **wired into all 4 write call sites**
  (`backupWithRetry`, plus the two `Map<String, Object> doc` builders further
  down, plus the batch-backup path) — every one now calls
  `BackupCryptoHelper.buildAad(...)`, `computeHmac(...)`, and
  `encryptCompressed(key, json, aad)`, and writes `doc.put("hmac", hmac)`
  instead of `doc.put("checksum", checksum)`. Confirmed the restore path
  (`restoreAllSync`) reads both `checksum` (legacy) and `hmac` (current)
  fields, reconstructs the identical AAD from the doc's own
  `(docSnap.getId(), conversationId, compressed)` **only when `hmac` is
  present** (the marker for "written by the AAD-aware path"), and prefers
  `verifyHmac` over the legacy `verifyChecksum` — a doc written before this
  fix has no `hmac` field and falls through to the old path unchanged. This
  confirms `S07-H2` and `S07-M3` are wired at real call sites, not just
  helper code sitting unused.
- **`GroupCipherHelper.java`** (`git show 46627a2` full diff read): confirmed
  new `buildAad(groupId, senderUid, messageId)` and 3-arg
  `encrypt`/`decrypt` overloads that call `Cipher.updateAAD`; legacy 2-arg
  overloads kept for messages written before this fix.
- **`GroupChatActivity.java`** (`git show 46627a2` + `git show 443820e`, both
  full diffs read): confirmed the send path (`~line 561` at the time of the
  first commit) builds `aad = GroupCipherHelper.buildAad(groupId, myUid,
  msgId)` and calls the AAD-aware `encrypt`, and the doc-write further down
  sets `doc.put("aadV1", true)`. Confirmed the receive path reads
  `doc.getBoolean("aadV1")` and, only when `true`, reconstructs
  `GroupCipherHelper.buildAad(groupId, sender, id)` from the message's own
  fields before calling the AAD-aware `decrypt` — a legacy message with no
  `aadV1` field decrypts with `aad = null`, i.e. exactly as before. Verified
  the `id` field used at decrypt time is the same field written at send time
  (both are the message's own Firestore doc id), so the AAD reconstructed on
  read is byte-identical to the AAD bound at write.
- **`GroupChatActivity.java`'s creatorUid check** (`git show 443820e`, full
  diff read + `sed -n` on current source): confirmed the fix changed
  `if (creatorUid != null && !creatorUid.equals(sender))` to
  `if (creatorUid == null || !creatorUid.equals(sender))` — the exact
  fail-open → fail-closed flip the finding calls for. A null/missing
  `creatorUid` (legacy Room row, sync gap) now denies unconditionally instead
  of silently trusting any claimed sender.
- **`DuoShieldSignalStore.java`** (`git show 443820e`, full 277-line diff
  read): confirmed the trust-storage rekeying — new
  `KEY_FP_POINTER_PREFIX`/`KEY_TRUST_BY_FP_PREFIX` key families anchored on
  `fingerprintOf(serializedKey)` (SHA-256 of the serialized identity key,
  immutable), replacing the old address-scoped (mutable-uid-embedding)
  `KEY_TRUSTED_IDENTITY_PREFIX` as the primary trust anchor. Confirmed new
  `readTrustRecord(address)` performs a one-time, read-triggered migration:
  on first read for an address with no fingerprint-keyed record yet, it
  reads the **legacy record for that same address** (if any), computes that
  key's own fingerprint, and writes it forward — it migrates the address's
  own already-trusted key forward to the new storage, it does not invent or
  cross-wire trust from a different address/uid. A genuinely first-contact
  address (no legacy record either) still correctly returns `null`. Confirmed
  `saveIdentity`/`getIdentity`/`isTrustedIdentity` all route through
  `readTrustRecord`/the new key families. Confirmed the legacy key is left
  in place afterward (harmless, unread going forward) rather than deleted —
  a deliberate one-way, non-destructive migration.
- **`DuoShieldSignalStore.loadSession`** (same diff): confirmed the fix
  replaced `catch (Exception e) { Log.e(...); return new SessionRecord(); }`
  with: delete the corrupt row from `AppDatabase`, then `throw new
  SessionDeserializationException(...)` (new `RuntimeException` subclass
  defined in the same file). Confirmed the fresh-session return for a
  genuinely-absent row (`row == null`) is unchanged — only the
  deserialize-failure branch changed behavior. Traced `loadSession`'s only
  in-repo caller (`loadExistingSessions`, no local catch) and confirmed the
  libsignal `SessionStore` contract this class implements does not require
  `loadSession` to never throw — it is a checked-exception-free interface
  method already declared to potentially throw a `RuntimeException`
  implicitly, and every app-level entry point that ends up invoking
  libsignal operations backed by this store (`SignalCipherHelper`/
  `GroupCipherHelper` call sites in `GroupChatActivity`, etc.) already wraps
  those calls in `catch (Exception ex)`, and `SessionDeserializationException
  extends RuntimeException extends Exception`, so it is caught by every
  existing handler — this does not introduce a new uncaught-crash surface.
- **Peer-uid log redaction (`S07-L4`/`S10-N2`)**: grepped every `Log.w`/
  `Log.e` call in `app/src/main/java/com/duoshield/app` that references an
  address/sender/creatorUid/uid-shaped variable. Confirmed every one in the
  two files this session's implementation touched
  (`DuoShieldSignalStore.java`: `saveIdentity` ×2, `isTrustedIdentity`,
  `getIdentity`, `storeSession`; `GroupChatActivity.java`: the creator-check
  log) now wraps the identifier in `LogRedact.uid(...)`. Confirmed
  `LogRedact` itself is a pre-existing utility (not new this session, not
  new this program) already used elsewhere (`SignalSessionManager.java`,
  `SignalKeyManager.java`, `CreateGroupActivity.java`) — this session applied
  an existing pattern to two files that had not yet adopted it, it did not
  invent the redaction mechanism. Confirmed the only `Log.w` lines in the
  broader repo that still contain a literal `uid` substring are logging a
  constant diagnostic string with no actual uid *value* interpolated (e.g.
  `FcmTokenHelper.java`'s `"register: uid still null after ... retries"`),
  which is not an instance of this finding.
- **Test files** (`git show ad703c9`, full diff read;
  `grep -n "@Test"` on both files): confirmed
  `app/src/test/java/com/duoshield/app/backup/BackupRoundTripTest.java` and
  `app/src/test/java/com/duoshield/app/crypto/GroupCipherHelperAadTest.java`
  are plain-JVM `src/test` unit tests (not `androidTest`/instrumented) —
  same category as the existing, already-working test pattern this program's
  prior AND-lane sessions relied on. Confirmed both files contain real
  `@Test`-annotated methods exercising HMAC compute/verify and AAD round-trip
  plus tamper/mismatch rejection, not stubs.

## Disposition of all seven findings

### S07-H2 — Unkeyed SHA-256 plaintext oracle in backup docs → **Partial**

- **Fix (already landed, commit `46627a2`):** `BackupCryptoHelper.computeHmac`/
  `verifyHmac` — HMAC-SHA256 keyed with an HKDF-derived value distinct from
  the AES-GCM key — replaces the legacy unkeyed SHA-256 `checksum` field as
  the integrity mechanism for all new backup writes. Legacy
  `computeChecksum`/`verifyChecksum` kept, `@Deprecated`, read-only for docs
  written before this fix.
- **Wired, not just added:** confirmed this session (see verification above)
  that all 4 `BackupManager` write call sites compute and store `hmac`
  instead of `checksum`, and the restore path prefers `verifyHmac` whenever a
  doc carries an `hmac` field, falling back to the legacy path only for
  pre-fix docs.
- **Test coverage:** `BackupRoundTripTest.java` gained HMAC compute/verify
  round-trip cases (plain JVM, no Android/Firebase dependency).
- **Why Partial, not Fixed:** no JDK/Gradle/Android SDK in this environment —
  source-reviewed and structurally checked (brace/paren/bracket balance),
  not compiled or test-executed. AND-lane verification routed to **S3-19b**
  per this program's standing exit criterion for every Android session.

### S07-H3 — Group message AAD (not rules-only attribution) → **Partial**

- **Fix (already landed, commits `46627a2` + `443820e`):**
  `GroupCipherHelper.buildAad(groupId, senderUid, messageId)` binds a group
  message's ciphertext to the exact context it was sent in at the
  **crypto layer** — the group's single shared AES key otherwise had no
  binding preventing a ciphertext written for one (group, sender, message
  id) from being replayed/spliced into another slot the same key protects;
  that binding previously existed only in Firestore security rules.
  `GroupChatActivity`'s send path builds the AAD from
  `(groupId, myUid, msgId)`, calls the AAD-aware `encrypt`, and tags the doc
  `aadV1: true`; the receive path reconstructs the identical AAD from the
  message's own `(groupId, sender, id)` fields **only** when `aadV1` is
  present, so a legacy message (no bound context, predates this fix) still
  decrypts with no AAD exactly as before.
- **Test coverage:** new `GroupCipherHelperAadTest.java` — AAD round-trip and
  tamper/mismatch rejection (plain JVM, no libsignal native dependency).
- **Two lanes, both still open, neither closes this to Fixed:**
  1. **AND** — no JDK/Gradle/Android SDK here; source-reviewed and
     structurally checked, not compiled/tested; routed to **S3-19b**.
  2. **RULES** — this fix is crypto-layer only. The plan's own exit
     criterion for this finding explicitly separates "AND rows" from "the
     `S07-H3` rules half" — i.e. a Firestore-rules-level binding of
     sender/group attribution (the plan's "not rules-only attribution"
     phrasing implies rules should also enforce this, not just the crypto
     layer) is untouched by this session and remains **pending S3-15b**.

### S07-M2 — Trust keyed on mutable uid, not immutable identity → **Partial**

- **Fix (already landed, commit `443820e`):** `DuoShieldSignalStore` trust
  storage re-anchored on `fingerprintOf(serializedKey)` (SHA-256 of the
  serialized identity key — immutable; a new key always gets a new
  fingerprint, and the same physical key always maps back to the same
  fingerprint even if the uid pointing at it changes) via new
  `KEY_FP_POINTER_PREFIX`/`KEY_TRUST_BY_FP_PREFIX` SecurePrefs key families,
  replacing the old uid-embedding `KEY_TRUSTED_IDENTITY_PREFIX` as the
  primary anchor.
- **Migration verified non-destructive and non-misattributing:** new
  `readTrustRecord(address)` migrates a legacy uid-keyed record forward to
  fingerprint-keyed storage on first read — this session specifically
  verified the migration reads and forwards **the same address's own**
  legacy record (not a different uid's), so no cross-uid trust
  misattribution is introduced; a genuinely-first-contact address still
  correctly returns "no record."
- **Gap (recorded, not hidden):** no dedicated unit test was added for the
  migration path itself — the change is verified by source read only in
  this session, not by a new JUnit test exercising `readTrustRecord`
  directly.
- **Why Partial, not Fixed:** no JDK/Gradle/Android SDK here —
  source-reviewed and structurally checked, not compiled/tested; AND-lane
  verification routed to **S3-19b**.

### S07-M3 — Backup metadata outside the AEAD → **Partial**

- **Fix (already landed, commit `46627a2`, same commit as S07-H2):**
  `BackupCryptoHelper.buildAad(docId, conversationId, compressed)` binds
  those three Firestore metadata fields — previously plain, unauthenticated
  fields living alongside the `enc` ciphertext — into the GCM tag via new
  AAD-aware `encryptCompressed`/`decryptCompressed` overloads. Flipping
  `compressed` or splicing one document's ciphertext under a different
  id/conversationId now fails the GCM tag check instead of silently
  succeeding or misapplying the wrong decompression path.
- **Wired, not just added:** confirmed this session (see verification above)
  that all 4 `BackupManager` write call sites build and pass this AAD, and
  the restore path reconstructs the identical AAD from the doc's own
  Firestore fields **only** when the doc carries the new `hmac` field (the
  shared marker for "written by the current, AAD-aware path") — a doc
  written before this fix has no bound AAD and keeps decrypting exactly as
  it always did.
- **Why Partial, not Fixed:** no JDK/Gradle/Android SDK here —
  source-reviewed and structurally checked, not compiled/tested; AND-lane
  verification routed to **S3-19b**.

### S07-L1 — `fetchGroupKey` creator check fails open on null `creatorUid` → **Partial**

- **Fix (already landed, commit `443820e`):** `GroupChatActivity`'s
  group-key-doc sender check flipped from
  `creatorUid != null && !creatorUid.equals(sender)` (short-circuited to
  `false` — i.e. let **any** claimed sender through — whenever the locally
  cached `creatorUid` was `null`, e.g. a legacy Room row from before
  `creatorUid` was populated, or a sync gap) to
  `creatorUid == null || !creatorUid.equals(sender)` (a missing/null
  `creatorUid` now denies unconditionally, exactly like a mismatched one —
  fails **closed**).
- **Bundled with the S07-L4/S10-N2 redaction fix in the same commit:** the
  accompanying `Log.w` for this branch now redacts both `sender` and
  `creatorUid` via `LogRedact.uid()`.
- **Gap (recorded, not hidden):** no dedicated Activity-level/instrumentation
  test was added for this specific branch this session — verified by source
  read only, consistent with this codebase's general lack of
  Activity-level test infrastructure (no existing precedent this session
  could extend).
- **Why Partial, not Fixed:** no JDK/Gradle/Android SDK here —
  source-reviewed and structurally checked, not compiled/tested; AND-lane
  verification routed to **S3-19b**.

### S07-L4 — `loadSession` silent fresh-session substitution → **Partial**

- **Fix (already landed, commit `443820e`):** on a session-row
  deserialization failure, `DuoShieldSignalStore.loadSession` now deletes the
  corrupt row (so `containsSession()` correctly reports `false` afterward
  and a real X3DH renegotiation can happen) and throws a new typed
  `SessionDeserializationException` (`RuntimeException`) instead of
  silently returning a fresh, empty `SessionRecord`. The old behavior let
  `containsSession()` keep reporting `true` for a row that was actually
  unusable, so `establishSession`'s fast path believed a session already
  existed, skipped renegotiation, and every subsequent `encrypt` silently
  ran against an empty ratchet with no indication anything had gone wrong.
- **Crash-safety verified this session:** confirmed `loadSession`'s only
  in-repo caller (`loadExistingSessions`) and every app-level entry point
  that transitively invokes libsignal operations backed by this store
  already wrap those calls in `catch (Exception ...)` — `RuntimeException`
  **is** an `Exception`, so the new exception surfaces as a caught, logged
  failure, not an uncaught crash. This was the specific risk worth checking
  before accepting a "throw instead of silently succeeding" fix.
- **Why Partial, not Fixed:** no JDK/Gradle/Android SDK here —
  source-reviewed and structurally checked, not compiled/tested; AND-lane
  verification routed to **S3-19b**.

### S10-N2 — Peer uid in release logcat → **Partial**

- **Fix (already landed, commit `443820e`):** every raw-peer-uid `Log.w`/
  `Log.e` call site in the two files this session's implementation touched
  (`DuoShieldSignalStore.java`: `saveIdentity`, `isTrustedIdentity`,
  `getIdentity`, `storeSession`; `GroupChatActivity.java`: the creator-check
  log) now routes the identifier through the pre-existing `LogRedact.uid()`
  helper — a stable-per-process, salted-SHA256 tag, not a raw uid — before
  logging at a level (`Log.w`/`Log.e`) that survives release builds per
  `proguard-rules.pro` (which deliberately keeps those two levels for real
  failure diagnostics while stripping `Log.d`/`Log.i`).
- **`LogRedact` itself is not new** — it already existed and was already in
  use elsewhere (`SignalSessionManager.java`, `SignalKeyManager.java`,
  `CreateGroupActivity.java`, `BackupManager.java`). This session's fix
  applied that existing, established pattern to the two files where it had
  not yet been adopted.
- **Verification this session:** grepped every `Log.w`/`Log.e` call across
  `app/src/main/java/com/duoshield/app` referencing an
  address/sender/creatorUid/uid-shaped variable — confirmed no raw-peer-uid
  line remains in either of the two touched files.
- **Why Partial, not Fixed (two independent reasons):**
  1. **Scope:** this session's fix (and this session's verification) is
     limited to the two files touched for the other six findings in this
     cluster. A full-repo sweep for any other release-surviving raw-uid log
     line outside those two files was **not** performed and is not claimed
     — it is plausible (though not confirmed) that every other call site
     already uses `LogRedact` given how established the pattern is
     elsewhere, but this session did not verify that claim repo-wide.
  2. **Toolchain:** no JDK/Gradle/Android SDK here — source-reviewed and
     structurally checked, not compiled/tested; AND-lane verification
     routed to **S3-19b**.

## Files changed (already committed before this session's documentation pass)

- `app/src/main/java/com/duoshield/app/crypto/BackupCryptoHelper.java` —
  `computeHmac`/`verifyHmac`/`deriveHmacKey`/`buildAad`, AAD-aware
  `encryptCompressed`/`decryptCompressed` overloads, legacy
  `computeChecksum`/`verifyChecksum` marked `@Deprecated`.
  (`S07-H2`, `S07-M3`)
- `app/src/main/java/com/duoshield/app/backup/BackupManager.java` — all 4
  write call sites + the restore path switched from `checksum`/no-AAD to
  `hmac`/AAD, with a legacy fallback path preserved. (`S07-H2`, `S07-M3`)
- `app/src/main/java/com/duoshield/app/crypto/GroupCipherHelper.java` — new
  `buildAad`, AAD-aware `encrypt`/`decrypt` overloads, legacy 2-arg overloads
  kept. (`S07-H3`)
- `app/src/main/java/com/duoshield/app/GroupChatActivity.java` — send path
  builds/passes AAD + tags `aadV1: true` (`S07-H3`); receive path
  reconstructs AAD only when `aadV1` is present (`S07-H3`); creator-check
  flipped fail-open → fail-closed (`S07-L1`); creator-check log redacted
  (`S07-L4`/`S10-N2`).
- `app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java`
  — trust storage rekeyed onto identity-key fingerprint + one-time migration
  (`S07-M2`); `loadSession` deletes corrupt row + throws
  `SessionDeserializationException` instead of silently substituting a
  fresh session (`S07-L4`); `saveIdentity`/`isTrustedIdentity`/`getIdentity`/
  `storeSession` logs redacted via `LogRedact.uid()` (`S10-N2`).
- `app/src/test/java/com/duoshield/app/backup/BackupRoundTripTest.java` —
  new HMAC compute/verify test cases. (`S07-H2`)
- `app/src/test/java/com/duoshield/app/crypto/GroupCipherHelperAadTest.java`
  — new file: AAD round-trip + tamper/mismatch rejection tests. (`S07-H3`)
- `BUG_TRACKER.md` — all 7 rows moved `Open` → `Partial (S3-17)` with full
  source + wiring evidence (this session's documentation work).
- `security-remediation/sessions/SESSION-S3-17.md` (this file),
  `security-remediation/START_HERE.md`,
  `security-remediation/SESSION_INDEX.md` — chain-state + index updates
  (this session's documentation work).

No files outside this list were modified. No server, Firestore rules, or
unrelated Android code touched by this session.

## Test evidence

### Toolchain availability (checked, not assumed)

```
$ which java javac gradle
(no output — none found on PATH)
```

No JDK, no Gradle wrapper execution, no Android SDK in this environment.
Consistent with every AND-lane session in `SESSION_INDEX.md`
(S3-15/S3-16/S3-18's own framing) — **compilation and instrumented/unit test
execution were not run and are not claimed.**

### What was actually run as a substitute check

A proper char-by-char brace/paren/bracket balance scanner (comment- and
string/char-literal-aware — implemented as a real tokenizer, not a
regex-based approximation) was run against all 7 touched/added `.java`
files:

```
app/src/main/java/com/duoshield/app/GroupChatActivity.java                      { 186,186 } ( 1062,1062 ) [ 27,27 ]  BALANCED
app/src/main/java/com/duoshield/app/backup/BackupManager.java                   { 202,202 } (  876, 876 ) [ 59,59 ]  BALANCED
app/src/main/java/com/duoshield/app/crypto/BackupCryptoHelper.java              {  31, 31 } (  146, 146 ) [ 46,46 ]  BALANCED
app/src/main/java/com/duoshield/app/crypto/GroupCipherHelper.java               {   8,  8 } (   44,  44 ) [ 17,17 ]  BALANCED
app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java     {  71, 71 } (  241, 241 ) [ 10,10 ]  BALANCED
app/src/test/java/com/duoshield/app/backup/BackupRoundTripTest.java            {  51, 51 } (  321, 321 ) [ 44,44 ]  BALANCED
app/src/test/java/com/duoshield/app/crypto/GroupCipherHelperAadTest.java       {  21, 21 } (   68,  68 ) [ 12,12 ]  BALANCED
```

**Correction made during this session's verification:** an initial
regex-based strip-then-count pass reported a false-positive `MISMATCH` on
`GroupChatActivity.java` (571 vs 572 parens). Root cause traced: the naive
regex stripped block/line comments and string literals in a fixed order,
which did not correctly handle a comment appearing after a multi-line string
concatenation, leaving a stray unmatched `)` artifact in the stripped text.
A proper single-pass character-by-character tokenizer (correctly handling
`//`, `/* */`, `"..."` with escapes, and `'...'` with escapes, in the actual
order they appear in the source) was written instead and confirmed the file
is genuinely balanced (`186/186` braces, `1062/1062` parens, `27/27`
brackets) — this was a false alarm from the checking method, not a defect in
the source. Recorded here so a future session does not re-discover the same
false positive and waste time on it, and so this claim is not just asserted
without showing the correction.

All 7 files balanced with the corrected tokenizer. This proves the edits are
structurally well-formed; it does **not** prove they compile (no
type-checking, no symbol resolution, no annotation processing) — that gate
is S3-19b's.

### New/changed regression tests — logic manually traced, not executed

`BackupRoundTripTest.java` and `GroupCipherHelperAadTest.java` are both
plain-JVM `src/test` unit tests using the same dependency surface as this
program's existing, already-working Android unit tests (no
Android/Firebase/libsignal-native dependency) — confirmed by reading both
files' imports and test bodies. They are expected to run under the same
`testImplementation` set already declared in `app/build.gradle` (plain
JUnit) once a JDK/Gradle environment is available.

**These tests are NOT claimed as "passing" — they are claimed as present,
logically consistent with the file's existing pattern, and structurally
verified (see brace-balance check above).** Actual execution
(`./gradlew testDebugUnitTest` or equivalent) is deferred to S3-19b, same as
every compile/instrumented claim in this program's AND lane.

## Toolchain / Android blockers (proven, not asserted)

- `which java && which javac && which gradle` — all three return nothing on
  this environment's PATH. No `JAVA_HOME`. No Android SDK directory found.
- Consistent with every prior AND-lane session in `SESSION_INDEX.md` — this
  is not a new or session-specific blocker.
- No workaround was attempted that would risk a false "compiles" claim.

## RULES lane (S07-H3's half)

- `S07-H3`'s crypto-layer fix (AAD binding via `GroupCipherHelper`) is
  complete for this session's scope. The plan's own exit criterion for this
  finding explicitly names a separate "rules half" — i.e. Firestore
  security rules should also enforce sender/group attribution, not rely on
  the crypto layer alone (defense in depth, matching the existing comment in
  `GroupChatActivity` about the creator-check being "defense in depth" on
  top of the Firestore rule). This session did **not** touch
  `firestore.rules` for this finding. Grepped `firestore.rules` this session
  to confirm the existing group-key-doc write restriction (creator-only)
  is unchanged and not regressed by this session — it is not, because this
  session made no rules changes at all.
- Routed to **S3-15b**, the RULES catch-up gate this program uses for every
  Firestore-rules verification that needs the emulator (`no java/firebase`
  CLI in this environment).

## Pre-existing failures / regressions

None encountered — no test suite in this environment could be executed at
all for the Android module (no JDK/Gradle), so there is no "pre-existing vs.
new" test-failure distinction to draw here; the distinction that matters is
compile-time correctness, which is asserted only via source review + the
brace-balance check above, not via a green test run.

## Verification NOT run (recorded, not fabricated)

- **Gradle unit test execution** (`BackupRoundTripTest.java`,
  `GroupCipherHelperAadTest.java`, all tests) — BLOCKED, no JDK/Gradle.
  Routed to S3-19b.
- **Android instrumented tests** (any `androidTest` covering backup
  restore, group message send/receive, or the trust-migration path
  end-to-end) — BLOCKED, no Android SDK/emulator. Routed to S3-19b.
- **Full app compile** (`./gradlew assembleDebug` or equivalent) — BLOCKED,
  same reason. Routed to S3-19b.
- **Firestore rules emulator run** for `S07-H3`'s rules half — BLOCKED, no
  `java`/`firebase` CLI. Routed to S3-15b. (No rules change was made this
  session for this finding in the first place — this blocker applies to
  the *future* rules work, not to re-verifying something already written.)
- **Repo-wide raw-peer-uid log sweep** beyond the two files this session's
  implementation touched — not performed, not claimed (see S10-N2
  disposition above).

## Diff review before finishing (per task instructions)

- `git diff --stat` against `HEAD` before this session's documentation
  commit: only `BUG_TRACKER.md`, `security-remediation/START_HERE.md`,
  `security-remediation/SESSION_INDEX.md`, and this file — the Java
  implementation/test files were already committed on this branch and were
  not re-touched by this documentation pass (verified via `git log` showing
  `46627a2`/`443820e`/`ad703c9` predate this session's work, and via
  `git status` showing a clean tree at session start before any
  documentation edit).
- No accidental encoding changes: all edited files remain UTF-8, no BOM
  introduced.
- No cosmetic-only changes bundled in: every line changed in the
  documentation files is either a disposition update backed by the source
  verification above, or a chain-state pointer update. No unrelated
  reformatting.

## Chain state

S3-17 is **complete for what this environment can do**: all 7 scoped
findings (`S07-H2`, `S07-H3`, `S07-M2`, `S07-M3`, `S07-L1`, `S07-L4`,
`S10-N2`) are source-fixed, wired at their real call sites (not merely
helper code), structurally checked, and documented. All seven land
**Partial** — none is promoted to `Fixed`, because:

- AND-lane compile/unit-test execution is BLOCKED in this environment for
  all seven (no JDK/Gradle/Android SDK) → routed to **S3-19b**.
- `S07-H3` additionally has an unclosed RULES half (Firestore-rules-level
  sender/group attribution, separate from the crypto-layer AAD fix landed
  this session) → routed to **S3-15b**.

This was **not** an out-of-order session — S3-15 and S3-16 (the two
sessions immediately before this one in the plan's numeric sequence) were
both already closed per `START_HERE.md`'s chain state before this session
began, and this session did not skip ahead of anything. The next session in
the plan's own numeric sequence is **S3-18** (Android platform privacy —
`S08-H2`/`S08-H3`/`S08-L4`, lane AND, verify BLOCKED → S3-19b).

## Session record

```
SESSION: S3-17  MODEL: Opus 5  CLUSTER: Android backup & group crypto (S07-H2/H3/M2/M3/L1/L4, S10-N2)  STATUS: partial (all 7 source-fixed + wired, AND-verification-BLOCKED -> Partial; S07-H3 additionally RULES-BLOCKED)
SEQUENCING: plan's actual next unstarted session — no out-of-order pickup. S3-15 and S3-16 were both already closed before this session began.
CHANGES (already committed on this branch before this session; independently re-verified from source, not newly written):
  - app/src/main/java/com/duoshield/app/crypto/BackupCryptoHelper.java: + computeHmac/verifyHmac/deriveHmacKey (HKDF-keyed HMAC-SHA256, distinct info string from AES-GCM key), + buildAad(docId, conversationId, compressed), AAD-aware encryptCompressed/decryptCompressed overloads, legacy computeChecksum/verifyChecksum kept @Deprecated (S07-H2, S07-M3)
  - app/src/main/java/com/duoshield/app/backup/BackupManager.java: all 4 write call sites + restore path switched checksum->hmac and added AAD build/verify, legacy fallback preserved for pre-fix docs (S07-H2, S07-M3)
  - app/src/main/java/com/duoshield/app/crypto/GroupCipherHelper.java: + buildAad(groupId, senderUid, messageId), AAD-aware encrypt/decrypt overloads, legacy 2-arg overloads kept (S07-H3)
  - app/src/main/java/com/duoshield/app/GroupChatActivity.java: send path builds/passes AAD + tags aadV1:true, receive path reconstructs AAD only when aadV1 present (S07-H3); creatorUid check flipped fail-open->fail-closed (S07-L1); creator-check log redacted via LogRedact.uid() (S07-L4/S10-N2)
  - app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java: trust storage rekeyed onto SHA-256 identity-key fingerprint + one-time non-destructive migration from legacy uid-keyed record (S07-M2); loadSession deletes corrupt row + throws new SessionDeserializationException instead of silently substituting a fresh session (S07-L4); saveIdentity/isTrustedIdentity/getIdentity/storeSession logs redacted via LogRedact.uid() (S10-N2)
  - app/src/test/java/com/duoshield/app/backup/BackupRoundTripTest.java: + HMAC compute/verify test cases (S07-H2)
  - app/src/test/java/com/duoshield/app/crypto/GroupCipherHelperAadTest.java: new file, AAD round-trip + tamper/mismatch rejection tests (S07-H3)
DOCUMENTATION (this session):
  - BUG_TRACKER.md: all 7 rows Open -> Partial (S3-17) with full source + wiring evidence
  - START_HERE.md / SESSION_INDEX.md / SESSION-S3-17.md: chain-state + index + session log
VERIFICATION:
  PASS: independent source re-derivation of all 7 findings against current code (not carried from commit messages) — confirmed HMAC/AAD helpers are wired into real BackupManager/GroupChatActivity call sites, not just added as unused helper code; confirmed creatorUid fail-closed flip; confirmed trust-migration reads/forwards the same address's own legacy record (no cross-uid misattribution); confirmed loadSession's new thrown exception is caught by every existing app-level caller (no new uncaught-crash surface); confirmed LogRedact.uid() applied to every raw-peer-uid Log.w/Log.e site in the two touched files
  PASS: corrected char-by-char brace/paren/bracket tokenizer on all 7 touched/added files (all BALANCED) — corrects an initial regex-based false-positive MISMATCH on GroupChatActivity.java caused by comment/string-stripping order, documented in this session's evidence so it isn't rediscovered
  BLOCKED: JDK/Gradle/Android SDK compile and unit/instrumented test execution — which java/javac/gradle all empty on PATH. Routed to S3-19b.
  BLOCKED: Firestore rules emulator run for S07-H3's rules half — no java/firebase CLI. Routed to S3-15b. (No rules change was made this session; this blocker applies to future rules work.)
  NOT RUN: repo-wide raw-peer-uid log sweep beyond the two files this session's implementation touched — scoped, not claimed as complete
COMMIT: 46627a2, 443820e, ad703c9 (implementation + tests, already on branch before this session) ; documentation commit separate, this session  WORKTREE: clean
NEXT SESSION: S3-18 (Android platform privacy — S08-H2/S08-H3/S08-L4, lane AND, verify BLOCKED -> S3-19b) is next in the plan's numeric sequence. Do NOT claim S3-15b or S3-19b done — neither catch-up gate has run; S07-H3's rules half and all 7 findings' AND-lane compile/test verification remain BLOCKED pending those sessions.
```
