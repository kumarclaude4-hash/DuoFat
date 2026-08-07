# REMEDIATION SESSION 03 — Round 3: Rules, Quotas, Supply Chain, Final Dispositions (HARD STOP)

Maps to REMEDIATION_PLAN Round 3. This is the FINAL planned round. No round 4 exists.

## Objectives
Close remaining Firestore rules hardening, migrateUid/backup/group AAD, quotas/limits,
waitlist, supply-chain, the App Check decision, and assign a terminal disposition to
EVERY remaining Medium/Low/Informational finding. Then produce sign-off.

## Exact findings addressed
- S01-* remaining rules findings (migrateUid, backup, group AAD, field validation)
- S05-* remaining admin/quota findings; quotas & rate limits; waitlist abuse
- S09-* remaining supply-chain (pinned actions, dependency review, provenance) + SC-1/SC-3
- App Check adoption decision (accept/defer with justification)
- ALL remaining Medium / Low / Informational findings across S01–S10 and S10-N* notes

## Folders / files in scope
`firestore.rules`, storage rules, Functions quota/limits, `.github/workflows/*`, dependency manifests.

## Root-cause analysis
Residual least-privilege gaps in rules; missing rate/quota controls; unpinned CI supply chain; several informational/defense-in-depth items requiring an explicit accept/defer decision.

## Implementation plan
1. Tighten rules: validate fields, restrict migrateUid/backup/group writes to authorized principals with AAD binding.
2. Add quotas/rate limits to abusable endpoints (waitlist, mint, media).
3. Pin GitHub Actions by SHA, enable dependency review, finalize build provenance.
4. Decide App Check: fixed / accepted / deferred-with-justification (RISK_REGISTER + DECISION-LOG).
5. Walk FINDING_INDEX: assign terminal disposition to every not-yet-dispositioned finding.

## Tests to run
Rules unit tests (allow/deny); quota exhaustion tests; CI runs with pinned actions; provenance attestation present.

## Evidence (evidence/round-3/)
Rules test output, quota test output, CI diff, disposition table snapshot.

## Exit criteria (HARD STOP)
- Every finding in FINDING_INDEX has exactly one disposition.
- Every Critical + High is fixed or accepted-with-justification.
- Every trust boundary revalidated.
- FINAL_SECURITY_REPORT.md + FINAL_SIGNOFF.md written.
No further remediation round is created after this session.

## Regression checks
Full rules suite; end-to-end mint→media→admin happy path; CI green.

## Findings explicitly NOT touched this round
None deferred to a later round — this is the last round. Anything not fixed is explicitly accepted or deferred-with-justification here, with an owner and revisit trigger.
