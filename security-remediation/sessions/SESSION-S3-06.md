# SESSION S3-06 — Server auth & identity (lane SRV, co-release with APK)

Date: 2026-08-11
Model: Sonnet 5 · Budget: ~$4.73 max
Findings in scope: `S02-H1`, `S02-M1`, `S02-L1`/`S07-H1`, `S02-L2`
Guard honored: did not touch the mandatory `nonce`/`signatureHex` gate anywhere in `/mintToken`.

## 1. Inherited-state falsification (SESSION_PROTOCOL §3) — done before any new code

Read each finding's row in `BUG_TRACKER.md`, all four were stale `Open`/`Carried`. Re-derived each
from current `server/index.js` source rather than trusting the tracker:

- **S02-H1** — `migrateUid` (`:2354-2361` pre-fix) really did `.set(data)` the whole
  `users/{oldUid}` document onto `users/{userId}` verbatim, no allow-list. **Confirmed still open.**
- **S02-M1** — cooldown reservation logic at `:1963-1999` (pre-existing, unchanged by this session)
  already reserves the slot *before* the auth-consuming steps and releases it on every rejection path.
  **Confirmed already fixed** — `git log --oneline -S "S02-M1" -- server/index.js` traces this to
  commit `5c2cd73`, which predates the Round-3 tracker entirely. Not reimplemented.
