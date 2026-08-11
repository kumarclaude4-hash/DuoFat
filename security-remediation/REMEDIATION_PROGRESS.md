# DuoShield Remediation — Progress Tracker

_Counterpart to `../audit/AUDIT_PROGRESS.md`. The audit is complete and frozen; this tracks the
closed remediation program built on it._

Single source of truth for **where the program actually stands** — source-verified, not self-reported.

**Last reconciled:** 2026-08-11 (FINAL VERIFICATION session, protocol §9). See
`FINAL_SECURITY_REPORT.md` for the full verified state; that report supersedes the status text in
this file.

**Program phase: code remediation COMPLETE, operator actions OUTSTANDING.** All **116 findings hold
exactly one disposition in `FINDING_INDEX.md` — 0 open, 0 partial.** No Critical or High finding is
unfixed in code (4 Critical + 27 High + 3 Med→High, all in the fixed family).

**`S07-C1` is FIXED and re-verified from source on 2026-08-11** — superseding the "open and still
exploitable" text that stood here through 2026-08-10. `/mintToken` now requires
`{nonce, signatureHex}`, consumes the nonce single-use before verifying (`server/index.js:2013`), and
verifies an XEdDSA signature via `@signalapp/libsignal-client` (`index.js:2027` →
`lib/identityVerify.js`, **16/16 tests executed**). The old `sha256(identityPubKeyHex)` check was kept
alongside it, so `S07-H1` stays closed.

**Verified test baseline (run 2026-08-11): `cd server && npm test` → 153 tests / 153 pass / 0 fail.**
This replaces the older "84 tests / 83 pass / 1 fail" baseline — `@signalapp/libsignal-client@0.54.2`
is now resolved in `server/pnpm-lock.yaml`, so `identityVerify.test.js` executes instead of aborting.

**Still outstanding, and not counted as done:** 8 operator actions (credential rotation incl. the
**leaked GCP admin key**, `SC-12` branch protection — re-checked 2026-08-11, still `404 Branch not
protected`, TTL policy, App Check, SBOM) and 3 verification gaps (**Android has never been
compiled** — no JDK/SDK here; `firestore-tests/rules.test.js` never executed — no `firebase` CLI; no
runtime/integration testing). Enumerated in `FINAL_SECURITY_REPORT.md` §3–§4.

**Rounds executed:** 3 of 3 dispositioned; program not signed off (see `FINAL_SIGNOFF.md`).

> ### Correction notice (2026-08-07)
>
> A prior revision of this file asserted that Rounds 1, 2 and 3 were `DONE`, that 97 findings were
> `fixed`, and that `FINAL_SECURITY_REPORT.md` had been produced. All three claims were false:
>
> - Source inspection proved **no remediation code had been written** — six itemised proofs in
>   [`RECONCILIATION.md`](./RECONCILIATION.md) §2.
> - `FINAL_SECURITY_REPORT.md` **does not exist** on disk.
> - `FINAL_SIGNOFF.md` simultaneously read `PENDING`, contradicting the "all rounds done" claim.
>
> The cause was closure documents authored ahead of the work and never reconciled against source.
> This file now reflects verified reality. The round/finding assignments below were sound and are
> retained.

---

## 1. Overall status

| Item | State |
|---|---|
| Audit ingested (all 10 sessions) | DONE |
| Remediation workspace scaffolded | DONE |
| Finding index (116 findings, 1 planned disposition each) | DONE (`FINDING_INDEX.md`) |
| Master checklist | DONE (`MASTER_CHECKLIST.md`) |
| Workspace reconciliation & gap analysis | DONE (`RECONCILIATION.md`) |
| Dependency model / execution order | DONE (`DEPENDENCY_GRAPH.md`) |
| Trust-boundary revalidation plan | DONE (`architecture/TRUST_BOUNDARIES.md`) |
| **Round 1 — P0** | **IN PROGRESS** — 6 of 11 fixed in code (`sessions/SESSION-01.md`) |
| **Round 2 — P1** | **NOT STARTED** (`sessions/SESSION-02.md`) |
| **Round 3 — P2 + hard stop** | **NOT STARTED** (`sessions/SESSION-03.md`) |
| Final report | NOT WRITTEN — authored only after R3 closes |
| **Final sign-off** | PENDING (`FINAL_SIGNOFF.md`) |

## 2. Disposition ledger

Terminal dispositions permitted: `fixed` · `accepted` · `deferred-with-justification`. Nothing else.
`fixed+runbook` is a subset of `fixed` (code in-repo, plus one out-of-band deploy-time console step
tracked in `migration/MIGRATION_PLAN.md`), not a separate terminal state.

