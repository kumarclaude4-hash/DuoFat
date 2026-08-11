# SESSION S3-05 — Firestore rules: field validation & abuse caps (lane RULES, verify BLOCKED → S3-15b)

Date: 2026-08-11
Model: Opus 5 · Budget: $5 max
Findings in scope: `S01-M1`, `S01-M2`, `S01-M3`, `S01-M4`, `S01-L1` (finish), `S01-L2`

## 0. Reconciliation performed before new work (SESSION_PROTOCOL §3/§7)

`git log --oneline -10` showed the S3-05 implementation (`firestore.rules` field-validation/abuse-cap
fixes for all six findings above, plus the first batch of regression tests) already committed as
**`3070b0b`** (`feat: add regression tests for Firestore rules validation`), landed after `812813d`
(S3-04) and `fd6ce2c` (a tracker-only touch-up). `START_HERE.md`'s chain state and `BUG_TRACKER.md`
had **not** been advanced past S3-04, and no `SESSION-S3-05.md` existed — a documentation-lag gap of
the same shape SESSION_PROTOCOL §7 warns about, not a code defect. Re-verified `3070b0b`'s
`firestore.rules` changes against current source line-by-line (see §1/§2 below) before writing any new
code, per protocol ("re-confirm before building on inherited state").

While re-verifying, found `3070b0b`'s test file had **not** been updated to match two of its own rule
changes:
- The pre-existing identities test asserted **success** for `.update({ label: 'legacy' })`, but
  `label` is not in the new `hasOnly(['uid','identityPubKeyHash'])` allow-list the same commit added —
  that assertion would now fail against the rule it was supposed to guard.
- The pre-existing backup_logs "owner can create" test omitted the `count` field, which the same
  commit's new rule requires (`request.resource.data.count is number`) — that assertion would now
  fail too.

Both were test bugs, not rule bugs (confirmed against `BackupManager.logEvent` — `count` is written
on every call site, always as a number; `label` is written by no client code anywhere). Fixed the
tests, not the rules, and added the remaining coverage the original commit's scope called for.
Committed separately as **`ec8a919`** — implementation (`firestore.rules`) and this documentation
pass both stay untouched by that commit; only test assertions changed. No amend of `3070b0b`.

## 1. Inherited-state falsification (SESSION_PROTOCOL §3)

Tracker claimed all six Open (L1 already Partial from the S03-H1 side-effect). Re-confirmed against
current source before trusting `3070b0b` had actually closed each gap:

- **S01-M1** — `groups/{id}/messages` create rule (pre-3070b0b) had no `text` size bound → really open
  before that commit; `3070b0b` added `text.size() < 100000`. Confirmed present in current
  `firestore.rules`.
- **S01-M2** — `identities` update rule (pre-3070b0b) pinned `uid`/`identityPubKeyHash` values but had
  no `keys().hasOnly(...)` → really open before; `3070b0b` added the allow-list. Confirmed present.
- **S01-M3** — `backup_logs` create rule (pre-3070b0b) checked only `uid == auth.uid` → really open
  before; `3070b0b` added the full shape/enum/size validation. Confirmed present.
- **S01-M4** — `groups/{id}/messages` delete rule (pre-3070b0b) checked only `sender == auth.uid`, no
  membership re-check → really open before; `3070b0b` added the `get()` membership guard. Confirmed
  present.
