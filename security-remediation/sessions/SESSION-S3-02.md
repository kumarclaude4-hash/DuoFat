# SESSION-S3-02 — Supply-chain integrity, release provenance (lane CI)

Status: **COMPLETE.** All three S3-02 findings are fixed and verified in the CI
lane: `SC-05` (this workflow's first pass), then `SC-01` + `SC-04` (commit
`dcf85c5`, re-verified from source in the reconciliation pass below).

Findings in scope: `SC-01` (vendored `libsignal` JAR not reproducible/hashed/
gated in CI), `SC-04` (release APKs have no checksums/provenance), `SC-05`
(workflow deletes all releases + tags on every push to `main`).

> **Log history.** This session was worked in two passes. Pass 1 fixed `SC-05`
> and correctly left the log at PARTIAL. Pass 2 (`dcf85c5`) landed `SC-01` +
> `SC-04` and updated `../BUG_TRACKER.md`, but did **not** update this log or the
> `START_HERE.md` chain state — so both stale-pointed back at S3-02. The
> reconciliation pass re-verified `SC-01`/`SC-04` from source (commands recorded
> under each finding), promoted this log to COMPLETE, and advanced the chain to
> S3-03. No new code changed in reconciliation; it is documentation catch-up
> over already-committed, re-verified work (protocol §2 "source beats tracker",
> §9 narrative-lag correction).

---

## SC-05 — Workflow deletes all releases and tags on every push · **Fixed** · lane CI

### Claim falsified from source (protocol §3)
Tracker had this `Open`. Confirmed still live before fixing: `release.yml` had a
"Delete all previous releases and tags" step that paginated the full releases
list (`per_page=100`) and, for every release, issued
`DELETE repos/…/releases/{id}` **and** `DELETE repos/…/git/refs/tags/{tag}`.
Every push to `main` therefore erased all prior signed APKs, their tags, and any
checksums — no version could be pinned, audited, or rolled back, and a single
push (or a leaked `GITHUB_TOKEN`) could wipe the entire release record at once.

### Change
`.github/workflows/release.yml`: removed the delete-all step; added
**"Clear existing release for this tag"**. It resolves the single rolling tag
(`steps.tag.outputs.name`), looks up only a release whose `tag_name` matches it
(`gh api repos/${REPO}/releases/tags/${TAG}`), and deletes at most that one
stale release record. 404 (no such release) is treated as the normal
first-release case, not an error. `softprops/action-gh-release@v2` then updates
the release and re-points the tag in place. All other releases and tags are
preserved. Step still runs after "Resolve release tag", so its output is
available. Fails closed if no tag resolved (refuses to touch anything).

### Verification (lane CI)
- **YAML lint:** `js-yaml` parse of `release.yml` → OK; `Delete all previous
  releases and tags` absent, `Clear existing release for this tag` present.
- **Exit criterion — no delete-all path remains:** grep finds no `per_page=100`,
  no `releases?` enumeration, and no `git/refs/tags` DELETE in active code;
  deletion is scoped to `releases/tags/${TAG}`.

Commit: `8508746`.

---

## SC-01 — Vendored libsignal JAR not reproducible / unhashed / ungated · **Fixed** · lane CI

### Claim falsified from source (protocol §3)
The vendored `app/libs/libsignal-client-0.54.1-stripped.jar` is the app's entire
Signal Protocol implementation (`build.gradle` ships it as `implementation
files(...)`; the Maven artifact is only `compileOnly`), and Gradle applies no
integrity checking to `files(...)` deps. The committed strip script declared a
6-entry `STRIP` set, but an entry-by-entry diff of the shipped JAR against
upstream `org.signal:libsignal-client:0.54.1` showed **10 removed** — the
documented procedure did not reproduce the shipped binary, so no future check
could distinguish a legitimate update from a malicious swap.

### Change (commit `dcf85c5`)
- `scripts/strip_signal_records.py`: `STRIP` corrected to the **10** entries
  actually removed; the source JAR is fetched from Maven Central and pinned by
  `UPSTREAM_SHA256` (`9605b9c6…87d4a`) instead of scraped from
  `~/.gradle/caches`; the output is hashed and asserted against
  `EXPECTED_OUTPUT_SHA256` (`fa7d3afe…d89e3`); new `--check` mode writes nothing.
- `app/libs/libsignal-client-0.54.1-stripped.jar.sha256`: hash recorded in-repo.
- `ci.yml`: new `verify-libsignal-jar` job that `lint` **needs** (so `build-debug`
  and `instrumented-tests`, which need `lint`, inherit the gate).
