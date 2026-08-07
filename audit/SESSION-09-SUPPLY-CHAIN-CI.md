# Session 09 — Supply Chain & CI/CD

**Scope:** dependency provenance, vendored artifacts, build reproducibility, GitHub Actions
workflows, release publishing, secret handling in CI.

**Files reviewed:**
- `build.gradle`, `settings.gradle`, `app/build.gradle`
- `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`
- `app/libs/libsignal-client-0.54.1-stripped.jar`, `scripts/strip_signal_records.py`
- `.github/workflows/release.yml`, `ci.yml`, `firestore.yml`, `firestore-rules-test.yml`
- `.github/CODEOWNERS`, `.gitignore`, `server/.npmrc`
- `server/`, `worker/`, `functions/` manifests and lockfiles

**Verification performed live in this session:** downloaded the upstream Maven artifact and
diffed it entry-by-entry against the vendored JAR (see SC-01). This is the only finding below
that was empirically proven rather than read from source.

---

## Summary

| ID | Severity | Title |
|----|----------|-------|
| SC-01 | **Critical** | Vendored libsignal JAR is not reproducible from the committed build script |
| SC-02 | **Critical** | Release workflow bakes all backend secrets into the shipped APK |
| SC-03 | **High** | No Gradle dependency verification — every Maven dependency floats unpinned |
| SC-04 | **High** | Release publishes unverifiable APKs: no checksums, no signature record, no provenance |
| SC-05 | **High** | Release workflow deletes all prior releases and tags on every push to `main` |
| SC-06 | **High** | JitPack in the dependency repository list — builds from mutable Git refs |
| SC-07 | **Medium** | `gradle-wrapper.jar` committed with no wrapper validation in CI |
| SC-08 | **Medium** | All GitHub Actions pinned to mutable tags, not commit SHAs |
| SC-09 | **Medium** | No dependency scanning, secret scanning, SAST, SBOM, or Dependabot |
| SC-10 | **Medium** | Firestore rules deploy runs `npm install` (unpinned) with `audit=false` |
| SC-11 | **Low** | Production crypto depends on an alpha-quality library |
| SC-12 | **Low** | CODEOWNERS present but no evidence of enforced branch protection |

---

## SC-01 — Vendored libsignal JAR is not reproducible from the committed build script

**Severity: Critical** · `app/libs/libsignal-client-0.54.1-stripped.jar`,
`scripts/strip_signal_records.py`, `app/build.gradle:86`

The entire Signal Protocol implementation — the app's whole confidentiality guarantee — is
loaded from a 38 MB JAR that is committed to the repository as a binary blob, hand-modified
from the upstream Maven artifact:

```gradle
implementation('org.signal:libsignal-android:0.54.1') {
    exclude group: 'org.signal', module: 'libsignal-client'
}
compileOnly 'org.signal:libsignal-client:0.54.1'
implementation files('libs/libsignal-client-0.54.1-stripped.jar')   // ← DEX'd, ships to users
```

Note what those three lines mean: the Maven artifact is `compileOnly`, so it is used to
type-check the code but is **not** what runs on the device. The classes users actually execute
come from the local file. Gradle applies no integrity checking of any kind to
`files(...)` dependencies.

`scripts/strip_signal_records.py` is documented as the regeneration procedure. It declares an
explicit six-entry allowlist:

```python
STRIP = {
    "org/signal/libsignal/net/ChatService$DebugInfo.class",
    "org/signal/libsignal/net/ChatService$Request.class",
    "org/signal/libsignal/net/ChatService$Response.class",
    "org/signal/libsignal/net/ChatService$ResponseAndDebugInfo.class",
    "org/signal/libsignal/net/Svr3$RestoredSecret.class",
    "org/signal/libsignal/zkgroup/groupsend/GroupSendEndorsementsResponse$ReceivedEndorsements.class",
}
```

and the loop removes an entry only on an exact match against that set — no prefix matching, no
outer-class logic:

```python
for item in z_in.infolist():
    if item.filename in STRIP:
        removed.append(item.filename)
    else:
        z_out.writestr(item, z_in.read(item.filename))
```

**Measured result.** I fetched the upstream artifact from Maven Central and compared it to the
committed JAR entry-by-entry, by name and CRC:

