# DuoShield (DuoFat) — Security Code Review

**Repo/app:** DuoFat-main → `com.duoshield.app` (DuoShield), Android (Java, minSdk 26) + Node.js push server + Firebase backend (Firestore rules / Cloud Functions stub).
**Reviewed by:** Claude, following the repo owner's standing security-review prompt template.
**Method:** Manual full-text read of every file in scope for this round (listed below), plus repo-wide automated sweeps (weak crypto, hardcoded secrets, sensitive-data logging, TLS bypass, WebView JS bridges, SQL string concatenation, escape-sequence literals).
**No dynamic/runtime testing was performed** — this is a static read-through only. No build, no emulator run, no live endpoint testing. (Round 4 is a partial exception: the bundled `libsignal-client-0.54.1-stripped.jar` was inspected and cross-checked against the upstream `signalapp/libsignal` source on GitHub for one specific call-order question — see Finding 22 — but this is source comparison, not execution.)

This is now **six review rounds** (see §3 Roadmap for what each round covered). Re-attach this file alongside your next zip export and say "continue"/"update it in place" so it can keep being extended round-over-round instead of restarted.

> **Round 6 note:** Read `GroupChatActivity.java` in full — the natural next priority per Round 5's own closing note, since it's the group-chat analog of `ChatMediaActivity.java`. This surfaced two new findings (27, 28) in the group-messaging trust model that have no equivalent in 1:1 chat, because 1:1 chat's real per-pair Signal sessions provide guarantees the group implementation's static shared key and unauthenticated Firestore fields don't. Separately: the "Current end-to-end priority order" list in §3 was last re-derived in Round 3 and was never updated for Findings 18–26 in Rounds 4–5 — this round flags that gap and prepends 27/28, but a full re-derivation across all 28 findings is still owed and is called out as a Round 7 task.

> **Round 7 note:** Started on the `ui/*` backlog flagged in Round 6 as the single largest remaining gap. Read in full: `CreateGroupActivity.java`, `SeedPhraseDisplayActivity.java`, `ContactDetailActivity.java`, plus re-read `RestoreFromSeedActivity.java`'s identity-write path and `firestore.rules`' `identities` block against both. This surfaced one new **Critical** finding (29): the `identities/{userId}` write rule never binds the document path (`userId`) to `request.auth.uid`, only the payload's `uid` field — so any authenticated DuoShield account can silently overwrite *any other, already-established* user's identity mapping at any time, not just during the narrow onboarding race Finding 2 already covers. `ContactDetailActivity.java` was clean (matches Finding 8's known call-permission model, nothing new). The full re-derivation of §3's priority order that Round 6 flagged as owed is still outstanding — deferred again to keep this round's scope to what was actually finished; see §5 for the exact remaining `ui/*` and root-package line-up.

> **Round 4 correction to this file's own bookkeeping:** Rounds 1–3's §5 coverage note tracked `ui/*` as the main remaining gap but never listed an entire second set of files — 16 files, ~5,637 lines, sitting directly under `app/src/main/java/com/duoshield/app/` (not in any subpackage) — including **`ChatMediaActivity.java` (2,646 lines, the main chat screen)**, `GroupChatActivity.java`, `KeyFingerprintActivity.java`, `SignInActivity.java`, `LockScreenActivity.java`, `MainActivity.java`, `DuoShieldApp.java`, `ConversationListActivity.java`, and 8 others. These were referenced in passing by earlier findings (e.g. Finding 10 cites a `ChatMediaActivity.java` line number, Finding 14 cites `ConversationListActivity.java`) but were never read in full or listed as outstanding. This is now corrected in §5. Round 4 prioritized this gap over the previously-tracked `ui/*` backlog precisely because it was the larger and higher-risk of the two, but did not finish it — see §5 for exactly what's read vs. still outstanding.

> **Round 5 correction to this file's own bookkeeping:** Round 4's own §1 table cited Findings 21, 22, and 23 against specific files (`ChatMediaActivity.java`, `KeyFingerprintActivity.java`, `DuoShieldSignalStore.java`) but the review ran out of room before those three findings were actually written up in §2 — they existed only as dangling references. Round 5's first task was closing that gap (they're now written up below, and re-checked against the code rather than just reconstructed from the old table entries — Finding 21's number turned out to already match what a fresh read of `ChatMediaActivity.java`'s delete path independently pointed to, which is a useful cross-check). Round 5 then finished the rest of `ChatMediaActivity.java` (the ~1,600 lines Round 4 left unread — media upload/download, voice record/playback, contact-card send, `ensureSignalSession()`, the disappearing-messages picker/sync, listener setup), which surfaced three more new findings (24, 25, 26). `GroupChatActivity.java` and the other 12 remaining root-package files are still outstanding — Round 5 deliberately scoped itself to closing the Round 4 gap and finishing the one file already in progress, rather than also starting new files, specifically so this round would finish cleanly instead of leaving a *second* round in a row with dangling references. See §5 for the exact line-up.

---

## 1. Files reviewed so far

*Round 1 files:*

| File | Verdict |
|---|---|
| `app/src/main/java/com/duoshield/app/crypto/SeedPhraseHelper.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/crypto/BackupCryptoHelper.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/crypto/GroupCipherHelper.java` | ⚠️ solid crypto, architectural note (see Finding 3) |
| `app/src/main/java/com/duoshield/app/crypto/signal/SignalKeyManager.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/crypto/signal/SignalCipherHelper.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/crypto/signal/SignalSessionManager.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/crypto/signal/SignalPreKeyRefresher.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/security/DuressManager.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/security/BiometricHelper.java` | ✅ solid (UI gate only, not a crypto gate — by design) |
| `app/src/main/java/com/duoshield/app/util/PinManager.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/SecurePrefs.java` | ⚠️ solid design, uses a deprecated API (Finding 4) |
| `app/src/main/java/com/duoshield/app/db/DatabaseKeyProvider.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/db/AppDatabase.java` (key-setup path only, lines 1–100) | ✅ solid |
| `app/src/main/java/com/duoshield/app/auth/AuthTokenHelper.java` | ⚠️ see Finding 2 |
| `app/src/main/java/com/duoshield/app/contacts/ContactManager.java` (identity-resolution path only) | ⚠️ see Finding 2 |
| `app/src/main/java/com/duoshield/app/ui/AddContactActivity.java` (deep-link handler only) | ✅ solid |
| `app/src/main/AndroidManifest.xml` | ✅ solid |
| `app/src/main/res/xml/network_security_config.xml` | ✅ solid (cleartext blocked globally) |
| `firestore.rules` | ✅ solid, one architectural note (Finding 2) |
| `server/index.js` | ⚠️ see Finding 2 |
| `functions/src/index.ts` | ✅ trivial (2 lines, just `initializeApp()`) |
| `app/build.gradle` (signing block only) | 🔴 see Finding 1 |
| `app/duoshield-release.keystore` | 🔴 see Finding 1 |
| `.gitignore` | 🔴 see Finding 1 |

*Round 2 files (call/ and backup/, plus the remaining Signal rotation-scheduling files):*

| File | Verdict |
|---|---|
| `app/src/main/java/com/duoshield/app/crypto/signal/SignedPreKeyRotationWorker.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/crypto/signal/SignedPreKeyScheduler.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/call/CallSignalRepository.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/call/CallManager.java` | ⚠️ see Findings 6, 8 |
| `app/src/main/java/com/duoshield/app/call/CallActivity.java` | ✅ solid (not exported; trusts only in-app-supplied Intent extras) |
| `app/src/main/java/com/duoshield/app/call/IncomingCallActivity.java` | ⚠️ see Finding 6 (displays an unverified caller name/ID) |
| `app/src/main/java/com/duoshield/app/call/CallCleanupWorker.java` | 🟢 see Finding 9 (dead code) |
| `app/src/main/java/com/duoshield/app/backup/BackupManager.java` | ⚠️ see Findings 5, 7 |
| `app/src/main/java/com/duoshield/app/backup/BackupScheduler.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/backup/BackupSyncWorker.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/backup/MediaRestoreHelper.java` | ✅ solid (messy progress-callback plumbing, not a security issue) |

*Round 3 files (`notifications/`, the two `call/` history files, and the highest-risk network/file-I/O files in `util/`):*

