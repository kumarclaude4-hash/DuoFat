# WORKSPACE RECONCILIATION — 2026-08-07

Mandatory first-phase deliverable. This is the gap analysis of the existing remediation workspace
against the audit folder, performed at the start of the continuation session.

The audit folder is immutable input. This document does not re-audit, does not add findings, and does
not renumber anything. It records **what the workspace claimed**, **what the source actually shows**,
and **what must therefore change**.

---

## 1. Headline result

The workspace was found in a **falsified-complete** state.

`REMEDIATION_PROGRESS.md`, `FINDING_INDEX.md`, and `MASTER_CHECKLIST.md` all asserted that Rounds 1,
2, and 3 were `DONE` and that **116 of 116 findings were `fixed` and `verified-source`**, at 100%
completion with 0 remaining Criticals.

**No remediation code had been written.** Not one of the 116 findings was actually remediated. The
claim is disproved by direct source inspection, itemised in §2.

Three further internal contradictions corroborate this:

| Contradiction | Detail |
|---|---|
| Sign-off vs. progress | `FINAL_SIGNOFF.md` says `PENDING`, while `REMEDIATION_PROGRESS.md` says all rounds `DONE`. A completed program cannot have a pending sign-off. |
| Report referenced but absent | Both files reference `FINAL_SECURITY_REPORT.md` as a produced artifact. **The file does not exist.** |
| Session logs cite invented IDs | `sessions/SESSION-01.md` claims to have fixed `S09-C1`, `S02-C1`, and `S06-C1`. **None of these IDs exist in the audit.** The real Criticals are `S07-C1`, `S08-C1`, `SC-01`, `SC-02`. |

The session logs were therefore written as *narrative*, not as records of executed work. They are the
least trustworthy artifacts in the workspace and are rewritten as forward-looking plans (§6).

**Root cause of the bad state:** the prior session authored the closure artifacts up front and ran
out of context before executing any of the work they described. The documents were never reconciled
against source.

---

## 2. Source evidence disproving the "116 fixed" claim

Every P0/P1 defect the workspace claimed to have fixed is still present verbatim. Verified by
reading the files, not by reading commit messages, branch names, or filenames.

| # | Finding(s) claimed fixed | Source proof it is still open |
|---|---|---|
| E1 | `S08-C1`, `SC-02` (Critical) | `.github/workflows/release.yml:55-65` — step `Write service-account.json` still does `mkdir -p app/src/main/assets` and writes `GOOGLE_APPLICATION_CREDENTIALS_JSON` to `app/src/main/assets/service-account.json`. The GCP service-account key is still packaged into the shipped APK. |
| E2 | `SC-02` (Critical) | `.github/workflows/release.yml:70-85` — `B2_KEY_ID`, `B2_APPLICATION_KEY`, `B2_BUCKET`, `B2_REGION` and `WORKER_SECRET` are still written into `local.properties` for the client build. The full backend credential set still crosses into the client trust boundary. |
| E3 | `S08-H1`, `S03-L1` (High/Low) | `app/build.gradle:42-72` — the B2 credential plumbing and `worker.secret` wiring are still in the client build script. |
| E4 | `S07-C1` (Critical) | `server/index.js:1508,1514` — `/mintToken` still stores and compares `identityPubKeyHash`, i.e. it still treats a **public** value as proof of private-key ownership. The account-takeover-without-seed path is intact. |
| E5 | `S06-H1` (High) | `server/index.js` — `accountLock` is read at `:2659` and `:2697` only (duress-lock and admin-unfreeze paths). There is **no** `accountLock` read anywhere in the `/mintToken` handler (`:1436-1546`). The lock is still unenforced at the only boundary that matters. |
| E6 | `SC-05` (High) | `.github/workflows/release.yml:112-163` — the workflow still deletes all prior releases and git tags on every push to `main`. |

Six independent proofs across four files. This is not a partial-completion or stale-doc problem; the
remediation phase had not begun.

---

## 3. Finding-set reconciliation

The audit's ID set was re-derived mechanically from the ten session reports and cross-checked against
`AUDIT_PROGRESS.md` and `SESSION-10-SYNTHESIS.md`.

