# CHANGELOG — Security Remediation

Chronological record of remediation changes. Each entry references finding ID(s)
and the session that produced it. This is the record of what was DONE (the audit
folder remains the record of what was FOUND).

## [Unreleased] — Program scaffolding

### Added
- Created `security-remediation/` workspace: README, REMEDIATION_PROGRESS, REMEDIATION_PLAN, MASTER_CHECKLIST, FINDING_INDEX, RISK_REGISTER, SESSION_INDEX, FINAL_SIGNOFF, CHANGELOG.
- `architecture/TRUST_BOUNDARIES.md`, `architecture/TARGET_STATE.md`.
- `decisions/DECISION-LOG.md` (DL-001..DL-005).
- `migration/MIGRATION_PLAN.md`.
- `sessions/` round files and `checklists/` folder checklists.
- Ingested and indexed all audit findings from `audit/` (SESSION-00..10). Reconciled the aggregate count (117) against IDs actually present in the reports (116; Session 04 ledger over-counts Lows by one — see FINDING_INDEX note).

_Round entries (Round 1/2/3) are appended below as each session's fixes land with evidence._
