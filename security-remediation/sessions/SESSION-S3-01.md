# SESSION S3-01 — Get secrets out of the APK and CI (lane CI/build, P0)

Date: 2026-08-11
Model: Opus 5 · Budget: $5 max
Findings in scope: `S08-C1`, `SC-02`, `S08-H1`, `S03-L1`, `SC-12`

## 1. Inherited-state falsification (SESSION_PROTOCOL §3)

The tracker claimed `S08-C1`/`SC-02`/`S08-H1`/`S03-L1` were **Fixed** and `SC-12` **Open**. Per §3
these were not taken on faith — each was falsified against current source this session.

- **S08-C1** — `find . -name service-account.json` (excluding node_modules) → **none**.
  `.github/workflows/release.yml`, `build-release.sh`, and `build-apks.sh` each contain a guard that
  fails the build if `app/src/main/assets/service-account.json` reappears; none writes it.
  `app/src/main/assets/` holds only `README.txt`, `brand/`, `watch_together/`. → Code **Fixed**.
- **SC-02** — `release.yml` and `ci.yml` write `local.properties` with non-secret routing values only
  (`b2.bucket`, `b2.region`, `push.server.url`, `worker.url`). `app/build.gradle` emits
  `B2_KEY_ID`/`B2_APPLICATION_KEY` as empty strings and no longer resolves the B2 credential pair.
  `release.yml` has an "Assert no secrets in packaged build inputs" step. → Code **Fixed**, but see §2:
  the same guard was **missing from `ci.yml`'s `build-debug` job**, which also uploads an APK artifact.
- **S08-H1 / S03-L1** — `grep buildConfigField app/build.gradle` shows no `WORKER_SECRET` field.
  `grep -rn WORKER_SECRET .github/workflows build-*.sh app/build.gradle` → every hit is a historical
  comment, **no live value injection**. `grep "env.WORKER_SECRET" worker/src/index.js` → **none**;
  `/stats` is gated by `isStatsAuthorized()` reading `env.STATS_SECRET` only, fail-closed if unset
  (`worker/src/index.js:88-99`). → Code **Fixed**.
- **SC-12** — re-checked from **live** evidence:
  `gh api repos/kumarclaude4-hash/DuoFatass/branches/main/protection` → `404 Branch not protected`.
  → **Open (operator)**; not closable from source.

## 2. Change implemented (smallest necessary)

One code gap remained relative to the session's exit criterion ("grep proves no secret reaches
BuildConfig/APK"): `release.yml` enforces the no-secret guard, but `ci.yml`'s `build-debug` job — which
runs on every pull request and **uploads the built debug APK as an artifact** — wrote `local.properties`
with the identical pattern yet had **no** enforcement step. A future edit re-adding a secret there
would leak via the PR artifact with nothing to stop it.

Fix (add, don't replace — standing invariant #3): added the same "Assert no secrets in packaged build
inputs" step to `ci.yml`'s `build-debug` job, immediately after the `local.properties` write and before
`Build debug APK`. It blocks `b2.key.id`/`b2.application.key`/`worker.secret` keys (allowing empty
`${VAR:-}` defaults via the `=.` anchor) and any `BEGIN … PRIVATE KEY` block, and does not use `set -e`
so a missing file cannot skip the guard. It is a mirror of the release-job step; a comment notes to keep
both in sync. No existing step was removed or weakened.

## 3. Verification (lane CI — available)

- **YAML parse (real):** `js-yaml` loaded all four workflows without error —
  `ci.yml` (jobs: lint, build-debug, instrumented-tests), `release.yml`, `firestore.yml`,
  `firestore-rules-test.yml`. Confirmed the new step object is present in `build-debug.steps` as
  `"Assert no secrets in packaged build inputs"`, positioned before `"Build debug APK"`.
- **Shell syntax:** `bash -n` on the extracted guard body → OK.
- **Functional behavior of the guard** (ran the exact logic against fixtures):
  - clean `local.properties` (routing values only) → exit 0, "OK: no packaged secret material detected."
  - empty-value keys (`b2.key.id=`, `worker.secret=`) → exit 0 (correctly allowed).
  - `worker.secret=deadbeefleaked` → exit 1, `SECRET:worker.secret`.
  - `foo=-----BEGIN RSA PRIVATE KEY-----` → exit 1, `PRIVKEY`.
- **Grep exit-criterion:** no `WORKER_SECRET` `buildConfigField`; no live secret injection in build
  inputs; `worker/src/index.js` has no `env.WORKER_SECRET` read.
- **Not run / blocked:** actual GitHub Actions run of the workflow is an **operator** step (CI lane
  note in the plan). JAR-hash recompute is out of scope (that is S3-02).

## 4. Dispositions written to `../BUG_TRACKER.md`

| Finding | New disposition | Basis |
|---|---|---|
| S08-C1 | Fixed (S3-01), code half | source: no SA-key write path + 3 guards; `find` shows file absent. Operator still to revoke leaked GCP key (runbook). |
| SC-02  | Fixed (S3-01) | non-secret `local.properties` only; guard now on both `release.yml` and `ci.yml`; guard functionally tested. |
| S08-H1 | Fixed (S3-01) | no `WORKER_SECRET` BuildConfig field; worker `/stats` = `STATS_SECRET` only, fail-closed; no `env.WORKER_SECRET` path. Operator still to rotate leaked value (runbook). |
| S03-L1 | Fixed (S3-01) | duplicate of S08-H1, same evidence. |
| SC-12  | Open (operator) | live `gh api` → 404 Branch not protected. |

## 5. Runbook items still owned by an operator (not closed here)

- **Revoke the leaked GCP service-account key** (it shipped inside published APKs).
- **Rotate `WORKER_SECRET`** and any previously-baked B2 credential.
- **Enable branch protection** on `main` (SC-12) — re-verify with the `gh api` call above.

These are tracked as runbook/operator items; the code that makes each leak inert is in place, so a lag
in rotation does not reopen the vulnerability, but the credentials themselves remain compromised until
an operator rotates them.

---

```
SESSION: S3-01  MODEL: Opus 5  BUDGET: $5 max  CLUSTER: S3-01 (APK/CI secrets)  STATUS: fixed (code) + operator-runbook open
CHANGES:
  - .github/workflows/ci.yml: add "Assert no secrets in packaged build inputs" guard to build-debug job
  - BUG_TRACKER.md: update S08-C1, SC-02, S08-H1, S03-L1, SC-12 rows with this-session source/live evidence
VERIFICATION:
  PASS: js-yaml parse of all 4 workflows; guard present+ordered in build-debug; bash -n OK; guard fixtures (clean/empty=0, poisoned/privkey=1); grep no WORKER_SECRET BuildConfig / no env.WORKER_SECRET in worker
  FAIL: none
  BLOCKED: actual GitHub Actions workflow run (operator)
  NOT RUN: JAR-hash recompute (belongs to S3-02)
COMMIT: 60c8cde7900a375b24e66251c0197a7f22ab6d30          WORKTREE: clean
NEXT SESSION: S3-02 — Supply-chain integrity, release provenance (SC-01, SC-04, SC-05), lane CI
```
