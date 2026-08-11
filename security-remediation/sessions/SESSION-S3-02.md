# SESSION-S3-02 — Supply-chain integrity, release provenance (lane CI)

Status: **PARTIAL.** One finding closed this session (`SC-05`). The other two
S3-02 findings (`SC-01`, `SC-04`) remain **open** — S3-02 is not complete and
the chain state still points here.

Findings in scope: `SC-01` (vendored `libsignal` JAR not reproducible/hashed/
gated in CI), `SC-04` (release APKs have no checksums/provenance), `SC-05`
(workflow deletes all releases + tags on every push to `main`).

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

This is the fix itself (removing a destructive action), consistent with the
plan's "remove the destructive delete-all step" and with standing invariant
"add, don't replace" for the surrounding guards (none removed).

### Verification (lane CI — available now)
- **YAML lint:** real `js-yaml` parse of `release.yml` → OK; `release` job step
  list confirmed, `Delete all previous releases and tags` absent,
  `Clear existing release for this tag` present.
- **Exit criterion — no delete-all path remains:** grep finds no
  `per_page=100`, no `releases?` enumeration, and no `git/refs/tags` DELETE in
  active code (only one descriptive comment references the old behavior);
  deletion is scoped to `releases/tags/${TAG}`.
- Actual CI execution is operator-side (requires a push to `main`); config is
  verified here per the CI lane definition.

Commit: `8508746`.

---

## Not done this session (S3-02 remains open)

- **SC-01** (Open) — vendored `app/libs/libsignal-client-0.54.1-stripped.jar`
  still has no reproducible hash recorded or gated in CI. Needs hash recompute
  from `scripts/strip_signal_records.py` + a CI assertion step.
- **SC-04** (Open) — release job still attaches raw APKs only; needs SHA256SUMS
  + provenance/attestation on the release.

## Operator runbook (carried, unchanged)
From S3-01: revoke leaked GCP SA key; rotate `WORKER_SECRET` + baked B2 creds;
enable branch protection on `main` (SC-12).

## Chain state
`NEXT SESSION` stays **S3-02** (SC-01, SC-04 outstanding). `LAST DONE` records
SC-05.
