# REMEDIATION PLAN — fixed, closed program

Three remediation rounds, ordered by **dependency and risk reduction**, following the audit
synthesis roadmap (`../audit/SESSION-10-SYNTHESIS.md` §8: P0 → P1 → P2). **Round 3 is a hard stop.**
No round 4 exists; after Round 3 the program produces the final report and sign-off and ends.

The two Criticals that invalidate everything else — `S08-C1` (Admin key in the APK) and `S07-C1`
(public value accepted as auth) — are the first work in Round 1, because until they are closed no
other finding's severity is meaningful (synthesis §8, "nothing else matters until these are done").

Governing principle from the audit's own regression lesson (§4, items 6/11/12/15): **fix the class,
not just the instance, and re-derive what each moved check was ordering against.** Every fix is
verified from source, never from a commit title.

---

## Round 1 (Session log `sessions/SESSION-01.md`) — P0: stop the bleeding

**Goal.** Close both Criticals and their tightly-coupled Highs; stop shipping secrets in the client
build; add the CI gate that keeps them out; enforce the account lock at the mint boundary; confirm
branch protection. After this round a published build no longer hands an attacker the keys to the
whole system.

**Audit findings covered:** `S08-C1`, `SC-02`, `S08-H1`/`S03-L1`, `S07-C1`, `S07-H1`/`S02-L1`,
`S06-H1`, `S02-M1`, `SC-12`, and `S02-I3` (partial — lock enforced at mint).

**Scope / files expected to change:**
- `.github/workflows/release.yml` — remove the `service-account.json` write; remove `WORKER_SECRET`
  and the B2/service-account secret injection; add an APK secret-scan gate step.
- `.github/workflows/ci.yml` — mirror the stub-only posture; add the secret-scan gate on debug APK.
- `build-release.sh`, `build-apks.sh` — remove the same `assets/service-account.json` write (if
  present in the tree).
- `app/build.gradle` — remove the `WORKER_SECRET` `buildConfigField` (follow the B2 `""` precedent).
- `app/src/main/assets/README.txt` — delete (its instructions cause S08-C1).
- `app/src/main/assets/service-account.json` — remove from tree if present.
- `server/index.js` — `/mintToken`: challenge/response proof-of-seed (`S07-C1`), store full pubkey,
  fail closed on missing stored key (`S07-H1`), enforce `accountLock` inside the mint transaction
  (`S06-H1`), stamp cooldown only on verified success and key failed attempts separately (`S02-M1`);
  add `/authChallenge` endpoint. Mirror the lock check on `/migrateUid`.
- `app/src/main/java/com/duoshield/app/auth/AuthTokenHelper.java` — sign the server challenge with
  the identity private key; send `{userId, identityPubKeyHex, nonce, signature}`.
- `firestore.rules` — `identities` create/update require `identityPubKeyHash is string` (fail-closed
  support for `S07-H1`), and carry the stored public key.
- `scripts/ci/scan_apk_secrets.sh` (new) — the reusable secret scanner used by both workflows.
- `security-remediation/checklists/*`, `evidence/*`, `migration/MIGRATION_PLAN.md` — evidence + the
  operator runbook for credential rotation and branch protection.

**Dependencies:** none inbound. `SC-02`/`S08-C1` code change must land **before** the runbook's
credential rotation (rotating first just re-leaks). `S07-C1` and `S06-H1` and `S07-H1` and `S02-M1`
are **one** `/mintToken` code path and are implemented together.

**Risks:**
- Changing the auth handshake is protocol-breaking: an old client cannot sign the challenge. Handled
  by a versioned, additive endpoint and a documented client+server co-deploy order (MIGRATION_PLAN).
- Enforcing `accountLock` at mint could lock out a legitimate user if the latch were wrong; mitigated
  by reusing the existing 403 path and the audit-confirmed one-way-latch rules.

**Rollback strategy:** each change is isolated per file; `git revert` of the Round-1 commit restores
prior behavior. The auth change is feature-flag-guarded server-side (`AUTH_REQUIRE_SIG`) so it can be
disabled without a redeploy if a compatibility break is detected, then re-enabled after client
rollout. Rollback order is the reverse of deploy order in MIGRATION_PLAN.

**Verification strategy:** read the final `/mintToken` source and confirm (a) no `createCustomToken`
path exists that does not verify a signature over a fresh server nonce against the stored public key,
(b) a missing stored key throws 403, (c) `accountLock.locked===true` throws before mint, (d) cooldown
is stamped only after a verified mint / failed attempts keyed to IP. Grep the built config for
`WORKER_SECRET` literal (must be empty). Dry-run the APK secret-scan script against a synthetic APK
containing a key and confirm non-zero exit.

