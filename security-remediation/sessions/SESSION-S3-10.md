# SESSION-S3-10 — Duress eligibility & rules coverage

**Lane:** SRV + RULES (rules verify → S3-15b)
**Status:** All 3 plan-scoped findings verified `Fixed` from current source. No finding required
new production code — all three were already remediated by prior commits (`adaa218`, `1a0e15b`)
that predate this Round-3 tracker and were never reconciled against it. This session's only new
artifact is a regression test that closes a coverage gap the tracker's own evidence did not have.

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

S3-10 is scoped to 3 findings: `S05-M2` + `S06-M1` (`duressEligibility` actually enforced
server-side and in rules, not a cached client bool), `S06-L3` (`_duressNonces` rules-test
coverage).

## Reconciliation before touching anything

Per `BUG_TRACKER.md` at session start:
- `S06-M1` — tracker's "Corrections vs. the old trackers" section already said `Fixed`: the
  `firestore.rules` `accountLock` create predicate exists.
- `S05-M2` — already `Fixed`, cross-referenced to the `S06-M1` correction (same underlying defect,
  "upgraded" to `S06-M1` per `audit/SESSION-06-DURESS.md:525`).
- `S06-L3` — tracker said `Open | Carried` — i.e., not independently re-verified since being
  copied from the old index.

Per SESSION_PROTOCOL §3 ("source beats tracker"), all three findings' current dispositions were
re-derived from source in this session rather than taken on faith, including the two already
marked `Fixed` — a `Fixed` row that turns out to have no regression test is exactly the "reads
correctly, has no caller/no test" defect class this program keeps re-finding (see
`adminAuditWiring.test.js`'s own rationale from `S3-13`/`S05-H3`).

## What was found

1. **`S06-M1`/`S05-M2` — confirmed fixed, previously untested.** `server/index.js`'s
   `/requestLockNonce` handler (`:3469-3570`) reads `duressEligibility/{uid}` (`:3509`) and
   refuses with `404` — not `403`, matching the finding's enumeration-resistance requirement —
   before a nonce is ever generated (`:3510-3515`, explicitly commented `S06-M1`).
   `firestore.rules`'s `accountLock` create rule (`:558-573`, commented `S06-M1`) independently
   requires `duressEligibility/$(accountId)).data.eligible == true`. Both landed in commit
   `adaa218` ("add TTL policy for duress lock nonces and enforce duress lock in rules"), which
   predates this Round-3 tracker and was never reconciled against it — the tracker's "Corrections"
   section had already caught the rules half but not the equivalent server-side gate, and neither
   half had a regression test.
2. **`S06-L3` — confirmed fixed, tracker was stale.** `firestore-tests/rules.test.js`'s
   `describe('/_duressNonces/{nonce}')` block (`:1595-1649`) carries 8 cases — exceeding the
   finding's stated minimum of 4 (authed/anon × read/write) — and the paired `waitlist` collection
   the finding also named is covered at `:1750-1782` (6 cases). This landed in commit `1a0e15b`
   ("enhance duress reset handling and introduce PendingLockStore"), also pre-dating this tracker.
   `grep -c` confirms the case counts; not re-derived from the audit's line numbers, which have
   drifted before.

Neither finding needed new production code. What was missing was **regression coverage** for the
`S06-M1`/`S05-M2` half (the `S06-L3` half already had thorough behavioral rules tests) — a `Fixed`
disposition resting only on "I read the code and it looked right" is the same failure mode
`SESSION_PROTOCOL.md` was written to stop.

## What was done

Added `server/lib/duressEligibilityWiring.test.js`, a source-level structural test in the same
style as `adminAuditWiring.test.js` (explicitly documented as such in-file — it reads `index.js`
and `firestore.rules` as text, it does not execute a live Firestore query). Five cases:

1. `/requestLockNonce` actually reads `duressEligibility/{uid}` before proceeding.
2. The eligibility check runs *before* nonce generation, not after (checking after would still
   let an ineligible account observe a nonce).
