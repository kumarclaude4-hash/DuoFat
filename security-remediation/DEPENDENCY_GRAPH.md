# DEPENDENCY GRAPH — the real execution order

Derives the **actual** remediation order from finding interdependencies. Where this document and
document order disagree, **this document wins**: execution follows dependency order, not the order
findings happen to appear in the index.

Derived from `../audit/SESSION-10-SYNTHESIS.md` §8 (P0→P1→P2) plus the coupling analysis below.

---

## 1. P0 ordering rule

Findings are sequenced by *what they invalidate*, not by severity number alone. A fix is P0 when
leaving it open makes other controls moot.

| Rank | Class | Why it must go first | Findings |
|---|---|---|---|
| 1 | **Credential exposure / secret leakage** | Every other control is theatre while the admin service-account key and backend secret set ship inside the APK. An attacker with the GCP service-account key bypasses Firestore rules entirely, so all 11 `S01-*` rules fixes are worthless until this closes. | `S08-C1`, `SC-02`, `S08-H1`, `S03-L1` |
| 2 | **Authentication / authorization bypass** | `/mintToken` accepts a public value as proof of private-key ownership — full account takeover without the seed. Defeats all per-user authorization downstream. | `S07-C1`, `S07-H1`, `S02-L1` |
| 3 | **Trust-boundary violation** | `accountLock` is never enforced at the mint boundary, so lockout (including duress lock) is advisory only. | `S06-H1`, `S02-I3`, `S02-M1` |
| 4 | **Controls-invalidating features** | Unenforced branch protection means any remediation can be silently reverted. | `SC-12` |

Rank 1 strictly precedes rank 2: fixing mint auth while the service-account key still ships leaves
the takeover reachable by a different path.

## 2. Hard ordering constraints

Each edge is a **must-precede** relation. Violating one produces a fix that is either ineffective or
actively harmful.

```
CODE-BEFORE-ROTATION  (the critical edge)
  S08-C1 + SC-02  (stop emitting secrets into the build)
        │
        ▼
  ROTATE credentials  (GCP SA key, B2 key pair, WORKER_SECRET)
        │  runbook step, migration/MIGRATION_PLAN.md
        ▼
  S08-H1 / S03-L1 verification  (no secret in APK to find)

MINT AUTH  (single code path — one transaction)
  S07-H1 (fail closed on absent hash) ─┐
  S02-L1 (same defect, dup)           ├─► S07-C1 (signature challenge)
  S06-H1 (accountLock in transaction) ─┘        │
                                                ▼
                                          S02-M1 (cooldown stamped post-auth)
                                                │
                                                ▼
                                          S02-I3 (revocation honoured at mint)

MEDIA SCOPE
  S03-H1 (typed scope; kill self-asserted groups/{chatId} membership)
        │
        ├─► S03-H2 (per-user rate bucket — must key on a trustworthy holder)
        ├─► S03-H3 (per-user storage quota — same)
        ├─► S03-M2 (uploader-bound tokens)
        └─► S03-I3 (oracle bounded only once scope is typed)

LINK PREVIEW  (two halves of one fix — land together)
  S04-H3 (server-side proxy)  ⟷  S08-H4 (client stops direct fetch)
  S04-H1 (SSRF predicate) ─► S04-H2 (body cap + timeout) ─► S04-H3
  S04-M1 (IPv6 /64 normalization) ─► every IP-keyed limiter incl. S05-H1

REPRODUCIBLE BUILD
  SC-01a (make strip script deterministic)
        ▼
  SC-01b (assert JAR hash in CI)   ← cannot precede SC-01a
  SC-05 (stop deleting releases) ─► SC-04 (checksums/provenance are pointless
                                    if the release is deleted next push)

SECUREPREFS  (one change closes both IDs)
  S08-H5 ≡ S07-M1  (same defect: plaintext fallback holds keys + SQLCipher passphrase)
        ▼
  S07-L2 (static derivationCache survives wipe) — same storage-lifecycle area

DURESS
  S06-H3 (durable offline intent) ─► S06-I2 (success/failure now distinguishable)
  S06-H2 (WorkManager residue) ─── independent of S06-H3, may run in parallel
  S05-M2 ⟷ S06-M1  (duressEligibility unenforced — server + client halves)

ADMIN
  S04-M1 (IP normalization) ─► S05-H1 (brute-force ceiling actually holds)
  S05-H1 ─► ROTATE ADMIN_TOKEN (runbook)
  S05-L2 (collectBody before auth) ─► S05-H3 (audit log records authentic actors)

RULES  (all gated on rank 1 — a leaked SA key bypasses rules entirely)
  S08-C1/SC-02 ─► { S01-H1, S01-H2, S01-H3, S01-M1..M4, S01-L1, S01-L2 }
  S02-H1 (migrateUid field copy) ─► S07-M2 (trust keyed on mutable uid)
```

## 3. Architectural change required before code change

These four cannot be fixed by a local edit; the design decision must be settled first, and each has a
`decisions/DECISION-LOG.md` entry.

| Finding | Architectural precondition | Decision |
|---|---|---|
| `S07-C1` | Choose the ownership proof: signature-over-challenge vs. seed-derived secret. Changes the client/server contract and needs a migration path for existing accounts. | `D-001` |
| `S03-H1` | Define a **typed** media scope (`dm:` / `group:`) so a client-created doc can no longer self-assert membership. Changes token format. | `D-003` |
| `S04-H3` | Server-side image proxy: accept the bandwidth/cost change, or drop previews. | `D-004` |
| `S07-H3` | Group AAD binds sender identity into the ciphertext — a wire-format change requiring a compatibility window. | `D-006` |

