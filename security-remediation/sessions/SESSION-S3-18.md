# SESSION-S3-18 — Android platform privacy

**Lane:** AND (verify BLOCKED → S3-19b)
**Status:** PARTIAL. Two of this session's three plan-scoped findings are
source-fixed and land **Partial** (`S08-H2`, `S08-H3`); the third (`S08-L4`)
and the temp-prefix-sweep sub-item of `S08-H3` were **not** addressed by the
committed implementation and remain **Open** — see "Scope not completed"
below. Implementation had already landed on this branch (commit `bdcad65`)
before this documentation-and-verification pass started; this session's job
was to independently re-verify every change against current source (not trust
the prior commit messages), run the available structural checks, and close out
the tracker/session/index/start-here documentation for what was actually done.
**Model:** v0
**Sequencing note:** this is the plan's actual next unstarted session — no
out-of-order pickup. `S3-17` (the session immediately before this one) is
closed per its own session log and `START_HERE.md`'s chain state before this
session began.

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

> ### S3-18 — Android platform privacy · lane AND (verify BLOCKED → S3-19b)
> Findings: `S08-H2` (stop clearing `FLAG_SECURE` app-wide), `S08-H3`
> (disable/scrub 150 MB plaintext Glide disk cache + sweep 4 temp prefixes),
> `S08-L4` (exclude lock screen + rendered activity from recents).
> Exit: source-reviewed; pending S3-19b.

