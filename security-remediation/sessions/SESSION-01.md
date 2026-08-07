# REMEDIATION SESSION 01 — Round 1 (P0): stop the bleeding

Maps to `REMEDIATION_PLAN.md` Round 1 — the highest-risk trust-boundary failures and the audit
synthesis P0 set.

**Status:** NOT STARTED
**Round:** 1 of 3 · **Findings in scope:** 11
**Test plan:** [`../test-plans/ROUND-01.md`](../test-plans/ROUND-01.md)

> ### Rewritten 2026-08-07 — the previous content was invalid
>
> The prior version of this file was an execution log for work that was never performed, written
> against finding IDs that **do not exist in the audit**: `S09-C1`, `S09-C2`, `SC-2`, `S02-C1`,
> `S02-H2`, `S06-C1`, `S09-H1`. It also described a defect the audit never reported — a signing
> keystore and `key.properties` committed to version control — and cited decision IDs (`DL-001`,
> `DL-002`) absent from `decisions/DECISION-LOG.md`.
>
> It additionally misused one **real** ID: `S02-H1` is `migrateUid` copying user-doc fields verbatim
> (a Round 3 item), not a fail-open mint.
>
> The audit's actual Criticals are `S07-C1`, `S08-C1`, `SC-01`, `SC-02`. This file is now a forward
> plan keyed to real IDs. See [`../RECONCILIATION.md`](../RECONCILIATION.md) §1.

---

## 1. Objectives

Close the P0 set: **credential exposure** and **authentication bypass**. Until both are closed every
other control is unenforceable — the leaked GCP service-account key carries Firestore **Admin**
authority, which sits above the rules layer and therefore voids TB-2 entirely, and `/mintToken` grants
account takeover without the seed phrase.

Round 1 is the gate for Rounds 2 and 3. Neither may begin until Round 1 is closed and verified.

## 2. Findings in scope (11)

| ID | Sev | Root cause | Planned disposition |
|---|---|---|---|
| `S08-C1` | **Critical** | Admin GCP service-account key written into `app/src/main/assets/`, shipped in every APK | `fixed+runbook` |
| `SC-02` | **Critical** | Release workflow bakes the full backend credential set into the client build | `fixed+runbook` |
| `S07-C1` | **Critical** | `/mintToken` accepts a public value (identity pubkey) as proof of private-key ownership | `fixed` |
| `S08-H1` | High | `WORKER_SECRET` in `BuildConfig`, accepted on Worker `/stats` | `fixed+runbook` |
| `S07-H1` | High | Existing-account key check fails **open** when the stored hash is falsy | `fixed` |
| `S06-H1` | High | `accountLock` never enforced server-side; restore gate is client-side and post-auth | `fixed` |
| `S02-M1` | Medium | Mint cooldown stamped **pre-auth** for a caller-supplied `userId` → targeted re-auth DoS | `fixed` |
| `S02-L1` | Low | Dup of `S07-H1` — same fail-open branch | `fixed` |
| `S03-L1` | Low | Dup of `S08-H1` — `WORKER_SECRET` compiled into the APK | `fixed` |
| `SC-12` | Low | CODEOWNERS present, branch protection unverified | `fixed+runbook` |
| `S02-I3` | Info | No `checkRevoked` → locked sessions live until token expiry | `fixed` (residual `RR-06`) |

## 3. Folders / files in scope

- `.github/workflows/release.yml` — the credential-emitting steps (`:55-65`, `:70-85`)
- `app/build.gradle` — B2 / `worker.secret` plumbing (`:42-72`)
- `server/index.js` — `/mintToken` handler (`:1436-1546`), `accountLock` enforcement
- `worker/src/index.js` — `/stats` authentication
- `app/src/main/java/com/duoshield/app/util/AuthTokenHelper.java` — client mint request (`:101-120`)
- `app/src/main/java/com/duoshield/app/RestoreFromSeedActivity.java` — client-side restore gate (`:252-268`)
- Repository settings — branch protection on `main` (out-of-band)