```
upstream  sha256 9605b9c6ce51f13f9025ef1b7d3789426fefb9a023f4fc136a01af1ef4487d4a  (38,482,974 B)
vendored  sha256 fa7d3afe9376ee83b0370bd16aff3083ea61a9ce131ee62773b48b35e6bd89e3  (38,466,351 B)

REMOVED (10)
   org/signal/libsignal/net/ChatService$DebugInfo.class
   org/signal/libsignal/net/ChatService$InternalRequest.class          ← undocumented
   org/signal/libsignal/net/ChatService$Request.class
   org/signal/libsignal/net/ChatService$Response.class
   org/signal/libsignal/net/ChatService$ResponseAndDebugInfo.class
   org/signal/libsignal/net/ChatService.class                         ← undocumented
   org/signal/libsignal/net/Svr3$RestoredSecret.class
   org/signal/libsignal/net/Svr3.class                                ← undocumented
   org/signal/libsignal/zkgroup/groupsend/GroupSendEndorsementsResponse$ReceivedEndorsements.class
   org/signal/libsignal/zkgroup/groupsend/GroupSendEndorsementsResponse.class  ← undocumented
ADDED (0)
CRC-CHANGED (0)
```

Two conclusions, and they point in opposite directions:

**The good news, and it is worth stating plainly:** zero classes were added and zero classes
were modified. Every retained class is byte-identical to upstream by CRC. The vendored JAR
shipping today is a pure subset of the official artifact. There is no backdoor in it. This
finding is *not* an allegation that the crypto has been tampered with.

**The problem:** the committed script removes 6 entries; the committed artifact has 10 removed.
The artifact in the repository was not produced by the procedure in the repository. Four
removals — including two entire top-level classes, `ChatService` and `Svr3` — have no
documented provenance at all. Someone ran a different script, an unrecorded manual step, or an
edited version that was never committed.

That is what makes this Critical rather than Low. The security property that matters here is
not "is the current blob clean" — I verified it is. It is "can anyone detect if the next blob
isn't." Right now the answer is no:

- The documented regeneration procedure does not reproduce the shipped binary, so the obvious
  verification (re-run the script, compare hashes) fails on the *known-good* artifact. Any
  future check would be comparing against a known-broken baseline.
- No hash of the expected artifact is recorded anywhere in the repo or the build.
- The JAR does not appear in `.gitignore`, is not built in CI, and is not validated in CI.
- Review of a 38 MB binary diff in a pull request is not realistic, and CODEOWNERS coverage
  (SC-12) does not change that.
- A single commit swapping this file for one with a modified `SessionCipher` or a
  key-generation routine that leaks entropy would silently compromise every message in the
  product, and would look like a routine dependency update in the PR list.

The 4 undocumented removals are almost certainly innocent — `ChatService` and `Svr3` are
Signal-server networking classes DuoShield never calls, and removing the outer class alongside
its nested `Record` subclasses is the natural fix if the inner-class-only strip failed to build.
The point is that "almost certainly" is the strongest statement the repository currently
supports, and for the component that holds the app's entire security model that is not
sufficient.

**Recommendation:**
1. Make the artifact reproducible. Either update `STRIP` to the 10 entries actually removed, or
   better, strip by prefix (`org/signal/libsignal/net/`, `.../zkgroup/groupsend/`) so the intent
   is expressed rather than enumerated. Then regenerate and confirm the output hash matches what
   ships today — if it does not, the difference must be explained before release.
2. Pin provenance in the script: hardcode the expected upstream SHA-256
   (`9605b9c6…87d4a`) and abort if the source JAR does not match. Fetch from Maven Central
   directly rather than scraping `~/.gradle/caches`, which makes the output depend on
   whatever happens to be in a local cache.
3. Record the expected output hash (`fa7d3afe…d89e3`) in the repo and assert it in `ci.yml`, so
   any change to this file fails the build unless the hash is updated in the same commit.
4. Preferred long term: delete the vendored JAR entirely. The D8 `Record` desugaring crash it
   works around is a toolchain issue, not a libsignal issue. Raising `minSdk` to 34, or moving
   to a libsignal version that does not ship `java.lang.Record` subclasses, removes the need to
   hand-modify the cryptographic core at all.

---

## SC-02 — Release workflow bakes all backend secrets into the shipped APK

**Severity: Critical** · `.github/workflows/release.yml:47–76`

The release job injects the full backend credential set into the Gradle build:

```yaml
SERVICE_ACCOUNT:    ${{ secrets.GOOGLE_APPLICATION_CREDENTIALS_JSON }}
B2_KEY_ID:          ${{ secrets.B2_KEY_ID }}
B2_APPLICATION_KEY: ${{ secrets.B2_APPLICATION_KEY }}
B2_BUCKET:          ${{ secrets.B2_BUCKET }}
B2_REGION:          ${{ secrets.B2_REGION }}
PUSH_SERVER_URL:    ${{ secrets.PUSH_SERVER_URL }}
WORKER_URL:         ${{ secrets.WORKER_URL }}
WORKER_SECRET:      ${{ secrets.WORKER_SECRET }}
```

These land in the APK — `BuildConfig` constants and/or bundled assets — and an APK is a public
artifact published to GitHub Releases for anyone to download. `strings` on the DEX, or any
decompiler, recovers them in seconds. R8 (`minifyEnabled true`) renames symbols; it does not
encrypt string constants.

The consequences are not uniform, so it is worth separating them:

- `B2_APPLICATION_KEY` / `B2_KEY_ID` — a Backblaze B2 credential in every user's hands. Depending
  on the key's scope this is read/write/delete over the media bucket for all users. This is the
  most severe of the set.
- `GOOGLE_APPLICATION_CREDENTIALS_JSON` — a GCP service-account private key. Service accounts
  routinely carry broad project-level authority; if this one can mint tokens or reach Firestore
  outside the client's normal path, it is equivalent to full backend compromise.
- `WORKER_SECRET` — the shared secret authenticating the client to the Cloudflare Worker,
  which cross-references the authentication weaknesses recorded in earlier sessions. A shared
  secret embedded in a public artifact authenticates nothing; every "is this a real client"
  check that depends on it is decorative.
- `*_URL` values are not secrets in any meaningful sense and are fine to ship.

Note the interaction with SC-04 and SC-05: because releases carry no checksums and the tag is
rolling, there is no way to tell which credential set a given downloaded APK contains, which
makes credential rotation hard to reason about after the fact.

**Recommendation:**
1. Treat all four credential values as compromised and rotate them now. They exist in every
   published APK; rotation is not optional and not deferrable.
2. Remove the service-account key and the B2 keys from the client build entirely. A mobile
   client must never hold a cloud provider's master credential. Media access belongs behind
   short-lived, per-user, per-object presigned URLs issued by the Worker after it authenticates
   the user — the client gets a URL scoped to one object with a minutes-long expiry, and never
   sees a bucket key.
3. Replace `WORKER_SECRET` with per-user authentication (Firebase ID token verified by the
   Worker). If a build-identifying value is genuinely needed, it must be understood as a public
   constant and must not gate anything.
4. Add a CI gate that greps the built APK for the high-entropy secret values and fails the
   release if any is present, so this cannot regress silently.

---

## SC-03 — No Gradle dependency verification

**Severity: High** · `build.gradle`, `app/build.gradle`, no `gradle/verification-metadata.xml`

There is no `verification-metadata.xml`, no `*.lockfile`, and no `dependencyLocking` block. The
build declares roughly 30 Maven coordinates by version string only. Version strings are
resolved over the network at build time and are verified against nothing but the coordinate
name. `security-crypto:1.1.0-alpha06`, `android-database-sqlcipher:4.5.4`,
`libsignal-android:0.54.1`, `okhttp:4.12.0`, `firebase-bom:32.7.0` — all unpinned by hash.

This means a repository compromise, a cache-poisoning attack, or a hostile mirror can substitute
a modified `security-crypto` or `sqlcipher` and the build will accept it and sign it with the
production key. Nothing in the pipeline would notice. It also means builds are not reproducible:
two builds of the same commit at different times can produce different binaries, which
undermines any future attempt to verify a release independently.

Note that the JavaScript side is in better shape — `server/`, `worker/`, and `functions/` all
have committed lockfiles — so this gap is specific to the Android build, which is also the
build that ships the cryptography.

**Recommendation:** generate `gradle/verification-metadata.xml` with
`./gradlew --write-verification-metadata sha256 help`, commit it, and require verification in
CI. Add `dependencyLocking` so transitive resolution is pinned too. Review the generated file
once by hand — that review is the trust decision, and it is worth doing carefully for the
crypto dependencies specifically.

---

## SC-04 — Release artifacts are unverifiable

**Severity: High** · `.github/workflows/release.yml:130, 167`