- **S02-L1` / `S07-H1`** (duplicate findings, same defect) — the existing-account hash check at
  `:2100-2121` already requires `typeof storedHash === "string" && storedHash.length === 64` before
  comparing, with an explicit `S07-H1 fail-closed` label in its own comment. Same
  `git log -S` trace lands on `5c2cd73`. **Confirmed already fixed.** Not reimplemented.
- **S02-L2** — `createChat` (`:2529-2530` pre-fix) wrote `myDisplayName`/`partnerDisplayName` straight
  from the parsed body with only a truthiness check (`if (myDisplayName) ...`) — no type check, no
  length bound. **Confirmed still open.**

Two of the four scoped findings needed no code — this session's actual implementation work is
S02-H1 and S02-L2 only. The other two were corrected in the tracker (§4) with a citation to the real
pre-existing commit, per SESSION_PROTOCOL §3 ("if source already satisfies it, record that and skip").

## 2. Changes implemented this session

New pure, unit-tested module **`server/lib/profileSanitize.js`** (mirrors the `mediaScope.js`
pattern already used for S03-H1 — decision logic extracted so it can be tested without spinning up
the Admin SDK / HTTP server):

- `sanitizeMigratedUserFields(data)` — reduces an arbitrary object to only
  `displayName`/`fcmToken`/`platform`/`photoUrl`, each re-validated for type and a size bound matching
  the `users/{uid}` Firestore write rule's own allow-list/size bounds (`firestore.rules`). Unknown
  fields, wrong types, or oversized values are dropped, not copied.
- `isValidDisplayName(value)` — requires a non-empty string ≤200 chars, matching the
  `users/{uid}.displayName` rule bound.

Wired into `server/index.js`:

- **S02-H1** (`/migrateUid`): `.set(data)` replaced with
  `.set({ ...sanitizeMigratedUserFields(data), updatedAt: FieldValue.serverTimestamp() })`. The old
  document is still read and the migration is still idempotent/retryable; only the field set copied
  onto the new uid changed.
- **S02-L2** (`/createChat`): the `if (myDisplayName)` / `if (partnerDisplayName)` truthiness checks
  replaced with `if (isValidDisplayName(myDisplayName))` / `if (isValidDisplayName(partnerDisplayName))`.

New tests: **`server/lib/profileSanitize.test.js`** (9 cases) —
`sanitizeMigratedUserFields`: allow-listed passthrough, field-injection dropped (`role`, `accountLock`,
`identityPubKeyHash`, `isSystemAccount`), wrong-type dropped, oversized dropped, missing/non-object
doc tolerated. `isValidDisplayName`: normal name accepted, non-string/array/number/null/undefined
rejected, empty string rejected, exact-boundary accepted / one-over-boundary rejected.

## 3. Verification (lane SRV)

- `node --check index.js` → exit 0 (both before and after the edit).
- `cd server && npm test` → **146/147 pass.** The 1 failure is `lib/identityVerify.test.js`, which
  aborts with `Cannot find module '@signalapp/libsignal-client'` — the pre-existing, unrelated,
  documented baseline failure from `SESSION_PROTOCOL.md` §0 (native module unavailable in this
  environment). Re-ran `node --test lib/identityVerify.test.js` directly and confirmed the same
  `MODULE_NOT_FOUND` stack, not a regression introduced this session. All 9 new
  `profileSanitize.test.js` cases pass (147 total tests this session vs. 138 before = +9, matches).
- **Wiring grep-confirmed live:** `grep -n "sanitizeMigratedUserFields\|isValidDisplayName" index.js`
  shows the `require`, the `/migrateUid` call site, and both `/createChat` call sites.
- Commit hash obtained with `git log -1 --format=%H` **after** committing:
  `269d165e4c675d6b01b18a2aa8997e31b3f4e35d` (short `269d165`).

## 4. Dispositions written to `../BUG_TRACKER.md`

| Finding | New disposition | Basis |
|---|---|---|
| S02-H1 | **Fixed** (S3-06) | New `sanitizeMigratedUserFields()`, wired into `/migrateUid`, 5 unit tests, commit `269d165`. |
| S02-M1 | **Fixed** (S3-06, pre-existing) | Re-verified from source; already fixed in commit `5c2cd73` (predates this tracker); no code change this session. |
| S02-L1 | **Fixed** (S3-06, pre-existing) | Same defect/fix as S07-H1; commit `5c2cd73`; no code change this session. |
| S07-H1 | **Fixed** (S3-06, pre-existing) | Fail-closed hash check already in source at `5c2cd73`; no code change this session. |
| S02-L2 | **Fixed** (S3-06) | New `isValidDisplayName()`, wired into `/createChat`, 4 unit tests, commit `269d165`. |

All five (four scoped findings, one of which — S02-L1/S07-H1 — is a duplicate pair sharing one row
each) are written `**Fixed**` rather than `Partial`, because this session's lane is `SRV`
(`cd server && npm test`), which actually ran and passed — unlike the `RULES`/`AND` lanes, `SRV` has
no toolchain blocker in this environment.

## 5. Chain state advanced

`START_HERE.md` "Chain state" block updated: `LAST DONE: S3-06`, `NEXT SESSION: S3-07` (Server limits,
memory growth, IP keying, lane SRV). `SESSION_INDEX.md` updated with this session's row.

---

```
SESSION: S3-06  MODEL: Sonnet 5  BUDGET: ~$4.73 max  CLUSTER: S3-06 (Server auth & identity)  STATUS: 4 findings fixed (2 new code fixes + 2 pre-existing/stale-tracker corrections)
CHANGES:
  - server/lib/profileSanitize.js: NEW — sanitizeMigratedUserFields() (S02-H1), isValidDisplayName() (S02-L2)
  - server/lib/profileSanitize.test.js: NEW — 9 unit tests
  - server/index.js: /migrateUid uses sanitizeMigratedUserFields() instead of raw .set(data); /createChat uses isValidDisplayName() instead of truthiness check; require added (commit 269d165)
  - BUG_TRACKER.md: S02-H1, S02-M1, S02-L1, S07-H1, S02-L2 -> Fixed (S3-06) with this-session evidence and accurate commit references (269d165 for new code, 5c2cd73 cited for the two pre-existing fixes)
  - security-remediation/sessions/SESSION-S3-06.md: NEW — this log
  - security-remediation/START_HERE.md: LAST DONE -> S3-06, NEXT SESSION -> S3-07
  - security-remediation/SESSION_INDEX.md: append S3-06 row
VERIFICATION:
  PASS: node --check index.js; npm test 146/147 (1 pre-existing unrelated failure, reproduced independently); grep-confirmed live wiring for both new functions; commit hash captured with git log -1 --format=%H after committing
  NO REGRESSION: the identityVerify.test.js failure was reproduced in isolation and traced to the same missing-native-module cause documented in SESSION_PROTOCOL.md §0, not to this session's changes
```
