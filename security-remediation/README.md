# DuoShield Security Remediation Program

> **Live disposition tracking now lives in one place: [`../BUG_TRACKER.md`](../BUG_TRACKER.md).**
> `FINDING_INDEX.md`, `MASTER_CHECKLIST.md`, `RISK_REGISTER.md`, and `REMEDIATION_PROGRESS.md`
> (previously in this directory) and `../audit/AUDIT_PROGRESS.md` were retired because they had
> drifted out of sync with each other and with the code — see `BUG_TRACKER.md`'s "Corrections vs.
> the old trackers" section for specific examples. Everything else in this directory (plan,
> session logs, decision log, evidence, sign-off) remains as historical record.

This directory is the **record of what was done** to remediate the completed DuoShield security
audit. It is a companion to — never a replacement for — the audit in [`../audit/`](../audit/),
which remains the immutable **record of what was found**.

- The audit (`../audit/`) is **canonical, immutable input**. It is not modified, renumbered, or
  re-opened by this program.
- This directory (`security-remediation/`) contains **only remediation artifacts**: the plan, the
  finding disposition index, per-round session logs, decision logs, evidence, and the final
  sign-off. It does not duplicate the audit.

## The contract

This is a **closed remediation program** with a fixed number of rounds. It ends in a final
sign-off and does **not** schedule further audit passes or open-ended "needs more work" states.

End state (see [`FINAL_SIGNOFF.md`](./FINAL_SIGNOFF.md)):

- All **Critical** findings fixed (or explicitly accepted with written justification).
- All **High** findings fixed (or explicitly accepted with written justification).
- All **Medium / Low / Informational** findings fixed, consciously accepted, or explicitly
  deferred with written justification.
- Every fix verified from source (and tests where applicable) — never from a commit title.
- No unreviewed trust-boundary change remaining.
- Every one of the **117 audit findings** ends in exactly **one** final disposition:
  `fixed` · `accepted` · `deferred-with-justification`.

## Finding universe

The audit recorded **117 findings** (4 Critical / 28 High / 28 Medium / 34 Low / 23 Informational
as originally counted; 4C / 29H / 27M / 34L / 23I after the audit's own cross-session re-ratings in
`../audit/SESSION-10-SYNTHESIS.md` §7). This program uses the **re-rated** severities as the
governing severity for prioritization, and records both where they differ.

Session 09 labels its findings `SC-01 … SC-12` rather than `S09-*`; that scheme is preserved.

## Map of this directory

| File / dir | Purpose |
|---|---|
| `README.md` | This file — the program contract and map. |
| `../BUG_TRACKER.md` | **Live status tracker** — one row per finding, current disposition re-verified against source. Supersedes `REMEDIATION_PROGRESS.md`, `MASTER_CHECKLIST.md`, `FINDING_INDEX.md`, and `RISK_REGISTER.md` (all retired). |
| `REMEDIATION_PLAN.md` | The fixed round plan: goals, scope, dependencies, rollback, verification, exit criteria. |
| `SESSION_INDEX.md` | Index of the remediation rounds and their session logs. |
| `FINAL_SECURITY_REPORT.md` | Final consolidated report (written at the hard stop). |
| `FINAL_SIGNOFF.md` | The sign-off that closes the program. |
| `CHANGELOG.md` | Chronological log of remediation changes. |
| `architecture/` | `TRUST_BOUNDARIES.md`, `TARGET_STATE.md`. |
| `decisions/` | `DECISION-LOG.md`. |
| `evidence/` | Per-finding evidence (diffs, source refs, verification notes). |
| `migration/` | `MIGRATION_PLAN.md` (deploy/rollback/credential-rotation order). |
| `sessions/` | `SESSION-01.md` … `SESSION-03.md` remediation round logs. |
| `checklists/` | Folder-specific checklists (android, server, worker, firestore, functions, auth, crypto, storage, ci, github-actions, logging, deployment). |

## Environment note (important for reading dispositions)

This remediation was executed inside a source-only workspace. That boundary determines how each
finding can be closed, and the disposition vocabulary reflects it:

- **`fixed`** — the defect lives in source this program can edit, and the fix is present and
  verified from source (and tests where they exist).
- **`fixed` with an operator runbook** — the *code* half is done and verified here, and there is an
  irreducible **out-of-band** half that only a human with console access can perform (rotating a
  leaked cloud credential, toggling Firebase App Check enforcement, setting a Firestore TTL policy,
  enabling GitHub branch protection). The runbook step and its verification are recorded in
  [`migration/MIGRATION_PLAN.md`](./migration/MIGRATION_PLAN.md) and
  [`RISK_REGISTER.md`](./RISK_REGISTER.md). These are **not** deferrals — the engineering change
  that makes the console action effective ships in this program; the console action is a deploy-time
  operation, tracked to completion in the migration plan.
- **`accepted`** — a conscious decision to carry the risk, with written justification.
- **`deferred-with-justification`** — explicitly out of scope for this program, with written
  justification and a residual-risk entry.

Every disposition, including the runbook split, is recorded once per finding in
[`../BUG_TRACKER.md`](../BUG_TRACKER.md).
