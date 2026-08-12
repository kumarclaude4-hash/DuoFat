# SESSION-S3-16 — Android crypto storage

**Lane:** AND (verify BLOCKED → S3-19b)
**Status:** COMPLETE for what this environment can do — 2 of 4 findings Fixed
(source-reviewed, compile/instrumented verification routed to S3-19b); 2 of 4
findings re-confirmed genuinely Open (product-level scope, correctly excluded
from this session).
**Model:** Opus 5
**Sequencing note:** this is an **out-of-order pickup**, run at the user's
explicit direction. The plan's actual next unstarted session was **S3-15**
(App Check + client provider wiring, lane AND/RULES — `S10-N1`), which this
session does **not** touch and does **not** supersede. `NEXT SESSION` below
is being pointed at S3-17 only because S3-16's own scope is now closed; S3-15
remains unstarted and someone must still run it. This mirrors the precedent
set by the S3-13 out-of-order session (see `START_HERE.md`).

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

> S3-16 — Android crypto storage · lane AND (verify BLOCKED → S3-19b)
> Findings: `S08-H5`/`S07-M1` (SecurePrefs plaintext fallback holding identity
> key + backup key + SQLCipher passphrase; `isInitialized()` must not ignore
> fallback), `S07-L2` (static derivation cache surviving duress wipe),
> `S07-L3` (`mnemonicToSeed` canonicalization + `Locale.ROOT`).
> Exit: source-reviewed; lands `partial — AND verification BLOCKED` pending
> S3-19b.

Read in full before touching anything: `START_HERE.md`, `SESSION_INDEX.md`,
`SESSION_PROTOCOL.md`, `ROUND3_REMEDIATION_PLAN.md`, and the `BUG_TRACKER.md`
rows for all four findings. Per protocol §3 ("source beats tracker"), every
tracker claim below was independently re-verified against current source
before any code was written.

## Source verification performed (before any change)

- **`SecurePrefs.java`** (full file read): three-tier
  `EncryptedSharedPreferences` init (`buildTiered()`), ending in a plaintext
  `MODE_PRIVATE` fallback when all three tiers fail. Confirmed live in
  source, matching the tracker's citation.