`firestore.rules` is touched **only** if `accountLock` requires a read-path rule. All other rules work
is Round 3.

## 4. Root-cause analysis

**Credential exposure (`S08-C1`, `SC-02`, `S08-H1`, `S03-L1`).** The release pipeline treats the client
build as a trusted environment. `release.yml:55-65` writes `GOOGLE_APPLICATION_CREDENTIALS_JSON` to
`app/src/main/assets/service-account.json`; `:70-85` writes `B2_KEY_ID`, `B2_APPLICATION_KEY`,
`B2_BUCKET`, `B2_REGION` and `WORKER_SECRET` into `local.properties`, which `build.gradle:42-72`
compiles into `BuildConfig`. An APK is a public artifact, so this is unconditional disclosure of
server-side authority to anyone who downloads a release. This is architectural — a boundary placement
error — not a line bug.

**Authentication bypass (`S07-C1`, `S07-H1`, `S02-L1`).** `server/index.js:1508,1514` stores and
compares `identityPubKeyHash`. An identity **public** key is published by design — every peer fetches
it from `identities/{uid}` to start a session. Treating its hash as an ownership proof means anyone who
can read a victim's public identity can mint a token for that account without the seed.
`S07-H1`/`S02-L1` compound it: when the stored hash is absent or falsy the comparison is skipped and
the request **fails open**.

**Lock not enforced (`S06-H1`).** `accountLock` is read only at `server/index.js:2659` and `:2697`
(duress-lock write, admin unfreeze). There is **no** read in the `/mintToken` handler. The restore gate
lives in `RestoreFromSeedActivity.java:252-268` — client-side and post-authentication. An attacker who
ignores the client simply mints a token, so the duress lock is decorative at the only boundary that
matters.

**Cooldown DoS (`S02-M1`).** The cooldown is stamped before authentication using the caller-supplied
`userId`, so an unauthenticated attacker can pin any victim's cooldown and deny them re-auth.

## 5. Implementation plan

Order fixed by [`../DEPENDENCY_GRAPH.md`](../DEPENDENCY_GRAPH.md) §7. Steps 1-3 must precede step 4.

1. **Stop emitting secrets** — `release.yml`. Delete the `Write service-account.json` step. Remove
   `B2_*` and `WORKER_SECRET` from the `local.properties` block. Keep only genuinely public values
   (`WORKER_URL`, `PUSH_SERVER_URL`).
2. **Remove client secret plumbing** — `build.gradle`. Drop the `B2_KEY_ID` / `B2_APPLICATION_KEY` /
   `worker.secret` reads and their `buildConfigField` emissions. Any client path that used them routes
   through the Worker.
3. **Worker `/stats`** — stop accepting `WORKER_SECRET` from a client; make it operator-only
   (server-to-server) or remove it.
4. **Rotate** (runbook, `../migration/MIGRATION_PLAN.md`) — **only after 1-3 land.** Rotating first
   regenerates a secret the next release re-leaks. Order: revoke GCP SA key → revoke B2 application
   key → rotate `WORKER_SECRET` → invalidate tokens minted under the old secret.
5. **Fail closed** (`S07-H1`, `S02-L1`) — absence of a stored ownership record must **deny**. Land this
   before the `S07-C1` rework so the new path inherits deny-by-default.
6. **Proof of possession** (`S07-C1`) — replace hash-of-public-key with a challenge: server issues a
   nonce, client signs it with the seed-derived identity **private** key, server verifies against the
   stored public key. Per `decisions/DECISION-LOG.md` `D-001`, including the migration path for
   accounts that currently hold only a hash.
7. **Enforce `accountLock`** (`S06-H1`) — read `accountLock/{uid}` **inside** the mint transaction, not
   before it, so lock-then-mint cannot interleave. Deny when locked.
8. **Cooldown post-auth** (`S02-M1`) — stamp only after the signature verifies, keyed on the
   authenticated identity, never the request body.