**Evidence expected:** `evidence/R1-*` — annotated diffs of `release.yml`, `build.gradle`,
`server/index.js`, `AuthTokenHelper.java`, `firestore.rules`, the scan script output, and the
credential-rotation + branch-protection runbook with checkboxes.

**Exit criteria (all must hold):**
1. No client-build artifact contains a service-account key or `WORKER_SECRET` (source + CI gate).
2. `/mintToken` requires cryptographic proof of the identity private key and enforces the lock.
3. `S07-H1` fail-open path is closed.
4. `S02-M1` cooldown cannot be poisoned pre-auth.
5. Runbook for credential rotation + branch protection is written with explicit operator steps.

**Done condition:** the five exit criteria are verified from source and recorded in
`sessions/SESSION-01.md`; the four P0 code findings are marked `fixed` in FINDING_INDEX; the two
`+runbook` items have their console steps enumerated in MIGRATION_PLAN.

---

## Round 2 (Session log `sessions/SESSION-02.md`) — P1: the advertised guarantees

**Goal.** Restore the guarantees the product advertises and the audit found broken: media privacy,
duress, at-rest key protection, egress safety, admin accountability, on-device residue, and a
verifiable build.

**Audit findings covered:** `S03-H1`, `S06-H2`, `S06-H3`, `S06-I2`, `S08-H5`/`S07-M1`, `S04-H1`,
`S04-H2`, `S04-H3`/`S08-H4`, `S05-H1`, `S05-H3`, `S05-I1`, `S08-H2`, `S08-H3`, `S10-N2`/`S07-L4`,
`S10-N3`, `SC-05`, `SC-04`, `SC-01`, `S04-I2`.

**Scope / files expected to change:** `worker/src/index.js` (typed scope keys, holder-keyed limits,
migration orphan fix `S10-N3`, `nosniff`), `server/index.js` (media scope dispatch, SSRF DNS-resolve
classifier, bounded/timed body read, image proxy, admin entropy gate + durable audit + failure
logging), `server/lib/pure.js` (address-family classifier), `firestore.rules`+`groups` server-mint
(`S03-H1`), Android: `SecurePrefs.java` (fail closed), `BaseActivity.java`/lock activities
(`FLAG_SECURE`), `DuoShieldGlideModule.java`/`TempFileCleaner.java`/`WipeHelper.java` (cache bound +
sweep), `MessageAdapter.java` (block off-origin Glide), `DuressManager.java`/`AccountLockWorker.java`
(durable lock intent + prune WorkManager), `DuoShieldSignalStore.java` (LogRedact),
`.github/workflows/release.yml` (stop deleting releases/tags, SHA256SUMS + provenance + cert
fingerprint), `scripts/strip_signal_records.py` + `ci.yml` (reproducible + hash-assert).

**Dependencies:** `S03-H1` (typed scope) must land before Round 3's per-holder quotas. `S04-H3`
server proxy pairs with `S08-H4` client block. `S06-H3` durable-intent subsumes `S06-I2`.

**Risks:** SSRF classifier over-blocks legitimate previews (mitigated: unit tests on the nine audit
rows + monitoring); `FLAG_SECURE` breaks a user screenshot expectation (mitigated: opt-in preference
default-off per audit recommendation); reproducible-JAR change could alter the shipped artifact
(mitigated: hash-assert against the recorded value, explain any delta before release).

**Rollback strategy:** per-file `git revert`; the SSRF classifier and FLAG_SECURE behavior are each
independently revertible. Worker changes deploy behind a versioned key format so old tokens drain.

**Verification strategy:** unit-test the SSRF classifier against S04-H1's nine literal rows +
DNS-resolution cases; read the worker scope dispatch to confirm a chat ID can never be satisfied by a
group doc; confirm `SecurePrefs` throws instead of returning a plaintext handle; grep for remaining
`clearFlags(FLAG_SECURE)`; confirm the migration `else` branch deletes the B2 orphan; re-run the
strip script and diff the JAR hash against the recorded value.

**Evidence expected:** `evidence/R2-*` diffs, the SSRF classifier unit-test file and its pass output,
the recorded libsignal hash, the release-workflow diff.

**Exit criteria:** media capability tokens bind to a server-controlled membership source; duress locks
durably and offline and leaves no WorkManager residue; SecurePrefs fails closed; SSRF resolves and
classifies addresses; link-preview images are server-proxied and the client refuses off-origin loads;
admin has an entropy floor + durable audit + failure logging; FLAG_SECURE set; media cache bounded and
swept; releases/tags immutable with checksums+provenance; vendored JAR reproducible and hash-asserted
in CI; `S10-N2`/`S10-N3` closed.

**Done condition:** all Round-2 findings verified from source (SSRF also from test) and recorded in
`sessions/SESSION-02.md`; runbook items (`S05-H1` token rotation, `S04-I2` B2-key revoke) enumerated
in MIGRATION_PLAN.