3. An ineligible refusal is `404`, not `403` — the literal enumeration-resistance requirement.
4. `firestore.rules`'s `accountLock` create rule requires `duressEligibility/{accountId}.eligible
   == true` (not merely doc-exists).
5. `duressEligibility` itself denies all client writes (eligibility must only be grantable via the
   admin enroll/revoke endpoints).

**Falsification check (not just a passing test):** temporarily reverted the `eligible == true`
clause in `firestore.rules` to confirm test 4 fails without the fix (`AssertionError`, printed and
reviewed), then restored the file byte-for-byte (`git diff --stat firestore.rules` → empty,
confirmed before proceeding) — so the test is known to actually detect the regression it claims to
guard against, not merely known to pass today.

`S06-L3`'s existing rules tests were read and counted, not modified — they already meet and exceed
the finding's ask.

## Test evidence (run this session)

- `node --check server/index.js` — clean.
- `node --check firestore-tests/rules.test.js` — clean (syntax only; no `jest`/emulator run — see
  Verification NOT run).
- `node --test server/lib/duressEligibilityWiring.test.js` — **5/5 pass**.
- `cd server && npm test` — **200 pass / 1 fail / 201 total**. The 1 failure is
  `lib/identityVerify.test.js`, failing with `Cannot find module '@signalapp/libsignal-client'` —
  re-confirmed by isolating the failing suite and reading its `MODULE_NOT_FOUND` output directly
  this session (same pre-existing, environment-only missing-native-dependency failure documented
  across `SESSION-S3-05.md` through `SESSION-S3-13.md`).
- Falsification of the new test (above): fails without the `S06-M1` rules fix, passes with it,
  file restored, `git status` confirmed clean before continuing.
- Live-source checks: `grep -n 'db.collection("duressEligibility").doc(uid).get()' server/index.js`
  and `grep -n 'duressEligibility/\$(accountId)).data.eligible == true' firestore.rules` both hit;
  `grep -c "test(" firestore-tests/rules.test.js` within the `_duressNonces`/`waitlist` blocks
  matches the 8+6 case counts cited above.

## Verification NOT run (recorded, not fabricated)

- No Firestore emulator run of `firestore-tests/rules.test.js` — `which firebase` and `which java`
  both return nothing in this environment; the RULES lane's actual execution remains **BLOCKED**,
  same as every other rules-touching session (`S3-04`, `S3-05`, and this tracker's other `Fixed`
  rules rows like `S06-M1`/`S06-M2`/`S06-M3` are likewise source+test-existence verified only).
  Promotion of the *execution* result is `S3-15b`'s job, not this session's — this session did not
  attempt to provision a JVM/Firebase CLI toolchain for it, per SESSION_PROTOCOL §7's
  "do not provision large toolchains for low-value checks" guidance combined with the explicit
  BLOCKED-lane handling in `START_HERE.md` step 5.
- No live Firestore read/write against a real project (no service-account credentials available).

## Files changed

- `server/lib/duressEligibilityWiring.test.js` — new, 5 regression tests (see above).
- `BUG_TRACKER.md` — `S06-M1` and `S05-M2` rows annotated with this session's re-verification and
  the new test's existence; `S06-L3` row changed from `Open | Carried` to `Fixed (S3-10) |
  Verified` with the exact evidence (case counts, commit hash, still-BLOCKED emulator caveat).

## Git discipline

Implementation (`server/lib/duressEligibilityWiring.test.js`) and its `BUG_TRACKER.md` evidence
are committed together (the test *is* the evidence for the disposition change, same pattern as
prior sessions' fix+tracker commits). This session log, `START_HERE.md`, and `SESSION_INDEX.md`
are committed separately as documentation/chain-state. `git status`/`git diff` were reviewed
immediately before each commit.

## Chain state

All 3 of this session's plan-scoped findings (`S05-M2`, `S06-M1`, `S06-L3`) are now `Fixed` in
`BUG_TRACKER.md`. Per `ROUND3_REMEDIATION_PLAN.md`, `START_HERE.md`'s `NEXT SESSION` line advances
to `S3-11` (Worker abuse controls (per-user) — lane WORKER).
