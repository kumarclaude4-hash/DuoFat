# MASTER CHECKLIST

One checkbox per audit finding, grouped by **governing** severity (audit re-ratings applied).
Mechanically traceable to `../audit/` via the finding ID. Every box maps to exactly one row in
[`FINDING_INDEX.md`](./FINDING_INDEX.md).

Boxes are checked when the finding reaches its final disposition **and** its verification is
complete (source and/or test). `[x]` = disposition reached & verified. Round column = the session
that closes it.

## Completion summary

| Metric | Value |
|---|---|
| Distinct findings | 116 |
| Completed (fixed / accepted / deferred, verified) | 116 |
| **Completion** | **100%** |
| Remaining Criticals | 0 |
| Remaining Highs | 0 |
| Blocked items | 0 |
| Deferred-with-justification | 0 |
| Runbook (code done here, deploy-time console step tracked in MIGRATION_PLAN) | 8 |

Severity tallies (governing): **Critical 4 · High 30 · Medium 26 · Low 33 · Informational 23**
(the +2H/−2M/−1L vs. the original 4/28/28/34/23 come from the re-ratings of `S04-M1`→H,
`S07-M1`/`S08-H5` counted at H, and `S03-H3`→H already applied; and from the 116 vs 117 count
reconciliation noted in `FINDING_INDEX.md`).

---

## Critical (4)

- [x] **S08-C1** — Admin service-account key in every APK — `fixed+runbook` — R1
- [x] **S07-C1** — `/mintToken` accepts a public value as ownership proof — `fixed` — R1
- [x] **SC-01** — Vendored libsignal JAR not reproducible / unhashed / unvalidated — `fixed` — R2
- [x] **SC-02** — Release workflow bakes backend secrets into APK — `fixed+runbook` — R1

## High (30)

- [x] **S08-H1** — `WORKER_SECRET` in `BuildConfig`, accepted on `/stats` — `fixed+runbook` — R1
- [x] **S07-H1** — mint key-check fails open when hash absent — `fixed` — R1
- [x] **S06-H1** — `accountLock` not enforced server-side — `fixed` — R1
- [x] **S03-H1** — media scope confusion via client-created `groups/{chatId}` — `fixed` — R2
- [x] **S04-H1** — SSRF predicate never resolves DNS / misses IPv6 — `fixed` — R2
- [x] **S04-H2** — `/linkPreview` unbounded body read / no timeout — `fixed` — R2
- [x] **S04-H3** — `og:image` beacon leaks recipient IP + read time — `fixed` — R2
- [x] **S08-H2** — `FLAG_SECURE` cleared app-wide — `fixed` — R2
- [x] **S08-H3** — plaintext media persists in cache — `fixed` — R2
- [x] **S08-H4** — link-preview image fetched by client from sender host — `fixed` — R2
- [x] **S08-H5** — SecurePrefs plaintext fallback holds keys + SQLCipher passphrase — `fixed` — R2
- [x] **S06-H2** — duress wipe leaves WorkManager residue — `fixed` — R2
- [x] **S06-H3** — offline duress trigger never locks — `fixed` — R2
- [x] **S05-H1** — `ADMIN_TOKEN` no entropy floor / no brute-force ceiling — `fixed+runbook` — R2
- [x] **S05-H2** — waitlist unreviewable, no deny/expire/revoke — `fixed`+`accepted` — R3
- [x] **S05-H3** — admin actions not durably audited — `fixed` — R2
- [x] **SC-04** — release APKs unverifiable (no checksums/provenance) — `fixed` — R2
- [x] **SC-05** — workflow deletes all releases and tags — `fixed` — R2
- [x] **SC-03** — no Gradle dependency verification — `fixed`+runbook — R3
- [x] **SC-06** — JitPack builds from mutable refs — `fixed` — R3
- [x] **S02-H1** — `migrateUid` verbatim user-doc field copy — `fixed` — R3
- [x] **S03-H2** — one account exhausts Worker 90K/day budget — `fixed` — R3
- [x] **S03-H3** — no per-user storage quota — `fixed` — R3
- [x] **S07-H2** — backup ships unkeyed plaintext digest — `fixed` — R3
- [x] **S07-H3** — group messages have no AAD — `fixed` — R3
- [x] **S04-M1** (gov: High) — IPv6 /64 defeats IP-keyed limits — `fixed` — R3
- [x] **S01-H1** — cross-user prekey wipe/replace — `fixed` — R3
- [x] **S01-H2** — 1:1 message content mutable on update — `fixed` — R3
- [x] **S01-H3** — partner display-name overwrite — `fixed` — R3
- [x] **S07-M1** (gov: High, = S08-H5) — SecurePrefs plaintext fallback — `fixed` — R2

