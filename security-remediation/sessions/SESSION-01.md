# REMEDIATION SESSION 01 — Round 1: Secrets + Server Token Trust Boundary (P0)

Maps to REMEDIATION_PLAN Round 1. Highest-risk trust-boundary failures and the
audit synthesis P0 items.

## Objectives
Close the standing credential compromise and the token-minting trust break so no
forged/duress/UID-only request can obtain a privileged token.

## Exact findings addressed
- S09-C1 — keystore + `key.properties` committed to VCS
- S09-C2 — `serviceAccount.json` committed to VCS
- SC-2 — no push-time secret scanning / CI secret gate
- S02-C1 — `/mintToken` authorizes on UID alone (no proof-of-seed)
- S02-H1 — `/mintToken` fails open on error
- S02-H2 — no account lock / lockout consulted at mint
- S06-C1 — duress state not enforced server-side at mint
- S09-H1 — release branch not protected / unsigned pipeline path (branch protection + provenance kickoff)

## Folders / files in scope
- `.github/workflows/ci.yml`, `.github/workflows/release.yml`
- `app/build.gradle`, `key.properties`, keystore path, `serviceAccount.json`
- `.gitignore`
- Cloud Functions token-mint handler (server), account-lock/duress state store
- `firestore.rules` only where lock/duress state is read

## Root-cause analysis
- Secrets: developer convenience placed signing + admin credentials in VCS; git history makes them permanently compromised (DL-001).
- Token mint: trust placed on client-asserted UID; error path returns a token (fail-open); no server-side lock/duress consultation (DL-002). Architectural, not a line bug.

## Implementation plan
1. Stop tracking secrets; `.gitignore`; move to CI secrets; document rotation (MIGRATION §6). 
2. Add CI secret-scanning gate (SC-2) that fails the build on detected secrets.
3. Rework token-mint: require proof-of-seed bound to UID; deny-by-default on any exception; consult account-lock + duress flag; deny if locked/duress.
4. Enable branch protection + signed/provenance build path (S09-H1).

## Tests to run
- Unit: mint denies on missing/invalid proof-of-seed; denies on exception (fail-closed); denies when lock/duress set; allows valid.
- CI: secret-scan job fails on a planted test secret.
- Build: signed build uses CI-injected key, not repo key.

## Evidence to collect (evidence/round-1/)
- Diffs of workflows, build.gradle, .gitignore, functions handler.
- Test run output. Rotation record (timestamps, old-key retirement).
- Proof the working tree no longer contains the secrets.

## Exit criteria
All Round-1 findings verified from source + tests; secrets rotated and untracked; mint fail-closed + proof-of-seed + lock/duress enforced; CI secret gate active; branch protection on.

## Regression checks
Legitimate enrolled client can still mint (dual-accept window); CI still builds a valid signed artifact.

## Findings explicitly NOT touched this round
All S01 rules-hardening (except lock/duress read), S03 media, S04 egress, S05 admin, S07 crypto, S08 client platform beyond signing provenance, and all M/L/I — deferred to Rounds 2–3 per plan.