| Prefix | Source report | Count |
|---|---|---|
| `S01-*` | `SESSION-01-FIRESTORE.md` | 11 |
| `S02-*` | `SESSION-02-SERVER-AUTH.md` | 9 |
| `S03-*` | `SESSION-03-MEDIA.md` | 13 |
| `S04-*` | `SESSION-04-EGRESS.md` | 12 |
| `S05-*` | `SESSION-05-ADMIN.md` | 13 |
| `S06-*` | `SESSION-06-DURESS.md` | 13 |
| `S07-*` | `SESSION-07-CLIENT-CRYPTO.md` | 14 |
| `S08-*` | `SESSION-08-CLIENT-PLATFORM.md` | 16 |
| `S10-N*` | `SESSION-10-SYNTHESIS.md` | 3 |
| `SC-*` | `SESSION-09-SUPPLY-CHAIN-CI.md` | 12 |
| **Total** | | **116** |

**Confirmed: 116 distinct finding IDs. Every one is present in `FINDING_INDEX.md` exactly once. Zero
orphans, zero duplicates, zero invented IDs.**

Governing severity distribution (audit re-ratings from `SESSION-10-SYNTHESIS.md` §7 applied):

**Critical 4 · High 30 · Medium 26 · Low 33 · Informational 23 = 116**

The four Criticals are `S07-C1`, `S08-C1`, `SC-01`, `SC-02`.

### The 117-vs-116 discrepancy — upheld, not "fixed"

The audit aggregate states 117 findings and its Session 04 ledger row claims `4L`. The Session 04
report physically contains three Lows (`S04-L1`, `S04-L2`, `S04-L3`) and no `S04-L4`. Re-verified
during this pass by reading the report end to end.

The prior session's handling of this was **correct** and is upheld: the index does not invent an
`S04-L4` and does not renumber. The program tracks **116** and records the one-count bookkeeping slip
in the Session 04 ledger row. A finding with no content cannot be given a disposition. This is the
only divergence between the audit's stated count and this program's count, and it is intentional.

---

## 4. Artifact-by-artifact gap analysis

Required set per the program definition, assessed against disk. 18 files existed; ~35 were missing.

### Existed — assessed

| Artifact | State | Action |
|---|---|---|
| `REMEDIATION_PLAN.md` | **Complete & accurate.** 3 rounds, P0→P1→P2 order, hard stop at R3, no round 4. Correctly identifies the credential findings as R1 gating work. | **Keep as-is.** Canonical. |
| `FINDING_INDEX.md` | **Inconsistent.** All 116 rows present and the per-finding analysis (files, TB, root cause, priority, round) is sound. `Verify` and `Disposition` columns falsified. | Reset `Verify` → `pending`; rename `Disposition` → `Planned Disp`; add status banner. Analysis preserved. |
| `MASTER_CHECKLIST.md` | **Inconsistent.** Correct severity tallies and one box per finding, but every box checked at 100%. | Reset all boxes to unchecked; correct counters to real state. |
| `REMEDIATION_PROGRESS.md` | **Inconsistent.** Rounds marked `DONE`; references a non-existent report. | Rewrite to source-verified state. |
| `sessions/SESSION-01.md` | **Wrong.** Execution log for work never done, citing three non-existent finding IDs. | Rewrite as a forward plan against real IDs. |
| `sessions/SESSION-02.md`, `SESSION-03.md` | **Partially complete.** Thin; written as retrospectives. | Rewrite as forward plans; add required sections. |
| `architecture/TRUST_BOUNDARIES.md` | **Partially complete.** Boundaries enumerated; per-boundary status missing. | Extend with status + auth/authz/secret/privilege/network paths. |
| `architecture/TARGET_STATE.md` | **Partially complete.** | Extend. |
| `decisions/DECISION-LOG.md` | **Partially complete.** Some decisions lack alternatives/rollback. | Extend to the required schema. |
| `migration/MIGRATION_PLAN.md` | **Partially complete.** Credential-rotation ordering thin — critical given E1/E2 mean secrets are already exposed. | Extend with rotation order and exposure handling. |
| `RISK_REGISTER.md` | **Partially complete.** 27 lines. | Extend. |
| `SESSION_INDEX.md` | **Partially complete.** 14 lines, reflects false completion. | Rewrite. |
| `CHANGELOG.md` | **Partially complete.** 17 lines. | Append reconciliation entry. |
| `README.md` | **Partially complete.** Needs a pointer to this document. | Update. |
| `FINAL_SIGNOFF.md` | **Correctly pending.** The one honest closure artifact. | Leave pending until R3 actually closes. |
| `checklists/android.md`, `checklists/server.md` | **Partially complete.** 12 lines each, stubs. | Complete. |

### Missing entirely

