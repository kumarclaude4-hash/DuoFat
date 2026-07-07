# DuoShield — Agent Handoff Document

> **Purpose:** If you are a new agent picking up this project mid-stream, read this file
> completely before touching any code. This is the authoritative continuation guide.
> Last updated after completing Task #2 fixes (Findings 35, 30, 16, 31, 32).

---

## 1. Project in One Paragraph

DuoShield is a **native Android (Java, minSdk 26)** end-to-end encrypted messenger using the
**Signal Protocol** (libsignal-android 0.54.1). It supports multi-contact 1-to-1 chats, group
chats, media (images/video/voice via Backblaze B2), disappearing messages, a duress PIN,
biometric lock, seed-phrase backup/restore, and push notifications via a Node.js server on
Render.com. There is **no web UI** — the browser preview shows nothing useful.

- **Package:** `com.duoshield.app`
- **Firebase project:** `duoshield-8caf1`
- **Room DB version:** 12
- **Build system:** Gradle 8.2 + AGP 8.2 (requires JDK 17)
- **Signal library:** `org.signal:libsignal-android:0.54.1` + `org.signal:libsignal-client:0.54.1`

---

## 2. Non-Negotiable Rules (Read Before Editing Any File)

1. **Cross-check every change with a subagent before finalising.** After editing, run a review
   subagent to read the changed file in context. Fix severe issues before moving on.
2. **Compile after every cluster of changes:** validation command is
   `:app:compileDebugJavaWithJavac` with `ANDROID_HOME=/home/runner/android-sdk`. The SDK is
   re-downloaded if missing via `setup-android-sdk.sh`.
3. **Never change layout XML IDs** — binding references are spread across multiple Activities
   and will silently break.
4. **Never use `fallbackToDestructiveMigration()`** on the Room DB — schema v12, always write
   explicit migrations.
5. **All Signal encrypt/decrypt calls must happen on a background thread** — never the main
   thread. Use an `ExecutorService` or `new Thread()`.
6. **Firestore rule changes need deployment:** `firebase deploy --only firestore:rules`
7. **Secrets** — `SESSION_SECRET` is available in the Replit Secrets tab. Never print secrets
   to logs or commit them to files.
8. **libsignal-client** must be `implementation`, not `compileOnly` — `libsignal-android` has no
   Java classes; `libsignal-client` provides the Java surface. Both must be present.

---

## 3. Key Files & Architecture

```
app/src/main/java/com/duoshield/app/
│
├── security/
│   ├── DuressManager.java        ← duress PIN hash + performLogout()
│   ├── BiometricHelper.java      ← BiometricPrompt wrapper
│   └── PinManager.java           ← PBKDF2 PIN hash/verify
│
├── crypto/
│   ├── SeedPhraseHelper.java     ← BIP39 + HKDF derivation
│   ├── GroupCipherHelper.java    ← AES-256-GCM group encryption
│   └── signal/
│       ├── SignalCipherHelper.java     ← encrypt()/decrypt() — MUST be called on bg thread
│       ├── SignalKeyManager.java       ← isInitialized(), generateIdentityKey()
│       ├── DuoShieldSignalStore.java   ← Room-backed Signal protocol store
│       ├── SignalPreKeyRefresher.java  ← auto-replenish prekey pool (threshold=10, batch=25)
│       └── SignalSessionManager.java  ← SessionBuilder.process() entry point
│
├── db/
│   ├── AppDatabase.java          ← Room v12; clearInstance() before file delete
│   ├── MessageDao.java
│   ├── ContactDao.java
│   └── SelfDestructWorker.java   ← WorkManager; deleteExpired() + deleteExpiredFromFirestore()
│
├── util/
│   ├── AppLockManager.java       ← 3-min background timeout; onAppForegrounded(); shouldLock()
│   ├── WipeHelper.java           ← "Wipe & Exit" voluntary wipe
│   ├── SessionLogger.java        ← fire-and-forget Room inserts; logSync() on bg threads
│   ├── SecurePrefs.java          ← EncryptedSharedPreferences singleton
│   ├── B2StorageHelper.java      ← SigV4 PUT/DELETE to Backblaze B2
│   ├── PinManager.java           ← PBKDF2 hash/verify
│   ├── TempFileCleaner.java      ← WorkManager; cleans voice_*.m4a, vid_*.mp4, etc.
│   └── ConversationMetaUpdater.java ← writes lastMessage to chats/{chatId}
│
├── ui/
│   ├── SessionLogActivity.java   ← shows sign-in/sign-out events from Room
│   ├── MessageAdapter.java       ← DiffUtil; TextStyleHelper.apply() should be called here
│   └── SettingsActivity.java
│
├── notifications/
│   ├── NotificationStyler.java   ← showMessage() — must put partner_uid in replyIntent
│   └── MessageReplyReceiver.java ← reads partner_uid from intent extra
│
├── BaseActivity.java             ← all sensitive Activities extend this; handles lock/signout
├── LockScreenActivity.java       ← 5-attempt duress trigger; biometric; pin_fail_count
├── SignInActivity.java           ← returning-user auto-route guard
├── SplashActivity.java           ← addAuthStateListener-based routing
├── MainActivity.java             ← permission request then route()
├── ChatMediaActivity.java        ← primary chat screen; ~2650 lines
├── GroupChatActivity.java        ← group chat screen
└── ConversationListActivity.java ← conversation list; also drives contact backup restore
```

