# REMEDIATION SESSION 02 — Round 2: Media, Duress, Client Residue, Egress (P1)

Maps to REMEDIATION_PLAN Round 2. Depends on Round 1 (server trust boundary + rotated secrets).

## Objectives
Bind media/download authorization to conversation membership, enforce duress
end-to-end, make client secure storage fail-closed, and lock down server-side
egress (SSRF / link preview).

## Exact findings addressed (High unless noted)
- S03-C1 media token not membership-bound; S03-H1..H3 token scope/expiry/path
- S06-H1..H2 duress residue + client-only enforcement; S06-M-series duress UX/state
- S07-H1..H2 SecurePrefs fail-open / key handling; S07-C1 if present crypto misuse
- S04-C1/H-series SSRF: DNS rebinding, IPv6/link-local, redirect following; link-preview proxy
- S05-H-series admin endpoint hardening (authz on admin ops)
- S08-H-series client residue (logs, backups, screenshots) tied to trust boundary

## Folders / files in scope
Cloud Functions media/admin handlers; Android SecurePrefs/crypto; egress/link-preview service; storage rules.

## Root-cause analysis
Authorization derived from token possession rather than server-side membership (IDOR); duress enforced only on client; secure storage falls back to plaintext/no-op on error; egress fetcher resolves attacker-controlled hosts without allow-list/rebinding protection.

## Implementation plan
1. Media: server checks requesting UID membership in owning conversation; short-lived single-scope token; deny-closed.
2. Duress: server authoritative duress flag (from Round 1 store) gates media/admin; client wipes residue.
3. SecurePrefs: fail-closed on keystore error; no plaintext fallback.
4. Egress: allow-list + re-resolve/pin IP, block private/link-local/IPv6-mapped, no auto-redirect to new host.
5. Admin: enforce authz + duress + lock on every admin op.

## Tests to run
Membership deny/allow matrix; duress-blocks-media; SecurePrefs error → deny; SSRF suite (rebinding, 169.254.x, ::1, redirect); admin authz matrix.

## Evidence (evidence/round-2/)
Diffs, test output, SSRF test transcript, membership matrix results.

## Exit criteria
All Round-2 findings verified from source + tests; deny-closed defaults confirmed.

## Regression checks
Legit members still download; legit admins still operate; normal link previews still resolve.

## Findings explicitly NOT touched this round
Round 1 items (done) and Round 3 items (rules polish, quotas, supply-chain, M/L/I dispositions).