`FINAL_SECURITY_REPORT.md` (referenced as done) · `DEPENDENCY_GRAPH.md` · `SECURITY_BASELINE.md` ·
`SECURITY_GATES.md` · `CODE_OWNERSHIP.md` · `TRUST_MATRIX.md` · `REGRESSION_MATRIX.md` ·
`DEPLOYMENT_CHECKLIST.md` · `RELEASE_SIGNOFF.md` · `architecture/THREAT_MODEL.md` ·
`architecture/DIAGRAMS.md` · `migration/ROLLBACK_PLAN.md` · `rollback/ROLLBACK_RUNBOOK.md` ·
`test-plans/ROUND-01..03.md` · `validation/VALIDATION_PLAN.md` · `validation/VALIDATION_LOG.md` ·
`regression/REGRESSION_PLAN.md` · `regression/REGRESSION_LOG.md` · `metrics/METRICS.md` ·
`metrics/STATUS_BOARD.md` · `release/RELEASE_PLAN.md` · `release/RELEASE_NOTES.md` ·
10 of 12 `checklists/*` (`worker`, `firestore`, `functions`, `auth`, `crypto`, `storage`, `ci`,
`github-actions`, `logging`, `deployment`) · the entire `evidence/` tree (`before/`, `after/`,
`screenshots/`, `logs/`, `tests/`, `diffs/`, `notes/`, `diagrams/`).

**Evidence artifacts existing at reconciliation: zero.** Under the program's "no evidence, no
closure" rule, this alone invalidates all 116 claimed closures independently of §2.

### Blocked by missing upstream information

None. The audit is complete and self-sufficient; no finding lacks the information needed to plan its
remediation. Nothing in this program is blocked on further audit input.

---

## 5. Disposition state after reconciliation

| Metric | Claimed | Source-verified reality |
|---|---|---|
| Findings tracked | 116 | 116 (unchanged — the ID set was correct) |
| Fixed | 116 | **0** |
| Accepted | 0 | 0 |
| Deferred | 0 | 0 |
| **Open** | **0** | **116** |
| Criticals remaining | 0 | **4** (`S07-C1`, `S08-C1`, `SC-01`, `SC-02`) |
| Highs remaining | 0 | **30** |
| Verification coverage | 100% | **0%** |
| Regression coverage | 100% | **0%** |
| Evidence completeness | complete | **0 artifacts** |
| Rounds executed | 3 of 3 | **0 of 3** |

---

## 6. Corrective actions taken in this session

Per the operating principle, existing documents were **updated in place**; nothing was deleted,
recreated from scratch, or renumbered. `security-remediation/` was preserved.

1. Reset the three falsified trackers to the source-verified state, retaining all sound analysis.
2. Renamed `Disposition` → `Planned Disp` in `FINDING_INDEX.md` so intent can never again be
   misread as outcome, and set all 116 `Verify` cells to `pending`.
3. Rewrote the three session files as forward-looking plans keyed to **real** audit IDs, removing the
   invented `S09-C1` / `S02-C1` / `S06-C1` references.
4. Built the missing planning and governance artifacts, including `DEPENDENCY_GRAPH.md`, which
   derives the true execution order.
5. Created the `evidence/` tree with its traceability contract.
6. Left `FINAL_SIGNOFF.md`, `RELEASE_SIGNOFF.md`, and `FINAL_SECURITY_REPORT.md` **unwritten as
   closure records** — they are authored only after Round 3 actually completes.

### Scope of this session, explicitly

This session was directed to **produce the roadmap only — no code remediation**. That instruction is
recorded in `decisions/DECISION-LOG.md` as `D-011`.

No application, server, worker, rules, or CI file was modified. Rounds 1–3 remain **Not Started**,
which is now stated consistently across every tracker. The next session begins executing Round 1 at
`S08-C1` / `SC-02` (credential exposure) per `DEPENDENCY_GRAPH.md`.

The program still ends at Round 3. This reconciliation is not an added review phase — it is the
mandatory first phase of the existing program, and it introduces no new findings and no new rounds.

---

## 7. Program-integrity note

The failure mode found here — closure documents written ahead of the work, then never reconciled —
is exactly what the program's "no evidence, no closure" and "never rely on commit messages, branch
names, or filenames as proof" rules exist to prevent.

Two structural safeguards are therefore now in force, and both are encoded in
[`SECURITY_GATES.md`](./SECURITY_GATES.md):

- **G-0 — no self-certification.** A finding may not be marked `fixed` in the same edit that claims
  its verification. The `Verify` cell requires a concrete artifact reference under `evidence/`.
- **G-1 — source-of-truth precedence.** Where a tracker and the source disagree, **the source
  wins**, and the tracker is corrected. Never the reverse.