Read before touching anything: `START_HERE.md`, `SESSION_INDEX.md`,
`SESSION_PROTOCOL.md`, `ROUND3_REMEDIATION_PLAN.md`, and the `BUG_TRACKER.md`
rows for `S08-H2`, `S08-H3`, `S08-L4`. Per protocol §3 ("source beats
tracker"), every tracker/commit claim below was independently re-verified
against current source — including reading the already-landed implementation
diff (`git show bdcad65`) line by line, not just trusting its commit message —
before any documentation was written.

## State found at session start

`git log --oneline -8` showed the implementation already on this branch:

- `bdcad65` — `fix: enforce screenshot-security preference and purge Glide
  cache on self-destruct` (implementation: `BaseActivity.java`,
  `LockScreenActivity.java`, `MainActivity.java`, `db/MessageDao.java`,
  `db/SelfDestructWorker.java`, `ui/SecurityPrivacySettingsActivity.java`)
- `4a80703` — `feat: update BUG_TRACKER.md for partial fixes on FLAG_SECURE
  and Glide cache issues` (documentation: the `S08-H2`/`S08-H3` tracker rows)
- `99424e7` — merge of PR #87 (`security-remediation-s3-17`)

`git status` was clean — nothing uncommitted. `BUG_TRACKER.md` already carried
`S08-H2` and `S08-H3` as `Partial (S3-18)` (from `4a80703`); `S08-L4` was still
`Open`/`Carried`. This session's job was therefore verification + the remaining
documentation (session log, chain state, index), not new implementation — the
run had stopped after the implementation and tracker commits but before writing
the session record and advancing the chain.

## Backup-configuration claim check (per task instruction — recorded on the record)

The manifest was inspected directly to make sure this session does not repeat
any unsupported "backup is enabled / data leaks via ADB backup" claim. Actual
values in `app/src/main/AndroidManifest.xml` (lines 41–43):

```
android:allowBackup="false"
android:fullBackupContent="false"
android:dataExtractionRules="@xml/data_extraction_rules"
```

Android backup is **already disabled** at the manifest level (this is a
Round-1/Round-2-era hardening, not S3-18 scope). No backup-exposure claim is
made anywhere in this session's documentation because the source does not
support one. This check is recorded so a future session does not re-derive it.

## Source verification performed (before writing any documentation)

Every claim below was checked by reading current file content and/or the
`bdcad65` diff, not inferred from the commit message.

### S08-H2 — `FLAG_SECURE` cleared app-wide

- **`BaseActivity.java`**: confirmed new package-private
  `applyScreenshotSecurity(Activity)` reads `app_screenshot_enabled` from the
  `duoshield_prefs` SharedPreferences (`PREFS_NAME`) with a **`false` default**,
  and `addFlags(FLAG_SECURE)` when screenshots are **not** allowed /
  `clearFlags(FLAG_SECURE)` when they are. Default = secure. `onCreate()` calls
  it. Verified `PREFS_NAME == "duoshield_prefs"` in this file, the same store
  the settings screen writes to (see below), so the read and the write agree.
- **`MainActivity.java`** and **`LockScreenActivity.java`** (both extend
  `AppCompatActivity` directly, not `BaseActivity`): confirmed each
  `onCreate()` now calls `BaseActivity.applyScreenshotSecurity(this)` instead
  of the previous unconditional `getWindow().clearFlags(FLAG_SECURE)`. Confirmed
  the now-unused `import android.view.WindowManager;` was removed from both.
- **`SecurityPrivacySettingsActivity.java`**: confirmed `applyScreenshotFlag(
  boolean allow)` now branches on `allow` (`clearFlags` when `true`, `addFlags`
  when `false`) instead of ignoring the parameter and always clearing. Confirmed
  its two call sites in `attachScreenshotListener()` persist
  `app_screenshot_enabled = <desired>` to `prefs` **and** pass the same value to
  `applyScreenshotFlag(...)`, both in the no-PIN branch and inside the
  `promptCurrentPin(...)` success callback — so the switch and the window state
  cannot diverge. Confirmed `prefs = getSharedPreferences("duoshield_prefs",
  MODE_PRIVATE)` — same store `BaseActivity` reads.
- **No remaining unconditional clear:** `grep -rn "FLAG_SECURE"
  app/src/main/java/` returns only (a) the two branch-guarded call sites above,
  (b) javadoc/comment text, and (c) the two pre-existing comments in
  `GroupChatActivity.java:200` / `ChatMediaActivity.java:1348` that claim
  FLAG_SECURE "is applied globally in BaseActivity.onCreate()" — which this fix
  finally makes true. There is no longer any code path that clears FLAG_SECURE
  irrespective of the preference.

### S08-H3 — plaintext media in the 150 MB Glide disk cache (Glide-clear half)

- **`db/MessageDao.java`**: confirmed `deleteExpired(long currentTime)` return
  type changed from `void` to `int` (Room returns the affected-row count for an
  `@Query` DELETE). Confirmed its only caller is `SelfDestructWorker`.
- **`db/SelfDestructWorker.java`**: confirmed `doWork()` captures
  `int roomDeleted = db.messageDao().deleteExpired(now)` and
  `int firestoreDeleted = deleteExpiredFromFirestore(...)`, and that
  `deleteExpiredFromFirestore` → `commitBatchDelete` now both **return** the
  Firestore delete count (`commitBatchDelete` returns `0` on the empty-doc-list
  early return, `total` otherwise). Confirmed the Glide eviction is guarded by
  `if (roomDeleted > 0 || firestoreDeleted > 0)` — so an ordinary 15-minute
  poll that expires nothing does **not** call `clearDiskCache()` and therefore
  does not evict unrelated, still-live thumbnails (zero-expired safety holds).
  Confirmed `Glide.get(getApplicationContext()).clearDiskCache()` runs inside
  `Worker.doWork()` (already a WorkManager background thread — satisfies Glide's
  "not on the main thread" requirement) and is wrapped in a `try/catch` that
  logs a warning and continues (non-fatal).
- **No legitimate caching path disabled:** the fix does not touch
  `DuoShieldGlideModule` or Glide's disk-cache configuration at all — the
  150 MB (75 MB low-RAM) `DiskLruCache` still functions normally; it is only
  cleared reactively when a self-destruct pass actually deleted something.

## Disposition

### S08-H2 — stop clearing `FLAG_SECURE` app-wide → **Partial**

- **Fix (already landed, commit `bdcad65`):** as verified above — screenshots,
  screen recording, and the recents thumbnail are now blocked by default
  (`FLAG_SECURE` applied) on every `BaseActivity`-derived screen plus
  `MainActivity` and `LockScreenActivity`, and are only permitted when the user
  explicitly enables `app_screenshot_enabled` (default `false`). The settings
  toggle now honours its own `allow` parameter for immediate feedback.
- **Why Partial, not Fixed:** no JDK/Gradle/Android SDK in this environment —
  source-reviewed and structurally checked (brace/paren/bracket balance), not
  compiled or instrumentation-tested. AND-lane verification routed to
  **S3-19b** per this program's standing exit criterion for every Android
  session.

### S08-H3 — plaintext media in the 150 MB Glide disk cache → **Partial**

- **Fix (already landed, commit `bdcad65`):** the disappearing-message path
  (`SelfDestructWorker`) now evicts Glide's on-disk decoded-plaintext cache
  whenever a pass actually deletes at least one Room or Firestore message,
  closing the gap where a "disappeared" message's decoded image/video frame
  survived on disk after its Room row, Firestore doc, and encrypted B2 blob were
  all gone. (The full-wipe path already reached this directory via
  `WipeHelper.eraseLocalData()` step 9 before this session — unchanged.)
- **Why Partial, not Fixed:** same AND-lane toolchain block as `S08-H2` →
  **S3-19b**. **Additionally, the finding's second sub-item is not closed** —
  see below.

## Scope NOT completed this session (recorded, not hidden)

The committed implementation covered `S08-H2` and the Glide-disk-cache half of
`S08-H3`. The following parts of S3-18's plan scope were **not** implemented and
were **not** touched by `bdcad65`; they remain **Open** and are carried forward
as the remaining S3-18 work (this is why the chain state does **not** advance to
S3-19):

- **`S08-H3` — "sweep 4 temp prefixes" sub-item:** decrypted-plaintext temp
  files are written to `getCacheDir()` under several prefixes
  (`voice_`, `cam_`, `enc_`, `vid_view_`, `thumb_`, `b2dl_` — from
  `ChatMediaActivity`, `GroupChatActivity`, `MediaViewerActivity`,
  `B2StorageHelper`). `TempFileCleaner` currently sweeps only `voice_*`,
  `vid_*`, `share_*`, `duoshield_export_*`, the `chat_export_*` working dir, and
  `DuoShield_Export_*.zip`. It does **not** sweep `cam_`, `enc_`, `vid_view_`,
  `thumb_`, or `b2dl_`. Extending the sweep to the missing plaintext-media
  prefixes was not done this session (would be new implementation, out of scope
  for this documentation/verification pass).
- **`S08-L4` — exclude lock screen + rendered activity from recents:** still
  `Open`/`Carried`. `AndroidManifest.xml` has no `android:excludeFromRecents`
  and no per-activity recents handling. NOTE: `S08-H2`'s new FLAG_SECURE-by-
  default behaviour does blank the recents **thumbnail** for the affected
  windows, which mitigates the thumbnail-leak aspect — but `S08-L4`'s specific
  ask (lock screen shown over the rendered activity in the task/recents view)
  was not the target of `bdcad65`, was not implemented, and was not verified
  this session. Not claimed as addressed.

## Files changed (already committed before this session's documentation pass)

- `app/src/main/java/com/duoshield/app/BaseActivity.java` — new
  `applyScreenshotSecurity(Activity)` + `KEY_APP_SCREENSHOT_ENABLED`;
  `onCreate()` routes through it. (`S08-H2`)
- `app/src/main/java/com/duoshield/app/MainActivity.java` — `onCreate()` calls
  `BaseActivity.applyScreenshotSecurity(this)`; unused `WindowManager` import
  removed. (`S08-H2`)
- `app/src/main/java/com/duoshield/app/LockScreenActivity.java` — same call-site
  change; unused `WindowManager` import removed. (`S08-H2`)
- `app/src/main/java/com/duoshield/app/ui/SecurityPrivacySettingsActivity.java`
  — `applyScreenshotFlag(boolean allow)` now honours `allow`. (`S08-H2`)
- `app/src/main/java/com/duoshield/app/db/MessageDao.java` — `deleteExpired`
  `void` → `int`. (`S08-H3`)
- `app/src/main/java/com/duoshield/app/db/SelfDestructWorker.java` — count
  propagation + guarded `Glide.clearDiskCache()`. (`S08-H3`)

Documentation this session (separate commit): `BUG_TRACKER.md` was already
updated in `4a80703`; this session writes `sessions/SESSION-S3-18.md` (this
file), and updates `security-remediation/START_HERE.md` and
`security-remediation/SESSION_INDEX.md`. No source, server, or Firestore-rules
files were modified by this session.

## Test evidence

### Toolchain availability (checked, not assumed)

```
$ which java javac gradle
(no output — none found on PATH; gradlew wrapper present but unusable without a JDK)
```

No JDK, no runnable Gradle, no Android SDK in this environment. Consistent with
every AND-lane session in `SESSION_INDEX.md` — **compilation and
instrumented/unit test execution were not run and are not claimed.**

### Structural check actually run (substitute for compile)

A brace/paren/bracket balance check was run against all 6 touched `.java`
files. All balanced:

```
BaseActivity.java                      {}=39/39  ()=131/131 []=0/0   OK
MainActivity.java                      {}=15/15  ()=72/72   []=3/3   OK
LockScreenActivity.java                {}=26/26  ()=141/141 []=16/16 OK
db/MessageDao.java                     {}=1/1    ()=45/45   []=0/0   OK
db/SelfDestructWorker.java             {}=26/26  ()=92/92   []=3/3   OK
ui/SecurityPrivacySettingsActivity.java{}=68/68  ()=319/319 []=12/12 OK
```

This proves the edits are structurally well-formed; it does **not** prove they
compile (no type-checking, symbol resolution, or annotation processing) — that
gate is S3-19b's.

## Toolchain / Android blocker (proven, not asserted)

- `which java javac gradle` — all three return nothing; no `JAVA_HOME`; no
  Android SDK directory.
- Not a new or session-specific blocker — identical to every prior AND-lane
  session. No workaround was attempted that would risk a false "compiles" claim.

## Verification NOT run (recorded, not fabricated)

- **Full app compile** (`./gradlew assembleDebug` or equivalent) — BLOCKED, no
  JDK/Gradle/SDK. Routed to S3-19b.
- **Android instrumented tests** exercising the FLAG_SECURE preference toggle
  end-to-end, the recents-thumbnail blanking, or the SelfDestructWorker Glide
  eviction against a live Glide cache — BLOCKED, no SDK/emulator. Routed to
  S3-19b.
- **`S08-H3` temp-prefix sweep** and **`S08-L4` recents exclusion** — NOT
  implemented this session (out of scope for this documentation/verification
  pass); remaining S3-18 work, carried forward (see "Scope NOT completed").

## Diff review before finishing (per task instructions)

- Implementation (`bdcad65`) and the `S08-H2`/`S08-H3` tracker documentation
  (`4a80703`) were already committed before this pass; this session did not
  re-touch any source file. This session's own changes are documentation only:
  this file plus `START_HERE.md` and `SESSION_INDEX.md`.
- No accidental encoding changes; no cosmetic-only reformatting bundled in.

## Chain state

S3-18 is **partially** complete. `S08-H2` and the Glide-disk-cache half of
`S08-H3` are source-fixed, verified from source, structurally checked, and land
**Partial** (AND-lane compile/test execution BLOCKED → **S3-19b**). The
`S08-H3` "sweep 4 temp prefixes" sub-item and all of `S08-L4` were **not**
implemented and remain **Open**, carried forward as the remaining S3-18 work.

Because two scoped items are still open, **the chain state does NOT advance to
S3-19.** `NEXT SESSION` remains **S3-18** with the remaining scope named
explicitly. Neither catch-up gate (S3-15b RULES, S3-19b Android) has run; all
AND-lane compile/test promotion for `S08-H2`/`S08-H3` remains BLOCKED pending
S3-19b.

## Session record

```
SESSION: S3-18  MODEL: v0  CLUSTER: Android platform privacy (S08-H2, S08-H3, S08-L4)  STATUS: partial (S08-H2 + S08-H3 Glide-half source-fixed → Partial, AND-verification BLOCKED → S3-19b; S08-H3 temp-prefix-sweep sub-item + S08-L4 NOT implemented, remain Open/carried)
SEQUENCING: plan's actual next unstarted session — no out-of-order pickup. S3-17 was already closed before this session began.
CHANGES (already committed on this branch before this session; independently re-verified from source, not newly written):
  - app/src/main/java/com/duoshield/app/BaseActivity.java: + applyScreenshotSecurity(Activity) reading app_screenshot_enabled (default false → FLAG_SECURE applied), onCreate() routes through it (S08-H2)
  - app/src/main/java/com/duoshield/app/MainActivity.java + LockScreenActivity.java: onCreate() calls BaseActivity.applyScreenshotSecurity(this), unused WindowManager import removed (S08-H2)
  - app/src/main/java/com/duoshield/app/ui/SecurityPrivacySettingsActivity.java: applyScreenshotFlag(boolean allow) now honours allow instead of always clearing FLAG_SECURE (S08-H2)
  - app/src/main/java/com/duoshield/app/db/MessageDao.java: deleteExpired void→int (Room affected-row count) (S08-H3)
  - app/src/main/java/com/duoshield/app/db/SelfDestructWorker.java: count propagated from Room + Firestore deletes; Glide.clearDiskCache() called (off main thread, try/catch) only when roomDeleted>0||firestoreDeleted>0 (S08-H3)
DOCUMENTATION:
  - BUG_TRACKER.md: S08-H2 + S08-H3 rows Open→Partial (S3-18) — already committed in 4a80703, verified accurate this session (not re-touched)
  - START_HERE.md / SESSION_INDEX.md / SESSION-S3-18.md: chain-state + index + session log (this session's documentation commit)
VERIFICATION:
  PASS: independent source re-derivation of S08-H2 and S08-H3 against current code — confirmed FLAG_SECURE default-secure + toggle honours allow + no remaining unconditional clear; confirmed deleteExpired returns int, SelfDestructWorker propagates the count and clears Glide disk cache only on a non-empty delete pass (zero-expired safety holds), off the main thread, non-fatal on failure, no legitimate Glide caching path disabled
  PASS: brace/paren/bracket balance on all 6 touched .java files (all BALANCED)
  PASS: AndroidManifest.xml backup check — android:allowBackup="false" + fullBackupContent="false" confirmed; no backup-exposure claim made (source does not support one)
  BLOCKED: JDK/Gradle/Android SDK compile + unit/instrumented test execution — which java/javac/gradle all empty on PATH. Routed to S3-19b.
  NOT DONE: S08-H3 temp-prefix sweep (TempFileCleaner misses cam_/enc_/vid_view_/thumb_/b2dl_) and S08-L4 recents exclusion — not implemented, remain Open, carried forward as remaining S3-18 work
COMMIT: bdcad65 (implementation, already on branch) ; 4a80703 (S08-H2/H3 tracker rows, already on branch) ; documentation commit separate, this session  WORKTREE: clean
NEXT SESSION: S3-18 (REMAINING) — S08-L4 (exclude lock screen + rendered activity from recents) and the S08-H3 "sweep 4 temp prefixes" sub-item (extend TempFileCleaner to cam_/enc_/vid_view_/thumb_/b2dl_) are still Open. Do NOT advance to S3-19 until they are addressed. Do NOT claim S3-15b or S3-19b done — neither catch-up gate has run; S08-H2/S08-H3 AND-lane compile/test verification remains BLOCKED pending S3-19b.
```
