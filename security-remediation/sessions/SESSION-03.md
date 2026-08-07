# REMEDIATION SESSION 03 — Round 3: Rules, Quotas, Supply Chain, Terminal Dispositions

**Status: NOT STARTED — plan only.** Maps to `REMEDIATION_PLAN.md` Round 3.
**This is the FINAL planned round. No Round 4 exists and none may be created.**
**Blocked by:** Rounds 1 and 2 must both close first (`DEPENDENCY_GRAPH.md` §3).

> **ID integrity note.** A previous version of this file used wildcards (`S01-*`, `S05-*`, `S09-*`)
> and the malformed IDs `SC-1`/`SC-3`. Wildcards cannot be audited for completeness, and the audit has
> no `S09-*` finding IDs — Session 9 contributed the `SC-*` series. Rewritten 2026-08-07 with the
> definite 84-finding set below, enumerated from `MASTER_CHECKLIST.md`.

## Objectives

1. Complete Firestore/Storage least-privilege: field validation, `migrateUid`, backup, group AAD.
2. Add the quota and rate-limit controls that bound abuse of mint, waitlist, and media.
3. Finish supply-chain hardening: SHA-pinned actions, dependency verification, provenance.
4. Resolve the Firebase App Check question (`S10-N1`) with a recorded decision.
5. **Assign exactly one terminal disposition to every finding still open**, so that all 116 findings
   are closed and the program can sign off.

## Exact findings addressed — 84 total (12 High, 25 Medium, 28 Low, 19 Informational)

### High (12)
`S01-H1` · `S01-H2` · `S01-H3` · `S02-H1` · `S03-H2` · `S03-H3` · `S04-M1` (gov. High) ·
`S05-H2` · `S07-H2` · `S07-H3` · `SC-03` · `SC-06`

### Medium (25)
`S01-M1` · `S01-M2` · `S01-M3` · `S01-M4` · `S03-M1` · `S03-M2` · `S03-M3` · `S04-M2` · `S04-M3` ·
`S05-M1` · `S05-M2` · `S05-M3` · `S06-M1` · `S06-M2` · `S06-M3` · `S07-M2` · `S07-M3` · `S08-M1` ·
`S08-M2` · `S08-M3` · `S10-N1` · `SC-07` · `SC-08` · `SC-09` · `SC-10`

### Low (28)
`S01-L1` · `S01-L2` · `S02-L2` · `S02-L3` · `S02-L4` · `S03-L2` · `S03-L3` · `S03-L4` · `S04-L1` ·
`S04-L2` · `S04-L3` · `S05-L1` · `S05-L2` · `S05-L3` · `S05-L4` · `S06-L1` · `S06-L2` · `S06-L3` ·
`S06-L4` · `S07-L1` · `S07-L2` · `S07-L3` · `S07-L4` · `S08-L1` · `S08-L2` · `S08-L3` · `S08-L4` ·
`SC-11`

### Informational (19)
`S01-I1` · `S01-I2` · `S02-I1` · `S02-I2` · `S03-I1` · `S03-I2` · `S03-I3` · `S04-I1` · `S04-I3` ·
`S05-I2` · `S05-I3` · `S06-I1` · `S06-I3` · `S07-I1` · `S07-I2` · `S07-I3` · `S08-I1` · `S08-I2` ·
`S08-I3`

Round-3 sub-workstreams:

| Workstream | Findings |
|---|---|
| Firestore / Storage rules least-privilege | `S01-H1`–`H3`, `S01-M1`–`M4`, `S01-L1`–`L2`, `S03-H2`–`H3`, `S03-M1`–`M3` |
| Quotas, limiters, DoS bounds | `S02-H1`, `S04-M1`–`M3`, `S04-L1`–`L3`, `S05-M1`–`M3`, `S06-M2` |
| Crypto / session hygiene | `S07-H2`–`H3`, `S07-M2`–`M3`, `S07-L1`–`L4` |
| Client hardening remainder | `S08-M1`–`M3`, `S08-L1`–`L4` |
| Waitlist governance | `S05-H2`, `S05-L1`–`L4` |
| Supply chain remainder | `SC-03`, `SC-06`–`SC-11` |
| App Check decision | `S10-N1` |
| Accept/defer ratification | the 19 Informational findings + any Low resolved as `accepted` |

## Folders / files expected to change

