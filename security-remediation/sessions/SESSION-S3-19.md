# SESSION-S3-19 — Android surface hardening

**Lane:** AND (verify BLOCKED → S3-19b)
**Status:** PARTIAL, **IN PROGRESS — NOT scope-complete.** Only **2 of the 7**
plan-scoped findings are addressed: `S08-M1` and `S08-M2`, both source-fixed
and landing **Partial**. The remaining five (`S08-L1`, `S08-L2`, `S08-L3`,
`S08-I1`, `S06-L4`) are **untouched** and stay **Open**. Because five scoped
items are still open, the chain state does **not** advance to `S3-20` — `S3-19`
remains the current/next session.
**Model:** v0
**Sequencing note:** this is the plan's actual next scheduled session after
`S3-18` — no out-of-order pickup. `S3-18` was scope-complete per its own
session log and `START_HERE.md`'s chain state before this session began.

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

> ### S3-19 — Android surface hardening · lane AND (verify BLOCKED → S3-19b)
> Findings: `S08-M1` (re-enable heap pointer tagging), `S08-M2` (scope
> `file_paths.xml`, drop unused external roots), `S08-L1` (validate deep-link
> Account ID), `S08-L2` (`EXTRA_IS_SENSITIVE` on clipboard), `S08-L3` (don't
> store PIN length beside hash), `S08-I1` (stop R8 keeping
> `crypto.**`/`security.**` names), `S06-L4` (`AccountLockWorker` report real
> failure + cap 5xx retries).
> Exit: source-reviewed; pending S3-19b.

**Note on scope arithmetic:** the plan lists **7** findings for S3-19 (above).
`S08-M3` is **not** one of them — it carries a separate `Accepted` disposition
("no root/tamper detection — documented as out of threat model") in
`BUG_TRACKER.md` and is not part of this session's remaining work. This session
addresses `S08-M1` + `S08-M2` (2 of 7); the outstanding five are `S08-L1`,
`S08-L2`, `S08-L3`, `S08-I1`, `S06-L4`.

Read before touching anything: `START_HERE.md`, `SESSION_INDEX.md`,
`SESSION_PROTOCOL.md`, `ROUND3_REMEDIATION_PLAN.md`, and the `BUG_TRACKER.md`
rows for `S08-M1` and `S08-M2`. Per protocol §3 ("source beats tracker"), every
tracker/commit claim below was independently re-verified against current source
— reading the already-landed implementation (`git show 51f5573`) and the live
files line by line, not trusting the commit message.

## State found at session start

`git log --oneline -8` showed the implementation for the first two findings
already on this branch:

- `2ad7fc1` — `feat: update security remediation plan for S3-19 session`
  (documentation: `START_HERE.md` chain-state line advanced to
  "S3-19 IN PROGRESS — 2 of 7")
- `980bf12` — `feat: update BUG_TRACKER.md and Android manifest changes for
  S08-M1 and S08-M2` (documentation: the `S08-M1`/`S08-M2` tracker rows flipped
  `Open` → `**Partial** (S3-19)`)
- `51f5573` — `fix(s08-m1,s08-m2): re-enable heap pointer tagging, scope
  FileProvider paths` (implementation: `AndroidManifest.xml`, `file_paths.xml`,
  new `util/SharedCacheDir.java`, `ChatMediaActivity.java`,
  `GroupChatActivity.java`, `util/SecureShareHelper.java`,
  `util/ChatExportHelper.java`, `util/TempFileCleaner.java`, and the new
  `S319ManifestTest.java`)

`git status` was clean — nothing uncommitted. `BUG_TRACKER.md` already carried
`S08-M1` and `S08-M2` as `**Partial** (S3-19)` (from `980bf12`). The implementation
and tracker rows for the first two findings had landed, but the session log
(this file) had not been written and the `SESSION_INDEX.md` Round-3 summary had
not been updated to say S3-19 had started. This session's job was therefore:
independently re-verify the two landed findings against current source, correct
the chain-state's remaining-findings list (which had been written with the wrong
IDs), reconcile `SESSION_INDEX.md`, and write this session log — **not** new
implementation, and **not** an advance to S3-20.

## Source verification performed (before writing any documentation)

Every claim below was checked by reading current file content and/or the
`51f5573` diff, not inferred from the commit message.

### S08-M1 — native heap pointer tagging disabled process-wide