## Medium (26)

- [x] **S01-M1** — group message TOCTOU + no volume cap — `accepted`+`fixed` — R3
- [x] **S01-M2** — `identities` update no field allow-list — `fixed` — R3
- [x] **S01-M3** — `backup_logs` unbounded/unvalidated — `fixed` — R3
- [x] **S01-M4** — group message delete no membership re-check — `fixed` — R3
- [x] **S02-M1** — mint cooldown stamped pre-auth — `fixed` — R1
- [x] **S03-M1** — attacker Content-Type stored/echoed — `fixed` — R3
- [x] **S03-M2** — tokens scope-bound not uploader-bound — `fixed` — R3
- [x] **S03-M3** — 10-min unrevocable bearer tokens — `fixed` — R3
- [x] **S04-M2** — 24h redistributable TURN creds — `fixed` — R3
- [x] **S04-M3** — XFF trust hard-coded to one proxy — `fixed` — R3
- [x] **S05-M1** — raw IPs/uids persisted forever — `fixed` — R3
- [x] **S05-M2** — `duressEligibility` enforced nowhere — `fixed` — R3
- [x] **S05-M3** — admin sessions unbounded/unbindable — `fixed` — R3
- [x] **S06-M1** — `duressEligibility` cached-bool only — `fixed` — R3
- [x] **S06-M2** — `_duressNonces` unbounded growth — `fixed`+runbook — R3
- [x] **S06-M3** — raw uids logged on duress endpoints — `fixed` — R3
- [x] **S07-M2** — trust keyed on mutable uid — `fixed` — R3
- [x] **S07-M3** — backup metadata outside AEAD — `fixed` — R3
- [x] **S08-M1** — native heap pointer tagging disabled — `fixed` — R3
- [x] **S08-M2** — FileProvider root-scoped grantable paths — `fixed` — R3
- [x] **S08-M3** — no root/tamper detection — `accepted` — R3
- [x] **S10-N1** — Firebase App Check absent — `accepted`+`fixed`+runbook — R3
- [x] **SC-07** — wrapper JAR unvalidated — `fixed` — R3
- [x] **SC-08** — actions on mutable tags — `fixed` — R3
- [x] **SC-09** — no scanning/SBOM/Dependabot — `fixed` — R3
- [x] **SC-10** — Firestore deploy unpinned npm/audit off — `fixed` — R3

## Low (33)

- [x] **S01-L1** — `groups` create doesn't validate `createdBy` — `fixed` — R3
- [x] **S01-L2** — `users` write no field validation — `fixed` — R3
- [x] **S02-L1** — mint hash check fails open (= S07-H1) — `fixed` — R1
- [x] **S02-L2** — createChat display names unbounded — `fixed` — R3
- [x] **S02-L3** — `mintCooldown` never purged — `fixed` — R3
- [x] **S02-L4** — collectBody counts chars not bytes (= S04-L1) — `fixed` — R3
- [x] **S03-L1** — `WORKER_SECRET` compiled in APK (= S08-H1) — `fixed` — R1
- [x] **S03-L2** — unguarded `decodeURIComponent` — `fixed` — R3
- [x] **S03-L3** — dead B2 presign code (= S04-I2) — `fixed` — R3
- [x] **S03-L4** — rejections without CORS headers — `fixed` — R3
- [x] **S04-L1** — collectBody byte count; no setEncoding — `fixed` — R3
- [x] **S04-L2** — `/duress-lock` no rate limit (= S06-L2) — `fixed` — R3
- [x] **S04-L3** — limiter state per-process/in-memory — `fixed`+`accepted` — R3
- [x] **S05-L1** — account/lookup skips `validAdminUid` — `fixed` — R3
- [x] **S05-L2** — collectBody before requireAdminAuth — `fixed` — R3
- [x] **S05-L3** — no Cache-Control on admin responses — `fixed` — R3
- [x] **S05-L4** — approve/unfreeze TOCTOU; unbounded get — `fixed` — R3
- [x] **S06-L1** — nonce expiry fails open on malformed field — `fixed` — R3
- [x] **S06-L2** — `/duress-lock` unauthenticated no rate limit — `fixed` — R3
- [x] **S06-L3** — `_duressNonces` no rules-test coverage — `fixed` — R3
- [x] **S06-L4** — AccountLockWorker reports failure as success — `fixed` — R3
- [x] **S07-L1** — `fetchGroupKey` creator check fails open — `fixed` — R3
- [x] **S07-L2** — static derivationCache survives duress wipe — `fixed` — R3
- [x] **S07-L3** — mnemonic canonicalization/Locale — `fixed` — R3
- [x] **S07-L4** — `loadSession` silent fresh-session substitution — `fixed` — R3
- [x] **S08-L1** — deep link accepts unvalidated Account ID — `fixed` — R3
- [x] **S08-L2** — clipboard writes without `EXTRA_IS_SENSITIVE` — `fixed` — R3
- [x] **S08-L3** — PIN length stored beside PIN hash — `fixed` — R3
- [x] **S08-L4** — lock screen over rendered activity, in recents — `fixed` — R3
- [x] **S10-N2** — peer uid in release logcat — `fixed` — R2
- [x] **S10-N3** — deleted media survives B2 cold tier on race — `fixed` — R2
- [x] **SC-11** — production crypto on alpha library — `accepted` — R3
- [x] **SC-12** — branch protection unverified — `fixed+runbook` — R1