9. **Revocation** (`S02-I3`) — honour lock state at mint. Residual: already-issued bearer tokens live
   to expiry — recorded as `RR-06`.
10. **Branch protection** (`SC-12`) — require reviews and status checks on `main`.

## 6. Tests to run

| Check | Proves |
|---|---|
| `unzip -l` a release APK, grep `service-account.json` | `S08-C1` closed at the **artifact**, not just the workflow |
| `strings` / `apktool` the APK for B2 key and `WORKER_SECRET` values | `SC-02`, `S08-H1`, `S03-L1` |
| Unit: mint with valid pubkey but **no** signature | `S07-C1` — must deny |
| Unit: mint with a replayed nonce | `S07-C1` replay resistance |
| Unit: mint when stored record is absent / empty / null | `S07-H1`, `S02-L1` — must deny, not permit |
| Unit: mint while `accountLock.locked == true` | `S06-H1` |
| Concurrency: lock write racing a mint | `S06-H1` transaction correctness |
| Unauthenticated mint for a victim `userId`, then victim's own mint | `S02-M1` — victim must not be cooled down |
| Worker `/stats` with a client-held secret | `S08-H1` — must reject |
| `gh api` branch-protection read on `main` | `SC-12` |

## 7. Evidence to collect

Under `../evidence/`, each item named `<FINDING-ID>-<slug>` per the traceability contract:

- `before/` — `release.yml:55-85`, `build.gradle:42-72`, `index.js:1436-1546` excerpts
- `after/` — the same regions post-fix
- `diffs/` — one unified diff per finding ID
- `tests/` — output for every row in §6
- `logs/` — APK inspection output proving **absence** of each secret
- `notes/` — rotation record: what was revoked, when, by whom. **No secret values.**

## 8. Validation steps

1. Re-read each changed region against the finding's exploit path — do not infer from the diff.
2. Confirm no secret value appears in a **built APK** (artifact-level, not workflow-level).
3. Confirm mint denies on: no signature · bad signature · replayed nonce · absent stored key · locked account.
4. Confirm the cooldown is unreachable by an unauthenticated caller.
5. Confirm rotation happened **after** the code change and that old credentials are dead.

## 9. Regression checks

- Legitimate restore-from-seed on an unlocked account still succeeds.
- Existing accounts holding only the old hash still authenticate via the `D-001` migration path.
- Media upload/download still works once `WORKER_SECRET` leaves the client.
- Push registration still works without the service-account asset.
- CI still produces a valid signed release artifact.

## 10. Exit criteria

All must hold. Any failure moves that finding to `deferred-with-justification` — **never** to `fixed`.

- [ ] No secret value present in a built release APK, proven by artifact inspection
- [ ] `S07-C1` mint requires proof of possession; all five deny-cases verified
- [ ] `S06-H1` `accountLock` enforced inside the mint transaction, race-tested
- [ ] `S02-M1` cooldown unreachable pre-auth
- [ ] All exposed credentials rotated; old ones confirmed dead
- [ ] `SC-12` branch protection asserted
- [ ] Evidence present for all 11 findings
- [ ] §9 regression checks pass
- [ ] `FINDING_INDEX.md`, `MASTER_CHECKLIST.md`, `REMEDIATION_PROGRESS.md`, `validation/VALIDATION_LOG.md`, `regression/REGRESSION_LOG.md` updated

## 11. Findings explicitly NOT touched this round

All `S01-*` Firestore rules (deliberately deferred — unenforceable while the SA key leaks), all
`S04-*` egress, all `S05-*` admin, `S03-H1/H2/H3/M*/L2/L3/L4/I*`, `S06-H2/H3/M*/L*/I*`,
`S07-H2/H3/M*/L*/I*`, `S08-H2..H5/M*/L*/I*`, `SC-01`, `SC-03`–`SC-11`, `S10-N1/N2/N3`.