- **`AndroidManifest.xml`**: confirmed `android:allowNativeHeapPointerTagging`
  is **absent** from the `<application>` tag —
  `grep -n "allowNativeHeapPointerTagging" app/src/main/AndroidManifest.xml`
  returns **nothing**. Its absence restores the platform default (`true`),
  re-enabling pointer tagging (Android 11+, arm64 Top-Byte-Ignore) as a
  memory-safety mitigation for the process. The three native libraries this
  process loads that parse attacker-controlled bytes — libsignal's Rust core,
  WebRTC media parsing, SQLCipher — are the reason the mitigation matters here.
- **Well-formedness**: `xmllint --noout app/src/main/AndroidManifest.xml`
  exits clean — the manifest is still valid XML after the attribute removal.

### S08-M2 — FileProvider roots scoped to whole directories

- **`file_paths.xml`**: confirmed it now declares exactly one root —
  `<cache-path name="shared" path="shared/" />` — with a leading comment
  documenting why the previous four `path="."`-scoped roots (`cache-path`,
  `external-cache-path`, `files-path`, `external-files-path`) were collapsed to
  it. The two external-storage roots and the unscoped `files-path` root are
  **deleted outright**, not merely narrowed. `xmllint --noout` on the file
  exits clean.
- **`util/SharedCacheDir.java`** (new): confirmed it centralizes the single
  FileProvider-grantable root `getCacheDir()/shared/` (`ROOT_NAME = "shared"`,
  which the class doc explicitly ties to `file_paths.xml`'s `shared` cache-path)
  and exposes three purpose subdirectories: `camera(ctx)` → `shared/camera/`,
  `media(ctx)` → `shared/media/`, `export(ctx)` → `shared/export/`, plus
  `root(ctx)` for the cleaner's sweep. `subdir()` calls `mkdirs()` (a safe
  no-op when the directory already exists).
- **Call sites** (all four confirmed migrated off the cache root via
  `grep -rn "SharedCacheDir" app/src/main/java/`):
  - `ChatMediaActivity.java:2463` — `cam_*.jpg` camera captures →
    `SharedCacheDir.camera(this)`.
  - `GroupChatActivity.java:863` — `grp_cam_*.jpg` camera captures →
    `SharedCacheDir.camera(this)`.
  - `util/SecureShareHelper.java:30` — shared/decrypted image →
    `SharedCacheDir.media(ctx)`.
  - `util/ChatExportHelper.java:205` — export ZIP →
    `SharedCacheDir.export(ctx)`.
- **`util/TempFileCleaner.java`** (S08-H3 interaction handled): confirmed
  `sweepDir(File[], long)` was extracted from the existing per-file loop and is
  now called a **second** time over each `getCacheDir()/shared/<category>/`
  subdirectory (`SharedCacheDir.root(...)` → `listFiles()` → per-category
  `sweepDir(...)`), applying the identical filename/age rules already used at
  the cache root. This is the reason relocating these four file classes into
  `shared/**` did **not** silently stop them from being cleaned up (which would
  have reopened S08-H3). Confirmed by reading the current file: `sweepDir` at
  line 100, the shared-tree sweep at lines 76–84.

### Regression coverage — `S319ManifestTest.java` (new, not run)

- Confirmed `app/src/test/java/com/duoshield/app/S319ManifestTest.java` exists.
  It is a plain-JVM `javax.xml.parsers.DocumentBuilder` test (no Android SDK /
  Robolectric), mirroring `LockScreenManifestTest`'s approach from S3-18:
  - `applicationTag_hasNoHeapPointerTaggingOverride()` parses the manifest
    namespace-aware and asserts the single `<application>` element does **not**
    carry `android:allowNativeHeapPointerTagging` (S08-M1).
  - `filePaths_declaresOnlyScopedSharedCacheRoot()` parses `file_paths.xml`
    namespace-**unaware** (the file declares no `xmlns:android` and, per
    FileProvider's documented meta-data format, its `name`/`path` attributes
    are unprefixed) and asserts exactly one `cache-path` root named `shared`
    with `path="shared/"`, and zero `external-cache-path` / `external-files-path`
    / `files-path` elements (S08-M2).
- **Not compiled or run** — no JDK/Gradle/Android SDK in this environment. The
  assertions were hand-verified against the live files with `xmllint`/`grep`
  before the test was written; the file itself is routed to S3-19b like every
  other AND-lane artifact this round.

## Disposition

### S08-M1 — re-enable heap pointer tagging → **Partial**

