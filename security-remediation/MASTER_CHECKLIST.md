# MASTER CHECKLIST

One checkbox per audit finding, grouped by **governing** severity (audit re-ratings applied).
Mechanically traceable to `../audit/` via the finding ID. Every box maps to exactly one row in
[`FINDING_INDEX.md`](./FINDING_INDEX.md).

A box is checked only when **all three** hold: the finding has reached its final disposition, its
verification is complete (source and/or test), and its evidence artifact exists under `evidence/`.
Round column = the session that closes it.

The `plan` tag on each row is the **intended** disposition, not an achieved one.

> **Reset notice (2026-08-07).** Every box in this file was previously checked, asserting 100%
> completion, while source inspection proved that no remediation code had been written. All 116 boxes
> were reset during the reconciliation pass — see [`RECONCILIATION.md`](./RECONCILIATION.md) §2 for
> the six source proofs.
>
> Compound dispositions such as `` `fixed`+`accepted` `` were also normalized. The program rule is
> **exactly one** terminal disposition per finding, so the governing disposition is recorded here and
> any accepted remainder is carried as a numbered residual risk (`RR-xx`) in
> [`RISK_REGISTER.md`](./RISK_REGISTER.md).

## Completion summary

Source-verified as of 2026-08-07. Rounds 1–3 are **Not Started**.

| Metric | Value |
|---|---|
| Distinct findings | 116 |
| Completed (fixed / accepted / deferred, verified) | **0** |
| **Completion** | **0%** |
| Remaining Criticals | **4** — `S07-C1`, `S08-C1`, `SC-01`, `SC-02` |
| Remaining Highs | **30** |
| Blocked items | 0 — no finding lacks the information needed to remediate it |
| Deferred-with-justification | 0 |
| Planned runbook items (code here + deploy-time step in `migration/MIGRATION_PLAN.md`) | 8 |

Severity tallies (governing): **Critical 4 · High 30 · Medium 26 · Low 33 · Informational 23**
(the +2H/−2M/−1L vs. the original 4/28/28/34/23 come from the re-ratings of `S04-M1`→H,
`S07-M1`/`S08-H5` counted at H, and `S03-H3`→H already applied; and from the 116 vs 117 count
reconciliation noted in `FINDING_INDEX.md`).

---

## Critical (4)

- [ ] **S08-C1** — Admin service-account key in every APK — plan `fixed+runbook` — R1
- [ ] **S07-C1** — `/mintToken` accepts a public value as ownership proof — plan `fixed` — R1
- [ ] **SC-01** — Vendored libsignal JAR not reproducible / unhashed / unvalidated — plan `fixed` — R2
- [ ] **SC-02** — Release workflow bakes backend secrets into APK — plan `fixed+runbook` — R1

## High (30)

- [ ] **S08-H1** — `WORKER_SECRET` in `BuildConfig`, accepted on `/stats` — plan `fixed+runbook` — R1
- [ ] **S07-H1** — mint key-check fails open when hash absent — plan `fixed` — R1
- [ ] **S06-H1** — `accountLock` not enforced server-side — plan `fixed` — R1
- [ ] **S03-H1** — media scope confusion via client-created `groups/{chatId}` — plan `fixed` — R2
- [ ] **S04-H1** — SSRF predicate never resolves DNS / misses IPv6 — plan `fixed` — R2
- [ ] **S04-H2** — `/linkPreview` unbounded body read / no timeout — plan `fixed` — R2
- [ ] **S04-H3** — `og:image` beacon leaks recipient IP + read time — plan `fixed` — R2
- [ ] **S08-H2** — `FLAG_SECURE` cleared app-wide — plan `fixed` — R2
- [ ] **S08-H3** — plaintext media persists in cache — plan `fixed` — R2
- [ ] **S08-H4** — link-preview image fetched by client from sender host — plan `fixed` — R2
- [ ] **S08-H5** — SecurePrefs plaintext fallback holds keys + SQLCipher passphrase — plan `fixed` — R2
- [ ] **S06-H2** — duress wipe leaves WorkManager residue — plan `fixed` — R2
- [ ] **S06-H3** — offline duress trigger never locks — plan `fixed` — R2
- [ ] **S05-H1** — `ADMIN_TOKEN` no entropy floor / no brute-force ceiling — plan `fixed+runbook` — R2
- [ ] **S05-H2** — waitlist unreviewable, no deny/expire/revoke — plan `fixed` (residual: RR-04) — R3
- [ ] **S05-H3** — admin actions not durably audited — plan `fixed` — R2
- [ ] **SC-04** — release APKs unverifiable (no checksums/provenance) — plan `fixed` — R2
- [ ] **SC-05** — workflow deletes all releases and tags — plan `fixed` — R2
- [ ] **SC-03** — no Gradle dependency verification — plan `fixed+runbook` — R3
- [ ] **SC-06** — JitPack builds from mutable refs — plan `fixed` — R3
- [ ] **S02-H1** — `migrateUid` verbatim user-doc field copy — plan `fixed` — R3
- [ ] **S03-H2** — one account exhausts Worker 90K/day budget — plan `fixed` — R3
- [ ] **S03-H3** — no per-user storage quota — plan `fixed` — R3
- [ ] **S07-H2** — backup ships unkeyed plaintext digest — plan `fixed` — R3
- [ ] **S07-H3** — group messages have no AAD — plan `fixed` — R3
- [ ] **S04-M1** (gov: High) — IPv6 /64 defeats IP-keyed limits — plan `fixed` — R3
- [ ] **S01-H1** — cross-user prekey wipe/replace — plan `fixed` — R3
- [ ] **S01-H2** — 1:1 message content mutable on update — plan `fixed` — R3
- [ ] **S01-H3** — partner display-name overwrite — plan `fixed` — R3
- [ ] **S07-M1** (gov: High, = S08-H5) — SecurePrefs plaintext fallback — plan `fixed` — R2