The workflow uploads per-ABI APKs to a GitHub Release with `softprops/action-gh-release@v2` and
the release body instructs users to "pick the APK for your device architecture." There is no
`SHA256SUMS` file, no `.asc` signature, no `actions/attest-build-provenance` step, and no
record of the signing certificate fingerprint. Grep across `.github/` for
`sha256sum|checksum|attest|provenance|sbom` returns nothing.

For a sideloaded security application this is the wrong default. Users are being asked to
install an APK from a URL with no way to confirm they received the artifact the maintainer
built. The APK is signed (SC-02 notes the keystore flow, and the keystore is correctly wiped
from the runner at line 106 — good), so Android will refuse an *upgrade* signed by a different
key. But a first install has nothing to compare against, and the release page publishes no
fingerprint for a user to check `apksigner verify --print-certs` output against.

**Recommendation:** generate `SHA256SUMS` in the job, attach it to the release, and print the
signing certificate SHA-256 in the release body so it is publicly pinned and users can verify a
fresh install. Add `actions/attest-build-provenance` for signed provenance tying each APK to
the workflow run and commit that produced it.

---

## SC-05 — Release workflow destroys all prior releases and tags

**Severity: High** · `.github/workflows/release.yml:126–162`

On every push to `main`, before publishing, the job paginates the releases API and deletes
every release *and its git tag*:

```bash
gh api --method DELETE "repos/${REPO}/releases/${RELEASE_ID}"
gh api --method DELETE "repos/${REPO}/git/refs/tags/${TAG_NAME}"
```