- **Fix (already landed, commit `51f5573`):** as verified above —
  `android:allowNativeHeapPointerTagging="false"` deleted from the manifest's
  `<application>` tag, restoring the default `true` and re-enabling the arm64
  pointer-tagging memory-safety mitigation process-wide.
- **Why Partial, not Fixed:** no JDK/Gradle/Android SDK in this environment —
  source-reviewed and `xmllint`-validated (manifest still well-formed after the
  removal), not compiled or run on a device/emulator. The finding also asks
  that "if a library genuinely faults with tagging enabled, identify it, file
  it upstream, and record the specific reason inline" — that verification needs
  a real build+run and is therefore part of **S3-19b**; if a genuine fault
  surfaces there, the attribute should be reinstated with an inline comment
  naming the specific library, not restored unconditionally. AND-lane
  verification routed to **S3-19b**.

### S08-M2 — scope `file_paths.xml`, drop unused external roots → **Partial**

- **Fix (already landed, commit `51f5573`):** the four whole-directory-scoped
  FileProvider roots collapsed to a single narrowly-scoped
  `getCacheDir()/shared/` root (new `util/SharedCacheDir.java`, split into
  `camera`/`media`/`export`); the four call sites that create FileProvider-shared
  files now write under it instead of the cache root; `TempFileCleaner`'s sweep
  was extended to the new `shared/**` tree so the relocation did not reopen
  S08-H3. A `Uri` grant for any shared file is now scoped to `shared/` only, no
  longer to the whole cache directory (which also holds S08-H3's plaintext Glide
  disk cache).
- **Why Partial, not Fixed:** same AND-lane toolchain block as `S08-M1` —
  source-reviewed, `xmllint`-validated, Java brace/paren balance-checked, not
  compiled or instrumentation-tested. AND-lane verification routed to
  **S3-19b**.

## Scope NOT completed this session (recorded, not hidden)

The committed implementation (`51f5573`) covered only `S08-M1` and `S08-M2`.
The following five plan-scoped findings were **not** implemented and were
**not** touched by `51f5573`; they remain **Open** and are carried forward as
the remaining S3-19 work (this is why the chain state does **not** advance to
S3-20):

- **`S08-L1`** — validate the deep-link Account ID. Not attempted.
- **`S08-L2`** — set `EXTRA_IS_SENSITIVE` on clipboard writes. Still `Open`
  in `BUG_TRACKER.md` ("Clipboard writes without `EXTRA_IS_SENSITIVE`"). Not
  attempted.
- **`S08-L3`** — stop storing the PIN length beside the PIN hash. Still `Open`
  in `BUG_TRACKER.md` ("PIN length stored beside PIN hash"). Not attempted.
- **`S08-I1`** — stop R8 keeping `crypto.**`/`security.**` symbol names. Not
  attempted.
- **`S06-L4`** — `AccountLockWorker` should report real failure and cap 5xx
  retries. Not attempted.

## Files changed (already committed before this session's documentation pass)

Implementation — all in commit `51f5573`:

- `app/src/main/AndroidManifest.xml` — `android:allowNativeHeapPointerTagging`
  removed from `<application>`. (`S08-M1`)
- `app/src/main/res/xml/file_paths.xml` — four roots collapsed to one
  `<cache-path name="shared" path="shared/" />` (+ explanatory comment).
  (`S08-M2`)
- `app/src/main/java/com/duoshield/app/util/SharedCacheDir.java` — new; the one
  FileProvider-grantable root split into `camera`/`media`/`export`. (`S08-M2`)
- `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` — `cam_*.jpg`
  capture target → `SharedCacheDir.camera(this)`. (`S08-M2`)
- `app/src/main/java/com/duoshield/app/GroupChatActivity.java` — `grp_cam_*.jpg`
  capture target → `SharedCacheDir.camera(this)`. (`S08-M2`)
- `app/src/main/java/com/duoshield/app/util/SecureShareHelper.java` — shared
  image → `SharedCacheDir.media(ctx)`. (`S08-M2`)
- `app/src/main/java/com/duoshield/app/util/ChatExportHelper.java` — export ZIP
  → `SharedCacheDir.export(ctx)`. (`S08-M2`)
- `app/src/main/java/com/duoshield/app/util/TempFileCleaner.java` — `sweepDir()`
  extracted and called a second time over `getCacheDir()/shared/<category>/`.
  (`S08-M2`, S08-H3 interaction)
- `app/src/test/java/com/duoshield/app/S319ManifestTest.java` — new plain-JVM
  XML-parse regression test for both findings (not compiled/run). (`S08-M1`,
  `S08-M2`)

