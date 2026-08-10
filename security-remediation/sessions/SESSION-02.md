# SESSION 02 — Round 2: "Advertised guarantees" (P1)

Round 2 fixes the findings where DuoShield **advertises a security guarantee it does not deliver**:
media-scope isolation, duress lock durability, egress containment, and admin accountability.

> **This log is revised in place across multiple working sessions.** Round 2 is split into clusters
> (see `../SESSION_PROTOCOL.md` §8). Each cluster appends its own section below and updates only its
> own findings. Do not read the presence of this file as "Round 2 is done" — check the per-cluster
> sections and the `Status` column in `../FINDING_INDEX.md`.

## Cluster status

| Cluster | Findings | Status |
|---|---|---|
| A | `S03-H1`, `S06-H2`, `S06-H3`, `S06-I2` | **DONE** (2026-08-10, this session) |
| B | `S04-H1`, `S04-H2`, `S04-H3`, `S05-H1`, `S05-H3`, `S05-I1` | not started in this log |
| C | `S08-H5`/`S07-M1`, `S08-H2`, `S08-H3`, `S08-H4`, `S10-N2`, `S10-N3`, `S07-L4`, `SC-01`, `SC-04`, `SC-05`, `S04-I2` | not started in this log |

Round 2 is **NOT closed.** Only cluster A is complete.

---

## Cluster A — media scope + duress lock (2026-08-10)

### What the tracker claimed vs. what source actually said

The protocol's §1 rule ("source beats narrative") paid for itself immediately. All four findings'
`Planned Disp` column read `fixed`, which carries no evidentiary weight; the authoritative `Status`
column said `partial`/`open`/`open`/`open`. Verifying each against source found the tracker wrong in
**three of four** rows — in both directions:

| Finding | Tracker `Status` | Source truth |
|---|---|---|
| `S03-H1` | partial | **open** — bypass fully intact, nothing had been done |
| `S06-H2` | open | **already fixed** — stale row, no work needed |
| `S06-I2` | open | **already fixed** — stale row, no work needed |
| `S06-H3` | open | **partially fixed, and silently inert** — see below |

Audit line numbers had drifted substantially (`S03-H1` cited `server/index.js:509-530`; the handler
is now at `~2386-2484` and the decision function at `595-620`). Line numbers in the index have been
corrected to the real locations.

### `S03-H1` — media-token scope confusion (fixed)

Confirmed exploitable exactly as described. `firestore.rules:126-130` allows any authenticated user
to create `groups/{ANY_ID}` provided they list themselves in `members`, with **no constraint on the
document ID**. `callerMayAccessScope` accepted *either* a `chats/{scopeId}` or a `groups/{scopeId}`
document as proof of membership. Since 1:1 chat IDs are deterministic (SHA-256 over the two sorted
UIDs), an attacker who can compute a victim conversation's `chatId` could create a **shadow**
`groups/{thatChatId}` naming only themselves and mint a `read` or `delete` media token for a
conversation they have nothing to do with.

Fix: the decision moved to `server/lib/mediaScope.js` as a pure function, and now requires the
scopeId to resolve **unambiguously**. A scopeId naming both a chat and a group is not a state any
legitimate client flow produces — chat IDs are content-derived hashes, group IDs are random — so the
overlap is itself the attack signature and is denied.

Two details that matter more than they look:

1. **Ambiguity is checked before membership.** If the membership tests ran first, an attacker who is
   a legitimate member of the shadow group *they just created* would be allowed by the group branch
   before the collision was ever noticed. There is a regression test asserting this specific
   ordering.
2. **Groups must carry `createdBy` ∈ `members`.** This rejects the minimal `{members:[self]}`
   document a squatter writes, as defense in depth for the case where a future rules change
   reintroduces ID squatting.

Made pure and I/O-free specifically so it could earn a **real** test rather than an asserted one:
`node --test lib/mediaScope.test.js` → **16/16 pass**; full `npm test` → **99/99 pass**, no
regressions. Wiring confirmed live at `index.js:602` — deliberately checked, because see below.

### `S06-H3` — offline duress lock (fixed; the session's most important finding)

The durable-intent machinery was real: `PendingLockStore` exists, records an intent that survives the
wipe, and `drainPendingLockIntent()` is genuinely wired into the launch path. On that basis the row
looked nearly done.

But the offline path consumes a **warm nonce** parked ahead of time by
`DuressManager.maintainLockCredential()`, and a repo-wide grep found that method had
**zero callers** — definition only, at `DuressManager.java:753`. It was dead code. The nonce was
therefore never parked, so on a genuinely offline duress trigger `getWarmToken()` always returned
null, the intent was recorded with no credential, `drainPendingLockIntent()` found nothing to send,
and **the account was never locked** — silently, which is precisely the attacker's win condition.
Meanwhile `PendingLockStore`'s javadoc asserted the pre-fetch "always" gave the duress path a usable
credential.