**SharedPreferences files:**
- `duoshield_prefs` — main prefs (my_uid, conversation_id, disappear_ms, etc.)
- `duoshield_security_prefs` — pin_fail_count ONLY (survives performLogout intentionally)
- `duoshield_contacts_bak` — plain (non-encrypted) contact list backup

---

## 4. Security Review Status

Two security review reports were read in full (44 findings, 14 rounds). Full details:
- **`SECURITY_REMEDIATION_PLAN.md`** — exact fix instructions for every open finding
- **`.agents/memory/security-review-plan.md`** — compact status map

### Already Fixed (confirmed in live code)
| # | What |
|---|------|
| 18 | SignalCipherHelper thread safety — SESSION_LOCKS per-address synchronized |
| 22 | KeyFingerprintActivity uses per-contact `signal_partner_identity_key_<name>` |
| 23 | VERIFY only clears safety banner after confirmed scan match |
| 27 | Group key substitution — `keys/{memberUid}` write restricted to `createdBy` |
| 28 | Group message spoofing — Firestore enforces `sender==auth.uid`, `isEncrypted==true` |
| 29/44 | Identity hijacking — `identities/{userId}` rule checks path AND payload |
| 10 | B2CleanupWorker has `isOwnedB2Path()` ownership check |

### Fixed by Task #2 (this session)
| # | What | Files changed |
|---|------|---------------|
| 35 | Duress logout no longer writes "Duress logout" label to Session Log | `DuressManager.java`, `SessionLogActivity.java`, `SessionLogger.java` |
| 30 | SignInActivity routing race closed via `duress_wipe_in_progress` flag | `DuressManager.java`, `SignInActivity.java`, `SplashActivity.java`, `MainActivity.java` |
| 16 | SessionLogger write ordered before DB delete (synchronous inside bg thread) | `SessionLogger.java`, `DuressManager.java` |
| 31 | Biometric success now resets `pin_fail_count` | `LockScreenActivity.java` |
| 32 | WipeHelper now clears `duoshield_security_prefs` | `WipeHelper.java` |
| 14 | WipeHelper now calls `FirebaseAuth.signOut()` | `WipeHelper.java` |

---

## 5. Completed Tasks

### Tasks #3 and #4 — COMPLETE (all confirmed fixed in live code)
All Clusters A–J in `SECURITY_REMEDIATION_PLAN.md` have been implemented and compile-verified.

### Most Recent Work — F21 / F42 / F37 (COMPLETE, 2026-07-07)

| # | Finding | Fix |
|---|---------|-----|
| F21 | "Delete for everyone" had no ownership check | UI: `addMsgAction` for "Delete for everyone" now gated on `mine`. Firestore rule: messages update now adds `deletedForAll` restriction to sender only |
| F42 | "Delete for everyone" silently removed message (no tombstone) | MODIFIED listener + `deleteForEveryone()` now call `adapter.updateMessage()` to show "⛔ Message deleted" tombstone; Room updated via `updateText()` instead of `deleteMessage()` |
| F37 | Saved gallery photos/videos survived wipe and duress logout | New `MediaStoreWipeHelper.java`: tracks MediaStore URIs in `duoshield_prefs`. `FullScreenImageActivity.writeImageToGallery()` and `MediaViewerActivity.writeVideoToGallery()` call `recordUri()` after save. Both `WipeHelper.wipeAll()` and `DuressManager.performLogout()` call `wipeAll()` before prefs clear |

**⚠️ ACTION REQUIRED:** Firestore rules were updated in `firestore.rules` but NOT yet deployed (Firebase CLI requires auth). Run: `firebase deploy --only firestore:rules --project duoshield-8caf1`