| File | Verdict |
|---|---|
| `app/src/main/java/com/duoshield/app/notifications/DuoShieldMessagingService.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/notifications/NotificationHelper.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/notifications/NotificationStyler.java` | ⚠️ see Finding 13 |
| `app/src/main/java/com/duoshield/app/notifications/MarkReadReceiver.java` | ✅ solid (not exported; confirmed against manifest) |
| `app/src/main/java/com/duoshield/app/notifications/MessageReplyReceiver.java` | ⚠️ see Finding 13 |
| `app/src/main/java/com/duoshield/app/call/CallHistoryActivity.java` | ✅ solid (local-only, confirmed no network/backup path) |
| `app/src/main/java/com/duoshield/app/call/CallHistoryAdapter.java` | ✅ solid (presentation-only) |
| `app/src/main/java/com/duoshield/app/util/MessageBuilder.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/AppLockManager.java` | ✅ solid (one dead-code nit, see Finding 17) |
| `app/src/main/java/com/duoshield/app/util/WipeHelper.java` | ⚠️ see Finding 14 |
| `app/src/main/java/com/duoshield/app/util/ContactBackupHelper.java` | ⚠️ see Finding 14 |
| `app/src/main/java/com/duoshield/app/util/ExportHelper.java` | ⚠️ see Finding 15 |
| `app/src/main/java/com/duoshield/app/util/SecureShareHelper.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/TempFileCleaner.java` | ✅ solid (referenced by Finding 15) |
| `app/src/main/java/com/duoshield/app/util/B2StorageHelper.java` | 🔴 see Finding 10; ⚠️ see Finding 11 |
| `app/src/main/java/com/duoshield/app/util/LinkPreviewFetcher.java` | ⚠️ see Finding 12 (SSRF hardening itself is ✅ solid) |
| `app/src/main/java/com/duoshield/app/util/GlideHelper.java` | ⚠️ see Finding 12 |
| `app/src/main/java/com/duoshield/app/util/ImageCacheHelper.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/FcmTokenHelper.java` | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/SessionLogger.java` | ⚠️ see Finding 16 |
| `app/src/main/java/com/duoshield/app/util/AppUpdateHelper.java` | ✅ solid (trivial — version-name/code getters only) |
| `app/src/main/java/com/duoshield/app/security/DuressManager.java` (re-examined: `performLogout()` interaction with `SessionLogger`) | ⚠️ see Finding 16 (supersedes Round 1's unqualified ✅) |

**Not yet reviewed** (next round): all of `ui/` except `AddContactActivity`'s deep-link handler and the slice of `MessageAdapter` read for Finding 12 (~14 more Activities/Adapters), `db/` DAOs and remaining migrations beyond `AppDatabase`'s key-setup path, the remaining ~30 files in `util/` (`ClipboardHelper`, `ConversationMetaUpdater`, `DeliveryReceiptHelper`, `EditMessageHelper`, `ForwardMessageHelper`, `MediaPickerHelper`, `MuteHelper`, `OnlinePresenceHelper`, `PresenceThrottle`, `ReactMessageHelper`, `ReadReceiptHelper`, `SearchHelper`, `SelfDestructScheduler`, `ShakeDetector`, `StorageCleanupWorker`, `TypingThrottle`, `VoiceMessagePlayer`, `VoiceRecorderHelper`, etc.), `firestore-tests/`, `firestore.indexes.json`, rest of `app/build.gradle`, `render.yaml`, `firebase.json`. See §5 Coverage note.

*Round 4 files (the previously-untracked root-package Activities — see the correction note above §1 — plus two flagged-priority `util/` files and a batch of small `util/` helpers pulled in along the way; full detail in §5):*

| File | Verdict |
|---|---|
| `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (send/receive/decrypt/edit-redecrypt/delete-for-everyone/safety-number-banner paths only — **not the full 2,646-line file**; superseded by the Round 5 entry below, which covers the rest) | 🔴 see Finding 18; ⚠️ see Findings 20 (via `BaseActivity`), 21 |
| `app/src/main/java/com/duoshield/app/BaseActivity.java` (full) | ⚠️ see Finding 20 |
| `app/src/main/java/com/duoshield/app/KeyFingerprintActivity.java` (full) | ⚠️ see Findings 22, 23 |
| `app/src/main/java/com/duoshield/app/crypto/signal/SignalCipherHelper.java` (re-examined for thread-safety) | 🔴 see Finding 18 (supersedes Round 1's unqualified ✅) |
| `app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java` (re-examined: `loadSession`/`storeSession` locking, `saveIdentity`/`isTrustedIdentity` interaction) | 🔴 see Finding 18; ⚠️ see Finding 22 (supersedes Round 1's unqualified ✅) |
| `app/src/main/java/com/duoshield/app/crypto/signal/SignalSessionManager.java` (re-examined: confirms `establishSession` uses the standard `SessionBuilder.process()` entry point) | ✅ solid (unchanged from Round 1) |
| `firestore.rules` (re-examined in full) | 🔴 see Finding 19 (supersedes Round 1's "✅ solid" verdict) |
| `app/src/main/java/com/duoshield/app/util/ForwardMessageHelper.java` (full) | ✅ solid — good forward-isolation design (fresh per-file media key on every forward, never reuses the original) |
| `app/src/main/java/com/duoshield/app/util/MediaPickerHelper.java` (full) | ✅ solid (trivial) |
| `app/src/main/java/com/duoshield/app/util/EditMessageHelper.java` (full) | 🔴 see Finding 18 |
| `app/src/main/java/com/duoshield/app/util/MessageBuilder.java` (re-examined) | 🔴 see Finding 18 (supersedes Round 3's unqualified ✅) |
| `app/src/main/java/com/duoshield/app/util/ClipboardHelper.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/ConversationMetaUpdater.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/DateHeaderHelper.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/DeliveryReceiptHelper.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/HapticHelper.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/KeyboardHelper.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/LastSeenFormatter.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/LinkPreviewHelper.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/MediaSizeEstimator.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/MessageStatusHelper.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/util/MuteHelper.java` (full) | ✅ solid |
| `app/src/main/java/com/duoshield/app/ui/SettingsActivity.java` (screenshot-toggle section only, ~55 lines of 1,018 — **not the full file**) | 🔴 see Finding 20 |

**Not yet reviewed** (unchanged items carried forward, plus the newly-identified root-package gap — see §5 for the complete, corrected list): all of `ui/` except `AddContactActivity`, the `MessageAdapter` slice, and the `SettingsActivity` screenshot section noted above; `db/` DAOs beyond `AppDatabase`'s key-setup path; ~15 more `util/` files (`FirebaseCostGuard`, `FirebaseQuotaSummary`, `OnlinePresenceHelper`, `PinMessageHelper`, `PresenceThrottle`, `ReactMessageHelper`, `ReadReceiptHelper`, `SearchHelper`, `SelfDestructScheduler`, `ShakeDetector`, `StorageCleanupWorker`, `TextStyleHelper`, `TimeFormatter`, `TypingThrottle`, `UiModeHelper`, `UnreadCountHelper`, `VoiceMessagePlayer`, `VoiceRecorderHelper`); 13 of the 16 root-package files (`ConversationListActivity`, `DisplayNameActivity`, `DuoShieldApp`, `FullScreenImageActivity`, `GroupChatActivity`, `LockScreenActivity`, `MainActivity`, `MediaSendPreviewActivity`, `MediaViewerActivity`, `MessageSearchActivity`, `SearchResultsAdapter`, `SignInActivity`, `SplashActivity`); ~1,600 of `ChatMediaActivity.java`'s 2,646 lines (media upload/download, contact-card send, `ensureSignalSession()`'s X3DH/watchdog logic, `retryPendingDecryption()`, pinning, wallpaper); `firestore-tests/`, `firestore.indexes.json`, rest of `app/build.gradle`, `proguard-rules.pro`, `render.yaml`, `firebase.json`. See §5 Coverage note.

*Round 5 files (closed the Round 4 write-up gap for Findings 21–23, then read the entire remainder of `ChatMediaActivity.java`):*

| File | Verdict |
|---|---|
| `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (remaining ~1,600 lines: `setupChat()`, call-launch, voice record/playback, `onRequestPermissionsResult`, lifecycle callbacks, both Firestore listener setup methods, disappearing-messages picker/sync, wallpaper, media upload/retry, `sendContactCard()`, `sendMessage()`, `ensureSignalSession()`, `retryPendingDecryption()` — **this completes the file**, combined with the Round 4 entry above) | 🔴 see Finding 18 (additional evidence); ⚠️ see Findings 21, 23, 24, 25, 26 |
| `app/src/main/java/com/duoshield/app/KeyFingerprintActivity.java` (re-confirmed against Round 4's read — no code changes since, this was a write-up gap, not a re-review) | ⚠️ see Findings 22, 23 (write-ups completed this round) |
| `app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java` (`saveIdentity()`, lines 113–139 — re-confirmed against Round 4's read for the same reason) | ⚠️ see Finding 22 (write-up completed this round) |
| `app/src/main/java/com/duoshield/app/util/TempFileCleaner.java` (`isTempMediaFile()`, re-examined against actual temp-file names created elsewhere in the app) | ⚠️ see Finding 25 |
| `app/src/main/java/com/duoshield/app/util/VoiceRecorderHelper.java` (spot-checked: output file naming/format only, lines ~36–44 — **not a full read**, left in the "not yet reviewed" list below for that reason) | — (evidence for Finding 25 only) |
| `app/src/main/java/com/duoshield/app/MediaViewerActivity.java` (spot-checked: one `createTempFile` call site only, line 81 — **not a full read**, left in the "not yet reviewed" list below) | ✅ solid on the one point checked (this file's temp-file naming *does* match `TempFileCleaner`'s pattern, unlike `ChatMediaActivity`'s) |
| `firestore.rules` (`match /chats/{chatId}/messages/{msgId}`, re-examined for Finding 21 specifically — the rest of the file was already re-examined in full in Round 4 for Finding 19) | 🔴 see Finding 19 (unchanged); ⚠️ see Finding 21 (new angle on a rule already quoted in Round 4) |

**Not yet reviewed** (updated — `ChatMediaActivity.java` drops off this list entirely, everything else carried forward unchanged from Round 4): all of `ui/` except `AddContactActivity`, the `MessageAdapter` slice, and the `SettingsActivity` screenshot section; `db/` DAOs beyond `AppDatabase`'s key-setup path; ~14 more `util/` files (`FirebaseCostGuard`, `FirebaseQuotaSummary`, `OnlinePresenceHelper`, `PinMessageHelper`, `PresenceThrottle`, `ReactMessageHelper`, `ReadReceiptHelper`, `SearchHelper`, `SelfDestructScheduler`, `ShakeDetector`, `StorageCleanupWorker`, `TextStyleHelper`, `TimeFormatter`, `TypingThrottle`, `UiModeHelper`, `UnreadCountHelper`); `VoiceMessagePlayer.java` (full) and `VoiceRecorderHelper.java` (beyond the four lines spot-checked for Finding 25) and `MediaViewerActivity.java` (beyond the one line spot-checked); all 13 remaining root-package files (`ConversationListActivity`, `DisplayNameActivity`, `DuoShieldApp`, `FullScreenImageActivity`, `GroupChatActivity`, `LockScreenActivity`, `MainActivity`, `MediaSendPreviewActivity`, `MessageSearchActivity`, `SearchResultsAdapter`, `SignInActivity`, `SplashActivity`) — `GroupChatActivity.java` in particular is the natural next priority given it parallels `ChatMediaActivity.java` closely enough that several of this round's findings (21, 24, 26 especially) may have direct siblings there; `firestore-tests/`, `firestore.indexes.json`, rest of `app/build.gradle`, `proguard-rules.pro`, `render.yaml`, `firebase.json`. See §5 Coverage note for the fully reconciled list.

*Round 6 files (the top remaining root-package priority per Round 5's note):*

| File | Verdict |
|---|---|
| `app/src/main/java/com/duoshield/app/GroupChatActivity.java` (full, 449 lines) | 🔴 see Finding 27; 🟠 see Finding 28 |
| `app/src/main/java/com/duoshield/app/crypto/GroupCipherHelper.java` (re-examined: confirmed no AAD/sender-binding parameter on `encrypt`/`decrypt`) | 🔴 see Finding 27; 🟠 see Finding 28 (supersedes Round 1's unqualified ✅ — the crypto primitive itself is still sound AES-256-GCM, this is about what it doesn't bind, not a weakness in the cipher) |
| `app/src/main/java/com/duoshield/app/crypto/signal/SignalCipherHelper.java` (re-examined: `decrypt()`'s use of the caller-supplied `senderUid` to select the session address, and `PREKEY_TYPE`'s auto-establish behavior) | 🔴 see Finding 27 (additional evidence; supersedes Round 4's "unchanged from Round 1" note for this specific angle) |
| `firestore.rules` (`match /groups/{groupId}/keys/{memberUid}` and `match /groups/{groupId}/messages/{msgId}`, re-examined) | 🔴 see Finding 27; 🟠 see Finding 28 (both re-derive from rules already quoted in §1's Round 1 entry, which called this file "✅ solid, one architectural note" — that verdict was already superseded for the top-level file by Finding 19 in Round 4, and these two subcollection rules are a further correction specific to groups) |

**Not yet reviewed** (updated — `GroupChatActivity.java` drops off this list entirely, everything else carried forward unchanged from Round 5): all of `ui/` except `AddContactActivity`, the `MessageAdapter` slice, and the `SettingsActivity` screenshot section; `db/` DAOs beyond `AppDatabase`'s key-setup path; ~14 `util/` files (`FirebaseCostGuard`, `FirebaseQuotaSummary`, `OnlinePresenceHelper`, `PinMessageHelper`, `PresenceThrottle`, `ReactMessageHelper`, `ReadReceiptHelper`, `SearchHelper`, `SelfDestructScheduler`, `ShakeDetector`, `StorageCleanupWorker`, `TextStyleHelper`, `TimeFormatter`, `TypingThrottle`, `UiModeHelper`, `UnreadCountHelper`); `VoiceMessagePlayer.java` (full), `VoiceRecorderHelper.java` (beyond the spot-check), `MediaViewerActivity.java` (beyond the spot-check); 12 remaining root-package files (`ConversationListActivity`, `DisplayNameActivity`, `DuoShieldApp`, `FullScreenImageActivity`, `LockScreenActivity`, `MainActivity`, `MediaSendPreviewActivity`, `MessageSearchActivity`, `SearchResultsAdapter`, `SignInActivity`, `SplashActivity`) — note `MainActivity.java` line 41 and `LockScreenActivity.java` line 61 were already spot-checked (not full reads) as evidence for Finding 20, the same partial-read caveat `MediaViewerActivity.java` already carries; `firestore-tests/`, `firestore.indexes.json`, rest of `app/build.gradle`, `proguard-rules.pro`, `render.yaml`, `firebase.json`. See §5 Coverage note for the fully reconciled list.

*Round 7 files (started the `ui/*` backlog Round 6 flagged as the largest remaining gap):*

| File | Verdict |
|---|---|
| `app/src/main/java/com/duoshield/app/ui/CreateGroupActivity.java` (full, 313 lines) | ✅ solid — confirms Finding 27's write-side mechanism (writes `senderUid` unauthenticated, per-member), no new issue on the creator side itself; silent no-retry skip when `FirebaseCostGuard.canWrite()` is false is a minor reliability gap only (a member can end up with no key doc at all, no user-visible error), not worth its own numbered finding |
| `app/src/main/java/com/duoshield/app/ui/SeedPhraseDisplayActivity.java` (full, 372 lines) | 🔴 see Finding 29 — `registerIdentity()` is the unconditional-`.set()` new-account write path into the vulnerable rule |
| `app/src/main/java/com/duoshield/app/ui/RestoreFromSeedActivity.java` (re-examined: the identity-record read/compare/write step, lines 200–231, against the `identities` rule) | 🔴 see Finding 29 — this file's own `identityPubKeyHash` comparison is good client-side hygiene but doesn't close the rule-level gap, since a direct Firestore write bypasses it entirely |
| `app/src/main/java/com/duoshield/app/ui/ContactDetailActivity.java` (full, 243 lines) | ✅ solid — call-permission flow matches Finding 8's already-documented model, nothing new |
| `firestore.rules` (`match /identities/{userId}`, lines 138–142, re-examined against both write call sites above) | 🔴 see Finding 29 |

---

## 2. Findings

### 🔴 Critical

**1. Release signing keystore is present in the repo and is not git-ignored.**

- File: `app/duoshield-release.keystore` (binary, 2750 bytes, present in the zip you attached).
- `.gitignore` excludes `app/src/main/assets/service-account.json` and `app/google-services.json`, but has **no entry for `app/duoshield-release.keystore`**.
- `app/build.gradle` explicitly documents the *intended* workflow:
  ```groovy
  // Credentials read from Replit Secrets (env vars) at build time.
  // The keystore binary is written to app/duoshield-release.keystore by the
  // assembleRelease workflow before Gradle runs (base64-decoded from KEYSTORE_BASE64).
  def ksFile = rootProject.file('app/duoshield-release.keystore')
  def ksPwd  = System.getenv('KEYSTORE_PASSWORD')
  ```
  i.e. the keystore is supposed to be a *build-time, ephemeral* artifact reconstituted from a secret, never a checked-in file.
- **Why it matters:** if this file is (or ever gets) committed to git — which nothing currently prevents — anyone with repo access gets the actual private signing key. Combined with the `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` secrets (which live outside the repo in Replit Secrets, so are not themselves exposed here), possession of the keystore file is one leaked password away from being able to publish a malicious update that Android will accept as a legitimate upgrade to the real DuoShield app (Play Store signature pinning is the *only* thing stopping a same-signature malicious APK from being installed as an "update"). For an E2EE messaging app this is one of the highest-impact possible compromises — worse than most in-app bugs, because it bypasses the crypto entirely by replacing the app itself.
- **Fix:**
  1. Add `app/duoshield-release.keystore` (and any `*.keystore` / `*.jks`) to `.gitignore` immediately.
  2. Run `git log --all --oneline -- app/duoshield-release.keystore` (or equivalent) to check whether it was **ever** committed. If yes, treat the key as compromised: generate a new release keystore and be aware that any *future* Play Store upload will need to go through a key-upgrade / account-recovery flow with Google, since Play App Signing may already be bound to the old key.
  3. Keep confirming this file is produced only inside the CI/Replit build step (base64-decoded from a secret at build time, deleted after) and never lands in a commit.

**18. Signal Protocol encrypt/decrypt calls are only serialized *within* each caller — not across callers — so ordinary concurrent use (a notification quick-reply, an edit, or a forward arriving while the chat screen is also sending/receiving) can corrupt the Double Ratchet session state for a contact, silently reusing message keys or discarding a ratchet advance.**

- Files: `app/src/main/java/com/duoshield/app/crypto/signal/SignalCipherHelper.java` (`encrypt()`/`decrypt()`, no locking at all), `app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java` (`loadSession()`/`storeSession()`, lines 288–314 — plain Room read-then-later-write, no per-address lock), `app/src/main/java/com/duoshield/app/util/MessageBuilder.java` (`sendTextMessage()`, line 45), `app/src/main/java/com/duoshield/app/util/EditMessageHelper.java` (`editMessage()`, line 42), `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (`dbExecutor`, declared line 188).
- `ChatMediaActivity` itself is careful about this: every Signal call it makes (send, receive/decrypt, edit-redecrypt, retry-pending-decryption) is routed through one dedicated single-thread `dbExecutor`, with an explicit comment at line 2351 explaining why (*"SessionCipher.encrypt() mutates ratchet state — must be single-threaded via dbExecutor"*). That part is good design.
- The bug is that two **other** call sites don't share that executor — each spins up its own, brand-new `Executors.newSingleThreadExecutor()` per call:
  ```java
  // MessageBuilder.sendTextMessage() — used by MessageReplyReceiver (notification quick-reply)
  Executors.newSingleThreadExecutor().execute(() -> {
      SignalCipherHelper.EncryptResult r = SignalCipherHelper.encrypt(ctx, partnerUid, text);
      ...
  });
  ```
  ```java
  // EditMessageHelper.editMessage() — used by the "Edit" action in the message action sheet
  Executors.newSingleThreadExecutor().execute(() -> {
      SignalCipherHelper.EncryptResult r = SignalCipherHelper.encrypt(ctx, partnerUid, newText);
      ...
  });
  ```
  Neither of these executors is the same object as `ChatMediaActivity.dbExecutor`, and `DuoShieldSignalStore`/`SignalCipherHelper` — the layer that would actually need to enforce mutual exclusion for this to be safe — has no synchronization of its own: `loadSession()` does a plain Room `SELECT`, and `storeSession()` does a plain Room upsert, with nothing serializing a load-mutate-store cycle against a concurrent one for the same `SignalProtocolAddress`.
- **Concrete race:** the user has a chat open (so `ChatMediaActivity.dbExecutor` is actively decrypting an incoming message from Bob) at the moment a notification-shade "Reply" is tapped for the same conversation (`MessageReplyReceiver` → `MessageBuilder.sendTextMessage()`, its own independent executor) — or, more simply, the user edits a message right as a new message from the same partner is arriving. Both threads call `store.loadSession(bobAddress)` and get the same on-disk `SessionRecord`; each mutates its own copy (one advances the sending chain to encrypt, the other advances the receiving chain / consumes a pre-key to decrypt) and calls `store.storeSession(bobAddress, ...)` independently. Whichever write lands second **silently overwrites** the other's update in Room. If the encrypt's update is the one discarded, the *next* message this device sends to Bob re-derives the identical sending-chain state (same message key, same counter) as the message that was just sent moments ago — the Double Ratchet's core guarantee (a message key is used exactly once) is broken by nothing more than ordinary concurrent use, with no attacker involved at all.
- **Why this is Critical, not architectural:** Findings 1/19/20 all require *something* external (a leaked file, a malicious Firestore write, a screen-capture) to cause harm. This one doesn't — two everyday, unremarkable user actions happening within the same few hundred milliseconds are sufficient to silently corrupt the confidentiality property the entire app is built around, and neither party would see an error (the losing thread's `storeSession()` call succeeds fine at the Room layer; it just gets clobbered a moment later).
- **Fix:** move the synchronization down into `DuoShieldSignalStore`/`SignalCipherHelper` where it can actually be enforced globally, e.g. a static `ConcurrentHashMap<String, Object>` of per-address lock objects (keyed by `address.toString()`) that `encrypt()`/`decrypt()` acquire (`synchronized`) for the full load→mutate→store span — mirroring the pattern the upstream `libsignal-protocol-java` reference implementation uses internally (a static `SessionCipher.SESSION_LOCK`). Once that lock lives in the shared layer, `MessageBuilder` and `EditMessageHelper` no longer need their own throwaway executors to be safe, though funneling all three call sites through one shared executor (e.g. an app-scoped singleton instead of `ChatMediaActivity`'s per-instance one) would be a reasonable belt-and-suspenders companion fix.

**19. A Firestore security rule meant to let a *stranger* consume one field of your public key bundle instead lets them silently overwrite your entire identity key and signed pre-key — a one-line rule bug that opens a real, remotely-triggerable X3DH man-in-the-middle.**

- Files: `firestore.rules` (`match /users/{uid}/public_keys/{doc}`, lines 16–21), `app/src/main/java/com/duoshield/app/crypto/signal/SignalKeyManager.java` (`uploadPublicBundle()`, lines 613–689 — defines the bundle schema), `app/src/main/java/com/duoshield/app/crypto/signal/SignalSessionManager.java` (`consumeOtpkOnFirestore()`, lines 262–276 — the *legitimate* use this rule was written for).
- The bundle document at `/users/{uid}/public_keys/bundle` holds **everything** needed to X3DH with that user in one place: `identityKey`, `registrationId`, `signedPreKey` (public key + signature), and `oneTimePreKeys` (a list). The rule protecting it:
  ```
  allow read:   if request.auth != null;
  allow create: if request.auth != null && request.auth.uid == uid;
  allow update: if request.auth != null;                              // ← any authenticated user, any field
  allow delete: if request.auth != null && request.auth.uid == uid;
  ```
  The comment above it explains the intent correctly: any authenticated user needs to be able to *consume* a one-time pre-key from someone else's bundle (X3DH forward secrecy requires the pre-key to be removed after first use), and without cross-user `update` permission that write gets rejected and pre-key index 0 is silently reused for every new session forever (the rule's own cited BUG-F01/BUG-F05). The one legitimate call site that needed this, `consumeOtpkOnFirestore()`, only ever touches one field: `.update("oneTimePreKeys", FieldValue.arrayRemove(chosenEntry))`.
- The rule as written doesn't scope the grant to that field. Firestore's `allow update` has no implicit "only the fields you meant" restriction — it would need an explicit `request.resource.data.diff(resource.data).affectedKeys().hasOnly(['oneTimePreKeys'])` clause to do that, and there isn't one. So **any authenticated DuoShield account can call `.update()` on *any other user's* `public_keys/bundle` and overwrite `identityKey` and `signedPreKey`/`signature` with values of their own choosing** — the exact fields an active X3DH man-in-the-middle needs to control.
- **Concrete attack:** attacker generates their own Signal identity key pair and a signed pre-key that they sign with *that* identity key (a normal, valid, self-consistent bundle from libsignal's point of view), then overwrites the victim's `public_keys/bundle` document with it. The next time anyone establishes a **first-ever** session with the victim (a new contact, or the victim's own partner after a reinstall/re-pair), `SessionBuilder`'s signature check passes — because the substituted `signedPreKey`'s signature really was produced by the substituted `identityKey` — and the TOFU trust model (confirmed in Round 1/`DuoShieldSignalStore.isTrustedIdentity()`) accepts the attacker's identity with **no warning at all**, since from that new contact's perspective this is a completely ordinary first encounter. This gives the attacker a working Signal session with the victim's real contact while impersonating the victim, achievable with nothing more than the victim's UID (already established as low-friction to obtain — see Finding 2) and zero reverse-engineering.
- **Interacts with:** Findings 22/23 below — the one user-facing safety net against exactly this attack (manual fingerprint/safety-number verification) turns out not to work for ordinary first-time pairings either, so there's currently no practical way for a DuoShield user to catch this even if they tried.
- **Fix:** scope the cross-user grant to only the field it's for: `allow update: if request.auth != null && request.resource.data.diff(resource.data).affectedKeys().hasOnly(['oneTimePreKeys', 'updatedAt']);`. This is a one-line rules change with no client code impact, since the only legitimate cross-user caller (`consumeOtpkOnFirestore()`) already only ever touches those two fields.

**20. The global screenshot-protection default is inverted between what `SettingsActivity` *shows* the user and what `BaseActivity` actually *enforces* — a fresh install displays "screenshots off" while every screen, including the PIN lock screen, is actually fully screenshot/Recents/screen-record-capturable.**

- Files: `app/src/main/java/com/duoshield/app/BaseActivity.java` (`onCreate()`, lines 41–50), `app/src/main/java/com/duoshield/app/ui/SettingsActivity.java` (initial switch state, line 200; toggle listener, lines 816–854), `app/src/main/java/com/duoshield/app/LockScreenActivity.java` (line 61) and `app/src/main/java/com/duoshield/app/MainActivity.java` (line 41), both of which duplicate the same enforcement independently since neither extends `BaseActivity`.
- All **enforcement** call sites read the same preference key with the same default:
  ```java
  // BaseActivity.onCreate(), LockScreenActivity, MainActivity — all three, independently:
  .getBoolean("app_screenshot_enabled", true)   // default: ALLOWED if never set
  ```
  But the Settings screen's *displayed* toggle state (`activity_settings.xml` labels this switch **"Allow screenshots"**) reads the identical key with the opposite default:
  ```java
  // SettingsActivity.onCreate(), line 200:
  switchAppScreenshot.setChecked(prefs.getBoolean("app_screenshot_enabled", false));  // default: shown as OFF
  ```
- **Why it matters:** on any fresh install — which is to say, for every user who has never once touched this specific switch — the two defaults disagree. Real enforcement (`BaseActivity`/`LockScreenActivity`/`MainActivity`) treats the unset key as `true` and never applies `FLAG_SECURE`, so screenshots, screen recording, and the Recents-app-switcher thumbnail are all live on every screen in the app, including chat content and the PIN-entry lock screen. But `SettingsActivity` displays the "Allow screenshots" switch in the **off** position, which any ordinary user reads as "screenshots are currently blocked" — the opposite of what's actually true. A security-conscious user who checks Settings, sees the toggle already off, and concludes there's nothing to change will never discover that their actual protection state is the reverse of what they were shown. The `true` default itself is also a questionable choice for a stated E2EE app for at-risk users — the surrounding code comment says it's `true` "for testing," suggesting this was meant to be flipped before release and wasn't.
- **Fix:** make both defaults agree — the safer direction is to default to `false` (screenshots blocked) everywhere, matching what `SettingsActivity` already (accidentally) displays, since that's the appropriate secure-by-default posture for this app's stated audience. Concretely: change `BaseActivity`/`LockScreenActivity`/`MainActivity`'s `getBoolean("app_screenshot_enabled", true)` to `getBoolean("app_screenshot_enabled", false)`, and keep `SettingsActivity`'s existing `false` default as-is. Separately worth noting (minor, not the core bug): `applyScreenshotFlag()` only affects `SettingsActivity`'s own window, so an already-open `ChatMediaActivity` instance won't retroactively gain `FLAG_SECURE` until it's next recreated — acceptable given each activity re-checks the pref in its own `onCreate()`, but worth a one-line doc comment so a future maintainer doesn't assume the toggle applies instantly app-wide.

**27. Any current group member can silently plant a bogus group key in another specific member's private key slot, and the client will use it without ever checking it actually came from the group's creator — a targeted, working key-substitution attack that requires no prior relationship with the victim at all.**

- Files: `app/src/main/java/com/duoshield/app/GroupChatActivity.java` (`fetchGroupKey()`, lines 199–244), `app/src/main/java/com/duoshield/app/crypto/signal/SignalCipherHelper.java` (`decrypt()`, lines 94–113), `firestore.rules` (`match /groups/{groupId}/keys/{memberUid}`, lines 60–65).
- The rule for a group's per-member key slot restricts *read* to the owning member, but restricts *write* only to "any current group member" — not to the group's creator, and not to the slot's own owner:
  ```
  match /keys/{memberUid} {
    allow read:  if request.auth != null && request.auth.uid == memberUid;
    allow write: if request.auth != null
                 && request.auth.uid in get(/databases/$(database)/documents/groups/$(groupId)).data.members;
  }
  ```
- `fetchGroupKey()` reads this doc's `senderUid` field and passes it straight to `SignalCipherHelper.decrypt()` with no check against `Group.createdBy` (already cached locally in Room as `creatorUid`, but never compared against it):
  ```java
  String sender = snap.getString("senderUid");
  ...
  String decrypted = SignalCipherHelper.decrypt(this, sender, encryptedKey, sigType);
  groupKey = decrypted;
  localDb.groupDao().updateGroupKey(groupId, groupKey);
  ```
- `SignalCipherHelper.decrypt()` uses that unverified string to build the very address it decrypts against:
  ```java
  SignalProtocolAddress address = new SignalProtocolAddress(senderUid, SignalSessionManager.DEVICE_ID);
  SessionCipher cipher = new SessionCipher(store, address);
  ```
  For a `PREKEY_TYPE` message this **auto-establishes a brand-new inbound Signal session** with whatever `senderUid` the caller names, consuming the victim's already-public one-time-prekey bundle — the same bundle ordinary contact pairing consumes — with no requirement that the victim has ever paired with, messaged, or heard of that UID before.
- **Concrete attack:** `fetchGroupKey()` only runs while `Group.groupKey` is still `null` locally — i.e. exactly the moment a member is newly added to a group. Any *other* current member (including one just added by a careless or compromised creator) writes to `groups/{groupId}/keys/{victimUid}` with `senderUid` set to their own UID and `encryptedKey` set to an X3DH-encrypted blob of a group key **they generated themselves**. The victim's client silently establishes a fresh Signal session with the attacker and adopts the attacker's key as "the" group key, with nothing telling the user it didn't come from the group's actual creator. From then on the victim's outgoing group messages are encrypted under a key only the attacker holds — every legitimate member sees `[Decryption failed]` for that one member, while the attacker alone can read them.
- **Why Critical:** unlike Finding 2 (front-running an account ID during a narrow propagation window), this needs nothing but ordinary, permanent co-membership in the same group — a status the victim's own group creator hands out routinely. It fully defeats Signal Protocol confidentiality for one targeted member's group traffic using a Firestore write the rules explicitly permit.
- **Fix:** restrict the `keys/{memberUid}` write rule to the group's creator only (`request.auth.uid == get(/databases/$(database)/documents/groups/$(groupId)).data.createdBy`) — the existing code comment already says "creator distributes keys on creation," so this tightens the rule to match the stated design rather than changing it. As defense in depth, have `fetchGroupKey()` compare the fetched `senderUid` against the locally-cached `creatorUid` before calling `decrypt()`, and refuse/warn on mismatch instead of silently adopting the key.

**29. The `identities/{userId}` write rule never checks that the document being written actually *is* the caller's own identity slot — only that the caller labeled the payload with their own uid — so any authenticated account can silently hijack any other, already-established user's DS-ID → UID mapping at any time, with no race condition or timing needed.**

- Files: `firestore.rules` (`match /identities/{userId}`, lines 138–142), `app/src/main/java/com/duoshield/app/ui/SeedPhraseDisplayActivity.java` (`registerIdentity()`, lines 299–316), `app/src/main/java/com/duoshield/app/ui/RestoreFromSeedActivity.java` (identity-record step, lines 200–231), `server/index.js` (`/mintToken`, lines ~370–430, for contrast).
- The rule:
  ```
  match /identities/{userId} {
    allow read:  if request.auth != null;
    allow write: if request.auth != null
                 && request.resource.data.uid == request.auth.uid;
  }
  ```
  This checks that the *payload's* `uid` field equals the caller's own `request.auth.uid` — but it never checks that the *document ID* (`userId`, the path segment) has anything to do with the caller at all. Since a legitimately-minted Firebase custom token has `uid == userId` by construction (`server/index.js`: `admin.auth().createCustomToken(userId)` — the seed-derived DS-ID *is* the Firebase UID), an attacker's own valid, ordinary session lets them write `data.uid = <their own DS-ID>` to **any** `identities/{someoneElsesUserId}` document and have the rule accept it, because the check only ever inspects fields the attacker fully controls.
- **This is a different, more severe bug than Finding 2.** Finding 2 is a *race*: it only works against a `userId` that hasn't finished onboarding yet, in the narrow propagation window before the legitimate owner's own `identities` doc is written. This rule has no such restriction — it allows overwriting a DS-ID that has been active and in use for months, at a moment of the attacker's choosing, with no window to catch.
- **Client-side checks don't help, because they aren't part of the trust boundary.** `RestoreFromSeedActivity` does the right thing *in its own code* — it fetches the existing doc, compares `identityPubKeyHash`, and throws `"Identity hash mismatch (ID-COLLISION)"` on a mismatch before writing. But that logic lives in the DuoShield app's Java, not in the Firestore rule. Firestore rules are the actual enforcement boundary for anyone talking to the database directly (a modified client, or just the Firebase JS/REST SDK with a valid attacker session) — such a caller never runs `RestoreFromSeedActivity`'s hash check at all and can `.set()`/`.update()` the target document directly. `SeedPhraseDisplayActivity.registerIdentity()` on the legitimate new-account path doesn't even attempt a hash check, for comparison — it just `.set()`s unconditionally.
- **Concrete attack:** attacker learns (or is told, or intercepts) a victim's human-readable DS-ID — already established elsewhere in this review (Finding 2/8) as low-friction to obtain. Using their own, already-authenticated DuoShield session, the attacker issues a direct Firestore write to `identities/{victimUserId}` with `{uid: <attacker's own uid>}`. The write passes the rule immediately — no propagation window, no rate limit (the `/mintToken` cooldown and IP limits in `server/index.js` don't apply, since this bypasses `/mintToken` entirely and goes straight to Firestore). From then on, anyone who looks up the victim's DS-ID via `identities/{victimUserId}` — e.g. `ContactManager`'s `resolveIdentityWithRetry()` when a *new* contact tries to add the victim, or any re-pairing/QR-restore flow that resolves a DS-ID to a UID — resolves to the attacker's account instead of the victim's. Existing 1:1 sessions already pinned to the victim's real Signal identity key aren't retroactively broken (Signal's own session state doesn't re-resolve via this lookup), so the practical impact is concentrated on *new* contact-adds and re-pairing/restore flows for the victim's DS-ID going forward — but for an app whose stated audience actively adds new contacts under threat of a known adversary, redirecting all *future* first-contact attempts to an attacker-controlled account is a serious, persistent identity-hijack primitive, and a careful attacker could restore the mapping to the true uid after use to leave no visible trace beyond the messages exchanged during the window.
- **Fix:** bind the write to the path segment, not just the payload: `allow write: if request.auth != null && request.auth.uid == userId && request.resource.data.uid == request.auth.uid;`. This is a one-line rules change — every legitimate write already satisfies `request.auth.uid == userId` (a custom token's UID *is* the DS-ID), so no client code changes are needed. Once this is in place, the `/mintToken` first-mint race in Finding 2 becomes the only remaining way to contest a `userId`, narrowing that finding back to its originally-scoped onboarding window instead of a permanent, any-time hijack.

### 🟠 High

**10. Any DuoShield user can be tricked into deleting an arbitrary object from the shared media bucket for any other user, just by receiving a normal message.**

- Files: `app/src/main/java/com/duoshield/app/util/B2StorageHelper.java` (`deleteFile()`, line 658; `isB2Path()`/`toObjectKey()`, lines 216–224), `app/src/main/java/com/duoshield/app/db/SelfDestructWorker.java` (`commitBatchDelete()`, lines 138–161), `app/src/main/java/com/duoshield/app/db/B2CleanupWorker.java`, `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (line 2251).
- `B2StorageHelper.deleteFile(String b2Path)` performs an S3-style `DELETE` against Backblaze B2, using the object key exactly as given — `toObjectKey()` just strips the literal `"b2:"` prefix with **no check that the resulting key belongs to the caller, the caller's conversation, or even the caller's own account**:
  ```java
  public static void deleteFile(String b2Path) throws Exception {
      ...
      String objectKey = toObjectKey(b2Path);   // "b2:" stripped, used verbatim
      ...
      Request request = new Request.Builder().url(urlStr).delete()...
  ```
- The two call sites that fire this automatically (rather than only cleaning up a file the current device itself just uploaded) both read the path **directly from a Firestore message document that the *sender* authored**, with no ownership check:
  ```java
  // SelfDestructWorker.commitBatchDelete() — runs periodically for every conversation,
  // over messages the CURRENT USER received as well as sent:
  String mediaPath = doc.getString("path");
  if (mediaPath != null && B2StorageHelper.isB2Path(mediaPath)) {
      B2StorageHelper.deleteFile(mediaPath);   // no sender==myUid check anywhere above this
  }
  ```
  `isB2Path()` only checks for a `"b2:"` prefix — a trivially attacker-satisfiable condition, since the sender of any message fully controls every field of the Firestore doc they write, including `path`.
- **Concrete attack:** an attacker (who, per Finding 6, needs nothing more than a victim's Account ID/UID to open a chat with them) sends the victim a "media" message whose Firestore `path` field is set to `"b2:media/<someone-else's-chatId>/<someone-else's-uuid>.jpg"` — i.e. a path pointing at a **completely different, unrelated third victim's** photo or video — with `expiresAt` set for the near future (disappearing message / TTL). The next time the first victim's `SelfDestructWorker` runs (a routine periodic WorkManager job, requiring no interaction — the victim doesn't even need to open the chat), their own device signs and sends a real `DELETE` request to Backblaze B2 for that path, using their own copy of the app's embedded storage credential (see Finding 11), and destroys the third victim's media. The first victim is an entirely unwitting participant; nothing in the UI ever shows them the raw `path` string or asks for confirmation.
- **Why this matters more than a normal logic bug:** it turns *every ordinary DuoShield client* into an unauthenticated deletion oracle against a bucket shared by the whole user base — reachable via completely ordinary in-app usage (send a message), not reverse-engineering. It doesn't touch Signal Protocol message confidentiality (that layer is unaffected), which is why this is ranked High rather than Critical, but the blast radius (any object, any user, app-wide) and the near-zero skill required to trigger it argue against Medium.
- **Fix:** before calling `B2StorageHelper.deleteFile()` for a *received* message, verify the `path` actually belongs to the conversation/sender who wrote it — e.g. require the object key to be prefixed with `media/<conversationId>/` (checked against the message's own `conversationId`, which the client already trusts) and additionally require `doc.getString("sender").equals(myUid)` before ever issuing a delete on the strength of a locally-run cleanup job, since only the uploader should ever be allowed to delete their own object. Longer-term, this class of bug goes away entirely if deletes are moved server-side (Backblaze lifecycle rules keyed on a real per-object owner tag, or a Cloud Function/`server/index.js` endpoint that checks Firestore-recorded ownership before deleting) instead of trusting whatever `path` string shows up in a peer-authored document.

**22. The app's only user-facing defense against the Finding 19 identity-substitution attack — safety-number/fingerprint verification — never has anything to show for an ordinary first-time pairing, and even the one case it does work in isn't tied to a specific contact.**

- Files: `app/src/main/java/com/duoshield/app/crypto/signal/DuoShieldSignalStore.java` (`saveIdentity()`, lines 113–139), `app/src/main/java/com/duoshield/app/KeyFingerprintActivity.java` (`onCreate()`'s partner-fingerprint block, lines 76–92).
- `KeyFingerprintActivity` reads the partner's identity key for fingerprint comparison from exactly one place: a single, app-wide (not per-contact) `SecurePrefs` key, `signal_partner_identity_key`. Here's the entire write path for that key, in `DuoShieldSignalStore.saveIdentity()`:
  ```java
  if (existing == null) {
      prefs.edit().putString(prefsKey, incoming).apply();
      return true; // new identity — session can proceed
      // ← "signal_partner_identity_key" is NOT written here.
  }
  if (!existing.equals(incoming)) {
      // ... key changed (TOFU re-trust) ...
      prefs.edit()
          .putString(prefsKey, incoming)
          .putString("signal_partner_identity_key", incoming)   // ← only written here
          .apply();
      ...
  }
  ```
  The per-address key (`prefsKey`, keyed by the real `SignalProtocolAddress`) is written on *every* first contact, correctly. But the single global slot `KeyFingerprintActivity` actually reads is only ever populated on the **identity-changed** branch — never on ordinary first-use TOFU establishment, which is the `existing == null` branch a few lines above and is what happens for every normal new pairing.
- **Concrete effect:** open `KeyFingerprintActivity` (via either entry point — see Finding 23) right after pairing with a brand-new contact, before anything has gone wrong, and the partner-fingerprint field shows "Not available — complete pairing first," permanently, no matter how long ago pairing actually completed. The screen only ever shows *something* after a subsequent identity-key change — i.e. exactly the scenario Finding 19 describes, where an attacker has overwritten a victim's `public_keys/bundle` document. Even then, the value shown is whichever contact's identity most recently changed on this device, not necessarily the contact the user opened this screen to check on (both entry points are non-contact-specific — see Finding 23) — so in an app that supports multiple simultaneous contacts, a user with two or more contacts has no reliable way to verify *any specific one* of them, and no way at all to verify a brand-new one.
- **Why High, not Medium:** this isn't a new attack path by itself — nothing here is remotely exploitable on its own. It's ranked with Finding 10 because it fully neutralizes the one mitigation the app offers against an already-Critical finding (19). A Critical MITM with a working, if manual, detection mechanism is a meaningfully different risk than the same MITM with a detection mechanism that silently never fires for the case that matters most (first contact) — and per Finding 19, first contact with an attacker-substituted bundle is exactly the scenario with no other warning sign at all.
- **Fix:** populate a per-contact fingerprint record on *every* `saveIdentity()` call, not just the changed-key branch — e.g. `SecurePrefs` key `signal_partner_identity_key_<address>` written unconditionally alongside the existing per-address `prefsKey` write (they're already the same value on first use, so this is a one-line addition to the `existing == null` branch). Then have `KeyFingerprintActivity` require and read that per-contact key exclusively (see Finding 23 for why it currently can't even ask which contact it means), retiring the single global slot entirely.

**28. Group chat messages carry a client-asserted `sender` field and an optional `isEncrypted` flag that the recipient trusts unconditionally — any current group member can forge a message that displays as sent by any other member, or skip encryption entirely, and Firestore rules stop neither.**

- Files: `app/src/main/java/com/duoshield/app/GroupChatActivity.java` (`listenForMessages()`, lines 288–327; `trySend()`, lines 383–390), `app/src/main/java/com/duoshield/app/crypto/GroupCipherHelper.java` (no AAD/sender-binding parameter exists on `encrypt`/`decrypt` at all), `firestore.rules` (`match /groups/{groupId}/messages/{msgId}`, lines 50–54).
- The rule for group messages checks only membership, for every verb (`read, write` covers create, update, *and* delete):
  ```
  match /messages/{msgId} {
    allow read, write: if request.auth != null
                       && request.auth.uid in get(/databases/$(database)/documents/groups/$(groupId)).data.members;
  }
  ```
  Nothing requires `request.resource.data.sender == request.auth.uid`.
- On receive, the client takes both fields straight from the document with no cross-check against who actually wrote it:
  ```java
  String sender = doc.getString("sender");
  ...
  Boolean isEncrypted = doc.getBoolean("isEncrypted");
  plain = (Boolean.TRUE.equals(isEncrypted))
      ? GroupCipherHelper.decrypt(cipher, groupKey)
      : cipher;   // ← treated as already-plaintext if false/missing
  ```
  `GroupCipherHelper`'s AES-GCM auth tag only proves "encrypted by someone holding the shared group key" — i.e. *a* group member, not a specific one — since neither `encrypt()` nor `decrypt()` takes any AAD binding the ciphertext to a sender identity.
- **Concrete attack:** any group member writes a document to `groups/{groupId}/messages/{msgId}` with `sender` set to any other member's UID, `isEncrypted: false`, and `text` set to arbitrary plaintext. Every other member's client renders it as a normal message "from" the impersonated member — no group key or Signal session is needed, only the ordinary write access every member already has. The same rule gap permits overwriting an *existing* message document too (an update, not just a create), which a newly-added member's first history sync would pick up as if it were original, since an empty local Room DB means every existing message arrives fresh as an `ADDED` event.
- **Why High, not Critical:** a real, trivially reachable authenticity break within a group's existing membership, but it doesn't reach outside the group and doesn't touch 1:1 chat, which stays protected by a real per-pair Signal session — a forged `sender` there can't produce ciphertext that session will actually decrypt, because the Double Ratchet ties ciphertext to the specific session, unlike this static shared-key design.
- **Fix:** enforce `request.resource.data.sender == request.auth.uid` and `request.resource.data.isEncrypted == true` on `create`, and disallow `update` on existing message documents (only `create` and a narrowly-scoped `delete` restricted to `resource.data.sender == request.auth.uid`) — making messages append-only and self-attested at the rules layer instead of only client-trusted.

### 🟡 Medium / architectural

**2. New-account identity registration has a narrow but real front-running window.**

- Files: `server/index.js` (`/mintToken` handler, lines ~370–442), `firestore.rules` (`match /identities/{userId}`), `app/src/main/java/com/duoshield/app/auth/AuthTokenHelper.java`, `app/src/main/java/com/duoshield/app/contacts/ContactManager.java` (`resolveIdentityWithRetry`).
- The account ID (`userId`, format `XXXXX-XXXXX-XXX`) is deterministically derived from the BIP39 seed (`SeedPhraseHelper.deriveUserId`) and is what a user shares (QR code / deep link / typed ID) to be added as a contact **before** anything requires them to have proven ownership of that ID to the server.
- Server logic (`server/index.js`):
  ```js
  // New accounts (no /identities doc yet): token minted unconditionally.
  const idDoc = await db.collection("identities").doc(userId).get();
  if (idDoc.exists) {
    const storedHash = idDoc.data().identityPubKeyHash;
    if (storedHash && storedHash !== incomingHash) { /* 403 */ }
  }
  const token = await admin.auth().createCustomToken(userId);
  ```
  For a `userId` with **no existing `identities` doc**, the server mints a Firebase custom token unconditionally to whoever asks first, and the *first* successful mint effectively "claims" that UID. The client then writes the `identities/{userId}` doc client-side (`SeedPhraseDisplayActivity`), which Firestore rules gate on `request.resource.data.uid == request.auth.uid` — but by that point the UID has already been claimed via the custom token.
- **Concrete scenario:** DuoShield's own pairing flow shares the account ID *before* the recipient's `identities` doc is guaranteed to have propagated — `ContactManager` explicitly retries the `identities` lookup 5× with 1.5s backoff to paper over exactly this propagation delay. If an attacker learns a not-yet-registered `userId` (e.g. by intercepting a QR code/share intent meant for a partner who hasn't finished onboarding yet, or simply by racing a brand-new user's first app launch), the attacker can call `/mintToken` for that `userId` with their own key first and permanently own the identity (subsequent legitimate mint attempts for the same `userId` will 403 with "Key mismatch" once the attacker's hash is stored).
- **Mitigating factor:** `userId` has ~64 bits of entropy derived from a 128-bit-entropy seed, so blind guessing is infeasible. This is a **race/front-running** risk conditioned on an attacker learning a specific `userId` during the narrow window between generation and first mint — not a brute-force risk. That's why this is Medium rather than Critical/High, but it's a real architectural gap, not just theoretical: the retry logic in `ContactManager` is direct evidence the propagation window is wide enough to matter in practice.
- **Fix options (pick one):**
  - Have the client write `identities/{userId}` (with a signature/proof binding it to the freshly-generated identity key) **before** ever exposing the `userId` to a share/QR/deep-link flow, and have `/mintToken` require that doc to already exist (removing the "unconditional mint for new accounts" branch entirely).
  - Or: have `/mintToken` itself create the `identities` doc atomically as part of the *first* mint in the same server-side transaction, so "first mint" and "identity claim" are the same atomic event with no window for a second party to observe an unclaimed ID and act on it before the legitimate owner's own claim completes. (This is close to what happens today, but the *client*, not the server, currently writes the `identities` doc, which is the actual source of the window.)

**3. No group-member removal exists yet, but the group key model has no rotation path for when it's added.**

- Files: `app/src/main/java/com/duoshield/app/crypto/GroupCipherHelper.java`, `firestore.rules` (`groups/{groupId}/keys/{memberUid}`).
- Confirmed by repo-wide search: there is currently no `removeMember`/`kickMember`/`leaveGroup` code path anywhere in the app, so this is **not exploitable today** — flagging it now so it's on the roadmap before group management ships, not after.
- Groups use a single static AES-256 key shared by all members (`Group.groupKey`), distributed once at creation via each member's Signal session. There's no `rotateGroupKey` function. If member removal is added later without also adding key rotation, a removed member retains the ability to decrypt every subsequent group message (the AES key doesn't change), even though the Firestore rules would correctly stop them from *reading the Firestore documents* — they'd just need one cached copy of the key from before removal.
- **Fix (when member-removal ships):** on any membership change, generate a fresh `groupKey`, re-distribute it (via Signal sessions) only to the *current* member list, and re-encrypt is not required for history (past messages can stay under the old key) but all *new* messages must use the new key. This is the same problem Signal/WhatsApp solve with sender-keys + periodic re-keying; a simple "bump key on every membership change" policy is sufficient for DuoShield's two-person-core, small-group scale.

**5. Contact and group metadata is backed up to Firestore in plaintext, unlike message content.**

- File: `app/src/main/java/com/duoshield/app/backup/BackupManager.java` — `backupContacts()` (line ~796), and the contacts/groups block inside `syncAll()` (line ~394 onward) and `syncIncrementalSync()` (line ~694 onward).
- Every message body goes through `BackupCryptoHelper.encryptCompressed(key, json)` before it's written to `backups/{uid}/messages/{msgId}` — that part is correctly client-side-encrypted, confirmed in Round 1. But the **contact** and **group** backup paths write plain `Map`s straight to Firestore with no encryption step at all:
  ```java
  Map<String, Object> cdoc = new HashMap<>();
  cdoc.put("partnerUid",     c.uid);
  cdoc.put("displayName",    c.displayName != null ? c.displayName : "");
  cdoc.put("conversationId", c.conversationId != null ? c.conversationId : "");
  fdb.collection(COL_BACKUPS).document(uid)
     .collection(COL_CONTACTS).document(c.uid)
     .set(cdoc);   // ← no BackupCryptoHelper call anywhere in this path
  ```
  The same pattern applies to `gdoc` (group id, name, creator, full member-UID list).
- **Why it matters:** `BackupCryptoHelper`'s own class doc states "the server never sees plaintext," and the app's overall pitch (per your own design notes) is E2EE messaging for a privacy-conscious/at-risk audience. That invariant holds for message bodies but **not** for who you talk to: your full contact list (display names + Firebase UIDs), and for every group you're in, its name and complete member list, sit in Firestore as plaintext. Firestore rules do correctly restrict *client* read access to the owning UID only (confirmed in Round 1), so this isn't exposed to other app users — but it is fully readable by anyone with backend/database access (a compromised service account, a Firebase console user, or a legal-process request against the project), which is a meaningfully different exposure level than "server never sees plaintext" implies.
- **Fix:** reuse `BackupCryptoHelper.encryptCompressed`/`decryptCompressed` for the `cdoc`/`gdoc` payloads exactly as already done for messages — store `{enc, checksum, compressed:true}` instead of raw fields, and update `restoreContactsSync()` to decrypt on the way back in. This is a small, mechanical change since the crypto helper and the wire format already exist and are already used elsewhere in this same file.

**6. Calling (and 1-to-1 chat creation) has no contact/consent gate — anyone who learns a Firebase UID can ring or message that user, with a spoofable display name.**

- Files: `firestore.rules` (`match /calls/{callId}` and `match /chats/{chatId}`, both re-examined this round), `app/src/main/java/com/duoshield/app/call/CallManager.java` (`startCall`, line 272), `app/src/main/java/com/duoshield/app/notifications/DuoShieldMessagingService.java` (`handleCallInvite`, confirmed `callerName` is taken verbatim from the FCM data payload), `app/src/main/java/com/duoshield/app/call/IncomingCallActivity.java` (displays that name with no further check).
- The Firestore rule for calls only checks that the *creator* of the doc names themselves as `callerId` — it does not require any prior relationship with `calleeId`:
  ```
  allow create: if request.auth != null
                && request.auth.uid == request.resource.data.callerId;
  ```
  The `chats/{chatId}` creation rule has the identical shape (`request.auth.uid in request.resource.data.participants`). Neither checks that the two parties have ever exchanged an "add contact" step.
- **Why it matters:** DuoShield's own UI only exposes "call" and "message" buttons for existing contacts — but that's a client-side convention, not a server-enforced one. Any authenticated DuoShield user who obtains another user's Firebase UID (trivially derivable from their human-readable Account ID via the world-readable `identities/{userId}` mapping noted in Finding 2) can write a `calls/{callId}` doc naming that person as `calleeId`. The push server (confirmed in Round 1, `server/index.js`) will then unconditionally FCM-push a full-screen, ringing, wake-the-device call invite to the victim — `IncomingCallActivity` shows it over the lock screen with vibration and ringtone. The caller name shown on that screen (`callerName`) is pulled from the *caller's own, self-set* `users/{uid}.displayName` field with no verification — so the same stranger can also set their display name to something trust-inducing (e.g. matching a real contact's name) before ringing. This is a genuine unsolicited-contact / social-engineering / harassment vector, which matters more than usual given the app's stated audience (people who may specifically need to avoid unwanted contact from a known adversary who has previously learned their Account ID).
- **Interacts with:** Finding 2 — the same account-ID exposure that enables identity front-running also enables this, since both require nothing more than knowing someone's Account ID.
- **Fix:** require an explicit mutual step before either party can call or message the other — e.g. a `contacts/{uid}/{partnerUid}` doc that both sides must have written (mirroring how `AddContactActivity`'s UI already implies a deliberate add-contact action) — and gate `calls` create / `chats` create on that relationship existing in both directions, not just on the caller naming themselves correctly. Short of that, at minimum rate-limit call invites per (caller, callee) pair server-side and let the callee mute/block a `callerId`.

**7. The duress "panic sync" deadline is documented as 5 seconds but is actually implemented as 10 seconds.**

- File: `app/src/main/java/com/duoshield/app/backup/BackupManager.java`, `syncIncrementalSync()` (lines 562–578).
- The Javadoc immediately above the method says: *"The entire operation is capped at **5 seconds**; if time runs out the method returns immediately so the caller can proceed to the destructive wipe."* The code three lines below it says:
  ```java
  public static void syncIncrementalSync(Context ctx) {
      // PERF-OPT: Increase deadline for panic sync to 10 seconds to improve reliability,
      // but prioritize the most recent messages first (timestamp DESC).
      final long DEADLINE_MS = 10_000L;
  ```
  `DuressManager.performLogout()` (reviewed Round 1) also documents this step as "Hard deadline: 5 seconds," and calls `BackupManager.syncIncrementalSync(context)` synchronously before proceeding to the destructive local wipe (DB delete, `SecurePrefs.clear().commit()`, etc.).
- **Why it matters:** this is a coercion/seizure safety feature — the whole point of "sync then wipe" is that key material and the SQLCipher database get destroyed as fast as possible once duress is triggered. The actual code doubles the window during which the pre-wipe state (full plaintext Room DB, Signal session keys, PIN hashes) still exists on disk, relative to what's documented and relative to what `DuressManager`'s own Javadoc promises. The delay isn't visible to an observer (the UI has already navigated to `SignInActivity` before this runs, per Round 1's read of `DuressManager`), so it doesn't break the plausible-deniability property — but it does mean a device physically seized/imaged in roughly the 5–10 second window after a duress trigger has up to 2× longer than documented before the wipe completes.
- **Fix:** either update both docstrings (`BackupManager` and `DuressManager`) to accurately say 10 seconds, or — better, given the stated purpose of the feature — revert `DEADLINE_MS` to `5_000L` and accept the reliability trade-off the comment mentions, since for a duress-wipe path speed-of-wipe should dominate over sync-completeness. Either way, the doc and the code must agree; right now anyone auditing only the Javadoc (as this review initially did, before reading the method body) would report the wrong number.

**8. WebRTC calls default to `IceTransportsType.ALL`, exposing both parties' real IP addresses to each other with no relay-only option.**

- File: `app/src/main/java/com/duoshield/app/call/CallManager.java`, `createPeerConnection()` (line 163).
  ```java
  config.iceTransportsType = PeerConnection.IceTransportsType.ALL;
  ```
- **Why it matters:** with `ALL` transports, ICE will negotiate direct host/server-reflexive candidates whenever NAT traversal allows it, meaning each party's real (or NAT-mapped) IP address is disclosed to the other party as part of normal call setup — the TURN relay (`BuildConfig.TURN_URL`, confirmed configured in Round 1) is only used as a fallback when a direct path isn't available, not as a privacy guarantee. For a general chat app this is standard WebRTC behavior and not a bug. Given DuoShield's own stated intent to serve a privacy-conscious audience where a call partner could plausibly be an adversary in some threat models (e.g. someone who has your Account ID but whom you'd rather not have your IP address), this is worth a deliberate decision rather than the current implicit default.
- **Fix:** add a per-call or global "relay calls" setting that sets `config.iceTransportsType = PeerConnection.IceTransportsType.RELAY` when enabled, forcing all media through the configured TURN server and never revealing a direct IP to the other party (the same feature Signal ships as "Always Relay Calls" in Settings → Privacy). This trades a small amount of call quality/latency for IP privacy, and should be the user's choice rather than unconditional either way.

**11. Backblaze B2 and WebRTC TURN credentials are compiled as plaintext constants into every released APK.**

- Files: `app/build.gradle` (lines ~37–62), `app/src/main/java/com/duoshield/app/util/B2StorageHelper.java` (`getKeyId()`/`getAppKey()`, lines 75–80; signing logic throughout).
- `B2_KEY_ID` / `B2_APPLICATION_KEY` / `TURN_CREDENTIAL` are read from environment variables at *build* time (so, like the release keystore in Finding 1, they aren't committed to the repo) but are then written into `BuildConfig` via `buildConfigField`, which bakes them as literal strings into every compiled APK — recoverable by anyone with the APK through basic decompilation (`jadx`/`apktool`), no root or reverse-engineering expertise required.
- All B2 operations — not just uploads — are signed client-side with this static key using full AWS SigV4 (`uploadFile()`, `downloadFile()`, `deleteFile()` all call the same `getSigningKey()`/`hmacSha256Hex()` helpers). This directly contradicts the class's own header comment ("Downloads are public — bucket allows public GET"): reads go through the identical authenticated, static-keyed path as writes and deletes, so there is in practice **one shared secret gating every operation on the bucket**, embedded identically in every install.
- **Why it matters:** anyone who extracts this key gets the same permissions as the app itself against the whole media bucket (at minimum `PutObject`/`DeleteObject`, confirmed by `deleteFile()`'s existence — see Finding 10), independent of holding any DuoShield account at all, and independent of Firebase authentication entirely. Because the key is identical across every install rather than per-user, a single extraction compromises every user's media, not just the extractor's own.
- **Fix:** move B2 request-signing server-side — have `server/index.js` (which already holds the Admin SDK trust boundary) mint short-lived, per-object pre-signed upload/download/delete URLs on request from an authenticated Firebase user, the same pattern already used for Firebase custom-token minting via `/mintToken`. The client would then never hold a credential capable of acting on objects it doesn't own.

**12. Link previews are fetched automatically by every recipient, leaking the viewer's IP address and view-timestamp to whatever domain is embedded in a received message — with no tap required.**

- Files: `app/src/main/java/com/duoshield/app/util/LinkPreviewFetcher.java`, `app/src/main/java/com/duoshield/app/ui/MessageAdapter.java` (`bindLinkPreview()`, lines 624–664, invoked unconditionally from the plain-text-message render path at line 551), `app/src/main/java/com/duoshield/app/util/GlideHelper.java`.
- `LinkPreviewFetcher`'s own SSRF hardening is genuinely good — `isSafeUrl()` blocks loopback/link-local/site-local ranges, resolves the hostname before checking (so DNS-rebinding can't slip a private IP past the check), and manually re-validates every redirect hop instead of trusting `HttpURLConnection`'s automatic-redirect handling. None of that is the problem.
- The problem is *who* triggers the fetch and *when*: `MessageAdapter.bindLinkPreview()` runs during ordinary `RecyclerView` view-binding — i.e. the instant a message containing a URL scrolls into view in the chat — and unconditionally calls `LinkPreviewFetcher.fetch(url, ...)`. This is the opposite of Signal's design (sender-side unfurl, embedded in the encrypted payload so recipients never contact the linked domain); here, **every recipient's device independently makes a real, unauthenticated outbound HTTP(S) request to the linked domain merely by viewing the message**, revealing their IP address, a fixed-but-still-fingerprintable User-Agent string, and the precise moment they viewed that specific message.
- If the fetched page's `og:image` meta tag is present, `MessageAdapter` hands that URL straight to `Glide.load(...)` with **no `isSafeUrl()` check at all** — the SSRF hardening built into `LinkPreviewFetcher` does not extend to this second, chained fetch, so a crafted page could point `og:image` at an internal/private address and have Glide attempt to load it unvalidated, independent of leaking the viewer's IP a second time to whatever the image host actually is.
- **Interacts with:** Finding 6 — since any authenticated user can open a chat with any other user's Account ID with no consent step, an attacker doesn't need to be an existing contact to exploit this: they can message a stranger a link to a server they control and learn that person's IP/approximate location/exact online-viewing-time from their own access logs, with no click needed from the victim.
- **Fix:** move link-preview generation to the sender's device at compose time (fetch once, embed the resulting title/image/domain in the encrypted message payload itself, matching how media messages already carry metadata) so recipients never make a direct network request to a link's domain. If recipient-side fetching must stay for some transitional reason, at minimum route the `og:image` load through the same `isSafeUrl()` validation already written for the HTML fetch, and consider gating any preview fetch behind an explicit "load previews" tap for messages from non-contacts.

**13. The notification "Reply" quick-action resolves the wrong (or no) conversation partner, because the correct value is computed but never passed through.**

- Files: `app/src/main/java/com/duoshield/app/notifications/NotificationStyler.java` (`showMessage()`, lines 139–198), `app/src/main/java/com/duoshield/app/notifications/MessageReplyReceiver.java` (lines 39–57), `app/src/main/java/com/duoshield/app/ui/RestoreFromSeedActivity.java` (lines 338–356, the only remaining writer of the `partner_uid` SharedPreferences key).
- `NotificationStyler.showMessage(ctx, title, body, convId, partnerUid, myUid, badgeCount)` receives the *correct* `partnerUid` for this specific notification (ultimately sourced from the FCM payload's `senderUid`) but only attaches `convId` and `myUid` to the reply `PendingIntent` — `partnerUid` is never put into `replyIntent`'s extras at all:
  ```java
  Intent replyIntent = new Intent(ctx, MessageReplyReceiver.class);
  replyIntent.putExtra(MessageReplyReceiver.EXTRA_CONV_ID, convId);
  replyIntent.putExtra(MessageReplyReceiver.EXTRA_MY_UID,  myUid);
  // partnerUid is in scope here and is simply never added.
  ```
  `MessageReplyReceiver` has no `EXTRA_PARTNER_UID` at all, so it falls back to `prefs.getString("partner_uid", null)` — a SharedPreferences key that the current multi-contact architecture no longer maintains. A repo-wide search found exactly one remaining writer: `RestoreFromSeedActivity`'s legacy single-chat restore path (`.whereArrayContains("participants", currentUid).limit(1)`), which is back-compat scaffolding from before the multi-contact model shipped.
- **Practical effect:** for any user who hasn't gone through that specific legacy restore path, `partner_uid` is simply absent, so `MessageReplyReceiver` always falls into `showOpenAppNotification()` — the inline "Reply" notification action is silently non-functional for essentially all current multi-contact users, always telling them to open the app instead. In the narrower case where a stale value *is* present (e.g. a restored, since-migrated account with multiple current contacts), a reply typed for conversation A gets Signal-encrypted using the session for whichever contact B that stale value points to (`MessageBuilder.sendTextMessage()`'s encryption target is `partnerUid`, independent of `convId`) and is then filed under conversation A's Firestore collection — so it reaches neither B (who could decrypt it, but never receives it) nor A (who receives it, but can't decrypt ciphertext meant for B), while still advancing B's Double Ratchet sending-chain state for a message that will never actually be delivered to B.
- **Fix:** add `replyIntent.putExtra("partner_uid", partnerUid);` in `NotificationStyler.showMessage()` and read that extra first in `MessageReplyReceiver` (falling back to the stored preference only if absent). This is a small, contained fix — the correct value already exists in scope at the point it's dropped.

**14. "Wipe & Exit" doesn't fully live up to its name: no Firebase sign-out, an intentionally-preserved plaintext contact list, and no confirmation prompt for a one-tap irreversible action.**

- Files: `app/src/main/java/com/duoshield/app/util/WipeHelper.java`, `app/src/main/java/com/duoshield/app/util/ContactBackupHelper.java`, `app/src/main/java/com/duoshield/app/ConversationListActivity.java` (lines 162–179), `app/src/main/java/com/duoshield/app/security/DuressManager.java` (`performLogout()`, for comparison).
- `WipeHelper.wipeAll()` — the code path behind the `ConversationListActivity` overflow menu's **"Wipe & Exit"** item — clears `SecurePrefs`, deletes the Room DB, clears the B2 disk cache, clears `duoshield_prefs`, and clears the cache directory. It never calls `FirebaseAuth.getInstance().signOut()`. `DuressManager.performLogout()` (the duress path) *does* call `signOut()`. A repo-wide search confirms `WipeHelper.wipeAll()` is only invoked from that one menu item — nothing else in the voluntary-wipe path signs out either.
- Separately, and by explicit design (per `ContactBackupHelper`'s own class doc): `WipeHelper.wipeAll()` backs up the contact list to a **separate, unencrypted** SharedPreferences file (`duoshield_contacts_bak` — plain `SharedPreferences`, not `SecurePrefs`) specifically so it survives a voluntary wipe and can restore contacts after the next sign-in. `DuressManager.performLogout()` correctly calls `ContactBackupHelper.clearBackup()` so the duress path doesn't have this gap — only the voluntary "Wipe & Exit" path does.
- **Why it matters:** the practical impact of the missing `signOut()` is muted — `SignInActivity` only auto-routes back into the app when `SignalKeyManager.isInitialized()` is also true, and the wipe does destroy those keys, so the user won't be silently dropped back into the app. But a still-valid, unrevoked Firebase Auth session token remains in the OS-level Firebase persistence store (untouched by any of `WipeHelper`'s clearing steps, which only reach `duoshield_prefs`/`SecurePrefs`/the cache dir/the Room DB file) — so anyone with root or forensic access to the "wiped" device's private storage can pull that token and use it to read whatever it's still entitled to, including the plaintext contact list preserved right alongside it, and (per Finding 5) the plaintext contact/group metadata already sitting in the cloud backup under that same UID. A user acting on the plain-language promise of a menu item called "Wipe & Exit" — e.g. before handing off or selling a device — gets meaningfully less than the name implies.
- Separately: the menu handler fires `WipeHelper.wipeAll(this)` directly from the `PopupMenu` item-click listener with no confirmation dialog at all — a single mis-tap in an overflow menu permanently deletes local chat history. Every other destructive action reviewed in this app (`CallHistoryActivity`'s per-item delete and "Clear all") shows a `MaterialAlertDialogBuilder` confirmation first; this one doesn't.
- **Fix:** add `FirebaseAuth.getInstance().signOut()` to `WipeHelper.wipeAll()` for parity with the duress path (this is a deliberate difference in scope — voluntary wipe intentionally keeps contacts for restore — so signing out doesn't need to also touch `ContactBackupHelper`, just close the live session the same way duress-logout already does). Add a confirmation dialog before invoking `wipeAll()`, matching the pattern already used elsewhere in the app. Consider whether "Wipe & Exit" should say something like "Wipe Chats & Exit" if contacts are meant to survive by design, so the label matches the actual guarantee.

**15. "Export Chat" tells the user the plaintext PDF will be removed after sharing; nothing in the code actually does that.**

- Files: `app/src/main/java/com/duoshield/app/util/ExportHelper.java` (lines 33–44), `app/src/main/java/com/duoshield/app/util/TempFileCleaner.java`.
- `exportToPdfWithConfirmation()`'s dialog text states: *"The file will be removed from your device after sharing."* No code anywhere — not in `ExportHelper`, not via a chosen-component callback on the `Intent.createChooser()` call — ever deletes the file in response to the share completing or being cancelled. `ExportHelper` uses the plain (non-callback) `createChooser(intent, title)` overload, so there is no signal at all in this code for "the user finished sharing."
- The **only** thing that ever removes `duoshield_export_*.pdf` is `TempFileCleaner`, a generic periodic `WorkManager` job that already exists for a different purpose (mopping up decrypted voice/video temp files). It deletes matching files only once they are at least 5 minutes old (`MAX_AGE_MS`), and only checks every 15 minutes (`INTERVAL_MIN`, the WorkManager minimum) — so a full plaintext chat-history PDF can sit in `getCacheDir()` for anywhere from ~5 to ~20+ minutes (longer if WorkManager is deferred by Doze/battery optimization), regardless of whether the user ever completed, cancelled, or even started the share.
- **Why it matters:** this is exactly the same category of issue as Finding 7 (a security-relevant claim shown to the user doesn't match the implementation) applied to a feature whose entire dialog is built around informing the user of exactly this risk. `SecureShareHelper`'s equivalent temp file (`share_*.jpg`) relies on the same `TempFileCleaner` mechanism but never claims otherwise in its own doc comment — only `ExportHelper`'s user-facing dialog text is inaccurate.
- **Fix:** either register a chosen-component `PendingIntent` callback (`Intent.createChooser(intent, title, pendingIntent)`, API 22+) or a `BroadcastReceiver` triggered on `ACTION_SEND` completion and delete `outFile` there, or — simpler — soften the dialog copy to accurately describe the actual cleanup window ("removed automatically within about 20 minutes") rather than promising immediate removal.

**16. The duress-wipe's own audit-log write races the database deletion it's supposed to precede, undermining the feature's documented "plausible deniability" guarantee in the case the code comment is trying to guard against.**

- Files: `app/src/main/java/com/duoshield/app/security/DuressManager.java` (`performLogout()`, lines 146–198), `app/src/main/java/com/duoshield/app/util/SessionLogger.java`, `app/src/main/java/com/duoshield/app/backup/BackupManager.java` (`syncIncrementalSync()`, re-examined for this finding).
- `performLogout()`'s very first line is `SessionLogger.log(context, SessionLogger.DURESS_LOGOUT)`, with a comment explaining the intent: *"Log before destroying the session (DB write must happen first)."* But `SessionLogger.log()` is fire-and-forget — it hands the actual Room insert to its own private single-thread `ExecutorService` and returns immediately, with no `Future`, no callback, and nothing in `performLogout()` that waits for it. A few lines later, a **separate** background thread (`"duress-logout"`) runs `BackupManager.syncIncrementalSync(context)` and then calls `AppDatabase.clearInstance(); context.deleteDatabase("duoshield_db");` — with no synchronization at all between these two independently-scheduled executors.
- The panic-sync step in between happens to usually — but not reliably — give the trivial local insert time to land before the delete: `syncIncrementalSync()` returns almost immediately whenever there's simply nothing new to upload (`"no new messages — nothing to panic-sync"`, confirmed in the method body), which is an ordinary, common condition entirely independent of network connectivity, not just a low-connectivity edge case.
- **Why it matters:** `performLogout()`'s own Javadoc lists *"Plausible deniability — device presents as unconfigured/factory-reset"* as one of the method's stated security guarantees. If the delete wins the race, `SessionLogger`'s queued insert (which calls `AppDatabase.getInstance(context)` — and `clearInstance()` means that call can transparently open a **brand-new** database file after the old one is already gone) can land in a freshly-recreated, otherwise-empty post-wipe database containing exactly one row: a `SessionEvent` explicitly typed `"DURESS_LOGOUT"`, with a timestamp and the device's manufacturer/model/Android version — about as far from "presents as unconfigured/factory-reset" as a forensic artifact can get. This is a race condition, not a guaranteed outcome — the actual result depends on Android's executor/thread scheduling and is not something I ran or timed — but the code provides no ordering guarantee at all for a one-shot, irreversible, safety-critical operation whose own comment shows the developer intended one.
- **Fix:** make the log write synchronous and awaited before proceeding, e.g. have `SessionLogger` expose a variant that blocks on `Tasks.await`/a `Future` until the insert completes (or fails), and call that from `performLogout()` immediately before `AppDatabase.clearInstance()` — so the ordering the comment already describes is actually enforced rather than incidental.

**21. "Delete for everyone" has no ownership check — either side of a 1:1 chat can permanently erase a message the *other* person sent, from both devices, with no confirmation, notice, or tombstone.**

- Files: `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (`showMessageActionDialog()`, lines 1625–1637; `deleteForEveryone()`, lines 1696–1719; the `MODIFIED`-listener handling, lines 1365–1380), `firestore.rules` (`match /chats/{chatId}/messages/{msgId}`, lines 31–35).
- `showMessageActionDialog()` gates several actions on `mine` (only your own messages get "Edit," confirmed in Finding 18's read of this same method) but adds "Delete for everyone" **unconditionally**, for every message regardless of sender:
  ```java
  if (canEdit) addMsgAction(actions, R.drawable.ic_edit, "Edit", false, sheet, () -> showEditDialog(msg));
  ...
  addMsgAction(actions, R.drawable.ic_delete, "Delete for everyone", true, sheet, () -> deleteForEveryone(msg));
  // ← no `mine` check here at all
  ```
  `deleteForEveryone()` then writes `deletedForAll: true` to the message document with no sender check of its own, and the Firestore rule that guards the write doesn't distinguish sender from recipient either — it only checks that the caller is *a* participant in the chat:
  ```
  match /messages/{msgId} {
    allow read, write: if request.auth != null
                       && request.auth.uid in
                          get(/databases/$(database)/documents/chats/$(chatId)).data.participants;
  }
  ```
  The receiving side's own code comment confirms this was known, not accidental: the `MODIFIED`-listener handler that actually removes the message locally is labeled *"Cross-device delete: either party called 'Delete for everyone'."*
- **Why it matters:** for an app whose stated audience may specifically need a reliable, tamper-proof record of what the other person said (the same audience consideration already raised for Findings 6 and 8), either participant can unilaterally and silently rewrite that record — the message just vanishes from both devices (`adapter.removeMessage()` + a Room delete, no "This message was deleted" placeholder), with no prompt, log, or recovery path on the side whose message got erased. This doesn't touch Signal Protocol confidentiality and is scoped to the two existing participants (no cross-user/app-wide blast radius like Finding 10), which is why it's Medium rather than High — but it's a real integrity/accountability gap in a feature explicitly named "for everyone."
- **Fix:** gate the "Delete for everyone" UI option the same way "Edit" already is (`mine` only), and — since a client-side-only fix doesn't stop a modified/rooted client from sending the write directly — add the matching server-side check: `allow update: if ... && (!('deletedForAll' in request.resource.data.diff(resource.data).affectedKeys()) || resource.data.sender == request.auth.uid);` (or a simpler dedicated rule scoping any `deletedForAll` write to the document's own `sender` field) so recipients can't delete-for-everyone on messages that aren't theirs even by calling the Firestore SDK directly.

**23. `KeyFingerprintActivity` has no way of knowing which contact it's supposed to be verifying, and the "VERIFY" action clears the underlying warning before anything has actually been checked.**

- Files: `app/src/main/java/com/duoshield/app/KeyFingerprintActivity.java` (whole file — it declares no `Intent` extras and accepts none), `app/src/main/java/com/duoshield/app/ConversationListActivity.java` (line 172, the generic "Key Fingerprint" overflow-menu entry), `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (`checkSafetyNumberBanner()`, lines 1871–1891).
- Both places that launch `KeyFingerprintActivity` do so with a bare `Intent` and nothing else:
  ```java
  // ConversationListActivity — generic Settings-style overflow item, no contact context at all
  if (id == 2) { startActivity(new Intent(this, KeyFingerprintActivity.class)); return true; }

  // ChatMediaActivity — DOES know which contact (partnerUid is in scope), but doesn't pass it
  startActivity(new Intent(this, KeyFingerprintActivity.class));
  ```
  `KeyFingerprintActivity` itself declares no `partner_uid`-style extra anywhere in its source, so even the second call site — which is reached specifically because *this* contact's identity changed — can't tell the activity which contact that was. Combined with Finding 22, the screen ends up showing whichever contact's key last changed on this device, which may not be the one the user is trying to check.
- Separately, and independent of Finding 22: `ChatMediaActivity.checkSafetyNumberBanner()`'s "VERIFY" button clears the per-contact warning flag **before** launching the verification screen, not after a successful check:
  ```java
  if (btnVerify != null) btnVerify.setOnClickListener(v -> {
      getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
              .edit().remove("safety_num_changed_" + partnerUid).apply();   // cleared immediately
      safetyNumberBanner.setVisibility(View.GONE);
      startActivity(new Intent(this, KeyFingerprintActivity.class));        // verification happens after, if at all
  });
  ```
  If the user taps VERIFY, glances at (or is confused by, given Finding 22) the fingerprint screen, and backs out without scanning a QR code or comparing anything — or the scan comes back "do NOT match" and they just close the dialog — the warning for that contact is already gone and will not reappear, because nothing re-arms `safety_num_changed_<partnerUid>` short of another identity-key change.
- **Why it matters:** this compounds Finding 22 in the one scenario where that finding's storage bug wouldn't otherwise apply (a returning user re-checking a contact whose key changed while the app already happened to have the right value cached) — the UI still can't confirm *which* contact is on screen, and the safety mechanism can be dismissed without ever completing the check it exists to prompt.
- **Fix:** add a `partner_uid` (and ideally `partner_display_name`) `Intent` extra at both call sites, have `KeyFingerprintActivity` require it (falling back to a contact-picker only for the generic Settings entry point, which legitimately doesn't have one contact in context), and move the `safety_num_changed_<partnerUid>` clear to fire only after `KeyFingerprintActivity` reports back a successful match (e.g. via `startActivityForResult`/`ActivityResultLauncher` with a result code), not unconditionally on tapping VERIFY.

**24. "Share Contact" sends the contact card as plaintext, completely bypassing Signal Protocol encryption that every other message type goes through.**

- Files: `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (`sendContactCard()`, lines 2265–2294, contrasted with `sendMessage()`, lines 2313–2418).
- `sendMessage()` — the normal text-send path — routes every outgoing message through `SignalCipherHelper.encrypt()` and stores only the resulting ciphertext, explicitly flagged `isEncrypted: true`:
  ```java
  SignalCipherHelper.EncryptResult r = SignalCipherHelper.encrypt(ChatMediaActivity.this, partnerUid, plaintext);
  doc.put("text", r.ciphertextB64);
  doc.put("isEncrypted", true);
  ```
  `sendContactCard()` — triggered from the same media-picker sheet as photos/videos — never calls `SignalCipherHelper` at all. It writes the literal card string straight to Firestore and says so:
  ```java
  String cardText = "DuoShield User|" + myUid;
  ...
  doc.put("text", cardText);          // plaintext, not ciphertext
  doc.put("isEncrypted", false);      // explicit — the receiving side is told not to try decrypting
  ```
- **Why it matters:** the whole premise of the app (and the explicit class-doc claim already flagged in Finding 5 for backups — "the server never sees plaintext") is that message content never reaches Firestore/the backend unencrypted. This message type is the one deliberate exception, and it's not documented as an intentional design decision anywhere in the code — there's no comment explaining why a contact card is exempt, unlike (for contrast) `ExportHelper`'s at-least-attempted user-facing disclosure in Finding 15. The specific data exposed today is limited (the sender's own UID, which per Finding 2 is already meant to be shareable and is arguably no more sensitive than a phone number in a normal contacts app), which is why this is Medium and not higher — but it establishes a working, precedent-setting "skip encryption" code path inside the encrypted-chat pipeline that a future change to this feature (e.g. adding a display name or note to the card) could extend without anyone having to deliberately decide to weaken encryption, since that decision was already made once, silently, here.
- **Fix:** route `sendContactCard()` through `SignalCipherHelper.encrypt()` exactly like `sendMessage()` does — the payload is a short string, so the existing text-message encryption path handles it with no format changes needed. Keep the `contact_card`/`mediaType` tagging so the receiving side still renders it specially; the only change needed is applying the same crypto step already used for everything else.

**25. Decrypted voice-note temp files are invisible to the app's only cleanup job because of a filename-extension mismatch — every plaintext voice-note byte written to the cache directory (recording *and* playback) is left to whatever `deleteOnExit()` can manage, which Android frequently doesn't honor.**

- Files: `app/src/main/java/com/duoshield/app/util/TempFileCleaner.java` (`isTempMediaFile()`, lines 79–84), `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (`onVoicePlay()`, line 937), `app/src/main/java/com/duoshield/app/util/VoiceRecorderHelper.java` (recording output file, line ~36).
- `TempFileCleaner`'s own class doc states its purpose plainly: *"DuoShield writes decrypted voice (.3gp) and video (.mp4) bytes to the cache directory just before playback. These files must not persist — they contain plaintext media."* Its matcher looks for exactly that:
  ```java
  private static boolean isTempMediaFile(String name) {
      return (name.startsWith("voice_") && name.endsWith(".3gp"))
          || (name.startsWith("vid_")   && name.endsWith(".mp4"))
          || ...
  }
  ```
  But neither of the two places that actually create a `voice_`-prefixed file uses `.3gp` — both use `.m4a` (an MPEG-4/AAC container, presumably from a since-changed recording format that the cleaner's patterns were never updated to match):
  ```java
  // ChatMediaActivity.onVoicePlay() — decrypted playback copy
  File tmp = File.createTempFile("voice_", ".m4a", getCacheDir());

  // VoiceRecorderHelper — original recording, pre-upload
  File out = new File(ctx.getCacheDir(), "voice_" + System.currentTimeMillis() + ".m4a");
  ```
  Neither file will ever match `name.endsWith(".3gp")`, so `TempFileCleaner`'s periodic WorkManager sweep — the app's stated safety net for exactly this class of file — silently skips every one of them, every time, indefinitely. (By contrast, `MediaViewerActivity.java`'s video-playback temp file is named `vid_view_*.mp4` — this happens to still match `isTempMediaFile()`'s `startsWith("vid_")` check since `"vid_view_...".startsWith("vid_")` is true, so that particular path is fine; it's specifically the voice/`.3gp`-vs-`.m4a` pairing that's broken.)
- **Why it matters:** the playback copy relies solely on `tmp.deleteOnExit()`, which registers a JVM shutdown hook — a mechanism Android very often never triggers, since the OS typically reclaims an app's process via `Process.killProcess()`/the low-memory killer/"Force Stop" rather than a clean JVM exit. The recording-side original is explicitly deleted after a *successful* upload (`f.delete()` in `uploadVoiceNoteWithRetry()`'s success path) but is never cleaned up on the failure path once retries are exhausted. Net effect: a plaintext copy of essentially every voice message a user ever sends or plays is written to `getCacheDir()` with no reliable removal mechanism at all — worse than Finding 15's already-flagged export-PDF gap, because voice messaging is routine, everyday usage rather than an occasional deliberate export action, and because unlike Finding 15 there's no user-facing claim at all here promising cleanup — so a user has no reason to even suspect the files are accumulating.
- **Fix:** the minimal fix is a one-line change to `isTempMediaFile()` to also match `.m4a` (or, better, to rename the created files to `.3gp` if nothing downstream depends on the real container format — it doesn't; `.m4a`/AAC is set explicitly in `MediaRecorder.setAudioEncoder()`, so renaming the extension without changing the encoder would just be inaccurate, whereas fixing the matcher pattern is correct either way). Additionally, add an `f.delete()` call to the exhausted-retries branch of `uploadVoiceNoteWithRetry()` so a failed upload doesn't leave its plaintext source file behind indefinitely either.

**26. The disappearing-messages timer is a single global preference, not scoped per conversation — opening a different chat, or even just receiving a sync update from one contact, can silently change (or apply) the self-destruct timer used for messages to a completely different contact.**

- Files: `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (`getDisappearMs()`, line 1765–1767; `showDisappearPicker()`'s write, line 1808; `listenForConvUpdates()`'s partner-sync write, lines 1113–1135), `app/src/main/java/com/duoshield/app/util/MessageBuilder.java` (lines 56, 133), `app/src/main/java/com/duoshield/app/util/SelfDestructScheduler.java` (line 14).
- Every read and write of the disappearing-messages setting — on the picker UI, in the outgoing-message builder used by ordinary sends *and* by `MessageReplyReceiver`'s notification quick-reply (see Finding 13's discussion of that same class), and in the scheduler — goes through one unscoped key in the app-wide `duoshield_prefs` file:
  ```java
  private long getDisappearMs() {
      return getSharedPreferences("duoshield_prefs", MODE_PRIVATE).getLong("disappear_ms", 0);
  }
  ```
  There is no `conversationId` (or `partnerUid`) anywhere in this key. The Firestore side of the feature *is* correctly scoped — each `chats/{conversationId}` document carries its own `disappear_ms`/`disappear_set_by` fields — but the client's local notion of "the current disappearing-messages timer" doesn't track per-conversation state at all; it just reflects whatever was written last, from any conversation.
- **Concrete scenario:** User A sets a 1-hour disappearing timer while chatting with Partner B, then switches to a chat with Partner C who has never touched the feature. `ChatMediaActivity.updateDisappearBanner()`/`getDisappearMs()` for the C conversation will show and apply "messages disappear after 1 hour" — a setting the user never chose for C — because it's reading the same global key B's chat just wrote. It gets worse with `listenForConvUpdates()`'s partner-sync logic: if Partner B changes their timer while A is in a *different* chat (with C) in the background, A's `convListener` for the B conversation still fires and executes `sp.edit().putLong("disappear_ms", partnerMs).apply()` — silently overwriting the global value out from under the C conversation A is actively looking at, with no on-screen indication that the change just applied came from an unrelated chat. And because `MessageBuilder.sendTextMessage()` (used by notification quick-replies) reads this same global key, a reply sent from a lock-screen notification for one contact can pick up a disappearing-timer value that was actually set for a different contact entirely.
- **Why it matters:** for a feature whose entire purpose is a user-controlled promise about message retention, this means the promise silently applies to the wrong conversation under ordinary multi-contact use — a user could reasonably believe they've turned disappearing messages *off* for a sensitive conversation, only to have it turned back on by background sync churn from an unrelated chat, or vice versa (messages that were supposed to self-destruct don't, because a different conversation's "off" setting leaked in).
- **Fix:** scope the SharedPreferences key by conversation, e.g. `"disappear_ms_" + conversationId`, in all four places listed above (`ChatMediaActivity`'s getter/setter/sync-listener and `MessageBuilder`'s two reads), and have `MessageBuilder` take the target `conversationId` as an explicit parameter (it should already have this in scope at both call sites — the outgoing chat screen and the notification-reply receiver, per Finding 13) rather than implicitly reading a single ambient value.

### 🟢 Low / cleanup

**4. `SecurePrefs` uses the deprecated `androidx.security.crypto.MasterKeys` / `MasterKeys.AES256_GCM_SPEC` API.**

- File: `app/src/main/java/com/duoshield/app/util/SecurePrefs.java`, lines 6–7, 38.
- `MasterKeys` was deprecated in `androidx.security.crypto` in favor of `MasterKey.Builder`. It still functions, and the fallback-to-plaintext-with-`isAvailable()`-guard design here is genuinely good defensive engineering — this is a low-severity maintenance note, not a security hole. Functionally nothing is wrong today; the risk is only that a future `androidx.security.crypto` library bump could remove `MasterKeys` entirely and break key storage at compile time with no runtime warning until then.
- **Fix:** migrate to `MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()` and pass the resulting `MasterKey` object instead of the alias string, on a normal maintenance cadence — no urgency.

**9. `CallCleanupWorker`'s Firestore query can never succeed — it's dead code that silently fails every 12 hours.**

- File: `app/src/main/java/com/duoshield/app/call/CallCleanupWorker.java`, `doWork()` (line 52).
  ```java
  com.google.firebase.firestore.Query query = FirebaseFirestore.getInstance()
          .collection("calls")
          .whereLessThan("createdAt", new Date(cutoffMs));
  ```
- **Why it matters:** `firestore.rules` (re-examined this round) restricts `calls/{callId}` access with `resource.data.callerId`/`calleeId` field checks and no rule that references `createdAt`. Firestore's security-rules engine rejects an entire query with `PERMISSION_DENIED` if it cannot prove every document the query *could* return is readable by the requester — and a collection-wide query filtered only by `createdAt`, with no `callerId`/`calleeId` filter tying it to the signed-in UID, can never be proven safe. In practice this means **every run of this worker throws and hits the `catch` block**, logs a warning, and returns `Result.retry()` — it will never once successfully delete a stale call doc, contradicting its documented purpose ("Protects against clients that crash mid-call..."). This is not a confidentiality problem (the rules correctly *block* the over-broad query rather than leaking data) — it's a data-hygiene bug: call docs and their `callerCandidates`/`calleeCandidates` subcollections (which contain ICE candidate data, i.e. IP-address-adjacent metadata) from crashed/killed clients accumulate in Firestore indefinitely instead of being purged after 24 hours as intended.
- **Fix:** this cleanup can't be done client-side under the current rules (no client can list other people's — or even a mix of — call docs by `createdAt` alone). Move this job server-side: either add it to `server/index.js` (which uses the Admin SDK and bypasses rules entirely, matching the `_server_health` pattern already used there) as a periodic `setInterval`, or add a scheduled Cloud Function. Delete `CallCleanupWorker`/its `WorkManager` scheduling from the client once the server-side equivalent exists, since the client-side version will never do anything but burn a WorkManager slot every 12 hours.

**17. `AppLockManager`'s `coldStart` flag is dead code — set, but never read by anything, including the method its own comment says depends on it.**

- File: `app/src/main/java/com/duoshield/app/util/AppLockManager.java`, lines 42–43 (field), 101 (only write site).
- The field's Javadoc says it exists to make `shouldLock()` skip evaluating a stale background timestamp right after a cold start. `shouldAutoSignOut()` sets `coldStart.set(false)` with the comment *"keep the flag consistent for shouldLock callers"* — but a repo-wide search found no read of `coldStart` anywhere, including inside `shouldLock()` itself, which makes its lock decision entirely from `bgTs`/`timeout` with no reference to this field. Functionally this looks harmless: `shouldLock()`'s actual cold-start behavior (don't flash-lock if `bgTs == 0`) already does the right thing without the flag, so this reads as leftover scaffolding from an earlier version of the logic rather than a live bug — no different in spirit from Finding 9's dead-code note.
- **Fix:** either remove the unused field and its stale comment, or, if some future refactor of `shouldLock()` is expected to actually consult it, leave a `// TODO: currently unused` marker so the next person doesn't spend time (as this review briefly did) looking for a read site that isn't there.

---

## 3. Roadmap / running log

**Round 1:** Covered the core crypto stack end-to-end (Signal Protocol key generation/rotation/session establishment/encryption, BIP39 seed derivation, backup encryption, group message encryption, duress wipe, PIN/duress-PIN storage, `SecurePrefs`/`DatabaseKeyProvider`), the manifest, Firestore rules, the push server, and the Cloud Functions stub. Found 1 Critical (release keystore exposure risk), 2 Medium architectural notes, 1 Low cleanup item. No weak crypto, no hardcoded secrets, no sensitive-data logging, no TLS bypass, no WebView JS bridge, no SQL injection found anywhere in the files read or in the repo-wide sweeps.

**Round 2:** Covered `call/` (WebRTC signaling, TURN config, incoming/outgoing call UI, call cleanup) and `backup/` (full backup/restore/panic-sync/retention logic), plus the two remaining Signal signed-pre-key rotation-scheduling files held over from Round 1. Found 4 new Medium findings (plaintext contact/group metadata in backups, no consent gate for calls/chats, a documented-vs-actual duress-deadline mismatch, no relay-only calling option) and 1 new Low finding (a Firestore-rules-incompatible query that makes `CallCleanupWorker` permanently non-functional). No weak crypto, hardcoded secrets, sensitive logging, or SQL injection found in this round's files either. `SeedPhraseHelperTest.java` remains explicitly out of scope as a test file.

**Round 3:** Covered `notifications/` in full, the two `call/` history files, and the highest-risk network/file-I/O files in `util/` (media storage, export/share, wipe, link previews, session logging), plus a re-examination of `DuressManager.performLogout()`'s interaction with a file read for the first time this round. Found this round's first **High** finding (an unauthenticated, attacker-triggerable delete against the shared media bucket, reachable via ordinary message-sending), 6 new Medium findings (client-embedded storage/TURN credentials, an automatic recipient-side link-preview IP leak, a broken/misdirected notification-reply path, a weaker-than-advertised "Wipe & Exit," a plaintext-export cleanup promise the code doesn't keep, and a race condition in the duress-wipe's own audit log that can undercut its "plausible deniability" claim), and 1 new Low/cleanup item (a dead flag in `AppLockManager`). No weak crypto, hardcoded secrets in the repo itself, or SQL injection found in this round's files; the AES-256-GCM media encryption in `B2StorageHelper` and the SSRF hardening in `LinkPreviewFetcher` are both genuinely solid on their own terms — this round's findings are about authorization/validation gaps and doc-vs-code mismatches around otherwise-sound crypto, not about the crypto itself.

**Round 6:** Read `GroupChatActivity.java` in full. Found two new **Critical/High** findings specific to the group-chat trust model (27, 28) — see header note above for the full summary.

**Round 7:** Read `CreateGroupActivity.java`, `SeedPhraseDisplayActivity.java`, `ContactDetailActivity.java` in full, plus re-examined `RestoreFromSeedActivity.java`'s identity-write path and the `identities/{userId}` Firestore rule together. Found one new **Critical** finding (29): the `identities` write rule checks the payload's `uid` field against the caller but never checks the document path itself, so any authenticated account can overwrite any other, already-established user's DS-ID mapping at any time — a strictly worse, non-race version of Finding 2. `ContactDetailActivity.java` was clean.

**Current end-to-end priority order for open findings:** this list was last fully re-derived in Round 3 and was never updated for Findings 18–26 (Rounds 4–5); Round 6 prepended 27/28 without redoing the rest, and this round adds 29 to the front of that same short prepended list rather than attempting the full re-derivation again — that full pass across all 29 findings remains owed and is deferred to Round 8. Everything from #1 onward below is still the unmodified Round 3 list.
-1. **Finding 29** (any-time identity-mapping hijack via the unbound `identities/{userId}` write rule, new this round) — ranks above Findings 27/28: it requires nothing but an ordinary DuoShield account and the victim's already-low-friction-to-obtain DS-ID (Finding 2/8), works against an account that's been live for months (not just a narrow onboarding window), and is reachable with a bare Firestore SDK call, no reverse-engineering of app internals needed at all.
0. **Finding 27** (group key substitution via unauthenticated `senderUid` + over-permissioned `keys/{memberUid}` write rule, new this round) — ranks above the below list entirely: it fully defeats Signal Protocol confidentiality for a targeted victim using only ordinary group membership, no reverse-engineering, no propagation-window timing, and no pre-existing relationship with the victim at all.
0b. **Finding 28** (group message sender spoofing / encryption bypass via unauthenticated `sender`/`isEncrypted` fields, new this round) — ranks with Finding 10: both are trivially reachable through ordinary in-app usage by anyone with baseline access (a message send, or in this case existing group membership), with no reverse-engineering required.
1. **Finding 1** (release keystore in repo, not gitignored) — unchanged from Round 1: fix immediately, independent of any code change; check git history for prior exposure.
2. **Finding 10** (unauthenticated B2 delete via crafted message `path`, new this round) — jumps straight to #2: exploitable through completely ordinary in-app usage (no reverse-engineering needed, unlike Finding 11), with an app-wide blast radius against every user's media, not just the sender's own.
3. **Finding 7** (duress panic-sync deadline: documented 5 s, actual 10 s) — drops one slot but still urgent for the same reason as before.
4. **Finding 16** (duress-wipe audit-log race, new this round) — grouped with Finding 7 since both concern whether the same duress-wipe code path actually delivers the guarantees its own comments and Javadoc claim.
5. **Finding 2** (identity front-running window) — unchanged rank: architectural, worth fixing before any wider rollout beyond trusted pairs.
6. **Finding 6** (no contact-consent gate for calls/chats) — unchanged rank: same root cause as Finding 2.
7. **Finding 12** (automatic link-preview IP/timing leak, new this round) — ranked with Finding 6 since it's compounded by the same missing consent gate and is a privacy-hardening item for the same at-risk audience.
8. **Finding 11** (B2/TURN static credentials embedded in every APK, new this round) — real architectural exposure and worth fixing, but requires reverse-engineering skill to exploit directly, and its most concrete consequence (arbitrary delete) is already covered more urgently by Finding 10.
9. **Finding 5** (contact/group metadata stored in plaintext in backups) — unchanged rank.
10. **Finding 14** (weaker-than-advertised "Wipe & Exit," new this round) — grouped with Finding 5 since exploiting the leftover Firebase session mainly matters because it can be used to pull that same plaintext cloud backup.
11. **Finding 8** (no relay-only calling option, IP exposed to call partner) — unchanged rank.
12. **Finding 13** (notification-reply resolves the wrong/no partner, new this round) — mostly a broken-feature/reliability bug with a contained blast radius (no third-party data exposure); worth fixing for correctness rather than urgency.
13. **Finding 15** (plaintext chat-export PDF outlives its "removed after sharing" promise, new this round) — real but narrow: it only fires when a user deliberately chooses to export a chat in the first place, which is itself already a conscious plaintext-exposure decision.
14. **Finding 3** (group key rotation) — unchanged rank: still not exploitable (no removal feature exists); fix *before* shipping member removal.
15. **Finding 9** (`CallCleanupWorker` dead code) — unchanged rank: no confidentiality impact, just needs to move server-side.
16. **Finding 17** (`AppLockManager` dead `coldStart` flag, new this round) — joins Finding 9 as routine cleanup with no functional impact.
17. **Finding 4** (deprecated `MasterKeys` API) — unchanged rank: routine maintenance, no urgency.

**What's left for next round:** see §5.

**Round 6:** Read `GroupChatActivity.java` in full (the top remaining priority per Round 5's own note). Found two new **Critical/High** findings specific to the group-chat trust model: an over-permissioned Firestore write rule on `groups/{groupId}/keys/{memberUid}` combined with an unchecked `senderUid` field lets any current group member plant a substitute group key in a *specific other member's* slot the first time that member fetches it — a targeted, working key-substitution attack against Signal Protocol confidentiality that requires no prior relationship with the victim at all, only shared group membership (Finding 27, Critical). Separately, the `groups/{groupId}/messages/{msgId}` rule and the client's unconditional trust in the message doc's `sender`/`isEncrypted` fields let any member forge a message that displays as sent by any other member, or bypass encryption entirely (Finding 28, High). Both stem from the same root cause: group subcollections carry client-asserted "who is this from" fields that, unlike 1:1 chat's real per-pair Signal sessions, are never bound to `request.auth.uid` or to the group's actual creator — this is the group-messaging equivalent of the identity-substitution problem Findings 2/19 already documented elsewhere in the app.

---

## 4. Ground rules reminder (for next round)
- Re-attach this `.md` file alongside your next zip. I'll edit it in place, renumber nothing, and correct anything this round's read contradicts.
- Say "continue" and I'll pick up from the "not yet reviewed" list in §1 rather than re-reading what's done.

---

## 5. Coverage note

**Read in full across Rounds 1–3:** every file listed with a ✅/⚠️/🔴 verdict in §1 — all were read completely, not pattern-screened, because they touch cryptography, key storage, authentication, network requests, file/media storage, or access-control rules. This now includes the full `crypto/`, `crypto/signal/`, `security/`, `auth/`, `call/`, `backup/`, and `notifications/` packages, the manifest, network security config, Firestore rules, push server, Cloud Functions stub, and the highest-risk (network- or file-I/O-touching) files in `util/`.

**Also read in full, Rounds 4–6:** `ChatMediaActivity.java`, `BaseActivity.java`, `KeyFingerprintActivity.java`, `GroupChatActivity.java` (Round 6), plus re-examinations of `SignalCipherHelper.java`, `DuoShieldSignalStore.java`, `firestore.rules`, `GroupCipherHelper.java`, and a batch of small `util/` helpers (`ForwardMessageHelper`, `MediaPickerHelper`, `EditMessageHelper`, `MessageBuilder`, `ClipboardHelper`, `ConversationMetaUpdater`, `DateHeaderHelper`, `DeliveryReceiptHelper`, `HapticHelper`, `KeyboardHelper`, `LastSeenFormatter`, `LinkPreviewHelper`, `MediaSizeEstimator`, `MessageStatusHelper`, `MuteHelper`, `TempFileCleaner`).

**Also read in full, Round 7:** `ui/CreateGroupActivity.java`, `ui/SeedPhraseDisplayActivity.java`, `ui/ContactDetailActivity.java`, plus a targeted re-read of `ui/RestoreFromSeedActivity.java`'s identity-record step (lines 200–231, not the full file — the rest of its ~557 lines, e.g. the mnemonic-verification and Signal-key-restore steps, is still outstanding) and `firestore.rules`' `identities` block.

**Not reviewed at all yet** (full list, so nothing is silently skipped):
- All of `app/src/main/java/com/duoshield/app/ui/*` except the `AddContactActivity` deep-link handler, the `bindLinkPreview()`/media-rendering slice of `MessageAdapter.java` read for Finding 12, and — new this round — `CreateGroupActivity.java`, `SeedPhraseDisplayActivity.java`, and `ContactDetailActivity.java` (all now read in full and excluded here). **~11 files remain**, still the single largest remaining gap: `ConversationAdapter.java`, `MessageAdapter.java` (beyond the Finding-12 slice), `RestoreFromSeedActivity.java` (beyond the identity-record step read this round), `SessionLogActivity.java`, `SettingsActivity.java` (beyond the screenshot-toggle section read for Finding 20), `StorageDiagnosticsActivity.java`, `SwipeToDeleteCallback.java`, `SwipeToReplyCallback.java`, `TypingDotsView.java`, `WaveformView.java`.
- **12 remaining root-package files** directly under `app/src/main/java/com/duoshield/app/` (not in any subpackage): `ConversationListActivity`, `DisplayNameActivity`, `DuoShieldApp`, `FullScreenImageActivity`, `LockScreenActivity`, `MainActivity`, `MediaSendPreviewActivity`, `MediaViewerActivity`, `MessageSearchActivity`, `SearchResultsAdapter`, `SignInActivity`, `SplashActivity`. (`MainActivity.java` line 41 and `LockScreenActivity.java` line 61 have been spot-checked, not fully read, as evidence for Finding 20; `MediaViewerActivity.java` line 81 similarly spot-checked for Finding 25.) This bullet was itself missing from this list prior to Round 6 — the header's Round 4 correction note flagged the gap but the fix wasn't carried through to this exact list until now; `ChatMediaActivity`, `BaseActivity`, `KeyFingerprintActivity`, and `GroupChatActivity` have since been read in full and are excluded here.
- **19 remaining files** in `app/src/main/java/com/duoshield/app/util/*` (reconciled against what Rounds 4–5 actually read — `ClipboardHelper`, `ConversationMetaUpdater`, `DateHeaderHelper`, `DeliveryReceiptHelper`, `EditMessageHelper`, `ForwardMessageHelper`, `HapticHelper`, `KeyboardHelper`, `LastSeenFormatter`, `LinkPreviewHelper`, `MediaPickerHelper`, `MediaSizeEstimator`, `MessageStatusHelper`, `MuteHelper`, and `TempFileCleaner` have all since been read in full and are excluded here; `NetworkStateHelper` had dropped off the running "not yet reviewed" notes between Round 3 and Round 4 without ever being read and is restored to this list): `FirebaseCostGuard`, `FirebaseQuotaSummary`, `NetworkStateHelper`, `OnlinePresenceHelper`, `PinMessageHelper`, `PresenceThrottle`, `ReactMessageHelper`, `ReadReceiptHelper`, `SearchHelper`, `SelfDestructScheduler`, `ShakeDetector`, `StorageCleanupWorker`, `TextStyleHelper`, `TimeFormatter`, `TypingThrottle`, `UiModeHelper`, `UnreadCountHelper`, `VoiceMessagePlayer` (full), `VoiceRecorderHelper` (beyond the Round 5 spot-check).
- `db/` DAOs and the full migration history beyond the key-setup path already read in `AppDatabase.java`. `SelfDestructWorker.java` was read only for its `commitBatchDelete()`/B2-deletion path (Finding 10) — its Firestore-query construction and TTL logic haven't been checked the way `CallCleanupWorker`'s was in Round 2, and given Finding 9's precedent it's worth the same rules-compatibility check next round.
- Root-level `app/build.gradle` beyond the signing block and the `buildConfigField` lines read for Finding 11 (dependency versions, ProGuard config `proguard-rules.pro`, `lint.xml`).
- `firestore-tests/rules.test.js`, `firestore.indexes.json`, `firebase.json`, `render.yaml` — out of primary scope per the review template (test/CI/deploy config) but worth a quick pass to cross-check they match the reviewed `firestore.rules`, especially now that Finding 6 questions whether the `calls`/`chats` create rules are actually the intended behavior.
- `app/src/test/java/com/duoshield/app/SeedPhraseHelperTest.java` — explicitly out of scope (test file).
- `attached_assets/`, `HANDOFF.md`, `DESIGN_SYSTEM.md`, `replit.md` — explicitly out of scope (docs/notes, not shipped app code).

**What would be needed to close remaining gaps beyond another reading pass:**
- An actual Gradle build, to verify there are no undeclared-identifier / compile errors (all files read so far compiled cleanly by inspection but were not verified against a real compiler).
- A CVE/dependency scan of `app/build.gradle` and `functions/package.json` / `package-lock.json` dependency versions (not attempted so far).
- Dynamic testing of the live push server (`duofat.onrender.com`) and Firestore rules (e.g. via the Firebase Rules Playground or the existing `firestore-tests/rules.test.js` suite) to confirm the rules behave as written under real requests — this would also directly confirm/deny Finding 9's "query always fails" claim, which is currently reasoned from the rules text rather than observed against a live project.
- Actually timing `DuressManager.performLogout()` on a real device under a no-connectivity condition would confirm or rule out whether Finding 16's race ever resolves the "bad" way in practice — this review reasoned about it statically from the executor/threading code rather than observing it run.
- Checking the actual Backblaze B2 application-key scope (bucket-wide vs. prefix-restricted, and exactly which S3 operations it's permitted) from the B2 dashboard directly — Finding 10/11's severity assumes at least `PutObject`+`DeleteObject` scope, which the client code's own use of `deleteFile()` confirms is at least available to the app, but the *ceiling* of what the key can do (e.g. whether `ListObjects` is also granted) can only be confirmed from the B2 account itself, not from the client repo.
- Confirmation from you on whether `app/duoshield-release.keystore` has ever been committed to git history (I can't check this myself from a zip export — only `git log` against the real repo would show it).