Documentation this session (separate commit, deliberately NOT recorded by hash
inside this file — the hash is written to `START_HERE.md`'s chain state after
the commit, per protocol, so this session log never claims its own future
commit): the `S08-M1`/`S08-M2` tracker rows were already updated in `980bf12`;
this session writes `sessions/SESSION-S3-19.md` (this file), corrects the
remaining-findings list in `security-remediation/START_HERE.md`, and updates the
Round-3 summary in `security-remediation/SESSION_INDEX.md`. No source, server,
or Firestore-rules files were modified by this session.

## Test evidence

### Toolchain availability (checked, not assumed)

```
$ which java javac gradle
(no output — none found on PATH; gradlew wrapper present but unusable without a JDK)
```

No JDK, no runnable Gradle, no Android SDK in this environment. Consistent with
every AND-lane session in `SESSION_INDEX.md` — **compilation and
instrumented/unit test execution were not run and are not claimed.**

### Structural checks actually run (substitute for compile)

- `xmllint --noout app/src/main/AndroidManifest.xml` → clean (well-formed after
  the S08-M1 attribute removal).
- `xmllint --noout app/src/main/res/xml/file_paths.xml` → clean (well-formed
  after the S08-M2 collapse to one root).
- `grep -n "allowNativeHeapPointerTagging" app/src/main/AndroidManifest.xml` →
  no hits (S08-M1 attribute confirmed absent).
- `grep -rn "SharedCacheDir" app/src/main/java/` → the four migrated call sites
  plus `SharedCacheDir.java`/`TempFileCleaner.java` (S08-M2 wiring confirmed).

These prove the edits are structurally well-formed and the wiring is present;
they do **not** prove the Java compiles (no type-checking, symbol resolution, or
annotation processing) — that gate is S3-19b's.

## Toolchain / Android blocker (proven, not asserted)

- `which java javac gradle` — all three return nothing; no `JAVA_HOME`; no
  Android SDK directory.
- Not a new or session-specific blocker — identical to every prior AND-lane
  session. No workaround was attempted that would risk a false "compiles" claim.

## Verification NOT run (recorded, not fabricated)

- **Full app compile** (`./gradlew assembleDebug` or equivalent) — BLOCKED, no
  JDK/Gradle/SDK. Routed to S3-19b.
- **`S319ManifestTest.java` execution** (JVM unit test) and any Android
  instrumented test exercising FileProvider grants against the new `shared/`
  root, or a device run confirming no native library faults with pointer
  tagging re-enabled — BLOCKED, no JDK/SDK/emulator. Routed to S3-19b.
- **Remaining five S3-19 findings** (`S08-L1`, `S08-L2`, `S08-L3`, `S08-I1`,
  `S06-L4`) — NOT implemented this session; carried forward as remaining S3-19
  work (see "Scope NOT completed").

## Diff review before finishing (per task instructions)

- Implementation (`51f5573`) and the `S08-M1`/`S08-M2` tracker rows (`980bf12`)
  were already committed before this pass; this session did not re-touch any
  source or tracker file. This session's own changes are documentation only:
  this file plus `START_HERE.md` (remaining-findings correction) and
  `SESSION_INDEX.md` (Round-3 summary).
- No accidental encoding changes; no cosmetic-only reformatting bundled in.

## Chain state

S3-19 is **partially** complete: `S08-M1` and `S08-M2` are source-fixed,
verified from source, structurally checked, and land **Partial** (AND-lane
compile/test execution BLOCKED → **S3-19b**). The remaining five findings
(`S08-L1`, `S08-L2`, `S08-L3`, `S08-I1`, `S06-L4`) are **not** implemented and
remain **Open**.

Because five scoped items are still open, the chain state does **not** advance
to S3-20. `S3-19` remains the current/next session. Neither catch-up gate has
run: `S08-H2`/`S08-H3`/`S08-L4` (from S3-18) plus this session's
`S08-M1`/`S08-M2` all have AND-lane compile/test verification BLOCKED and routed
to **S3-19b**, and `S3-15b` (RULES) is likewise still outstanding.

## Session record