- `release.yml`: same `--check` gate before the keystore is decoded.
- Deliberate: strip-by-prefix would remove **37** entries and would NOT reproduce
  the artifact — the explicit 10-entry list is intentional; do not "simplify" it.

### Verification (lane CI — commands run this reconciliation session)
- `python3 scripts/strip_signal_records.py --check` → **exit 0**,
  `sha256 fa7d3afe…d89e3 (38466351 B)` matches the recorded hash.
- Tamper: 1-byte append → **exit 1** with the `::error::` SC-01 message, then the
  JAR was restored and `git status app/libs/` is clean.
- `STRIP` set contains exactly **10** entries; `EXPECTED_OUTPUT_SHA256` and the
  `.sha256` sidecar agree.
- `js-yaml` parse: `ci.yml` jobs = `verify-libsignal-jar, lint, build-debug,
  instrumented-tests`; `lint.needs = "verify-libsignal-jar"`. `release.yml` runs
  `--check` before keystore decode.
- Actual GitHub Actions run is operator-side (requires a push), per the CI lane.

---

## SC-04 — Release APKs unverifiable (no checksums/provenance) · **Fixed** · lane CI

### Claim falsified from source (protocol §3)
`grep -iE "sha256|checksum|attest|provenance"` across `.github/` returned only
SC-05 comment lines, no functional step — releases attached raw APKs alone, so a
sideloading user could not distinguish the maintainer's build from a substituted
one on first install.

### Change (commit `dcf85c5`) — `release.yml`
- New "Generate SHA256SUMS and record signing certificate" step: writes
  `SHA256SUMS` with **bare filenames** (so `sha256sum -c SHA256SUMS` works in the
  download dir) and extracts the signing-cert SHA-256 via
  `apksigner verify --print-certs`.
- `actions/attest-build-provenance@v2` attaches signed provenance tying each APK
  to the run/commit; minimum `id-token: write` + `attestations: write`
  permissions added.
- "Compose release body" publishes checksums + cert digest + three concrete
  verification commands via `body_path`; `SHA256SUMS` is attached beside the APKs.
- **Fails closed:** missing APK dir, zero APKs, a failing `sha256sum`, or an empty
  `SHA256SUMS` each `exit 1`; an unreadable cert digest is omitted with a
  `::warning::`, never fabricated. Runs before the keystore-erase step.

### Verification (lane CI — commands run this reconciliation session)
- `js-yaml` parse OK; `release.yml` `permissions` =
  `{contents: write, id-token: write, attestations: write}`.
- grep confirms the SHA256SUMS/apksigner/`attest-build-provenance@v2`/`body_path`
  steps and that `SHA256SUMS` is listed in the release `files:` globs.
- Actual CI execution is operator-side (requires a push/dispatch), per the CI lane.

---

## Operator runbook (carried, unchanged)
From S3-01: revoke leaked GCP SA key; rotate `WORKER_SECRET` + baked B2 creds;
enable branch protection on `main` (SC-12).

---

```
SESSION: S3-02  MODEL: —  BUDGET: —  CLUSTER: S3-02 (supply-chain integrity, release provenance)  STATUS: fixed (all 3: SC-05, SC-01, SC-04)
CHANGES:
  - .github/workflows/release.yml: SC-05 scoped tag-clear; SC-04 SHA256SUMS + apksigner cert + attest-build-provenance + body_path (+ id-token/attestations perms)
  - .github/workflows/ci.yml: SC-01 verify-libsignal-jar job gated ahead of lint
  - scripts/strip_signal_records.py: SC-01 10-entry STRIP, UPSTREAM_SHA256 pin, EXPECTED_OUTPUT_SHA256 assert, --check mode
  - app/libs/libsignal-client-0.54.1-stripped.jar.sha256: recorded hash
  - BUG_TRACKER.md: SC-01, SC-04, SC-05 → Fixed (S3-02) with evidence
VERIFICATION:
  PASS: --check exit 0 on committed JAR; 1-byte tamper → exit 1 (::error::), JAR restored & git clean; STRIP=10; js-yaml parse of ci.yml + release.yml OK; ci lint.needs=verify-libsignal-jar; release.yml perms carry id-token+attestations write
  FAIL: none
  BLOCKED: none
  NOT RUN: actual GitHub Actions run of both workflows (operator; requires push/dispatch)
COMMIT: 8508746 (SC-05); dcf85c5 (SC-01, SC-04); reconciliation doc-only
NEXT SESSION: S3-03 — Dependency pinning & scanning (SC-03, SC-06, SC-07, SC-08, SC-09, SC-10), lane CI
```