The comment describes this as intentional ("we never have more than one release live at a
time"), so the mechanism is doing what was asked. The security consequences appear not to have
been part of that decision:

- **The tag is rolling.** The tag defaults to `v{versionName}` from `build.gradle`, so pushes
  that do not bump the version reuse the same tag. The same version string can therefore
  correspond to arbitrarily many different binaries over time. Combined with SC-04's absence of
  checksums, "DuoShield v1.3.1" identifies nothing specific.
- **Release history is unrecoverable.** Deleting the tag deletes the only record of which commit
  a shipped APK was built from. After an incident there is no way to determine what users are
  running, when it shipped, or what changed. This also erases the evidence needed to scope the
  SC-02 credential exposure.
- **`contents: write` plus automatic trigger on `main`.** Any merge to `main` — including one
  from a compromised action or a mis-scoped token — silently destroys the release history as a
  side effect. Deletion is not reviewed; it is a consequence of a normal push.

**Recommendation:** delete this step. Keep releases immutable and tags permanent; that is what
makes them useful as an audit record. If the goal is only that users see one obvious download,
mark old releases as pre-release or rely on GitHub's "Latest" label, both of which are
presentation changes rather than destructive ones. Derive tags from the commit
(`v{version}+{short-sha}`) so a tag always identifies one binary, and require a version bump for
a release rather than allowing tag reuse.

---

## SC-06 — JitPack in the repository list

**Severity: High** · `build.gradle:16`

```gradle
maven { url 'https://jitpack.io' }
```

present to resolve `com.github.chrisbanes:PhotoView:2.3.0`.

JitPack differs from Maven Central in a way that matters: it builds artifacts on demand from
GitHub tags. Git tags are mutable and the upstream repository owner controls them. A retagged
`2.3.0` yields a different binary under the same coordinate. PhotoView is also effectively
unmaintained, so the upstream account is exactly the kind of dormant-but-still-live dependency
that gets taken over. Because there is no dependency verification (SC-03), a substituted
artifact would be consumed silently — and this one runs inside the process that decrypts and
displays user media.

**Recommendation:** drop the dependency. Pinch-zoom on an `ImageView` is a small amount of code,
and `androidx` alternatives exist. If it must stay, pin its hash via SC-03's verification
metadata and scope the JitPack repository to that single group so it cannot serve anything else:

```gradle
maven {
    url 'https://jitpack.io'
    content { includeGroup 'com.github.chrisbanes' }
}
```

---

## SC-07 — Committed `gradle-wrapper.jar` with no validation

**Severity: Medium** · `gradle/wrapper/gradle-wrapper.jar`, all workflows

`gradle-wrapper.jar` is tracked in git and executed by every CI job before any other step, with
full access to the runner — including, in `release.yml`, the decoded keystore and the entire
secret set. No workflow runs `gradle/actions/wrapper-validation`. A modified wrapper JAR is
among the least visible ways to compromise an Android build, which is why the validation action
exists.

`gradle-wrapper.properties` does set `validateDistributionUrl=true` and pins Gradle 8.7, which
is good, but that validates the *URL*, not the JAR that reads it, and there is no
`distributionSha256Sum` pinning the distribution contents either.

There is also a stray `gradle/gradle-wrapper.properties` tracked alongside the real
`gradle/wrapper/gradle-wrapper.properties`; it is unused and should be removed to avoid
ambiguity about which file governs.

**Recommendation:** add `gradle/actions/wrapper-validation@v4` as the first step of every
workflow that runs `./gradlew`, and add `distributionSha256Sum` to
`gradle-wrapper.properties`.

---

## SC-08 — Actions pinned to mutable tags

**Severity: Medium** · all workflows

Every action reference uses a floating major tag: `actions/checkout@v4`,
`actions/setup-java@v4`, `actions/cache@v4`, `actions/setup-node@v4`,
`actions/upload-artifact@v4`, `softprops/action-gh-release@v2`,
`reactivecircus/android-emulator-runner@v2`.

Tags are pointers the action owner can move. Whoever controls the upstream repository — or an
attacker who gains that control — can change what `@v4` resolves to, and the next release build
runs their code with the keystore and the full secret set in the environment. The two
third-party actions (`softprops`, `reactivecircus`) carry more risk than the first-party
`actions/*` ones, and `softprops/action-gh-release` runs in the job that holds
`contents: write`.

**Recommendation:** pin every action to a full commit SHA with the version in a trailing
comment, e.g. `actions/checkout@b4ffde6…  # v4.2.2`. Prioritize the release workflow, and the
two third-party actions above all.

---

## SC-09 — No dependency, secret, or static analysis scanning

**Severity: Medium** · `.github/`

No `dependabot.yml`, and no CodeQL, `dependency-review-action`, OSV/Trivy/Grype scan, gitleaks
or trufflehog secret scan, and no SBOM generation anywhere in `.github/`. `ci.yml` builds, lints
and tests; it does not assess dependency or secret risk at all.

Practical effect: a published CVE in `okhttp`, `sqlcipher`, `libsignal`, or the Firebase SDKs
produces no signal. Nobody is told. For an app whose value proposition is confidentiality, and
which pulls ~30 third-party artifacts with no version pinning (SC-03), unmonitored dependencies
are a standing liability. Secret scanning is particularly relevant given SC-02: a pre-commit or
CI secret scan is the control that catches the *next* credential from being committed or baked
in.

**Recommendation:** add `dependabot.yml` covering `gradle`, `npm` (all four JS packages), and
`github-actions`; add `github/codeql-action` for Java and JavaScript; add
`actions/dependency-review-action` on pull requests; add gitleaks with the repo history in
scope; generate and attach a CycloneDX SBOM per release, which also supports SC-04.

---

## SC-10 — Firestore deploys use unpinned `npm install` with auditing disabled

**Severity: Medium** · `.github/workflows/firestore.yml:37,41`,
`.github/workflows/firestore-rules-test.yml:38,42`, `server/.npmrc`

Both Firestore workflows install with:

```yaml
run: npm install -g firebase-tools
run: npm install
```

`npm install -g firebase-tools` is entirely unpinned — it takes whatever the latest published
version is at the moment the job runs, and `firebase-tools` is the credential-bearing tool that
then deploys security rules. `npm install` (rather than `npm ci`) is permitted to update the
lockfile and resolve outside it, so committed lockfiles are not actually enforced in CI.

`server/.npmrc` additionally sets `audit=false`, suppressing the one advisory signal npm gives
by default. Combined with SC-09's absence of any scanner, nothing at all is watching these
dependency trees.

The blast radius is meaningful: these workflows deploy Firestore security rules, which are the
server-side authorization boundary for the entire product. A compromised `firebase-tools`
release lands inside the job that rewrites those rules.

**Recommendation:** pin `firebase-tools` to an exact version (`npm install -g
firebase-tools@13.x.y`), switch every `npm install` in CI to `npm ci`, and remove
`audit=false`. Prefer Workload Identity Federation over a long-lived service-account key for
the deploy step.

---

## SC-11 — Production crypto depends on an alpha library

**Severity: Low** · `app/build.gradle:11`

```gradle
implementation 'androidx.security:security-crypto:1.1.0-alpha06'
```

`EncryptedSharedPreferences` — which protects the PIN material and key metadata reviewed in
earlier sessions — comes from an alpha release. Alpha artifacts carry no API or behavioral
stability guarantee and receive no backported security fixes; the 1.1.0 line has sat in
alpha/beta for years and `androidx.security` has effectively stalled. Combined with SC-03, this
version is not even hash-pinned.

Severity is Low because `alpha06` is widely deployed and has no known exploitable defect, and
because the stable 1.0.0 alternative is itself limited. This is a maintenance-risk finding, not
an active vulnerability.

**Recommendation:** pin the hash via SC-03 and track it explicitly as a known-stale
dependency. Given the app already holds a SQLCipher-backed encrypted database and an
AndroidKeyStore key, consider consolidating small-secret storage onto that path and dropping
`security-crypto` rather than carrying an unmaintained alpha in the trust base.

---

## SC-12 — CODEOWNERS present, enforcement unverified

**Severity: Low** · `.github/CODEOWNERS`

`CODEOWNERS` exists, which is a good sign, but it is inert on its own: it only has effect if
branch protection on `main` requires code-owner review. Branch protection is repository
configuration and is not visible in the tree, so this could not be confirmed from source.

This matters most as the compensating control for SC-01 and SC-05 — both of which assume that
someone reviews changes to `app/libs/` and to the release workflow before they reach `main`,
and `release.yml` triggers automatically on push to `main`.

**Recommendation:** confirm branch protection on `main` requires pull requests, code-owner
review, and passing status checks, and blocks force-push. Ensure `app/libs/`,
`scripts/strip_signal_records.py`, `.github/workflows/`, and the Firestore rules each have an
owner entry. If protection is not enabled, this becomes the highest-leverage fix in the session,
because it is what several other recommendations rest on.

---

## Cross-session links

- **SC-02 ↔ Session 07 / earlier:** `WORKER_SECRET` being publicly recoverable invalidates the
  client-authentication assumption behind the Worker's request handling. Any finding that
  treated it as a secret should be re-read with that in mind.
- **SC-01 ↔ Session 07:** Session 07 reviewed the crypto *source*. SC-01 establishes that the
  crypto *binary* the source compiles against is not the binary users run, and that the shipped
  one is not reproducible. Source-level review conclusions hold only as far as the artifact
  matches — which I verified is true today, and which nothing enforces tomorrow.
- **SC-04 / SC-05 ↔ SC-02:** with no checksums and a rolling tag, it is impossible to determine
  which APKs contain which credential generation, which complicates scoping the rotation SC-02
  requires.

---

## Suggested order of work

1. **Rotate the four leaked credentials** (SC-02). Everything else can wait; this cannot.
2. **Confirm branch protection** (SC-12) — cheap, and other controls depend on it.
3. **Remove B2 and service-account keys from the client build** (SC-02), replacing them with
   Worker-issued presigned URLs.
4. **Make the vendored JAR reproducible and hash-asserted in CI** (SC-01).
5. **Stop deleting releases and tags** (SC-05) — a one-line deletion that restores the audit
   trail.
6. **Add checksums and provenance to releases** (SC-04).
7. **Generate and commit dependency verification metadata** (SC-03), then scope or drop JitPack
   (SC-06).
8. **Add wrapper validation, SHA-pin actions, enable scanners, pin npm installs**
   (SC-07, SC-08, SC-09, SC-10).

---

## Notes on what was done well

Worth recording, since audits skew negative:

- The keystore handling in `release.yml` is careful: it fails loudly if `KEYSTORE_BASE64` is
  absent rather than silently producing an unsigned build, and it removes the decoded keystore
  from the runner disk afterward with `if: always()`.
- Release builds set `minifyEnabled true`, `shrinkResources true`, and `debuggable false`.
- All four JavaScript packages have committed lockfiles.
- `validateDistributionUrl=true` is set and the Gradle version is pinned.
- No `.env` files, `node_modules`, or `local.properties` are tracked in git.
- `scripts/strip_signal_records.py` carries a genuinely thorough explanation of *why* the
  workaround exists, and `app/build.gradle` documents the three-line libsignal arrangement in
  detail. The intent was recorded; it is the enforcement that is missing.
- The `firestore-rules-test.yml` workflow exists at all — testing security rules is a step many
  projects skip.