---

## 6. Remaining Open Findings (Low-priority / Architectural)

## 6. Uncertain Findings (Verify Before Planning a Fix)

These need a targeted file read to confirm if they're open or already fixed:

| # | File to read | What to check |
|---|-------------|---------------|
| 1 | `.gitignore` + `git log` | Is `app/duoshield-release.keystore` gitignored? In history? |
| 5 | `backup/BackupManager.java` | Does the backup include contacts/group metadata in plaintext? |
| 11 | `util/B2StorageHelper.java` | Are B2 credentials `BuildConfig.B2_KEY_ID` etc.? Are they hardcoded? |
| 12 | `ui/MessageAdapter.java` `bindLinkPreview()` | Is link preview still auto-fetched on scroll? |
| 15 | `util/ExportHelper.java` | Does the dialog still say "removed after sharing"? Callback present? |
| 19 | `firestore.rules` `users/{uid}` create | Can any auth user claim any UID? |
| 21 | `ChatMediaActivity.java` `showMessageActionDialog()` | Is "Delete for everyone" gated on `mine`? |
| 34 | `ui/RestoreFromSeedActivity.java` `migrateOldUid()` | Does this still fail against current rules? |
| 37 | `FullScreenImageActivity.java`, `MediaViewerActivity.java` | Are saved URIs tracked for wipe? |
| 38 | `firestore.rules` `chats/{chatId}` update | Does the rule scope per-field writes? |
| 39 | `ChatMediaActivity.java` `pinMessage()` | Does it still pass `msg.getText()` as preview? |
| 42 | `db/B2CleanupWorker.java` | Is scheduling still only done by sender on upload success? |

---

## 7. Low-Priority Cleanup (Do Last)

- **Finding 40** — Wire up `TextStyleHelper.apply()` in `MessageAdapter.java` line ~550
  (currently plain `setText()`). Also reorder `applyPattern()` to handle MONO first.
- **Finding 43** — Remove `deleteOlderFromFirestore()` from `SelfDestructWorker.java` + prune
  stale index from `firestore.indexes.json`.
- **Finding 41** — Delete `ReactMessageHelper.java` and `TypingThrottle.java` (zero callers).
- **Finding 9** — Move `CallCleanupWorker` logic to `server/index.js` (Admin SDK bypasses rules).
- **Finding 4** — Migrate `MasterKeys` to `MasterKey.Builder` in `SecurePrefs.java`.

---

## 8. How to Build & Validate

```bash
# Verify Android SDK is present
ls /home/runner/android-sdk/platforms/

# If missing, re-download (takes ~5 min):
bash setup-android-sdk.sh

# Compile check only (fast, ~60s):
ANDROID_HOME=/home/runner/android-sdk ./gradlew :app:compileDebugJavaWithJavac --no-daemon

# Full lint:
ANDROID_HOME=/home/runner/android-sdk ./gradlew :app:lintDebug --continue --no-daemon

# Release APK (uses Build Release APK workflow — requires env vars):
# GOOGLE_SERVICES_JSON, GOOGLE_APPLICATION_CREDENTIALS_JSON must be set
```

`local.properties` must contain:
```
sdk.dir=/home/runner/android-sdk
```

---

## 9. Memory & Reference Files

| File | Purpose |
|------|---------|
| `SECURITY_REMEDIATION_PLAN.md` | Full 44-finding status + exact fix instructions |
| `HANDOFF.md` | Original implementation phase history |
| `replit.md` | Project overview + user preferences |
| `.agents/memory/MEMORY.md` | Agent memory index (57 topic entries) |
| `.agents/memory/security-review-plan.md` | Compact finding status map |
| `attached_assets/DuoShield_Code_Review_Report_(6)_1783403471815.md` | Full 13-round report |
| `attached_assets/DuoShield_Security_Code_Review_Report_1783403471815.md` | Round 14 report |

---

## 10. Strict Workflow for Every Code Change

```
1. READ the full target file before editing (use ReadFile with line numbers)
2. MAKE the edit (Edit tool — exact string match required)
3. VERIFY the edit (ReadFile the changed lines to confirm)
4. RUN a review subagent: subagent({ name: "review-X", task: "read <file> confirm <change>", config: { $kind: "explore" } })
5. FIX any issues the reviewer flags
6. COMPILE: ./gradlew :app:compileDebugJavaWithJavac --no-daemon
7. Only then move to the next file
```

This workflow is mandatory. Do not skip the review subagent step.