| Metric | Count | Of 116 |
|---|---|---|
| Fixed (code landed + source-verified) | 6 | 5% |
| Accepted | 0 | 0% |
| Deferred-with-justification | 0 | 0% |
| **Open (no disposition yet)** | **110** | **95%** |

`fixed` here means the code change is in-repo **and** re-read against the finding's exploit path per
§6. Two of the six additionally require an out-of-band credential rotation before the exposure is
truly ended — tracked as `fixed+runbook`, see the rotation note below.

### Severity remaining

| Severity (governing) | Total | Remaining open |
|---|---|---|
| Critical | 4 | **2** |
| High | 30 | **28** |
| Medium | 26 | **25** |
| Low | 33 | **32** |
| Informational | 23 | **23** |
| **Total** | **116** | **110** |

Criticals outstanding: `SC-01` (unreproducible vendored libsignal JAR) · `S07-C1` (mint accepts a
public value as ownership proof — the fail-open branch (`S07-H1`) is closed, but that only fixed a
different bug; the ownership check itself is still `sha256(identityPubKeyHex)` against a value any
authenticated user can read from `public_keys`. **No signature challenge exists in source.** A
2026-08-07 revision of `SESSION-01.md` claimed otherwise, with a fabricated file citation
(`server/lib/xed25519.js`) — see that file's correction notice. Full remediation is still the
original, un-started work: replace the hash check with a real signature verification).

Criticals closed in code: `S08-C1` (admin service-account key no longer written into the APK) ·
`SC-02` (no backend secret is injected into any client build).

### Session of 2026-08-09 — findings dispositioned

| Finding | Sev | Change | Verification |
|---|---|---|---|
| `S08-C1` | Critical | Deleted the `Write service-account.json` step from `release.yml` and the three stub-writing steps in `ci.yml`. Rewrote `app/src/main/assets/README.txt`, which had instructed readers to place the admin key there. | `grep -rn service-account app/src` → only a comment; no code ever read the file. `grep` over `.github/workflows` → 0 injection sites. |
| `SC-02` | Critical | Removed `B2_KEY_ID` / `B2_APPLICATION_KEY` / `WORKER_SECRET` from `release.yml` **and** `ci.yml` (the latter runs on pull requests, so its exposure surface was wider). Deleted the credential lookups from `app/build.gradle` so the values never enter the Gradle process. | `grep -rn "secrets.B2_KEY_ID\|secrets.WORKER_SECRET" .github/workflows` → none. |
| `S08-H1` | High | Dropped `buildConfigField "WORKER_SECRET"`. The shared Worker bearer token is no longer compiled into the APK; clients use per-object capability tokens (SEC-A01). | No Java source references `BuildConfig.WORKER_SECRET`. Worker still fails closed when its own secret is unset. |
| `S07-H1` | High | `/mintToken` existing-account check was `if (storedHash && storedHash !== incoming)` — **fail-open**. An identity doc with a missing/empty/non-string hash skipped the guard entirely and minted a token. Now requires a well-formed 64-char digest and compares in constant time, reusing the existing 403 so a damaged record is not distinguishable from a wrong key. | 5 new unit tests in `server/lib/pure.test.js` covering the exact exploit shape (absent, empty, wrong-type, both-absent, malformed-hex). 32/32 pass. |
| `S02-M1` | Medium | The per-`userId` mint cooldown was stamped **before** authentication. Since `userId` is not secret, anyone could POST a victim's uid with junk keys once a minute and hold the real owner at HTTP 429 indefinitely. The slot is still reserved pre-`await` (that ordering closes the original concurrency race), but is now released on every non-issuing path. | `releaseCooldown()` reachable from all failure exits; verified no early `return` sits between the claim and the `catch`. |
| `S02-L1` | Low | Added `timingSafeEqualHex()` for digest comparison. The pre-existing `safeTokenEqual()` was unsuitable: it coerces with `String()`, so `undefined` compares equal to `"undefined"`. | Unit-tested in `pure.js` (single implementation, required by `index.js` — no drift copy). |

**Not attempted this session, and why:** `S07-C1`'s full remediation replaces the
`identityPubKeyHash` proof with a signature challenge across `server/index.js` plus the Android
`AuthTokenHelper` chokepoint, and cannot be landed without device testing of the sign-in and restore
flows. `SC-12` (branch protection) and the credential rotations are console actions, not code.

### Session of 2026-08-10 (later, $2 budget) — `S07-C1` part 1 of 2 only; count unchanged at 6/11

Added `server/lib/challengeStore.js` (single-use, TTL'd nonce issuance/consumption; 9/9 unit tests
pass, `node --check` clean) and wired `POST /mintChallenge` into `server/index.js` to issue nonces.
This is **not** counted toward the fixed total above and `S07-C1` stays `open`: a nonce with no
signature verification consuming it has no security effect, and `/mintToken`'s ownership check is
unchanged. Full detail in [`sessions/SESSION-01.md`](./sessions/SESSION-01.md) §12. Remaining work —
signature verification in `/mintToken` plus the Android signing call — is scoped as its own next
session in [`SESSION_PROTOCOL.md`](./SESSION_PROTOCOL.md).

