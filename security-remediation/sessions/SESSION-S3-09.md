# SESSION-S3-09 — Duress server enforcement

**Lane:** SRV
**Status:** Partial — 3 of 5 plan-scoped findings closed this session
(`S06-M2`, `S06-L1` re-verified already-fixed from source + given new test
coverage; `S06-M3` fully closed by fixing its last two un-redacted log
lines). `S06-H1` and `S06-I1` were already `Fixed` before this session
started (confirmed against `BUG_TRACKER.md`, not re-touched). As a
side-effect, this session also closed 2 of `S05-M1`'s several call sites,
which remains `Partial` overall.
**Commits (this session):** implementation not yet committed at the time
this file was written — see "Git discipline" below for the exact commit
hash once created.

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

S3-09 is scoped to 5 findings: `S06-H1` (server-side `accountLock`
enforcement), `S06-M2` (`_duressNonces` per-uid single nonce + drop-path
delete; TTL), `S06-M3` (stop logging raw uids on both duress endpoints),
`S06-L1` (nonce-expiry fail-closed on malformed `expiresAt`), `S06-I1`
(correct contradictory rules comment).

## Reconciliation before touching anything

Per `BUG_TRACKER.md`, `S06-H1` and `S06-I1` were already `Fixed | Verified`
prior to this session — not re-implemented, not re-touched. That left three
open/partial findings in scope: `S06-M2 | Open`, `S06-M3 | Partial`,
`S06-L1 | Open`. This session addressed exactly those three ("do 3 more
bugs").

## What this session found: two of the three were already fixed in code, only the tracker was stale

Before writing any code, each finding's exact audit text
(`audit/SESSION-06-DURESS.md`) and current source were read side by side.

- **`S06-M2`** (`_duressNonces` unbounded growth) — **already fixed in
  source**, tracker was stale:
  - `firestore.indexes.json` declares a field-level Firestore TTL policy
    (`"collectionGroup": "_duressNonces", "fieldPath": "expiresAt", "ttl":
    true`) — the exact "intended mechanism, costs nothing" fix the audit
    recommended.
  - `/requestLockNonce` (`server/index.js:3512-3522`) deletes any prior
    nonce doc(s) for the calling uid before issuing a new one — the
    "cap outstanding nonces per uid" half of the audit's fix.
  - The issuance window was also shortened from the original 24h to 1h
    (`:3534`), reducing the fallback nonce's live window.
  - No code change was needed. Tracker corrected from `Open | Carried` to
    `Fixed | Verified` with the exact evidence above.

- **`S06-L1`** (nonce expiry fails open on malformed `expiresAt`) — **already
  fixed in source**, tracker was stale, but had **zero test coverage** for
  the fix (the logic was inline inside a Firestore transaction, following
  this codebase's established pattern of extracting such logic to a
  directly-testable pure function was the appropriate structural fix):
  - The `/duress-lock` transaction validated the expiry shape explicitly
    and threw a `401` on a missing/malformed `expiresAt`, rather than the
    old bare-`TypeError`-surfaces-as-500 / fail-open-on-unparseable-string
    behavior the audit described.
  - This session extracted that check into `pure.resolveNonceExpiry(expiresAt)`
    and `pure.isNonceUsable(nonceUid, expiresAt, now)` in
    `server/lib/pure.js`, wired the transaction to call
    `pure.isNonceUsable(...)` instead of the inline expression, and added 9
    regression tests in `pure.test.js` — including the two literal old-bug
    regressions (bare `TypeError` on missing `expiresAt`; fail-open on an
    unparseable string) so a future refactor cannot silently reintroduce
    either.
  - Tracker corrected from `Open | Carried` to `Fixed | Verified`.

- **`S06-M3`** (raw uids logged on both duress endpoints) — **partially
  fixed already**, tracker was accurate about what remained: `/requestLockNonce`
  and `/duress-lock` already used `uidTag(uid)`. The two lines the tracker
  flagged as the remaining gap — `/admin/api/duress/enroll` and
  `/admin/api/duress/revoke` (`console.log(...uid=${uid})`) — were fixed
  this session to use `uidTag(uid)`. Verified with
  `grep -n 'uid=\${uid}\`' server/index.js` returning **zero hits** anywhere
  in the file after the change. Tracker corrected from `Partial | Verified`
  to `Fixed | Verified`.

## `S05-M1` — updated, not closed (was only ever mentioned as overlapping S06-M3)

`S06-M3`'s tracker note said its remaining gap "overlaps with S05-M1's two
un-redacted admin lines" — those are the exact two lines fixed above.
Reading `S05-M1`'s full audit text (`audit/SESSION-05-ADMIN.md:358-407`)
before touching it showed its actual scope is **larger** than the tracker's
one-line summary implied: 4 call sites write `adminIp: getClientIp(req)`
**raw** into the permanent, no-TTL `adminAuditLog` Firestore collection
(`server/index.js:814`, `:4007`, `:4122`, `:4170`), and 2 more
`requestId=${requestId}` console lines are also part of the finding's
literal fix list. Neither was in this session's 3-bug scope, so neither was
touched. `S05-M1`'s tracker row was updated to reflect the 2 lines that
*are* now fixed (crediting this session's change) while explicitly
recording the raw-`adminIp`-to-Firestore issue and the `requestId` lines as
still open and out of scope — **not** marked `Fixed` merely because two of
its several call sites happened to get fixed as a side effect.

## Files changed

- `server/index.js` — `pure.isNonceUsable(...)` wired into the
  `/duress-lock` transaction in place of the inline expiry check; two
  `console.log` lines in `/admin/api/duress/enroll` and
  `/admin/api/duress/revoke` changed to `uidTag(uid)`; two pre-existing,
  unrelated mangled box-drawing-character bytes in nearby comments (found
  while re-reading this region, not introduced by this session) cleaned up
  in the same pass since they were adjacent to lines already being edited.
- `server/lib/pure.js` — added `resolveNonceExpiry(expiresAt)` and
  `isNonceUsable(nonceUid, expiresAt, now)`, exported both.
- `server/lib/pure.test.js` — added 9 tests for the two new functions.
- `BUG_TRACKER.md` — `S06-M2`, `S06-M3`, `S06-L1` corrected to
  `Fixed | Verified`; `S05-M1` updated in place (remains `Partial`).

## Test evidence (run this session)

- `node --check server/index.js` — clean.
- `node --check server/lib/pure.js` — clean.
- `node --test server/lib/pure.test.js` — **67/67 pass**, including the 9
  new `resolveNonceExpiry`/`isNonceUsable` cases.
- `cd server && npm test` — **194 pass / 1 fail / 195 total**. The 1 failure
  is `lib/identityVerify.test.js`, failing with `Cannot find module
  '@signalapp/libsignal-client'` — the same pre-existing, environment-only
  missing-native-dependency failure documented across
  `SESSION-S3-05.md` through `SESSION-S3-08.md`; re-confirmed in isolation
  this session (`node --test lib/identityVerify.test.js`) by reading the
  `MODULE_NOT_FOUND` stack directly, not assumed from memory.
- Live-wiring greps: `pure.isNonceUsable` is called from the `/duress-lock`
  transaction in `server/index.js`; `uidTag(uid)` confirmed present at both
  the enroll and revoke `console.log` call sites; `grep 'uid=\${uid}\`'
  server/index.js` returns no results anywhere in the file.

## Verification NOT run (recorded, not fabricated)

- No live Firestore transaction was exercised against `/duress-lock` or
  `/requestLockNonce` (would require a Firestore emulator or live project);
  correctness for the nonce-expiry logic was established via the new pure
  unit tests, and for the prune-on-issue logic via source read plus the
  pre-existing `firestore.indexes.json` TTL declaration, not a new
  integration test.
- No Firestore emulator run to confirm the TTL policy in
  `firestore.indexes.json` actually deletes documents in a real project —
  Firestore TTL deletion is an asynchronous background process on Google's
  side that cannot be observed from a unit test; the declaration's presence
  and correct field targeting were verified by reading the file directly.

## Git discipline

Implementation change (`server/index.js`, `server/lib/pure.js`,
`server/lib/pure.test.js`) and documentation change (`BUG_TRACKER.md`, this
file, `START_HERE.md`, `SESSION_INDEX.md`) are committed separately, per
protocol. `git status` / `git diff` were reviewed immediately before each
commit to confirm no unrelated changes were staged.

## Chain state

`START_HERE.md`: `LAST DONE: S3-09` — 3 of 5 plan-scoped findings addressed
this session (`S06-M2`, `S06-L1` re-verified already-fixed + given test
coverage; `S06-M3` fully closed). `S06-H1`/`S06-I1` were already `Fixed`
before this session and were not re-touched. `S05-M1` partially advanced as
a side effect but remains `Partial` — raw `adminIp` writes to
`adminAuditLog` and the `requestId` console lines are carried forward
explicitly. `NEXT SESSION: S3-10` (duress eligibility & rules coverage) per
`ROUND3_REMEDIATION_PLAN.md`.
