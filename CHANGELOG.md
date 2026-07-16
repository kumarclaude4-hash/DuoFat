# Changelog

All notable changes to DuoShield are documented here.  
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [1.3.0] — 2026-07-14

### Added
- Swipe-to-archive conversations with undo Snackbar
- Animated typing indicator in conversation list rows
- Haptic feedback on message send
- Waveform scrubbing for voice messages (swipe-to-seek)
- Voice message waveform renders in lavender palette (`#9A81FF` played / `#3A3548` unplayed)
- Avatar tap opens full-screen image viewer (`FullScreenImageActivity`)
- Profile photo cached locally to avoid redundant B2 round-trips
- B2 media request deduplication — N callers waiting on the same path share a single network request
- Search executor reuse with Future cancellation to debounce rapid typing
- Bubble fade-in animation cached (one XML parse per session, not per message)
- Voice note temp-file cleanup on new playback and in `onDestroy`

### Fixed
- Black screen when viewing encrypted `b2:` avatar paths in full-screen viewer
- Waveform poll interval 200 ms → 50 ms (eliminates visible stutter)
- Missing `DiffUtil` import in `CreateGroupActivity`

### Changed
- UI accent colour promoted to vivid lavender `#9A81FF`; backgrounds shifted to blue-tinted darks (`#191620`, `#24202E`)
- Signed release build workflow consolidated and simplified

---

## [1.2.0] — 2026-07-12

### Added
- Per-conversation disappearing messages (timer button in chat header)
- Message action sheet (reactions, forward, edit, star, pin, delete locally, delete for everyone)
- Safety number verification banner on identity key change
- Delivery and read receipts via FCM data messages
- Conversation archiving (hidden from list, retrievable)
- Self-destruct sweep via scheduled Cloud Function (purges Firestore + B2 media)
- Settings split into four sub-screens (Account, Privacy, Notifications, Appearance)
- Duress PIN two-state UI with guard preventing accidental clear before duress PIN is removed

### Fixed
- Cold-start auto sign-out bypass via force-quit (removed `coldStart` guard from `shouldAutoSignOut`)
- BaseActivity sign-out path no longer calls `onAppForegrounded()` on Firebase-null state
- Send button double-tap guard (`setEnabled(false)` at send start, re-enabled in all three outcome paths)
- Background-thread `Toast` calls in helpers replaced with `Handler(Looper.getMainLooper()).post()`
- Contact backup not called on restore — fixed by wiring `restoreIfNeeded()` in `ConversationListActivity.onCreate()`

### Changed
- `SupabaseStorageHelper` fully retired; all media routed through `B2StorageHelper`
- `VoiceNoteHelper.uploadVoiceNote()` now throws `UnsupportedOperationException` to surface accidental misuse

---

## [1.1.0] — 2026-07-10

### Added
- Group chat — AES-256-GCM shared group key distributed via Signal encryption
- `CreateGroupActivity`, `GroupChatActivity` with DiffUtil-backed adapters
- Room DB v12 — `contacts`, `groups`, `group_members` tables
- `AddContactActivity` with QR gallery scan, deep-link (`duoshield://add/`), clipboard paste, and share sheet
- Backblaze B2 storage — SigV4 signed PUT / DELETE / GET with AES-256-GCM encryption
- PBKDF2-protected encrypted account backup with seed-phrase restore
- Scheduled pre-key rotation via `SignalPreKeyRefresher` (threshold 10, batch 25)
- Daily signed pre-key rotation via WorkManager with grace-period fallback

### Fixed
- Signal pre-key IDs tracked via `signal_prekey_next_id` in `SecurePrefs` to prevent reuse
- `registerIdentity()` called inside `onSuccess` lambda to guarantee UID is available
- `SplashActivity` uses `addAuthStateListener` instead of `getCurrentUser()` to fix cold-start false logout

### Changed
- All encryption migrated from `CryptoHelper`/`ECDH` to `SignalCipherHelper` (libsignal-android 0.54.1)
- `sigType` column added to `messages` table (0 = legacy, 1 = WHISPER, 3 = PREKEY)
- `MODIFIED` Firestore event handler decrypts edited partner messages and persists to Room

---

## [1.0.0] — 2026-07-08

### Added
- Initial release — one-to-one Signal Protocol encrypted messaging
- Custom-token Firebase Auth with seed-phrase-derived UID (HKDF-SHA256)
- SQLCipher-encrypted Room database
- FCM push notifications via Node.js relay server
- OneSignal 5.x coexistence with `FCMBroadcastReceiver`
- App lock with biometric / PIN and configurable auto-lock timeout
- `FirebaseCostGuard` singleton — rate-limits and deduplicates all Firestore listeners
- Firestore security rules with emulator-based test suite
- Signed release APK CI pipeline with ABI splits (arm64-v8a / armeabi-v7a)
