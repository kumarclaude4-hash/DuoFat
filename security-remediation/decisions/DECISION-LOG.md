# DECISION LOG

Every significant remediation decision is logged here. Format per entry: Decision,
Reason, Alternatives, Pros, Cons, Security impact, Rollback plan, Future considerations.
Each references the audit finding ID(s) it serves.

---

## DL-001 — Treat leaked signing/service credentials as compromised and rotate, do not just stop committing them

- Findings: S09-C1 (keystore + `key.properties` in VCS), S09-C2 (`serviceAccount.json` in repo), SC-2.
- Decision: Any secret that has ever been committed to git history is considered compromised. Remediation is: (1) stop tracking it, (2) purge from working tree, (3) `.gitignore` it, (4) require rotation of the underlying credential, and (5) move to CI secret store. Simply deleting the file in a new commit is NOT sufficient because history retains it.
- Reason: Git history is immutable and the repo is shared; a deleted-but-historical keystore still signs malicious APKs, and a historical service account still grants Firebase admin.
- Alternatives considered: (a) `git filter-repo` history rewrite only — rejected as sole fix because it does not rotate the already-exposed key material; (b) leave keys, restrict repo access — rejected, does not address prior exposure.
- Pros: Eliminates the standing compromise, not just future commits. Cons: Rotation requires a new upload key / re-enrollment of Play App Signing and a new service account; coordination cost.
- Security impact: Removes attacker's ability to sign trojaned updates and to impersonate the backend. High positive.
- Rollback plan: Rotation is forward-only; keep the previous key offline (not in repo) until Play signing confirms the new key, then destroy.
- Future considerations: Add push-time secret scanning (S09-H-series / SC-2) so this cannot recur.

## DL-002 — `/mintToken` must prove seed possession and be fail-closed, rather than trusting UID alone

- Findings: S02-C1 (mintToken authorization gap), S02-H1 (fail-open), S02-H2 (no account lock), S06-C1 (duress not enforced server-side).
- Decision: Change the token-minting trust model. The server must (a) require a caller-provided proof-of-seed (HMAC/signature over a server challenge using the enrollment seed) bound to the authenticated UID, (b) deny by default on any error/exception (fail-closed), and (c) consult an account-lock/duress state and refuse to mint for locked or duress-flagged accounts.
- Reason: The audit shows the endpoint mints capability tokens from UID alone; a stolen/forged UID or a duress unlock currently yields a fully privileged token. This is the highest-severity trust-boundary failure (P0-1).
- Alternatives considered: (a) client-side duress check only — rejected, attacker controls the client; (b) rate limit only — insufficient, does not stop a single forged request.
- Pros: Collapses the primary account-takeover path; makes duress and lock authoritative on the server. Cons: Requires a challenge/response round trip and enrollment-time seed registration; small latency and migration cost (see MIGRATION_PLAN).
- Security impact: Very high positive; closes S02-C1 and enforces S06-C1 at the trust boundary.
- Rollback plan: Feature-flag the new verification path; if it misbehaves, flag can revert to prior behavior ONLY in a controlled staging environment — never re-open fail-open in production.
- Future considerations: Consider App Check attestation (S05 decision) as an additional signal, not a replacement.

## DL-003 — Media/download tokens must be bound to conversation membership and verified server-side

- Findings: S03-C1 (media token not membership-bound), S03-H-series (token scope/expiry).
- Decision: Download authorization is derived from server-side membership of the requesting UID in the conversation that owns the media, plus a short-lived, single-scope token. The token alone is never sufficient.
- Reason: Audit shows possession of a token (or guessable path) grants cross-conversation media access — a horizontal privilege-escalation / IDOR trust-boundary break.
- Alternatives considered: signed-URL-only with long TTL — rejected (replayable, shareable). 
- Pros: Enforces least privilege at the storage boundary. Cons: Every download does a membership check (latency, read cost).
- Security impact: High positive; closes IDOR on media.
- Rollback: Flagged; deny-closed remains the safe default.

## DL-004 — Fix by architecture where a line-patch cannot hold the boundary

- Findings: S02-C1, S06-C1, S08-C1 (client platform residue / verifiable build).
- Decision: Where the audit root cause is architectural (trust placed on the client, or unverifiable builds), the remediation changes the architecture (server-authoritative verification, reproducible/verifiable build + signing provenance) rather than patching a symptom. Each such case is recorded here and in TARGET_STATE.md.
- Reason: Patching a single check leaves the boundary crossable by another path.
- Pros: Durable closure. Cons: Larger change surface; captured per-session with regression checks.
- Security impact: High positive and durable.
- Rollback: Per-session feature flags and staged deploy (MIGRATION_PLAN).

## DL-005 — Disposition policy for Medium/Low/Informational

- Findings: all M/L/I in FINDING_INDEX.
- Decision: Each M/L/I ends as `fixed`, `accepted` (with written risk acceptance in RISK_REGISTER), or `deferred-with-justification` (with owner + revisit trigger). No M/L/I may remain open at sign-off. Criticals and Highs may ONLY be `fixed` or `accepted-with-justification` — never deferred.
- Reason: The program is closed; every finding needs exactly one terminal disposition.
- Pros: Deterministic stop condition. Cons: Forces explicit acceptance of residual risk (documented).
- Security impact: Neutral/positive (transparency).
- Rollback: N/A (documentation decision).