## 4. Parallelizable work

No shared files, no ordering relation — safe to execute concurrently within their round.

| Group | Findings | Shared surface |
|---|---|---|
| Firestore rules | `S01-H1`, `S01-H2`, `S01-H3`, `S01-M2`, `S01-M3`, `S01-M4`, `S01-L1`, `S01-L2` | `firestore.rules` only — one file, but independent rule blocks |
| Client platform hardening | `S08-M1`, `S08-M2`, `S08-L1`, `S08-L2`, `S08-L3`, `S08-L4`, `S08-I1` | distinct Android surfaces |
| CI hygiene | `SC-07`, `SC-08`, `SC-09`, `SC-10` | workflow files, no runtime coupling |
| Log redaction | `S05-M1`, `S06-M3`, `S10-N2` | independent call sites |
| Worker robustness | `S03-L2`, `S03-L4`, `S08-I3` | `worker/src/index.js`, independent handlers |

## 5. Shared trust boundaries

Findings touching the same boundary must be verified **together**, because fixing one can shift what
another was ordering against.

| Boundary | Findings | Joint-verification requirement |
|---|---|---|
| **TB-1** server↔client identity | `S07-C1`, `S07-H1`, `S02-L1`, `S02-M1`, `S06-H1`, `S02-I3`, `S06-L1` | Re-derive the whole `/mintToken` transaction after every edit; the checks are order-dependent. |
| **TB-2** Firestore rules | all `S01-*`, `S07-H3`, `S07-M2`, `S06-L3` | Run the full `firestore-tests/` suite; rules changes interact. |
| **TB-3** server egress/limits | `S04-*`, `S02-L3`, `S02-L4` | `S04-M1` changes the limiter key — every limiter test must be re-run. |
| **TB-4** media capability | `S03-*` | Token format change (`D-003`) invalidates cached tokens; verify old-token rejection. |
| **TB-5** admin | `S05-*`, `S04-M1` | Auth ordering (`S05-L2`) must be verified before trusting the audit log (`S05-H3`). |
| **TB-8** storage credentials | `S03-I1`, `S04-I2`, `SC-02` | Verify no B2 credential remains reachable from the client after rotation. |
| **TB-9 / Theme D** build-time secrets | `S08-C1`, `SC-02`, `S08-H1`, `S03-L1` | Verify by unzipping a built APK, not by reading the workflow. |

## 6. Duplicate-pair register

Findings the audit records separately that share a single root cause. Both IDs are retained and both
receive their own disposition; one code change closes both. Neither may be marked closed without the
other.

| Pair | Shared root cause | Round |
|---|---|---|
| `S07-H1` ≡ `S02-L1` | mint key-check fails open when stored hash falsy | R1 |
| `S08-H1` ≡ `S03-L1` | `WORKER_SECRET` compiled into the APK | R1 |
| `S08-H5` ≡ `S07-M1` | SecurePrefs silent plaintext fallback | R2 |
| `S04-L1` ≡ `S02-L4` | `collectBody` counts chars, not bytes | R3 |
| `S04-L2` ≡ `S06-L2` | `/duress-lock` unauthenticated, unlimited | R3 |
| `S04-I2` ≡ `S03-L3` | dead B2 presign surface with live credentials | R2/R3 |
| `S05-M2` ≡ `S06-M1` | `duressEligibility` enforced nowhere | R3 |
| `S04-L3` ≡ `S02-I2` | limiter state per-process/in-memory | R3 |

## 7. Resulting execution order

```
R1 ── 1. S08-C1, SC-02          stop emitting secrets        [rank 1]
      2. S08-H1, S03-L1         remove client secret plumbing
      3. ROTATE                 GCP SA, B2, WORKER_SECRET    [runbook, after 1-2]
      4. S07-H1, S02-L1         fail closed
      5. S07-C1                 signature challenge          [rank 2]
      6. S06-H1                 accountLock in transaction   [rank 3]
      7. S02-M1, S02-I3         cooldown post-auth, revocation
      8. SC-12                  branch protection            [rank 4]

R2 ── 9.  SC-05 → SC-04         non-destructive, verifiable releases
      10. SC-01a → SC-01b       reproducible then asserted JAR
      11. S04-H1 → S04-H2 → S04-H3 ⟷ S08-H4   egress chain
      12. S03-H1                typed media scope
      13. S08-H5 ≡ S07-M1       SecurePrefs fallback
      14. S06-H3 → S06-I2 ; S06-H2 (parallel)  duress
      15. S05-H1 (after S04-M1 if pulled fwd), S05-H3, S05-I1
      16. S08-H2, S08-H3, S10-N2, S10-N3, S04-I2

R3 ── 17. S04-M1                limiter key normalization (unblocks admin ceiling)
      18. all S01-* rules       (now meaningful — secrets no longer leak)
      19. S03-H2, S03-H3, S03-M*  per-user quotas (need typed scope from R2)
      20. S07-H2, S07-H3, S07-M2, S07-M3   crypto integrity
      21. S02-H1, S05-*, S06-*, S08-*, SC-03/06/07/08/09/10, S10-N1
      22. remaining L/I + all `accepted` ratifications
      ── HARD STOP ──
```

**No Round 4 exists.** After R3, the program reconciles `../BUG_TRACKER.md` to a final state, writes
`FINAL_SIGNOFF.md` and `RELEASE_SIGNOFF.md`, and ends.