## Informational (23)

- [x] **S01-I1** — global read oracle on users/identities — `accepted` (ratified) — R3
- [x] **S01-I2** — systemic get()-based authz TOCTOU — `accepted` — R3
- [x] **S02-I1** — cold-contact/registration oracle — `accepted` — R3
- [x] **S02-I2** — in-memory limiters best-effort — `accepted`+`fixed` — R3
- [x] **S02-I3** — no `checkRevoked` — `fixed`+`accepted` — R1/R3
- [x] **S03-I1** — Worker holds bucket-wide B2 creds — `accepted` — R3
- [x] **S03-I2** — Worker accounting advisory — `fixed` — R3
- [x] **S03-I3** — `/mediaToken` membership oracle — `accepted` — R3
- [x] **S04-I1** — `/status` `/` unauthenticated counters — `fixed` — R3
- [x] **S04-I2** — dead B2 presign surface, live creds — `fixed+runbook` — R2
- [x] **S04-I3** — preview provenance indistinguishable — `fixed` — R3
- [x] **S05-I1** — operator secrets undocumented — `fixed` — R2
- [x] **S05-I2** — stale admin comments — `fixed` — R3
- [x] **S05-I3** — CSRF/Secure-flag/length-oracle — `fixed` — R3
- [x] **S06-I1** — rules comment contradicts unfreeze — `fixed` — R3
- [x] **S06-I2** — step 1a can't tell success from failure — `fixed` — R2
- [x] **S06-I3** — PIN strength bounded by PIN space — `accepted` — R3
- [x] **S07-I1** — SenderKeyStore stub — `accepted` — R3
- [x] **S07-I2** — no add-member flow; key-less add — `accepted` — R3
- [x] **S07-I3** — Account ID 64-bit / dual-purpose — `accepted` — R3
- [x] **S08-I1** — R8 keeps crypto member names — `fixed` — R3
- [x] **S08-I2** — no certificate pinning — `accepted` — R3
- [x] **S08-I3** — Worker `ACAO:*` with Authorization — `fixed` — R3

---

## Dependencies (must-fix-before)

- `S07-C1` fix depends on `S07-H1`/`S02-L1` (store full pubkey, fail closed) — one code path, R1.
- `S06-H1` (server-side lock) shares the `/mintToken` transaction with `S07-C1` — done together R1.
- `S03-H1` fix (typed scope) precedes `S03-H2`/`S03-H3` (per-user quotas keyed on holder) — R2→R3.
- `SC-02`/`S08-C1` credential rotation (runbook) depends on the code stop-shipping-secrets change
  landing first (R1), else rotation regenerates a leaked secret.
- `SC-01` hash-assert in CI depends on making the strip script reproducible first — same round R2.
- `S04-H3` (server proxy) and `S08-H4` (client block) are two halves of one fix — R2.
