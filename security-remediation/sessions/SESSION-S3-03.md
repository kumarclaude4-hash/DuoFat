# SESSION S3-03 — Dependency pinning & scanning (lane CI, supply-chain)

Date: 2026-08-11
Model: Opus 5 · Budget: $5 max
Findings in scope: `SC-03`, `SC-06`, `SC-07`, `SC-08`, `SC-09`, `SC-10`

Preamble: this session also reconciled S3-02's narrative lag before starting. The `dcf85c5`
commit had landed SC-01 + SC-04 code and updated `BUG_TRACKER.md`, but left `SESSION-S3-02.md`
at PARTIAL and the chain state pointing at S3-02. Re-verified SC-01/SC-04 from source (JAR
`--check` exit 0; 1-byte tamper → exit 1; `js-yaml` parse of `ci.yml`+`release.yml` OK), promoted
the S3-02 log to COMPLETE, and advanced the chain — commit prior to this session's code work.

## 1. Inherited-state falsification (SESSION_PROTOCOL §3)

The tracker claimed all six were **Open/Verified**. Per §3 each was re-confirmed against current
source before any change, so a stale "Open" could not cause redundant work and a silently-already-fixed
finding could not be "fixed" twice:

- **SC-03** — `find . -name verification-metadata.xml` → none. No Gradle dependency verification. → really Open.
- **SC-06** — `build.gradle` `allprojects.repositories` had a bare, unscoped `maven { url 'https://jitpack.io' }`. → really Open.
- **SC-07** — `grep -rn wrapper-validation .github/` → none; no job validated `gradle-wrapper.jar`. → really Open.
- **SC-08** — every `uses:` across all five workflows referenced a mutable tag (`@v4`/`@v3`/`@v2`), none SHA-pinned. → really Open.
- **SC-09** — no `.github/dependabot.yml`; no scanning/SBOM workflow anywhere. → really Open.
- **SC-10** — `firestore.yml` + `firestore-rules-test.yml` ran bare `npm install` and `npm install -g firebase-tools` (floating latest). → really Open.

## 2. Changes implemented (smallest necessary; add-don't-remove — invariant #3)

- **SC-06 — `build.gradle`:** scoped JitPack with `content { includeGroupByRegex 'com\.github\..*' }` so
  it can only serve `com.github.*` coordinates. `google()`/`mavenCentral()` stay declared first and remain
  the only source for all androidx/Firebase/Google/glide artifacts. No dependency removed.
- **SC-07 — `.github/workflows/ci.yml`:** new `validate-gradle-wrapper` job runs
  `gradle/actions/wrapper-validation` (SHA-pinned) and is added to `lint.needs`. Since `lint` is the
  fan-in prerequisite of every build job (established in S3-02 for `verify-libsignal-jar`), a tampered
  wrapper JAR now fails the pipeline before `./gradlew` executes.
- **SC-08 — all five workflows** (`ci.yml`, `release.yml`, `firestore.yml`, `firestore-rules-test.yml`,
  new `security-scan.yml`): SHA-pinned every third-party action to a full 40-char commit SHA (each
  resolved live via `gh api repos/<action>/commits/<tag>`), with a trailing `# vN` comment.
- **SC-09 — two new files:** `.github/dependabot.yml` (weekly `github-actions` `/`, `gradle` `/`, `npm`
  `/firestore-tests` + `/server`; grouped minor/patch; open-PR caps) and
  `.github/workflows/security-scan.yml` (least-priv `permissions`, all actions SHA-pinned) running
  CodeQL SAST (`java-kotlin` + `javascript-typescript`), gitleaks secret scan, and `anchore/sbom-action`
  SBOM upload, on push/PR/weekly-cron.
- **SC-10 — both firestore workflows:** `npm install` → `npm ci` (installs from the committed
  `firestore-tests/package-lock.json`, fails on manifest/lock drift); global CLI pinned to
  `firebase-tools@15.26.0`.
- **SC-03 — `gradle/verification-metadata.xml` (scaffold):** `<verify-metadata>true`,
  `<verify-signatures>false`, `<trusted-artifacts>` for the wrapper distribution zips, empty
  `<components/>`, and an in-file operator runbook. Deliberately a scaffold — see §3/§5.

## 3. Verification (lane CI — available)

- **YAML parse (real):** `js-yaml` loaded all six files (`ci.yml`, `release.yml`, `firestore.yml`,
  `firestore-rules-test.yml`, `security-scan.yml`, `dependabot.yml`) without error.
- **Structural asserts:** `ci.jobs` contains `validate-gradle-wrapper`; `lint.needs =
  ["verify-libsignal-jar","validate-gradle-wrapper"]`; `security-scan` jobs = `codeql,gitleaks,sbom`;
  dependabot ecosystems = `github-actions:/`, `gradle:/`, `npm:/firestore-tests`, `npm:/server`.
- **SC-08 exit-criterion (real):** repo-wide `grep -E "uses: [^@]+@v[0-9]+([.][0-9]+)*$"` under
  `.github/` → **no matches** — zero unpinned actions remain.
