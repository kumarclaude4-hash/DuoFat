# SESSION-S3-20 — Accepted dispositions + final reconciliation

**Lane:** DOC
**Status:** COMPLETE. All 13 plan-scoped accepted-finding write-ups
(`S01-I1`, `S01-I2`, `S02-I1`, `S02-I3` (checkRevoked half), `S03-I1`,
`S03-I3`, `S06-I3`, `S07-I1`, `S07-I2`, `S07-I3`, `S08-M3`, `S08-I2`, `SC-11`)
are written into `../../BUG_TRACKER.md` and `../decisions/DECISION-LOG.md`.
`BUG_TRACKER.md` is reconciled end-to-end and its rollup now matches a direct
recount of the rows, not a stale header/summary claim. **This is the plan's
final scheduled session — `ROUND3_REMEDIATION_PLAN.md` has no S3-21.**
**Model:** v0
**Sequencing note:** continues directly from a prior run of this same
session that had already done the substantive evidence-gathering for the 13
accepted dispositions and stopped partway through writing the audit text.
This session did not redo that evidence-gathering except where needed to
resolve a specific inconsistency (see below); it finished the remaining
write-ups, added the decision-log entries, reconciled the tracker totals, and
wrote this log.

## Starting state

`git status`/`git log` at the start of this session showed a clean tree on
`v0/tevisi8439-5212-076af2ff`, with `S3-19` already landed (all 7 of 7
plan-scoped `S3-19` findings source-addressed, `NEXT SESSION: S3-20` recorded
in `START_HERE.md`). The 13 accepted-finding rows in `BUG_TRACKER.md` still
carried their pre-S3-20 one-line form (`Accepted | Carried | <short label>`)
with no session-specific rationale, evidence, or residual-risk text — the
substantive review referenced in this session's brief had been done in a
prior run but not yet written into the tracker for most of the 13 rows.

## Accepted findings reviewed

