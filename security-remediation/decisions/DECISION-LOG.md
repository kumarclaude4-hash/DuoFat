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

---

**S3-20 (DOC-lane reconciliation) — DL-006 through DL-018.** Per DL-005, every
accepted M/L/I needs a written risk acceptance. The entries below are that
acceptance record for the 13 Accepted-disposition rows S3-20 reviewed against
current source. Each preserves the audit's own rationale and terminology
(`audit/SESSION-0x-*.md`), re-verifies it against source as of this session,
and states residual risk — none upgrade a finding's status; `BUG_TRACKER.md`
is the disposition of record, this log is the risk-acceptance justification
DL-005 requires for each. One finding reviewed for possible acceptance
(`S02-I3`) is **not** included below because the evidence did not support
carrying it as Accepted — see `BUG_TRACKER.md`'s `S02-I3` row, which stays
`Open`.

## DL-006 — Accept the global read oracle on `users`/`identities` (S01-I1)

- Finding: S01-I1.
- Decision: Accept that any authenticated account can read any other account's `users`/`identities` document. Do not gate reads behind conversation/contact membership.
- Reason: ECDH/X3DH key exchange, contact lookup, and FCM token delivery all require every account's identity document to be reachable by any other account before a first conversation exists; gating this behind membership would break cold-contact messaging entirely. The audit conditions acceptance on field minimization (closed — see below) and a UID space too large to enumerate usefully (unchanged, re-verified this session).
- Alternatives considered: gate first contact behind a mutual/invite handshake (rejected as a product-feature change out of this reconciliation's scope, tracked jointly with S02-I1 below); per-field ACLs instead of a document-level allow (rejected as unnecessary once field minimization landed).
- Pros: Preserves the messenger's core cold-contact UX. Cons: Confirmed enumeration + metadata-oracle capability for any authenticated attacker.
- Security impact: Neutral — no new exposure created; the exposure is unchanged from audit and is now explicitly ratified rather than left implicit. `S01-M2`/`S01-L2` (field allow-lists on `identities`/`users`) independently narrow *what* is exposed; landed as Partial pending `S3-15b`'s emulator gate, not yet Fixed.
- Rollback: N/A — this is an accept-risk decision, not a code change.
- Future considerations: If product later wants to close the oracle, the alternative is an invite/mutual-contact gate; re-open this decision if that ships.

## DL-007 — Accept `get()`-based Firestore rule authorization as a systemic TOCTOU + cost pattern (S01-I2)

- Finding: S01-I2.
- Decision: Accept that every `get()`-based cross-document authorization check in `firestore.rules` (chat participants, group members/creator, call caller/callee) is a billed read and reflects parent-document state only at rule-evaluation time, not at request-completion time.
- Reason: This is an inherent property of Firestore security rules, not a fixable line-item — no single rule evaluation can be made atomic with an external membership change. The audit's own recommendation is to document this as an architectural fact, which S01-M1's in-rule comment (already landed) now does explicitly.
- Alternatives considered: none viable within Firestore rules' evaluation model; a fully atomic check would require a different authorization architecture (e.g., custom claims refreshed per-request), out of scope for this round.
- Pros: Avoids chasing an unfixable class of race as if it were a discrete bug. Cons: A membership change racing a `get()`-based check remains a real, if narrow, window.
- Security impact: Neutral — documents a pre-existing, unavoidable property; does not introduce new risk.
- Rollback: N/A.
- Future considerations: `S01-M4`'s delete re-check is an example of narrowing one instance of this pattern without eliminating the class; apply the same narrowing opportunistically where a specific rule's blast radius justifies it.

## DL-008 — Accept the `createChat` cold-contact/registration oracle as inherent to a messenger, jointly with S01-I1 (S02-I1)

- Finding: S02-I1.
- Decision: Accept that `createChat`'s distinguishable "Partner not found" 404 confirms account existence for a given user ID, and that any registered user can chat-request any other registered user with no consent step.
- Reason: This adds no capability beyond the already-accepted `identities` oracle (DL-006/S01-I1) — it is the server-side face of the same trade-off. Accepting S01-I1 without also accepting this would be inconsistent, since closing this endpoint alone would not remove the identical capability the `identities` collection already exposes.
- Alternatives considered: generic error response regardless of existence (rejected — breaks legitimate error UX without closing the underlying oracle at `identities`); gate first contact behind mutual/invite handshake (same joint alternative as DL-006, not actioned this session).
- Pros: Consistent, single accept-or-gate decision across both endpoints instead of two independent ones. Cons: Unsolicited first-contact/spam capability remains.
- Security impact: Neutral — no new exposure; consistent with DL-006's already-accepted risk.
- Rollback: N/A.
- Future considerations: Revisit jointly with DL-006 if an invite/mutual-contact gate is ever built.

## DL-009 — Accept `/mediaToken`'s scope-membership oracle as the same class already accepted at S01-I1/S02-I1 (S03-I3)

- Finding: S03-I3.
- Decision: Accept that `callerMayAccessScope()` lets a caller test membership in arbitrary `chats`/`groups` IDs via `/mediaToken`'s distinguishable allow/deny response, up to the endpoint's rate limit.
- Reason: The audit notes this "adds little beyond the enumeration trade-off already recorded in S01-I1/S02-I1" and that S03-H1 (fixed) makes it moot for any scope an attacker could otherwise shadow. Accepting S01-I1/S02-I1 without this would be inconsistent — same oracle class, third endpoint.
- Alternatives considered: generic denial response with no distinguishable reason (rejected — same reasoning as DL-008, does not close the underlying oracle elsewhere).
- Pros: Single consistent policy across all three oracle endpoints. Cons: Scope-ID enumeration remains possible at the endpoint's rate limit (120/min per audit).
- Security impact: Neutral — confirmed the denial log line (`uidTag` + raw `scopeId`) does not leak identity beyond what the oracle already reveals live, consistent with the pseudonymisation policy.
- Rollback: N/A.
- Future considerations: None beyond the joint DL-006/DL-008 revisit trigger.

## DL-010 — Accept bucket-wide B2 credentials in the Worker as a Backblaze-side configuration limitation, not a code defect (S03-I1)

- Finding: S03-I1 (audit's TB-8).
- Decision: Accept that the Worker's B2 client is scoped to the entire bucket, with no per-object or per-prefix key scoping, pending an operator check of whether Backblaze application-key scoping is available on the account's plan.
- Reason: Backblaze application-key scoping (if available) is configured at Backblaze, not in this codebase — there is no application-code fix available for this finding as stated.
- Alternatives considered: splitting media storage across multiple B2 buckets/keys per trust boundary (rejected as a larger architecture change out of scope for this reconciliation).
- Pros: Avoids fabricating a code-side mitigation the source does not support. Cons: A Worker compromise (or a stolen Cloudflare API token, SC-series) still grants total read/write/delete across both storage tiers.
- Security impact: Neutral — no new exposure; residual risk unchanged and explicitly carried forward as unmitigated.
- Rollback: N/A.
- Future considerations: Operator action — check Backblaze application-key scoping availability and apply it if present; this is the only path to closing this finding, not a future code session.

## DL-011 — Accept PIN strength bounded by the 4–6 digit keyspace, independent of PBKDF2 cost (S06-I3)

- Finding: S06-I3.
- Decision: Accept that offline brute force of an exfiltrated PIN salt:hash pair is bounded by the numeric PIN's keyspace (≤10^6 for 6 digits), not by iteration count, and that 310,000-iteration PBKDF2-HMAC-SHA256 is sized for the online/rate-limited threat this app actually defends against.
- Reason: No realistic PBKDF2 iteration count fixes a fixed small keyspace against an attacker who already has the salt:hash pair offline; that gap is inherent to choosing PIN-length UX over a passphrase. S08-L3 (landed) already closed the adjacent length-oracle problem, so an offline attacker must brute the full unified 4-6 digit range rather than a known length.
- Alternatives considered: raising `MIN_PIN_LEN`, or moving to alphanumeric passphrases (rejected as a product/UX decision out of this session's scope).
- Pros: Keeps the numeric-PIN UX users expect from a duress-capable lock screen. Cons: Offline brute force of an exfiltrated hash remains keyspace-bounded regardless of hashing cost.
- Security impact: Neutral — explicitly accepted as a UX-vs-strength trade-off, not remediated.
- Rollback: N/A.
- Future considerations: Revisit if product decides to raise minimum PIN length or add an alphanumeric option.

## DL-012 — Accept the `SenderKeyStore` stub as intentional dead code given no group multicast flow exists (S07-I1)

- Finding: S07-I1.
- Decision: Accept `storeSenderKey()`/`loadSenderKey()` as a non-functional stub, contingent on group messaging never invoking the Sender Key protocol path.
- Reason: Group messages are delivered via pairwise Signal Protocol sessions (fan-out), not Sender Key multicast; `CreateGroupActivity.java` exposes only group creation, and (per DL-013/S07-I2) there is no add-member flow that would exercise a distribution ID. The stub is dead code for the app's actual design, not a silently broken feature.
- Alternatives considered: implement a real `SenderKeyStore` now (rejected — no code path currently calls it; premature until group multicast is actually built).
- Pros: No wasted implementation effort on an unused code path. Cons: A future feature change adopting Sender Key without also implementing this store would silently fail or behave insecurely.
- Security impact: Neutral today; flagged as a forward-looking trap.
- Rollback: N/A.
- Future considerations: **Must** be revisited before any feature work introduces Sender Key-based group multicast encryption — implement the store for real at that time, do not ship it still stubbed.

## DL-013 — Accept the absence of an add-member-to-existing-group flow as a documentation/future-guardrail item (S07-I2)

- Finding: S07-I2.
- Decision: Accept that group membership is fixed at creation time with no code path to add a member afterward, and record the re-keying requirement any future add-member feature must satisfy.
- Reason: The audit's "key-less add" finding describes a hypothetical risk contingent on a feature that does not exist in this codebase; there is nothing to remediate today. What must be recorded is the guardrail: any future add-member feature must re-derive/redistribute group key material to the new member and must not grant them retroactive access to pre-membership history.
- Alternatives considered: none — no current code defect to fix.
- Pros: Correctly scopes effort to only what's actually built. Cons: None identified for the current codebase.
- Security impact: Neutral — the gap is purely prospective.
- Rollback: N/A.
- Future considerations: **Must** be revisited when add-member ships — see DL-012's identical revisit trigger, since both concern the same future group-multicast feature.

## DL-014 — Accept the 64-bit, seed-derived account ID as adequate collision resistance and a deliberate deterministic-recovery design (S07-I3)

- Finding: S07-I3.
- Decision: Accept `deriveUserId()`'s `SHA-256(seed)` → first 8 bytes (64 bits) → Base32 derivation, including its dual purpose as both public identifier and seed-derived value.
- Reason: 64 bits is adequate collision resistance for the app's expected population (negligible birthday-bound collision risk at any realistic scale); the dual-purpose derivation is deliberate — it guarantees deterministic account recovery from a seed phrase alone. SHA-256 is one-way, so this does not leak the seed itself or shrink its entropy.
- Alternatives considered: widening to 128 bits, or decoupling account ID from seed entirely (rejected — both are breaking migrations for every existing account, not scheduled in ROUND3_REMEDIATION_PLAN.md).
- Pros: Simple, deterministic, recovery-friendly design. Cons: Given a seed, the account ID is fully predictable — combined with the already-accepted DL-006/DL-008/DL-009 enumeration oracles, an attacker with a seed can compute the target ID directly.
- Security impact: Neutral — no new exposure beyond what DL-006/008/009 already accept.
- Rollback: N/A.
- Future considerations: None scheduled; revisit only if a breaking account-ID migration is independently justified for other reasons.

## DL-015 — Accept no TLS certificate pinning; rely on platform CA trust store plus E2E encryption of secret content (S08-I2)

- Finding: S08-I2.
- Decision: Accept that the app trusts any certificate the device's CA store accepts, with no `<pin-set>` and no `CertificatePinner` usage.
- Reason: Certificate pinning has a real operational cost this codebase has not built infrastructure for (pin rotation on cert renewal; a stale pin set can brick the app until an update ships), and the app's actual secret material (messages, keys) is already end-to-end encrypted independent of the TLS channel — a MITM with a trusted-but-illegitimate cert gains network metadata and ciphertext, not plaintext.
- Alternatives considered: implement pinning now (rejected — requires establishing a pin-rotation process tied to release cadence first, a future infrastructure decision, not a source change available this session).
- Pros: Avoids a self-inflicted denial-of-service risk from pin staleness. Cons: A network attacker with a trusted-but-illegitimate CA can MITM transport-layer metadata/timing and inject/withhold responses, though not decrypt E2E payload content.
- Security impact: Neutral — residual risk is bounded by the app's existing E2E encryption of sensitive content.
- Rollback: N/A.
- Future considerations: Implement pinning once a pin-rotation process tied to the release cadence exists.

## DL-016 — Accept no root/tamper/hooking/emulator detection as out of the client-trust threat model (S08-M3)

- Finding: S08-M3.
- Decision: Accept the absence of `RootBeer`/`isRooted`/`isEmulator`/`FridaDetect`/`isInsideSecureHardware` checks anywhere in the Android client.
- Reason: The audit's threat model already treats client-side integrity checks as advisory, not controls, since the attacker model grants the attacker the client; adding detection would not close any trust boundary, only raise the bar for a class of attacker the model does not defend against.
- Alternatives considered: implement `KeyInfo.isInsideSecureHardware()` and/or `RootBeer`-style checks now (rejected this session — the audit's own priority order treats `isInsideSecureHardware()` first, then root/signature checks, as a future implementation decision, not something this reconciliation pass executes).
- Pros: Avoids a false sense of security from advisory-only client checks. Cons: On a rooted/hooked/emulated device, PIN verification, duress-PIN comparison, and the lock timer can be hooked to always report success with no user-visible signal; a repackaged APK is indistinguishable from genuine absent a signature check.
- Security impact: Neutral — this is a genuine capability gap, explicitly not closed, feeding three other still-open/partial findings (S08-H5/S07-M1's plaintext fallback, S08-H3's plaintext Glide cache, and this session's own S06-I3).
- Rollback: N/A.
- Future considerations: Implement `KeyInfo.isInsideSecureHardware()` first per the audit's stated priority, then root/signature checks, in a future session — not scheduled by this reconciliation pass.

## DL-017 — Accept `androidx.security:security-crypto:1.1.0-alpha06` as a tracked maintenance risk, not an active vulnerability (SC-11)

- Finding: SC-11.
- Decision: Accept the alpha-channel dependency for `EncryptedSharedPreferences`, rather than downgrading to the more limited `1.0.0` "stable" release.
- Reason: `alpha06` is widely deployed industry-wide with no known exploitable defect; the audit itself rates this Low because the "stable" alternative is a functional downgrade, not a safety improvement. This is a maintenance-risk finding, not an active vulnerability, per the audit's own framing.
- Alternatives considered: downgrade to `1.0.0` (rejected — a functional downgrade with no security benefit); consolidate onto the app's own SQLCipher + AndroidKeyStore path instead of `security-crypto` entirely (noted as a longer-term architecture option, not actioned this session).
- Pros: Retains the more capable alpha API surface actually in use. Cons: No formal API/behavioral stability guarantee and no backported security fixes from an alpha channel; not yet hash-pinned (SC-03).
- Security impact: Neutral — tracked, not remediated; residual risk is maintenance/supply-chain hygiene, not a demonstrated exploit path.
- Rollback: N/A.
- Future considerations: Hash-pin the current version via SC-03; consider the SQLCipher/AndroidKeyStore consolidation as a longer-term architecture decision.

## DL-018 — Do not accept S02-I3's `checkRevoked` gap; evidence does not support carrying it as Accepted

- Finding: S02-I3.
- Decision: **Not accepted.** `ROUND3_REMEDIATION_PLAN.md`'s S3-20 scope line references "S02-I3 (checkRevoked half)" as if a prior split/accept decision existed for it; this session searched this log and every prior session file and found no record of that decision ever being made or ratified. `S02-I3` therefore remains `Open` in `BUG_TRACKER.md`, not `Accepted`.
- Reason: Fabricating an accepted disposition here would misstate confidence. Re-verification confirmed the gap is real: none of the 8 `verifyIdToken` call sites in `server/index.js` pass `checkRevoked: true`. Genuine, source-confirmed mitigating layers exist (S06-H1's in-transaction `accountLock` check before minting; `revokeRefreshTokens(uid)` on lock; `firestore.rules`' `accountNotLocked()` gate on the backup subsystem) but none of them close the literal gap on other routes (`/linkPreview`, `/turnCredentials`, `/mediaToken`, etc.) — a token issued before a lock/revocation event still authenticates those routes for up to its own ~1h natural lifetime.
- Alternatives considered: carry it as Accepted per the plan's apparent premise (rejected — no ratified decision exists to carry, and doing so would suppress a real residual window); mark it Fixed (rejected — none of the three mitigating layers touch the routes named above).
- Pros: Keeps the tracker's confidence honest. Cons: Leaves a real, if bounded, gap formally Open rather than closed out this session.
- Security impact: None — no change to actual risk; this decision only concerns how it's recorded.
- Rollback: N/A.
- Future considerations: To become Accepted in a future session, either (a) an explicit product decision on whether the ≤1h residual window is tolerable given the three mitigating layers, mirroring how DL-006 ratified S01-I1, or (b) a code fix threading `checkRevoked: true` through the highest-value subset of the 8 call sites reachable by a just-locked account.