This is `SESSION_PROTOCOL.md` failure mode #3 in miniature: fluent, confident documentation
describing behavior that does not execute. It would have passed any review that read comments instead
of call graphs, and it is exactly what the "verify the wiring, not just the function" rule exists to
catch. It also directly informed the `S03-H1` fix above — the wiring of `decideScopeAccess` was
explicitly re-verified rather than assumed, to avoid committing the identical sin in new code.

Fix: added the single missing call in `BaseActivity.onStart()`, in the branch reached only when the
session is valid and the app is genuinely foregrounded and unlocked — matching the "ordinary online
foreground operation" precondition in the method's own contract. It self-throttles, no-ops when
signed out or offline, and does its I/O on its own thread, so it adds no main-thread work.

Also corrected two **false comments** rather than leaving them to mislead the next reader:

- `drainPendingLockIntent()` claimed it enqueued a "best-effort worker retry" in the no-credential
  branch. No such call ever existed, and one would have been pure noise anyway —
  `AccountLockWorker.enqueue()` requires a nonce and returns immediately without one.
- `PendingLockStore`'s class javadoc asserted the pre-fetch happens during ordinary foreground
  operation, which only became true with this change. It now names `BaseActivity.onStart()` as the
  sole, load-bearing call site.

### `S06-H2` and `S06-I2` — already fixed (no code change)

Verified from source, not assumed:

- `S06-H2`: `AccountLockWorker`'s input data carries only an opaque nonce — no uid, no reason, no
  duress marker. `FcmUnregisterWorker` carries no input data at all and is enqueued with the same
  jittered delay on ordinary sign-out (`BaseActivity.java:90-92`), so the WorkManager database holds
  nothing that distinguishes a duress wipe from a normal logout. The non-duress enqueue is the part
  that actually removes the correlation, so it was confirmed to exist rather than inferred.
- `S06-I2`: the lock outcome is gated on `task.isSuccessful()` before `lockConfirmed` is set, and the
  durable intent is cleared only on true confirmation. A failed lock is retained as "believed
  unlocked" instead of being mistaken for success.

Both rows were stale `open`s. Recorded as `fixed` with `verified-source` and an explicit note that no
code change was required, so a later reader does not go looking for a phantom commit.

## Verification performed

| Check | Result |
|---|---|
| `node --test lib/mediaScope.test.js` | 16/16 pass |
| `npm test` (whole server suite) | 99/99 pass — no regression |
| `node --check server/index.js`, `lib/mediaScope.js` | clean |
| `decideScopeAccess` wiring | confirmed live at `index.js:602`, require at `index.js:7` |
| `maintainLockCredential` dead-code claim | proven by repo-wide grep (definition-only before fix) |
| Java call-site validity | signature `static void (Context)` matches; `Log`/`TAG`/package resolve |
| **Android compilation** | **BLOCKED — no JDK/Android SDK in this environment** |

## Honest limitations

- **No Java was compiled.** There is no JDK, no `javac`, and no `ANDROID_HOME` in this sandbox
  (`gradlew` is present but unusable). The three Java edits are reviewed against source and
  signature-checked by hand, and are **not** compile-verified. Same constraint recorded for `S07-C1`
  in Round 1 — it is an environment limit, not an oversight, and it should be cleared in CI.
- **No Android test.** The `S06-H3` fix is guarded only by a comment marking the call site
  load-bearing. A refactor that drops it re-breaks the offline duress lock silently. An
  instrumentation test is the real fix and is registered as the revisit trigger.
- **`S03-H1`'s fix trades confidentiality for availability.** Fail-closed on ambiguity means an
  attacker who squats a shadow group can deny *both* legitimate participants their conversation's
  media. Registered in `../RISK_REGISTER.md`; the clean elimination is at the rules layer and is
  owned by `S01-L1` (R3).
- **`firestore.rules` was deliberately not modified.** The ID-squatting primitive that makes the
  shadow group creatable is `S01-L1`'s scope and is scheduled for R3. Fixing it here would mean
  silently editing another finding's ownership; the server-side gate is the authoritative fix for
  `S03-H1` and stands on its own.
- Cluster A only. Nine other Round 2 findings are untouched.

## Next

Round 2 clusters B and C, per `../SESSION_PROTOCOL.md` §8. The highest-value carry-forward from this
session is procedural: **`S06-H3`'s dead-code gap proves that "the function exists and looks correct"
is not evidence.** Grep for call sites before believing any fix — three of four rows in this cluster
were mis-stated, and the one that looked most nearly finished was the one that was silently inert.