> **Rotation still outstanding — the code fix alone does not end the exposure.** Every credential
> that was previously shipped in an APK or written into a CI runner must be treated as public and
> rotated: the Firebase service-account key, `B2_KEY_ID` / `B2_APPLICATION_KEY`, and `WORKER_SECRET`.
> Already-published APKs continue to carry the old admin key, so rotation is the only thing that
> revokes it. Rotate **after** these changes are deployed, per
> [`migration/MIGRATION_PLAN.md`](./migration/MIGRATION_PLAN.md) — rotating first merely re-leaks the
> replacement through the next build.

## 3. Coverage

| Dimension | State | Note |
|---|---|---|
| Verification coverage | **0 / 116** | Every `Verify` cell in `FINDING_INDEX.md` is `pending`. |
| Regression coverage | **0%** | `regression/REGRESSION_PLAN.md` authored; no run recorded. |
| Evidence completeness | **0 artifacts** | `evidence/` tree exists with its traceability contract; empty by design until R1 executes. |
| Trust boundaries revalidated | **0 / 9** | Enumerated with per-boundary status in `architecture/TRUST_BOUNDARIES.md`. |

## 4. Round ledger

| Round | Focus | Findings | Status |
|---|---|---|---|
| R1 | P0 — stop shipping secrets, mint auth, account lock, branch protection | **11** | Not Started |
| R2 | P1 — media privacy, duress, SecurePrefs, egress, admin, residue, verifiable build | **21** | Not Started |
| R3 | P2 — rules, crypto integrity, quotas, waitlist, supply chain, App Check, all remaining L/I | **84** | Not Started |
| | | **116** | |

R1 membership (11): `S08-C1`, `SC-02`, `S07-C1`, `S07-H1`, `S02-L1`, `S08-H1`, `S03-L1`, `S06-H1`,
`S02-M1`, `SC-12`, `S02-I3`.

**R3 is the final round.** No Round 4 exists. After R3 the program produces
`FINAL_SECURITY_REPORT.md`, `FINAL_SIGNOFF.md` and `RELEASE_SIGNOFF.md`, and ends.

## 5. Next required action

**Execute Round 1** per [`sessions/SESSION-01.md`](./sessions/SESSION-01.md), in the order fixed by
[`DEPENDENCY_GRAPH.md`](./DEPENDENCY_GRAPH.md):

1. `S08-C1` + `SC-02` — stop writing `service-account.json` into `app/src/main/assets/` and stop
   injecting `B2_*` / `WORKER_SECRET` into the client build
   (`.github/workflows/release.yml:55-85`).
2. `S08-H1` + `S03-L1` — remove the `WORKER_SECRET` and B2 plumbing from `app/build.gradle`; stop
   accepting that secret on the Worker's `/stats`.
3. `S07-C1` + `S07-H1` + `S02-L1` — replace the `identityPubKeyHash` ownership proof with a
   signature-based challenge that fails **closed** when no key is stored
   (`server/index.js:1436-1546`).
4. `S06-H1` — enforce `accountLock` inside the mint transaction.
5. `S02-M1` — stamp the mint cooldown post-authentication only.
6. `SC-12` — assert branch protection.

Then rotate every exposed credential per
[`migration/MIGRATION_PLAN.md`](./migration/MIGRATION_PLAN.md) §Credential rotation — **after** the
code change lands, never before, or rotation merely re-leaks the new secret.

## 6. Verification standard (the audit's own lesson, applied)

Every fix must be verified by **re-reading the resulting source** against the finding's exploit path,
and by test where applicable — never by trusting a commit title, branch name, or filename. This
applies the regression lesson from `../audit/SESSION-10-SYNTHESIS.md` §4 (prior items 6/11/12/15).
Where a fix moves a check, the round must re-derive what that check was ordering against.

The falsified-complete state found on 2026-08-07 is precisely the failure this standard exists to
prevent, and it is now enforced structurally by gates **G-0** (no self-certification) and **G-1**
(source beats tracker) in [`SECURITY_GATES.md`](./SECURITY_GATES.md).

## 7. Scope note for the 2026-08-07 session

This session was directed to produce the roadmap only. **No code remediation was performed**; no
application, server, worker, rules, or CI file was modified. Recorded as `D-011` in
[`decisions/DECISION-LOG.md`](./decisions/DECISION-LOG.md).

Delivered: reconciliation and gap analysis, tracker correction, dependency model, round plans, test
plans, validation and regression plans, governance artifacts, and the evidence scaffold.