## Medium (26)

- [ ] **S01-M1** — group message TOCTOU + no volume cap — plan `fixed` (residual: RR-01) — R3
- [ ] **S01-M2** — `identities` update no field allow-list — plan `fixed` — R3
- [ ] **S01-M3** — `backup_logs` unbounded/unvalidated — plan `fixed` — R3
- [ ] **S01-M4** — group message delete no membership re-check — plan `fixed` — R3
- [ ] **S02-M1** — mint cooldown stamped pre-auth — plan `fixed` — R1
- [ ] **S03-M1** — attacker Content-Type stored/echoed — plan `fixed` — R3
- [ ] **S03-M2** — tokens scope-bound not uploader-bound — plan `fixed` — R3
- [ ] **S03-M3** — 10-min unrevocable bearer tokens — plan `fixed` — R3
- [ ] **S04-M2** — 24h redistributable TURN creds — plan `fixed` — R3
- [ ] **S04-M3** — XFF trust hard-coded to one proxy — plan `fixed` — R3
- [ ] **S05-M1** — raw IPs/uids persisted forever — plan `fixed` — R3
- [ ] **S05-M2** — `duressEligibility` enforced nowhere — plan `fixed` — R3
- [ ] **S05-M3** — admin sessions unbounded/unbindable — plan `fixed` — R3
- [ ] **S06-M1** — `duressEligibility` cached-bool only — plan `fixed` — R3
- [ ] **S06-M2** — `_duressNonces` unbounded growth — plan `fixed+runbook` — R3
- [ ] **S06-M3** — raw uids logged on duress endpoints — plan `fixed` — R3
- [ ] **S07-M2** — trust keyed on mutable uid — plan `fixed` — R3
- [ ] **S07-M3** — backup metadata outside AEAD — plan `fixed` — R3
- [ ] **S08-M1** — native heap pointer tagging disabled — plan `fixed` — R3
- [ ] **S08-M2** — FileProvider root-scoped grantable paths — plan `fixed` — R3
- [ ] **S08-M3** — no root/tamper detection — plan `accepted` — R3
- [ ] **S10-N1** — Firebase App Check absent — plan `fixed+runbook` (residual: RR-05) — R3
- [ ] **SC-07** — wrapper JAR unvalidated — plan `fixed` — R3
- [ ] **SC-08** — actions on mutable tags — plan `fixed` — R3
- [ ] **SC-09** — no scanning/SBOM/Dependabot — plan `fixed` — R3
- [ ] **SC-10** — Firestore deploy unpinned npm/audit off — plan `fixed` — R3

## Low (33)