`firestore.rules`, `storage.rules`, `server/index.js` (quotas, limiters, migrateUid, backup),
`server/lib/pure.js`, `app/src/main/java/com/duoshield/app/crypto/**`,
`app/src/main/java/com/duoshield/app/**` (client remainder), `.github/workflows/*`,
`app/build.gradle` + Gradle verification metadata.

## Root-cause analysis

Round 3 addresses **residual least-privilege and resource-bounding gaps** rather than single
exploitable breaks. Rules grant broader field and document access than any client flow needs, so a
compromised client can write documents the product never intended. Abusable endpoints have no
durable quota, so cost and availability are attacker-controlled. Supply-chain inputs are still
mutable — unpinned actions and unverified dependencies mean the build can change without a source
change. The Informational set is largely defence-in-depth: each needs an explicit, recorded decision
rather than a code change, because silently ignoring them is what produces an ambiguous program end.

## Implementation plan

1. **Rules first** (`S01-*`, `S03-H2`/`H3`, `S03-M*`) — field allow-lists, deny-by-default,
   constrain `migrateUid` and backup writes to authorized principals with AAD binding. Rules gate the
   quota work because quota enforcement assumes documents cannot be forged.
2. **Quotas and limiters** (`S02-H1`, `S04-M*`, `S04-L*`, `S05-M*`, `S06-M2`) — durable counters on
   mint/waitlist/media, bounded limiter state with purge, explicit DoS ceilings.
3. **Crypto/session hygiene** (`S07-H2`–`H3`, `S07-M2`–`M3`, `S07-L*`).
4. **Client remainder** (`S08-M*`, `S08-L*`).
5. **Waitlist governance** (`S05-H2`, `S05-L*`) — deny/expire/revoke path; residual reviewer-workflow
   gap recorded as `RR-04`.
6. **Supply chain** (`SC-03`, `SC-06`–`SC-11`) — SHA-pin every action, Gradle dependency
   verification, dependency review, build provenance.
7. **`S10-N1` App Check** — implement what is feasible in code; record the platform-attestation
   remainder as `RR-05` in `RISK_REGISTER.md` and as a decision in `decisions/DECISION-LOG.md`.
8. **Disposition sweep** — walk all 116 rows of `FINDING_INDEX.md` and confirm each holds exactly one
   terminal disposition with evidence. Any planned `fixed` that failed validation becomes
   `deferred-with-justification` with a named blocker — never a silent `fixed`.

## Tests to run

Full Firestore/Storage rules allow-deny suite; quota-exhaustion tests; limiter purge under load;
crypto/session unit tests; CI green with pinned actions; provenance attestation present and
verifiable; Gradle dependency verification fails closed on a tampered digest.

## Evidence to collect — `evidence/after/round-3/`

Rules test output, quota and limiter transcripts, crypto test output, CI diff and run link,
provenance attestation, dependency-verification metadata, and a final disposition-table snapshot.

## Validation steps

Per `validation/VALIDATION_PLAN.md`. Every one of the 84 findings needs a source citation plus either
a passing test or a written justification for why no test applies. Accepted findings additionally need
a residual-risk entry; deferred findings need a named blocker and revisit trigger.

## Regression checks

Full rules suite; end-to-end mint → media → admin happy path; duress path; CI green. All Round-1 and
Round-2 guarantees (`SG-01`…`SG-09`) must be re-run and still pass — Round 3 must not reopen them.

## Exit criteria — HARD STOP

- Every one of the 116 findings in `FINDING_INDEX.md` holds **exactly one** terminal disposition:
  `fixed`, `accepted`, or `deferred-with-justification`.
- Every Critical and High is `fixed`, or `accepted` with written justification.
- Every trust boundary in `architecture/TRUST_BOUNDARIES.md` revalidated.
- Validation and regression coverage complete; every evidence link resolves to a real artifact.
- No placeholder text and no TODO markers remain in any final artifact.
- `FINAL_SECURITY_REPORT.md`, `FINAL_SIGNOFF.md`, and `RELEASE_SIGNOFF.md` written.

**No further remediation round is created after this session.** Anything not fixed here is explicitly
`accepted` or `deferred-with-justification`, with an owner and a revisit trigger recorded in
`RISK_REGISTER.md`. A deferred finding is a closed program item with a tracked risk — it is not an
open remediation task and does not justify another round.

## Findings explicitly NOT touched this round

None. All 32 Round-1 and Round-2 findings must already be closed on entry; this round covers the
remaining 84. There is no finding outside the union of Rounds 1–3.