```
SESSION: S3-19  MODEL: v0  CLUSTER: Android surface hardening (S08-M1, S08-M2, S08-L1, S08-L2, S08-L3, S08-I1, S06-L4)  STATUS: partial, IN PROGRESS — 2 of 7 findings addressed (S08-M1, S08-M2 → Partial, AND-verification BLOCKED → S3-19b); 5 remaining OPEN; chain does NOT advance to S3-20
SEQUENCING: plan's actual next scheduled session after S3-18 — no out-of-order pickup. S3-18 was scope-complete before this session began.
CHANGES (S08-M1 + S08-M2 already committed on this branch before this documentation pass, commit 51f5573):
  - app/src/main/AndroidManifest.xml: android:allowNativeHeapPointerTagging removed from <application> — restores default true, re-enables arm64 pointer tagging for libsignal/WebRTC/SQLCipher native code (S08-M1)
  - app/src/main/res/xml/file_paths.xml: four path="." roots (cache/external-cache/files/external-files) collapsed to one <cache-path name="shared" path="shared/" /> (S08-M2)
  - app/src/main/java/com/duoshield/app/util/SharedCacheDir.java: new — sole FileProvider-grantable root getCacheDir()/shared/, split into camera/media/export (S08-M2)
  - app/src/main/java/com/duoshield/app/ChatMediaActivity.java + GroupChatActivity.java: cam_*.jpg / grp_cam_*.jpg captures → SharedCacheDir.camera(this) (S08-M2)
  - app/src/main/java/com/duoshield/app/util/SecureShareHelper.java: shared image → SharedCacheDir.media(ctx) (S08-M2)
  - app/src/main/java/com/duoshield/app/util/ChatExportHelper.java: export ZIP → SharedCacheDir.export(ctx) (S08-M2)
  - app/src/main/java/com/duoshield/app/util/TempFileCleaner.java: sweepDir() extracted, called again over getCacheDir()/shared/<category>/ so relocation doesn't reopen S08-H3 (S08-M2)
  - app/src/test/java/com/duoshield/app/S319ManifestTest.java: new plain-JVM XML-parse regression test for S08-M1 + S08-M2 (not compiled/run)
NOT DONE (carried forward as remaining S3-19): S08-L1 (deep-link Account ID validation), S08-L2 (EXTRA_IS_SENSITIVE on clipboard), S08-L3 (PIN length beside hash), S08-I1 (R8 keeping crypto.**/security.** names), S06-L4 (AccountLockWorker real-failure reporting + 5xx retry cap)
DOCUMENTATION:
  - BUG_TRACKER.md: S08-M1 + S08-M2 rows Open → Partial (S3-19) — already committed in 980bf12 before this pass; not re-touched
  - START_HERE.md: NEXT SESSION line kept at S3-19 (IN PROGRESS — 2 of 7), remaining-findings list corrected to S08-L1/S08-L2/S08-L3/S08-I1/S06-L4 (previously listed wrong IDs); NOT advanced to S3-20
  - SESSION_INDEX.md: Round-3 summary updated to record S3-19 has started (2 of 7 done) and has NOT advanced to S3-20; out-of-order/session history preserved
  - SESSION-S3-19.md: this file (session log); documentation commit is separate from implementation and its hash is intentionally NOT recorded inside this file
VERIFICATION:
  PASS: independent source re-derivation of S08-M1 + S08-M2 against current code — allowNativeHeapPointerTagging confirmed absent from the manifest; file_paths.xml confirmed to declare only the scoped shared/ cache-path; SharedCacheDir + the 4 migrated call sites + the extended TempFileCleaner sweep all present as claimed
  PASS: xmllint --noout on AndroidManifest.xml and file_paths.xml (both well-formed after the edits)
  PASS: grep confirms the M1 attribute is gone and the 4 SharedCacheDir call sites are wired
  BLOCKED: JDK/Gradle/Android SDK compile + unit/instrumented test execution (incl. running S319ManifestTest.java) — which java/javac/gradle all empty on PATH. Routed to S3-19b for both findings.
COMMIT: 51f5573 (S08-M1/S08-M2 implementation + S319ManifestTest, already on branch) ; 980bf12 (S08-M1/S08-M2 tracker rows, already on branch) ; documentation commit for this session's session-log/index/chain-state changes is separate and its hash is written to START_HERE.md after the commit, not here  WORKTREE: clean
NEXT SESSION: S3-19 (STILL — IN PROGRESS, 2 of 7 done). Remaining 5: S08-L1, S08-L2, S08-L3, S08-I1, S06-L4. Do NOT start S3-20. Do NOT claim S3-15b or S3-19b done — neither catch-up gate has run; all of S08-M1/S08-M2 (this session) plus S08-H2/S08-H3/S08-L4 (S3-18) AND-lane compile/test verification remains BLOCKED pending S3-19b.
```