All 13 rows named in `ROUND3_REMEDIATION_PLAN.md`'s S3-20 scope were
re-verified against current source this session (not carried from memory or
from the prior run's unwritten conclusions):

1. **`S01-I1`** — global read oracle on `users`/`identities`
   (`firestore.rules:40-41,469-470`, both still `allow read: if
   request.auth != null`). Accepted, ratifying the audit's own conditional
   framing; the field-minimization half of that condition (`S01-M2`/`S01-L2`)
   is confirmed landed as **Partial**, not yet `Fixed` pending `S3-15b`.
2. **`S01-I2`** — systemic `get()`-based Firestore rule authorization TOCTOU
   (`grep -c "get(/databases" firestore.rules` = 20). Accepted as an
   inherent property of the rules evaluation model, consistent with
   `S01-M1`'s already-landed in-rule documentation of the same point.
3. **`S02-I1`** — `createChat` cold-contact/registration oracle
   (`server/index.js:2901`, literal `"Partner not found"` 404). Accepted
   jointly with `S01-I1` — same oracle class, not an independent escalation.
4. **`S02-I3`** — **NOT accepted.** See "Finding that could not be
   substantiated as accepted" below; stays `Open`.
5. **`S03-I1`** — Worker holds bucket-wide B2 credentials
   (`worker/src/index.js:32-39`, `getB2Client(env)`). Accepted pending an
   operator-side Backblaze key-scoping check; not a code-fixable finding.
6. **`S03-I3`** — `/mediaToken` scope-membership oracle
   (`callerMayAccessScope()`, `server/index.js:665-679,3028-3029`). Accepted
   jointly with `S01-I1`/`S02-I1` as the same oracle class at a third
   endpoint.
7. **`S06-I3`** — PIN strength bounded by the 4-6 digit keyspace regardless
   of PBKDF2 cost (`PinManager.java:39-56`, `ITERATIONS = 310_000`,
   `MIN_PIN_LEN = 4`). Accepted as a UX-vs-strength trade-off; confirmed
   consistent with `S08-L3`'s already-landed length-oracle fix.
8. **`S07-I1`** — `SenderKeyStore` stub (`DuoShieldSignalStore.java:496-505`,
   `storeSenderKey()` empty body, `loadSenderKey()` always returns an empty
   record). Accepted as dead code contingent on `S07-I2`'s architectural fact
   (no group-multicast add-member flow exists to ever call it).
9. **`S07-I2`** — no add-member-to-existing-group flow exists at all
   (`CreateGroupActivity.java` exposes only `createGroup()`; repo-wide search
   for `addMember` returns nothing). Accepted as a documented future
   guardrail, not a present defect.
10. **`S07-I3`** — account ID is a 64-bit, seed-derived, dual-purpose value
    (`SeedPhraseHelper.java:603,616`, `SHA-256(seed)` → first 8 bytes →
    Base32). Accepted as adequate collision resistance and a deliberate
    deterministic-recovery design.
11. **`S08-M3`** — no root/tamper/hooking/emulator detection anywhere under
    `app/src/main/java` (confirmed via repo-wide grep for
    `RootBeer`/`isRooted`/`isEmulator`/`FridaDetect`/`isInsideSecureHardware`,
    zero hits). Accepted as out of the client-trust threat model per the
    audit's own framing.
12. **`S08-I2`** — no certificate pinning
    (`network_security_config.xml` declares only
    `cleartextTrafficPermitted="false"`, no `<pin-set>`; no
    `CertificatePinner` usage anywhere in the client). Accepted given the
    app's existing E2E encryption of secret content and the operational cost
    of pin rotation.
13. **`SC-11`** — production crypto on
    `androidx.security:security-crypto:1.1.0-alpha06`
    (`app/build.gradle:213`, confirmed unchanged). Accepted as a tracked
    maintenance risk, not an active vulnerability, per the audit's own Low
    rating and its own note that the "stable" `1.0.0` alternative is a
    functional downgrade.

Each row's `BUG_TRACKER.md` entry now carries the audit's rationale and
terminology verbatim where the audit used specific phrasing, the current-
source evidence this session re-checked, the residual risk the audit
identified (not invented), and any operator/product dependency the audit
named. No row was silently upgraded from Accepted to Fixed; no compensating
control was credited unless it is source-confirmed and already landed
(e.g., `S01-M2`/`S01-L2` for `S01-I1`, `S08-L3` for `S06-I3`).

## Finding that could not be substantiated as accepted

**`S02-I3`** — `ROUND3_REMEDIATION_PLAN.md`'s S3-20 scope line reads `S02-I3
(checkRevoked half)`, phrased as though a prior session had already split
this finding and accepted the `checkRevoked` portion. This session searched
`DECISION-LOG.md` and every `sessions/SESSION-S3-*.md` file for that split
decision and found **no record of it ever being made or ratified**.
Re-verification confirmed the underlying gap is real and unchanged: none of
the 8 live `verifyIdToken` call sites in `server/index.js` (`:2640, :2862,
:2988, :3070, :3386, :3540, :3647, :3710`) pass `checkRevoked: true`.

Three genuinely relevant, source-confirmed mitigating layers exist —
`S06-H1`'s in-transaction `accountLock` check before minting
(`server/index.js:2053-2062`), `revokeRefreshTokens(uid)` called on lock
(`server/index.js:3916`), and `firestore.rules`' `accountNotLocked()` gate on
the backup subsystem (`:139-141`) — but none of them close the literal
`checkRevoked` gap on other routes (`/linkPreview`, `/turnCredentials`,
`/mediaToken`, etc.). A token issued before a lock/revocation event still
authenticates those routes for up to its own ~1h natural lifetime.

Per this session's own instructions not to fabricate acceptance the evidence
doesn't support, **`S02-I3` stays `Open`** in `BUG_TRACKER.md`, marked
"Open — flagged, NOT accepted (S3-20)" with the full reasoning inline, and
`DECISION-LOG.md`'s `DL-018` records why it was not accepted rather than
silently omitting it. This is the one row named in the plan's S3-20 scope
that this session did **not** convert into an accepted-disposition writeup.

## Decisions made

`security-remediation/decisions/DECISION-LOG.md` had five pre-existing
program-level entries (`DL-001`–`DL-005`) and no prior per-finding
risk-acceptance entries in the `DL-00x` format, despite `DL-005` itself
requiring "written risk acceptance in RISK_REGISTER" for every accepted M/L/I.
This session added **`DL-006` through `DL-018`** (13 entries: one per
accepted finding above, plus `DL-018` recording why `S02-I3` was *not*
accepted) continuing the existing five-field format (Decision, Reason,
Alternatives, Pros/Cons, Security impact, Rollback plan, Future
considerations) and numbering. No existing `DL-00x` entry was rewritten.

## Tracker reconciliation

`BUG_TRACKER.md`'s Summary table carried an explicit caveat since S3-02
stating totals were "deliberately left as `—`" pending "S3-20 owns the
end-to-end reconciliation." This session performed that reconciliation by
recounting every row directly from the section bodies (pattern
`^\s*\|\s*S[A-Z0-9-]+`, allowing for the leading-space row formatting several
rows use) rather than trusting any section header's stated count:

- **Critical: 4** (unchanged, already exact).
- **High: 30** (unchanged; includes two governing-severity-annotated IDs,
  `S04-M1 (gov: High)` and `S07-M1 (gov: High, = S08-H5)`, which a naive
  `S0x-Hy`-only ID pattern would miss).
- **Medium: 25**, not the header's stale claim of 26 — recounted twice
  (raw grep and a structured parse) and both landed on 25; no 26th Medium
  row exists anywhere in the file. The stale `## Medium (26)` section header
  is corrected to `## Medium (25)` with an inline note.
- **Low: 33** (unchanged, already exact; includes `S02-L1`/`S02-L2`, which a
  naive pattern anchored without allowing a leading space would miss).
- **Informational: 23** (unchanged, already exact).

**Grand total: 115**, not the old header's 116 (which inherited the same
Medium off-by-one). Status breakdown across all 115 rows, verified by parsing
every row's own Status cell rather than any prior claim:

| Sev | Total | Fixed | Partial | Open | Accepted |
|---|---|---|---|---|---|
| Critical | 4 | 4 | 0 | 0 | 0 |
| High | 30 | 19 | 9 | 2 | 0 |
| Medium | 25 | 14 | 10 | 0 | 1 |
| Low | 33 | 18 | 13 | 1 | 1 |
| Info | 23 | 8 | 2 | 3 | 10 |
| **Total** | **115** | **63** | **34** | **6** | **12** |

The Summary section in `BUG_TRACKER.md` now states this table plus a
reconciliation note explaining both corrections (Medium header, grand total)
and the two governing-severity IDs, so a future session can recount it
directly against the section bodies rather than trust a claimed number.

**No row's individual Status/Confidence/Evidence text changed as part of this
reconciliation pass beyond the 13 accepted-finding rows reviewed above.**
The reconciliation is a straight recount of rows exactly as they stood after
those 13 edits — it did not itself alter any other row's disposition.

## Remaining Partial/Open items (unchanged by this session)

This was a DOC-lane session; it did not fix, verify, or touch any Partial or
Open finding's underlying code or rule text. For the record, as of the
recount above:

- **6 Open rows:** `S08-H5` (= `S07-M1`, SecurePrefs plaintext fallback —
  needs a product-level UX/consent decision), `SC-12` (branch protection —
  operator-only), `S02-I2`, `S02-I3` (this session's flagged row, see
  above), `S04-I1`, and `S07-M1` itself (counted under High per governing
  severity, same underlying defect as `S08-H5`).
- **34 Partial rows**, the large majority AND-lane (Android,
  compile/instrumentation-test BLOCKED, routed to `S3-19b`) or RULES-lane
  (Firestore emulator BLOCKED, routed to `S3-15b`) findings whose source-level
  fix already landed but whose execution-verification has not.

None of these were touched, promoted, or demoted this session.

## S3-15b status

**Not run.** No `java`/`firebase` CLI is available in this environment
(`which java`/`which firebase` both return nothing, consistent with every
prior session's finding). Every RULES-lane row that names `S3-15b` as its
promotion path (`S01-M1`–`M4`, `S01-L1`/`L2`, `S07-H3`'s rules half, and
others) remains **Partial**, not `Fixed`, exactly as before this session.
This session did not attempt to run it and does not claim it ran.

## S3-19b status

**Not run.** No JDK/Gradle/Android SDK is available in this environment.
Every AND-lane row from `S3-15` through `S3-19` that names `S3-19b` as its
promotion path remains **Partial**, not `Fixed`. This session did not attempt
to run it and does not claim it ran.

## S3-20 status

**Complete.** All 13 plan-scoped accepted-finding write-ups are in
`BUG_TRACKER.md` and `DECISION-LOG.md`; the tracker's rollup is reconciled
end-to-end and matches a direct recount of the rows (not an intent column).
Per `ROUND3_REMEDIATION_PLAN.md`, S3-20 is the plan's final scheduled DOC-lane
session — there is no S3-21.

## Final chain state

`START_HERE.md`'s chain-state block is updated to:

```
NEXT SESSION: S3-20 complete. Per ROUND3_REMEDIATION_PLAN.md, all 20 scheduled sessions have now been touched at the source/documentation level. Do NOT invent an S3-21 — the plan schedules none. Go to the sign-off gate at the bottom of ROUND3_REMEDIATION_PLAN.md next, per this file's own line 54 instruction ("If NEXT SESSION above is S3-20 complete, do not start coding — go to the sign-off gate ... and verify operator items instead"). The gate stays PENDING until: S3-15b (RULES emulator — no java/firebase CLI in this environment, never run) and S3-19b (Android build/test — no JDK/Gradle/Android SDK in this environment, never run) actually execute and promote every BLOCKED-verification Partial row to Fixed or an explicit deferred-with-justification; AND every operator-only item is done (GCP service-account key revoked, WORKER_SECRET + baked B2 creds rotated, branch protection enabled on main / SC-12, Gradle dependency-verification hashes populated+enforced / SC-03, App Check enforcement flipped from monitor to enforce). Both catch-up gates remain outstanding and unrun — this session did not run them and does not claim otherwise.
```

`SESSION_INDEX.md`'s Round 03 row is updated to reflect S3-20 completion
(reconciliation done, both catch-up gates still outstanding) rather than
"19 of 20 scheduled sessions touched."

## Git discipline

This session's only changes are to five documentation files:
`BUG_TRACKER.md`, `security-remediation/decisions/DECISION-LOG.md`,
`security-remediation/sessions/SESSION-S3-20.md` (this file),
`security-remediation/START_HERE.md`, `security-remediation/SESSION_INDEX.md`.
No implementation code (server, worker, Android, Firestore rules, CI) was
modified. One documentation/reconciliation commit was made after reviewing
the full diff.