- [ ] **S01-L1** — `groups` create doesn't validate `createdBy` — plan `fixed` — R3
- [ ] **S01-L2** — `users` write no field validation — plan `fixed` — R3
- [ ] **S02-L1** — mint hash check fails open (= S07-H1) — plan `fixed` — R1
- [ ] **S02-L2** — createChat display names unbounded — plan `fixed` — R3
- [ ] **S02-L3** — `mintCooldown` never purged — plan `fixed` — R3
- [ ] **S02-L4** — collectBody counts chars not bytes (= S04-L1) — plan `fixed` — R3
- [ ] **S03-L1** — `WORKER_SECRET` compiled in APK (= S08-H1) — plan `fixed` — R1
- [ ] **S03-L2** — unguarded `decodeURIComponent` — plan `fixed` — R3
- [ ] **S03-L3** — dead B2 presign code (= S04-I2) — plan `fixed` — R3
- [ ] **S03-L4** — rejections without CORS headers — plan `fixed` — R3
- [ ] **S04-L1** — collectBody byte count; no setEncoding — plan `fixed` — R3
- [ ] **S04-L2** — `/duress-lock` no rate limit (= S06-L2) — plan `fixed` — R3
- [ ] **S04-L3** — limiter state per-process/in-memory — plan `fixed` (residual: RR-02) — R3
- [ ] **S05-L1** — account/lookup skips `validAdminUid` — plan `fixed` — R3
- [ ] **S05-L2** — collectBody before requireAdminAuth — plan `fixed` — R3
- [ ] **S05-L3** — no Cache-Control on admin responses — plan `fixed` — R3
- [ ] **S05-L4** — approve/unfreeze TOCTOU; unbounded get — plan `fixed` — R3
- [ ] **S06-L1** — nonce expiry fails open on malformed field — plan `fixed` — R3
- [ ] **S06-L2** — `/duress-lock` unauthenticated no rate limit — plan `fixed` — R3
- [ ] **S06-L3** — `_duressNonces` no rules-test coverage — plan `fixed` — R3
- [ ] **S06-L4** — AccountLockWorker reports failure as success — plan `fixed` — R3
- [ ] **S07-L1** — `fetchGroupKey` creator check fails open — plan `fixed` — R3
- [ ] **S07-L2** — static derivationCache survives duress wipe — plan `fixed` — R3
- [ ] **S07-L3** — mnemonic canonicalization/Locale — plan `fixed` — R3
- [ ] **S07-L4** — `loadSession` silent fresh-session substitution — plan `fixed` — R3
- [ ] **S08-L1** — deep link accepts unvalidated Account ID — plan `fixed` — R3
- [ ] **S08-L2** — clipboard writes without `EXTRA_IS_SENSITIVE` — plan `fixed` — R3
- [ ] **S08-L3** — PIN length stored beside PIN hash — plan `fixed` — R3
- [ ] **S08-L4** — lock screen over rendered activity, in recents — plan `fixed` — R3
- [ ] **S10-N2** — peer uid in release logcat — plan `fixed` — R2
- [ ] **S10-N3** — deleted media survives B2 cold tier on race — plan `fixed` — R2
- [ ] **SC-11** — production crypto on alpha library — plan `accepted` — R3
- [ ] **SC-12** — branch protection unverified — plan `fixed+runbook` — R1

## Informational (23)

- [ ] **S01-I1** — global read oracle on users/identities — plan `accepted` (residual: RR-03) — R3
- [ ] **S01-I2** — systemic get()-based authz TOCTOU — plan `accepted` — R3
- [ ] **S02-I1** — cold-contact/registration oracle — plan `accepted` — R3
- [ ] **S02-I2** — in-memory limiters best-effort — plan `fixed` (residual: RR-02) — R3
- [ ] **S02-I3** — no `checkRevoked` — plan `fixed` (residual: RR-06) — R1
- [ ] **S03-I1** — Worker holds bucket-wide B2 creds — plan `accepted` — R3
- [ ] **S03-I2** — Worker accounting advisory — plan `fixed` — R3
- [ ] **S03-I3** — `/mediaToken` membership oracle — plan `accepted` — R3
- [ ] **S04-I1** — `/status` `/` unauthenticated counters — plan `fixed` — R3
- [ ] **S04-I2** — dead B2 presign surface, live creds — plan `fixed+runbook` — R2
- [ ] **S04-I3** — preview provenance indistinguishable — plan `fixed` — R3
- [ ] **S05-I1** — operator secrets undocumented — plan `fixed` — R2
- [ ] **S05-I2** — stale admin comments — plan `fixed` — R3
- [ ] **S05-I3** — CSRF/Secure-flag/length-oracle — plan `fixed` — R3
- [ ] **S06-I1** — rules comment contradicts unfreeze — plan `fixed` — R3
- [ ] **S06-I2** — step 1a can't tell success from failure — plan `fixed` — R2
- [ ] **S06-I3** — PIN strength bounded by PIN space — plan `accepted` — R3
- [ ] **S07-I1** — SenderKeyStore stub — plan `accepted` — R3
- [ ] **S07-I2** — no add-member flow; key-less add — plan `accepted` — R3
- [ ] **S07-I3** — Account ID 64-bit / dual-purpose — plan `accepted` — R3
- [ ] **S08-I1** — R8 keeps crypto member names — plan `fixed` — R3
- [ ] **S08-I2** — no certificate pinning — plan `accepted` — R3
- [ ] **S08-I3** — Worker `ACAO:*` with Authorization — plan `fixed` — R3

---

## Dependencies (must-fix-before)

- `S07-C1` fix depends on `S07-H1`/`S02-L1` (store full pubkey, fail closed) — one code path, R1.
- `S06-H1` (server-side lock) shares the `/mintToken` transaction with `S07-C1` — done together R1.
- `S03-H1` fix (typed scope) precedes `S03-H2`/`S03-H3` (per-user quotas keyed on holder) — R2→R3.
- `SC-02`/`S08-C1` credential rotation (runbook) depends on the code stop-shipping-secrets change
  landing first (R1), else rotation regenerates a leaked secret.
- `SC-01` hash-assert in CI depends on making the strip script reproducible first — same round R2.
- `S04-H3` (server proxy) and `S08-H4` (client block) are two halves of one fix — R2.