- **S01-L1** — `groups` create already had `createdBy`/ID-collision guards from S03-H1; the missing
  "full shape validation" piece (per tracker's own note) was absent before `3070b0b`, which added
  `keys().hasOnly(...)`, `name` type/size, `members` type/size. Confirmed present.
- **S01-L2** — `users/{uid}` `write` (pre-3070b0b) was a single unconstrained rule → really open
  before; `3070b0b` split it into an allow-listed `create, update` plus unconstrained `delete`.
  Confirmed present.

## 2. Changes implemented this session (test-only; rules already landed in 3070b0b)

`firestore.rules` was **not** modified in this session — all six fixes were already correct in
`3070b0b` per §1. Work this session was:

- **`firestore-tests/rules.test.js`** (commit `ec8a919`):
  - Fixed the two stale assertions described in §0 (identities `label` case, backup_logs missing
    `count`).
  - Added explicit S01-M1 tests: oversized `text` → fail, boundary-size `text` → succeed, non-string
    `text` → fail, empty-`text` media message still succeeds (type-carrying messages are unaffected).
  - Added explicit S01-M2 tests: extra field → fail, stored-XSS-shaped field → fail.
  - Added explicit S01-M3 tests: extra field → fail, unknown `event` value → fail, missing required
    field → fail, oversized `error` → fail, non-numeric `count` → fail; plus a success case that
    includes the optional `error` field.
  - Added explicit S01-M4 tests: sender-still-member delete → succeed, non-sender-member delete →
    fail, removed-ex-member delete of own historical message → fail.
  - Added explicit S01-L1 tests: extra field → fail, empty `name` → fail, non-string `name` → fail,
    257-member list → fail.
  - Added explicit S01-L2 tests: all-allow-listed-fields write → succeed, extra field → fail, oversized
    `displayName` → fail, non-string `fcmToken` → fail.

## 3. Verification (lane RULES — emulator BLOCKED)

- **Toolchain check (real):** `which java` → not found; `which firebase` → not found. Both absent this
  session — the RULES lane emulator cannot run here, same blocker as S3-04/S3-15b.
- **Structural/syntax (real, available):** `node --check firestore-tests/rules.test.js` → exit 0
  (both before and after `ec8a919`). Brace/paren/bracket balance of `firestore.rules` computed with
  comments stripped (a bare paren count including prose comments is not a valid signal, so comments
  were excluded first) → `{`:`}` 0, `(`:`)` 0, `[`:`]` 0.
- **Source re-review (real):** every fix in §1 re-read directly in `firestore.rules` at its current
  line numbers and confirmed to match the finding it claims to close; every allow-listed field in
  S01-L1/L2/M2/M3 cross-checked against actual client write call sites (`CreateGroupActivity`,
  `ContactManager`, `SettingsActivity`, `FcmTokenHelper`, `SeedPhraseDisplayActivity`,
  `BackupManager.logEvent`) rather than assumed from the finding text.
- **Not run / not claimed:** no emulator execution of `firestore-tests/rules.test.js` happened or is
  claimed to have happened. Per SESSION_PROTOCOL, this stays **Partial** until S3-15b runs the suite
  for real.

## 4. Dispositions written to `../BUG_TRACKER.md`

| Finding | New disposition | Basis |
|---|---|---|
| S01-M1 | **Partial** (S3-05) | `text` size cap added (`< 100000`); TOCTOU accepted/documented in-rule per audit's own recommendation, not fixed (inherent to `get()`-based checks). |
| S01-M2 | **Partial** (S3-05) | `hasOnly(['uid','identityPubKeyHash'])` added to update; no client write path uses any other field. |
| S01-M3 | **Partial** (S3-05) | Full shape validated against `BackupManager.logEvent`'s actual write (`uid`/`event`/`ts`/`count`/optional `error`); document-count volume itself is a server/quota concern, not rules-enforceable. |
| S01-M4 | **Partial** (S3-05) | Delete now re-checks current membership via `get()`, mirroring read/create. |
| S01-L1 | **Partial** (S3-05) | Shape validation (`hasOnly`, `name`, `members`) added on top of the S03-H1 ID-collision/`createdBy` guards — finding fully addressed in rule terms. |
| S01-L2 | **Partial** (S3-05) | `write` split into allow-listed `create, update` + unconstrained `delete`; allow-list matches every real client call site. |

All six stay **Partial**, not Fixed, solely because the RULES lane's emulator verification is
toolchain-blocked in this environment (§3) — promotion to `fixed` happens in **S3-15b** once a
`java`/`firebase-tools` toolchain is available to actually execute
`firestore-tests/rules.test.js` against the Firestore emulator.

## 5. Chain state advanced

`START_HERE.md` "Chain state" block updated: `LAST DONE: S3-05`, `NEXT SESSION: S3-06` (Server auth &
identity, lane SRV). `SESSION_INDEX.md` updated with this session's row.

---

```
SESSION: S3-05  MODEL: Opus 5  BUDGET: $5 max  CLUSTER: S3-05 (Firestore field validation & abuse caps)  STATUS: 6 partial (rules already correct in 3070b0b; this session reconciled docs + corrected/extended tests)
CHANGES:
  - firestore.rules: NO changes this session (S01-M1/M2/M3/M4/L1/L2 fixes already landed in 3070b0b; re-verified against current source)
  - firestore-tests/rules.test.js: fix 2 stale assertions (identities `label`, backup_logs missing `count`) that contradicted 3070b0b's own rule changes; add explicit pass/fail coverage for all 6 findings (commit ec8a919)
  - BUG_TRACKER.md: S01-M1/M2/M3/M4/L1/L2 -> Partial (S3-05) with this-session evidence and accurate commit references (3070b0b, ec8a919)
  - security-remediation/sessions/SESSION-S3-05.md: NEW — this log, including the pre-work reconciliation of 3070b0b's undocumented landing
  - security-remediation/START_HERE.md: LAST DONE -> S3-05, NEXT SESSION -> S3-06
  - security-remediation/SESSION_INDEX.md: append S3-05 row
VERIFICATION:
  PASS: node --check on rules.test.js (both before/after ec8a919); firestore.rules brace/paren/bracket balance = 0 (comments stripped); every fix in 3070b0b re-read at current line numbers and matched to its finding; every allow-listed field cross-checked against real client call sites
  BLOCKED: RULES-lane emulator run — `which java` and `which firebase` both return not-found this session; deferred to S3-15b per SESSION_PROTOCOL (no PASS fabricated)
```