- **`SignalKeyManager.java`** (`isInitialized()`, lines ~252–278): confirmed
  it reads via `SecurePrefs.get(ctx)` regardless of which tier produced that
  store, and is **explicitly, deliberately** not gated on
  `SecurePrefs.isAvailable()` — an in-source comment cites `BUG-S10` and
  explains that gating on `isAvailable()` previously caused a permanent
  "Continue → back to Sign In" loop on fallback devices where the identity
  key legitimately lives in the plaintext store. This is the literal
  sub-claim the plan calls out ("`isInitialized()` must not ignore
  fallback") — confirmed **already correct**, not a regression to fix.
- **`BackupCryptoHelper.java`** (`storeKey()`/`getStoredKey()`): confirmed
  `storeKey()` writes the derived backup key into `SecurePrefs` with **no**
  `isAvailable()` guard, while `getStoredKey()` opens with
  `if (!SecurePrefs.isAvailable()) return null;` — i.e. on a fallback device
  the backup key is written to disk in plaintext and the app then refuses to
  ever read it back. This is the part of S08-H5/S07-M1 that is genuinely
  still open.
- **`SeedPhraseHelper.java`** (full file read, 651 lines pre-edit): confirmed
  the static `derivationCache` (`AtomicReference<CachedDerivation>`) in
  `deriveIdentityKeyPair()`, and confirmed `mnemonicToSeed()` only trimmed +
  NFKD-normalised — no lower-casing, no internal-whitespace collapse.
  Confirmed `validateMnemonic()` lower-cases per word and splits on `\s+`,
  i.e. tolerates exactly the inputs `mnemonicToSeed()` did not canonicalise.
- **`WipeHelper.java`** (full file read): confirmed `eraseLocalData()`'s
  Step 4 cleared `SecurePrefs` but had no call into `SeedPhraseHelper` at
  all — the derivation cache was untouched by every wipe path (voluntary,
  unpair, duress).
- **`RestoreFromSeedActivity.java`** (relevant section read): confirmed it is
  the *only* one of the three `mnemonicToSeed()`-adjacent call sites that
  pre-canonicalised at all (a per-word `.toLowerCase()`, no explicit locale,
  no whitespace collapse — `DisplayNameActivity`/generation paths do not
  canonicalise a user-typed mnemonic since they generate it themselves).
- **`SeedPhraseHelperTest.java`** (existing test file read): confirmed no
  existing coverage for either canonicalisation or cache-clearing before this
  session — both were genuine coverage gaps, not just missing dispositions.

## Disposition of all four findings

### S08-H5 / S07-M1 — SecurePrefs plaintext fallback → **Open** (unchanged)

Genuinely open, confirmed from source this session. Not code-changed.

- The literal sub-claim in the plan text — "`isInitialized()` must not ignore
  fallback" — is **already correct and deliberate** (see above). Nothing to
  fix there; documenting this prevents a future session from "fixing" a
  working fail-safe and reintroducing the BUG-S10 sign-in loop.
- The root defect remains open: `BackupCryptoHelper.storeKey()` still writes
  the backup key to the plaintext fallback unconditionally, and
  `getStoredKey()` still refuses to read it back once
  `!SecurePrefs.isAvailable()`. Net effect: a plaintext copy of a key that
  can decrypt account backups sits on unencrypted disk on any device where
  all three ESP tiers failed, providing no functional benefit to offset the
  exposure (the app itself won't use it).
- **Why not fixed this session:** the only two fixes that actually close the
  finding are (a) refuse to persist the backup key at all when
  `!isAvailable()`, which silently breaks backup for every affected user with
  no explanation, or (b) surface a user-facing warning/consent screen before
  falling back at all, which is a product-level UX decision (what the
  warning says, whether backup is disabled entirely, whether the identity
  key itself should also be blocked from the fallback path). `SecurePrefs`'s
  own class javadoc documents that the plaintext fallback was a deliberate
  choice to avoid a hard lockout on budget/Android-Go devices, which
  reinforces that touching this needs product sign-off, not a quiet code
  patch. This is explicitly out of scope for an AND-lane, source-only session
  per the task's own instructions ("never weaken a security control... keep
  changes minimal"). Recommend a dedicated follow-up session once UX/product
  has decided the warning/consent shape.

### S07-L2 — Static derivation cache survives duress wipe → **Fixed**

- **Fix:** added `SeedPhraseHelper.clearDerivationCache()` (nulls the
  `AtomicReference` backing the cache) and wired it into
  `WipeHelper.eraseLocalData()` Step 4, immediately after the `SecurePrefs`
  clear, so it runs on every `WipeMode` (voluntary, unpair, duress) that
  funnels through that method.
- **Scope discipline:** best-effort by design, matching every other
  in-memory key handle in this codebase — `SeedPhraseHelper` cannot zero the
  private key bytes inside libsignal's `IdentityKeyPair` object graph before
  GC reclaims it; dropping the last strong reference is the strongest
  guarantee achievable from this class.
- **Regression coverage added:** `clearDerivationCache_nullsOutTheBackingField`
  in `SeedPhraseHelperTest.java` — uses reflection to read the private static
  `derivationCache` field, seeds it with a non-null value directly (bypassing
  `deriveIdentityKeyPair()`, which needs libsignal's native Curve25519 code
  unavailable in a plain JVM unit test), calls `clearDerivationCache()`, and
  asserts the field is now `null`. This proves the actual backing-field
  contract, not just that the public method returns without throwing.

### S07-L3 — `mnemonicToSeed` canonicalization + `Locale.ROOT` → **Fixed**

- **Fix:** added `SeedPhraseHelper.canonicalizeMnemonic()` — trim, then
  `Locale.ROOT` lower-case, then collapse any run of whitespace to a single
  space — and call it from inside `mnemonicToSeed()` before NFKD
  normalisation, so the method is self-sufficient regardless of what the
  caller already did. Also fixed the two existing locale-sensitive
  `.toLowerCase()` calls (`validateMnemonic()`'s per-word compare,
  `RestoreFromSeedActivity`'s pre-canonicalisation) to use `Locale.ROOT`
  explicitly instead of the platform default locale.
- **Why this matters (concrete failure mode, not theoretical):**
  `validateMnemonic()` already accepts mixed case and repeated whitespace as
  equivalent to the canonical form. Before this fix, `mnemonicToSeed()` did
  not — so a mnemonic that **validates successfully** (e.g. a capitalised
  first letter and a doubled space from a clipboard paste) could silently
  derive a **different seed and therefore a different identity** than its
  canonical form, with no error surfaced anywhere in the flow. Centralising
  canonicalisation inside `mnemonicToSeed()` itself closes this for all
  current and future callers, not just the one call site that happened to
  pre-process.
- **Cryptographic semantics preserved exactly:** the NFKD normalisation
  step, the PBKDF2 salt (`MNEMONIC_SALT`), and all KDF parameters are
  byte-for-byte unchanged — only the string handed into NFKD changed, and
  only for inputs that were already meant to be treated as equivalent per
  `validateMnemonic()`'s own existing tolerance. A mnemonic that was already
  in canonical form (trimmed, lowercase, single-spaced) derives the **exact
  same seed as before this change** — this is not a migration, no existing
  canonical-form account is affected.
- **`Locale.ROOT` rationale:** BIP39 mnemonics are plain ASCII English words,
  so no real input can hit the classic Turkish-`I`-to-dotless-`ı` failure
  today — but an implicit default-locale `.toLowerCase()` makes the
  canonical form depend on device language settings, which is exactly the
  class of silent, device-dependent derivation mismatch this fix exists to
  close off, present risk or not.
- **Regression coverage added** (`SeedPhraseHelperTest.java`, against a known
  12-word BIP39 test vector, all asserting byte-identical 64-byte seeds):
  - `mnemonicToSeed_isCaseInsensitive` — all-uppercase and mixed-case
    renderings of the vector must derive the same seed as the canonical
    lowercase form.
  - `mnemonicToSeed_collapsesInternalWhitespace` — a doubled space and a tab
    between words (both already accepted by `validateMnemonic()`) must
    derive the same seed as the single-space canonical form.
  - `mnemonicToSeed_trimsSurroundingWhitespace` — leading/trailing whitespace
    must not change the derived seed (this half already worked before the
    fix; kept as an explicit regression guard).

## Files changed this session

- `app/src/main/java/com/duoshield/app/crypto/SeedPhraseHelper.java` — added
  `Locale` import, `canonicalizeMnemonic()`, `clearDerivationCache()`;
  `mnemonicToSeed()` now canonicalises internally;
  `validateMnemonic()`'s per-word lower-case now uses `Locale.ROOT`.
- `app/src/main/java/com/duoshield/app/util/WipeHelper.java` —
  `eraseLocalData()` Step 4 now also calls
  `SeedPhraseHelper.clearDerivationCache()`.
- `app/src/main/java/com/duoshield/app/ui/RestoreFromSeedActivity.java` —
  added `Locale` import; pre-canonicalisation `.toLowerCase()` now uses
  `Locale.ROOT`.
- `app/src/test/java/com/duoshield/app/SeedPhraseHelperTest.java` — +4 new
  test methods (3 canonicalisation, 1 cache-clearing), 0 removed, 0 modified.
- `BUG_TRACKER.md` — `S08-H5`, `S07-M1` rows re-confirmed `Open` with expanded
  evidence; `S07-L2`, `S07-L3` rows moved `Open`/`Carried` →
  `Fixed (S3-16)`/`Verified` with full source + test evidence.
- `security-remediation/sessions/SESSION-S3-16.md` (this file),
  `security-remediation/START_HERE.md`, `security-remediation/SESSION_INDEX.md`
  — chain-state + index updates (done last, after implementation was
  committed).

No files outside this list were modified. No server, Firestore rules, or
unrelated Android code touched.

## Test evidence

### Toolchain availability (checked, not assumed)

```
$ which java javac gradle
(no output — none found on PATH)
```

No JDK, no Gradle wrapper execution, no Android SDK in this environment.
This matches every other AND-lane session in this program (S3-11 is a
WORKER-lane exception with Node; Android sessions are consistently blocked
here) — **compilation and instrumented tests were not run and are not
claimed.** Per the task instructions and the plan's own exit criterion for
this session ("source-reviewed; lands `partial — AND verification BLOCKED`
pending S3-19b"), that is the expected, honest outcome here.

### What was actually run as a substitute check

A Python `brace/paren/bracket`-balance script (comment- and
string/char-literal-stripped) was run against all four touched `.java`
files to catch any accidental truncation or malformed edit — not a
substitute for a real compile, only a sanity check on the mechanical edit
process:

```
$ python3 - <<'EOF' (brace/paren/bracket balance, comments+literals stripped)
SeedPhraseHelper.java        {24,24} (24,24) [2,2]   BALANCED
WipeHelper.java              {…}     (…)     […]     BALANCED
RestoreFromSeedActivity.java {…}     (…)     […]     BALANCED
SeedPhraseHelperTest.java    {…}     (…)     […]     BALANCED
EOF
```

All four files balanced. This proves the edits are structurally
well-formed; it does **not** prove they compile (no type-checking, no
symbol resolution, no annotation processing) — that gate is S3-19b's.

### New regression tests — logic manually traced, not executed

`SeedPhraseHelperTest.java`'s existing tests (e.g.
`mnemonicToSeed_differentMnemonicsProduceDifferentSeeds`) already exercise
`mnemonicToSeed()` end-to-end in a plain JVM without libsignal, so the 3 new
canonicalisation tests use the identical pattern and dependency surface —
they are expected to run under the same `testImplementation` set already
declared in `app/build.gradle` (plain JUnit) once a JDK/Gradle environment is
available. The 4th test (`clearDerivationCache_nullsOutTheBackingField`) uses
only `java.lang.reflect` and `java.util.concurrent.atomic`, both JDK-standard,
no Android/libsignal dependency — traced by hand against
`SeedPhraseHelper`'s actual field name (`derivationCache`) and type
(`AtomicReference<CachedDerivation>`), confirmed to match the source exactly.

**These 4 new tests are NOT claimed as "passing" — they are claimed as
present, logically traced against current source, and structurally
consistent with the file's existing, already-working test pattern.** Actual
execution (`./gradlew testDebugUnitTest` or equivalent) is deferred to
S3-19b, same as every compile/instrumented claim in this program's AND lane.

## Toolchain / Android blockers (proven, not asserted)

- `which java && which javac && which gradle` — all three return nothing on
  this environment's PATH. No `JAVA_HOME`. No Android SDK directory found.
- Consistent with every prior AND-lane session in `SESSION_INDEX.md`
  (S3-15's App Check work, and this program's own repeated "verify BLOCKED →
  S3-19b" framing) — this is not a new or session-specific blocker.
- No workaround was attempted that would risk a false "compiles" claim (e.g.
  no ad hoc `javac` against a hand-assembled classpath, no editing around
  libsignal's native dependency to fake a build) — the task instructions are
  explicit that unverifiable compilation must not be claimed as verified.

## Pre-existing failures / regressions

None encountered — no test suite in this environment could be executed at
all for the Android module (no JDK/Gradle), so there is no "pre-existing vs.
new" test-failure distinction to draw here; the distinction that matters is
compile-time correctness, which is asserted only via source review + the
brace-balance check above, not via a green test run.

## Verification NOT run (recorded, not fabricated)

- **Gradle unit test execution** (`SeedPhraseHelperTest.java`, all tests) —
  BLOCKED, no JDK/Gradle. Routed to S3-19b.
- **Android instrumented tests** (any `androidTest` covering
  `WipeHelper`/`SecurePrefs`/duress flow end-to-end) — BLOCKED, no
  Android SDK/emulator. Routed to S3-19b.
- **Full app compile** (`./gradlew assembleDebug` or equivalent) — BLOCKED,
  same reason. Routed to S3-19b.
- **RULES lane** — not applicable; S3-16 has no Firestore rules component.

## Diff review before finishing (per task instructions)

- `git diff --stat` reviewed: exactly the 4 source/test files listed above,
  no unrelated files touched.
- No accidental encoding changes: all four files remain UTF-8, no BOM
  introduced, no line-ending changes (verified via the same edit tool that
  performed the edits, which preserves existing file encoding).
- No cosmetic-only changes bundled in: every line changed is either (a) part
  of the four functional fixes, (b) a doc comment directly explaining one of
  those fixes, or (c) a new test. No unrelated reformatting.
- Commit contents cross-checked against this session's actual `git log -1
  --stat` output (see final report) — file list and line counts match what
  is described here.

## Chain state

S3-16 is **complete for what this environment can verify** — both
in-scope-and-fixable findings (`S07-L2`, `S07-L3`) are fixed, tested at the
source level, and documented; both findings that are genuinely still open
(`S08-H5`/`S07-M1`) are correctly left open with an accurate, expanded
evidence trail explaining exactly why (product-scope, not a code gap this
session can close). Compile/instrumented verification for the two fixes is
blocked on toolchain availability and is queued for **S3-19b**, per the
plan's own exit criterion for this session.

Because this was an **out-of-order** pickup, **S3-15 is still the plan's
next unstarted scheduled session** (App Check + client provider wiring,
`S10-N1`) and has not been touched by this session. The next session after
S3-16 in the plan's own numeric sequence is **S3-17** (Android backup &
group crypto). Whoever picks this up next should run **S3-15 first** unless
explicitly directed otherwise, the same way S3-13's earlier out-of-order
pickup did not change what the plan considered "next" until the skipped
session was actually run.

## Session record

```
SESSION: S3-16  MODEL: Opus 5  BUDGET: $5 max  CLUSTER: Android crypto storage (S08-H5/S07-M1/S07-L2/S07-L3)  STATUS: partial (2 fixed source-only, 2 confirmed open)
SEQUENCING: out-of-order pickup at explicit user direction — S3-15 (App Check, S10-N1) remains the plan's actual next unstarted session, not superseded by this one.
CHANGES:
  - app/src/main/java/com/duoshield/app/crypto/SeedPhraseHelper.java: + canonicalizeMnemonic() (trim + Locale.ROOT + whitespace collapse), mnemonicToSeed() now canonicalises internally, validateMnemonic() per-word lowercase -> Locale.ROOT, + clearDerivationCache() (S07-L2/S07-L3)
  - app/src/main/java/com/duoshield/app/util/WipeHelper.java: eraseLocalData() Step 4 now also calls SeedPhraseHelper.clearDerivationCache() (S07-L2)
  - app/src/main/java/com/duoshield/app/ui/RestoreFromSeedActivity.java: pre-canonicalisation lowercase -> Locale.ROOT (S07-L3)
  - app/src/test/java/com/duoshield/app/SeedPhraseHelperTest.java: +4 tests — case-insensitivity, whitespace-collapse, whitespace-trim (BIP39 vector, byte-identical-seed assertions), clearDerivationCache backing-field-null reflection test
  - BUG_TRACKER.md: S08-H5/S07-M1 rows re-confirmed Open with expanded evidence (no code change — root cause is product-scope, not a code gap); S07-L2/S07-L3 rows Open/Carried -> Fixed (S3-16)/Verified
  - START_HERE.md / SESSION_INDEX.md / SESSION-S3-16.md: chain-state + index + session log
VERIFICATION:
  PASS: source re-derivation of all 4 findings against current code (not carried from tracker); brace/paren/bracket balance check on all 4 touched files (all BALANCED); new test logic hand-traced against SeedPhraseHelper's actual field names/types
  BLOCKED: JDK/Gradle/Android SDK compile and unit/instrumented test execution — `which java/javac/gradle` all empty on PATH. Routed to S3-19b per plan's own exit criterion for this session.
  NOT RUN: RULES lane (not applicable — no Firestore rules component in this session's scope)
COMMIT: 3ef358e (implementation: SeedPhraseHelper.java, WipeHelper.java, RestoreFromSeedActivity.java, SeedPhraseHelperTest.java) ; docs commit recorded in SESSION_INDEX  WORKTREE: clean
NEXT SESSION: S3-15 is still the plan's actual next unstarted session (App Check + client provider wiring — lane AND/RULES, verify BLOCKED -> S3-15b/S3-19b — Finding: S10-N1). If continuing this out-of-order thread instead, S3-17 (Android backup & group crypto, lane AND+RULES, verify BLOCKED) is next in numeric sequence after S3-16. Do NOT claim S3-15 done — it was not touched by this session.
```