---

## Round 3 (Session log `sessions/SESSION-03.md`) — P2 batch + HARD STOP

**Goal.** Close every remaining finding: rules hardening, identity/backup/group-crypto integrity,
quotas and limits, waitlist reviewability, supply-chain hardening, the App Check decision, and all
remaining Lows/Info — each to a final disposition. Then stop.

**Audit findings covered:** all findings not closed in R1/R2 — `S01-*`, `S02-H1`/`S02-L*`/`S02-I*`,
`S03-H2`/`S03-H3`/`S03-M*`/`S03-L*`/`S03-I*`, `S04-M*`/`S04-L*`/`S04-I1`/`S04-I3`, `S05-H2`/`S05-M*`/
`S05-L*`/`S05-I2`/`S05-I3`, `S06-M*`/`S06-L*`/`S06-I1`/`S06-I3`, `S07-H2`/`S07-H3`/`S07-M2`/`S07-M3`/
`S07-L*`/`S07-I*`, `S08-M*`/`S08-L*`/`S08-I1`/`S08-I2`/`S08-I3`, `SC-03`/`SC-06`/`SC-07`/`SC-08`/
`SC-09`/`SC-10`/`SC-11`, `S10-N1`, `S01-I1`.

**Scope / files expected to change:** `firestore.rules` (prekey shrink-only, message content
immutability, partnerName block, field allow-lists, group delete membership, groups server-mint,
backup_logs schema), `server/index.js` (migrateUid allow-list, byte-accurate body, limiter purge/IPv6
normalization, TURN cap, XFF config, waitlist deny/expire, admin lookup validation + ordering +
cache-control + session lifetime, log redaction, /status gating), `worker/src/index.js` (per-holder
quotas, content-type derivation, uploader binding, TTL cut, decodeURIComponent guard, CORS on
errors), Android crypto/platform files (backup MAC / drop plaintext digest, group AAD, trust keyed on
stable id, derivationCache clear on wipe, mnemonic canonicalization, group-key creator fail-closed,
FileProvider scoping, manifest heap tagging, deep-link validation, clipboard sensitivity, PIN length,
proguard crypto-name strip, App Check provider), supply chain (`verification-metadata` scaffold +
`dependencyLocking`, JitPack `includeGroup`, wrapper-validation step, SHA-pinned actions, scan
workflow + Dependabot, firestore deploy pin), `firestore-tests/` (`_duressNonces` coverage).

**Dependencies:** per-holder quotas (`S03-H2`/`S03-H3`) depend on R2's typed scope. Everything else is
independent and batchable.

**Risks:** large batch surface; mitigated by grouping per folder-checklist and verifying each folder
independently. App Check enforcement can break sideloaded installs — handled as `accepted` (with the
sideloading caveat) + client wiring + a monitoring-mode runbook, never hard-enforced blind.

**Rollback strategy:** per-folder `git revert`. Rules changes are additive constraints; a revert
restores the prior (more permissive) rule. No data migration is required by any Round-3 change except
the backup-digest deprecation, which is read-compatible with legacy docs.

**Verification strategy:** re-read each changed rule/endpoint against its finding's exploit path;
run `firestore-tests/` (now including `_duressNonces`); grep for each residual pattern the audit named
(raw uid logs, `clearFlags`, unbounded `.get()`); confirm every remaining finding has exactly one
disposition in FINDING_INDEX.

**Evidence expected:** `evidence/R3-*` diffs and verification notes; updated folder checklists;
`firestore-tests` result note.

**Exit criteria (HARD STOP):**
1. Every one of the 116 findings has exactly one final disposition in FINDING_INDEX.
2. No Critical or High remains open (all `fixed` or `accepted`-with-justification).
3. Every trust boundary in `architecture/TRUST_BOUNDARIES.md` is marked verified, accepted, or
   runbook — none `blocked`.
4. `FINAL_SECURITY_REPORT.md` and `FINAL_SIGNOFF.md` are written.

**Done condition:** the four exit criteria hold; `FINAL_SIGNOFF.md` is signed; **the program ends —
no further round is scheduled.**

---

## What this program explicitly will NOT do

- No new vulnerability assessment, no new finding IDs, no renumbering (per the mandate).
- No fourth round, no "re-audit," no open-ended refinement loop.
- No code change outside a finding's scope; no style refactors; no renames unless a fix requires it.
- No closing a finding without evidence, and none marked "probably fixed."
- Out-of-band actions that only a console operator can perform (credential revocation, App Check
  enforcement toggle, Firestore TTL policy, GitHub branch protection) are delivered as **runbooks**
  with verification steps in `migration/MIGRATION_PLAN.md`; the engineering change that makes each
  effective ships within these three rounds.