- **SC-10 (real):** grep confirms `npm ci` (no bare `npm install`) and `firebase-tools@15.26.0` in both
  firestore workflows; `firestore-tests/package-lock.json` present for `npm ci` to consume.
- **SC-06 (real):** `includeGroupByRegex` present at JitPack block; `build.gradle` brace count balanced.
- **SC-03 (real):** `xml.dom.minidom.parse` → well-formed (after removing a `--` sequence illegal inside
  an XML comment).
- **Blocked / not run:** actual GitHub Actions runs of CodeQL/gitleaks/SBOM/wrapper-validation are
  **operator** steps (need a push/PR/dispatch event) per the CI lane definition. Populating SC-03
  component hashes needs the Android SDK + network dependency resolution — a **BLOCKED gate** (same
  toolchain blocker as S3-19b).

## 4. Dispositions written to `../BUG_TRACKER.md`

| Finding | New disposition | Basis |
|---|---|---|
| SC-03 | **Partial** (S3-03) | scaffold + trusted wrapper artifacts + operator runbook committed; `<components/>` empty so not yet enforcing per-artifact hashes — population needs Gradle+SDK+network (blocked). XML well-formed. |
| SC-06 | Fixed (S3-03) | JitPack scoped to `com.github.*` via `includeGroupByRegex`; google()/mavenCentral() first & content-addressed. |
| SC-07 | Fixed (S3-03) | `validate-gradle-wrapper` job (`gradle/actions/wrapper-validation`, pinned) in `lint.needs`; gates all builds. |
| SC-08 | Fixed (S3-03) | all actions SHA-pinned (live-resolved); grep confirms zero `@vN` refs remain across `.github/`. |
| SC-09 | Fixed (S3-03) | `dependabot.yml` (4 ecosystems) + `security-scan.yml` (CodeQL+gitleaks+SBOM); both parse. |
| SC-10 | Fixed (S3-03) | `npm ci` + `firebase-tools@15.26.0` in both firestore workflows; lockfile present. |

## 5. Runbook items still owned by an operator (not closed here)

- **SC-03 — populate + enforce Gradle dependency verification:** run
  `./gradlew --write-verification-metadata sha256 help` (and the build tasks) against the real
  dependency graph to fill `<components/>`, review the generated hashes, then set
  `<verify-signatures>true` and commit. Requires Android SDK + network resolution (blocked here).
- **SC-09 — triage scanning output:** once `security-scan.yml` runs on the operator's fork/branch,
  review CodeQL/gitleaks findings and the SBOM; enable required-status-check on these jobs (ties into
  the SC-12 branch-protection runbook item).

These are toolchain/operator-gated; the in-repo scaffolding + config for each is in place, and the
`SC-03` scaffold with signatures off does not weaken anything relative to the prior no-verification state.

---

```
SESSION: S3-03  MODEL: Opus 5  BUDGET: $5 max  CLUSTER: S3-03 (dependency pinning & scanning)  STATUS: 5 fixed + SC-03 partial (blocked gate)
CHANGES:
  - build.gradle: scope JitPack to com.github.* via content{includeGroupByRegex} (SC-06)
  - .github/workflows/ci.yml: add validate-gradle-wrapper job -> lint.needs; SHA-pin all actions (SC-07, SC-08)
  - .github/workflows/release.yml: SHA-pin all actions (SC-08)
  - .github/workflows/firestore.yml: SHA-pin actions; npm install->npm ci; pin firebase-tools@15.26.0 (SC-08, SC-10)
  - .github/workflows/firestore-rules-test.yml: SHA-pin actions; npm install->npm ci; pin firebase-tools@15.26.0 (SC-08, SC-10)
  - .github/workflows/security-scan.yml: NEW — CodeQL + gitleaks + SBOM, least-priv perms, pinned (SC-09)
  - .github/dependabot.yml: NEW — github-actions/gradle/npm(x2) weekly, grouped (SC-09)
  - gradle/verification-metadata.xml: NEW scaffold — verify-metadata on, signatures off, wrapper trusted-artifacts, operator runbook (SC-03 partial)
  - BUG_TRACKER.md: SC-03(Partial), SC-06/07/08/09/10(Fixed) dispositions with this-session evidence
  - security-remediation/sessions/SESSION-S3-02.md + START_HERE.md: reconcile S3-02 to COMPLETE, advance chain (pre-work)
VERIFICATION:
  PASS: js-yaml parse of all 6 yml/dependabot files; ci lint.needs includes validate-gradle-wrapper; security-scan jobs=codeql,gitleaks,sbom; dependabot 4 ecosystems; grep -> zero unpinned @vN actions under .github/; npm ci + firebase-tools@15.26.0 in both firestore wf; lockfile present; build.gradle braces balanced + includeGroupByRegex present; verification-metadata.xml well-formed
  FAIL: none
  BLOCKED: SC-03 component-hash population (needs Gradle+Android SDK+network); actual Actions runs of CodeQL/gitleaks/SBOM/wrapper-validation (operator push/PR event)
  NOT RUN: live GitHub Actions execution (operator lane)
COMMIT: <filled by commit step>          WORKTREE: clean
NEXT SESSION: see ROUND3_REMEDIATION_PLAN.md next unstarted cluster after S3-03
```
