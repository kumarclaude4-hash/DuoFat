# RISK REGISTER

Tracks residual risk for every finding that ends as `accepted` or
`deferred-with-justification`, plus program-level risks. Findings ending as
`fixed` are recorded in FINDING_INDEX/MASTER_CHECKLIST and need no entry here
unless residual risk remains.

Policy: Critical/High findings may only be `fixed` or `accepted-with-justification`
(never deferred). Medium/Low/Informational may be fixed, accepted, or deferred.

## Program-level risks

| ID | Risk | Likelihood | Impact | Mitigation | Owner | Status |
|----|------|-----------|--------|------------|-------|--------|
| PR-1 | Rotation of keystore/service account done late, leaving exposure window | Med | Critical | Rotate first in Round 1 before any deploy (MIGRATION §6) | Release eng | Open→tracked |
| PR-2 | Feature-flagged enforcement accidentally left in dual-accept | Med | High | Round 3 exit criteria requires legacy usage = 0 and flag flipped | Backend | Open→tracked |
| PR-3 | History rewrite breaks forks/clones | Low | Med | Communicate; rotate so history is worthless even if not rewritten | Release eng | Open→tracked |

## Finding-level residual risk (populated as dispositions are set)

| Finding | Severity | Disposition | Residual risk | Justification / acceptance | Revisit trigger |
|---------|----------|-------------|---------------|----------------------------|-----------------|
| _(to be filled during rounds; every accepted/deferred finding lands here)_ | | | | | |

_No Critical or High finding will be entered as `deferred`. Any Critical/High
accepted here must carry an explicit written business/technical justification and
a compensating control._
