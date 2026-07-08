# DuoShield

End-to-end encrypted, 1-to-1 secure messaging app for Android. Two users pair with a 6-digit code; all messages are ECDH + AES-256 encrypted before leaving the device. Built in Java, targeting Android 8+ (minSdk 26).

> **Native Android app** — cannot be previewed in a browser. Build with Android Studio or `./gradlew assembleDebug`.

---

## How to Build

### Prerequisites
- Android Studio Hedgehog (2023.1) or newer
- Java 8+ JDK
- `app/google-services.json` from Firebase console → Project `duoshield-8caf1`
- `app/src/main/assets/service-account.json` (FCM HTTP v1 — download from Firebase → Project Settings → Service accounts → Generate new private key)

### Build
```bash
cd DuoShield
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # requires signing config in build.gradle
./gradlew lint
```

### Install
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Stack

| Layer | Technology |
|---|---|
| Language | Java 8 |
| Min SDK / Target | 26 / 34 (Android 8.0 – 14) |
| Build | Gradle 8 + `com.google.gms.google-services` plugin |
| Auth + Cloud DB | Firebase Auth · Firestore · Storage · Messaging (FCM) |
| Local DB | Room 2.6 (schema **v7**) |
| Crypto | AndroidKeyStore · ECDH (`ECDHHelper`) · AES-256-GCM (`CryptoHelper`) · HKDF |
| UI | AppCompat · ConstraintLayout · RecyclerView · Material 1.11 · Glide 4.16 |
| Background | WorkManager 2.9 |
| Biometric | AndroidX Biometric 1.2.0-alpha05 |
| HTTP | `HttpURLConnection` (native, no OkHttp) |

---

## App Flow

```
MainActivity
 └─ (not paired) ─→ PairingActivity  ─→ ChatMediaActivity
 └─ (paired)     ─→ ConversationListActivity ─→ ChatMediaActivity
```

**Pairing (6-digit code)**
1. User A taps "Generate Code" → `PairingManager` writes a Firestore room doc with the code + ECDH public key.
2. User B enters the code → joins the room, writes their ECDH public key.
3. Both devices derive a shared AES-256 key via ECDH + HKDF and store it in SharedPreferences.
4. `onPaired()` → `goToChat()` → `ChatMediaActivity`.

---

## SharedPreferences Reference

All keys live in `"duoshield_prefs"` (`MODE_PRIVATE`).

| Key | Type | Meaning |
|---|---|---|
| `is_paired` | boolean | Pairing complete |
| `conversation_id` | String | Firestore conversation doc ID |
| `my_uid` | String | This device's Firebase Auth UID |
| `partner_uid` | String | Partner's Firebase Auth UID |
| `ecdh_shared_key` | String (Base64) | AES-256 key from ECDH derivation |
| `biometric_enabled` | boolean | User opted into fingerprint/face unlock |
| `app_lock_bg_ts` | long | Timestamp when app was last backgrounded |

---

## Where Things Live

| What | Path |
|---|---|
| Pairing handshake | `pairing/PairingManager.java` |
| ECDH key exchange | `crypto/ECDHHelper.java` · `crypto/CryptoInitializer.java` |
| AES encrypt/decrypt | `crypto/CryptoHelper.java` · `crypto/KeyManager.java` |
| Room DB schema (v7) | `db/AppDatabase.java` · `db/MessageDao.java` |
| Message model | `models/Message.java` |
| Conversation model | `models/Conversation.java` |
| Chat screen | `ChatMediaActivity.java` |
| Conversation list | `ConversationListActivity.java` |
| Message adapter | `ui/MessageAdapter.java` |
| Conversation adapter | `ui/ConversationAdapter.java` |
| Pairing screen | `ui/PairingActivity.java` |
| Settings screen | `ui/SettingsActivity.java` |
| Lock screen | `LockScreenActivity.java` |
| App lock logic | `util/AppLockManager.java` |
| Biometric prompt | `security/BiometricHelper.java` |
| Duress PIN | `security/DuressManager.java` |
| Wipe helper | `util/WipeHelper.java` |
| Voice message player | `util/VoiceMessagePlayer.java` |
| Link URL detector | `util/LinkPreviewHelper.java` |
| Link preview fetcher | `util/LinkPreviewFetcher.java` |
| FCM notifications | `notifications/NotificationHelper.java` |
| FCM HTTP v1 send | `ChatMediaActivity.sendFcmNotification()` |
| Layouts | `res/layout/` |
| Drawables / theme | `res/drawable/` · `res/values/colors.xml` |

---

## Features Implemented

- **Pairing** — 6-digit room code, 10-min expiry, ECDH key exchange on both sides
- **End-to-end encryption** — ECDH-derived AES-256-GCM; AndroidKeyStore AES fallback
- **Chat UI** — Dark theme; custom toolbar with partner avatar, online dot, lock icon; date separators; per-message timestamps; delivery ticks; bubbles (mine right / theirs left)
- **Message types** — Text · Image · Video · Voice note (waveform) · Contact card
- **Link previews** — Auto-detects URLs; fetches OG title + image in background (`LinkPreviewFetcher`); tappable card inside bubble; in-memory cache
- **Reactions** — Emoji badge below bubble
- **Reply / quote** — Preview strip inside bubble with original text
- **Pinned messages** — Pin banner + cycling scroll
- **Voice notes** — Record, upload to Firebase Storage, in-list playback with waveform
- **Media** — Photo/video pick, upload to Firebase Storage
- **Contact card share** — Share UID as structured card
- **Message actions** — Long-press: copy · delete · react · pin · edit · reply · forward
- **Typing indicator** — Real-time via Firestore field `typing_<uid>`
- **Online / last seen** — Updated on `onResume` / `onPause`
- **Push notifications** — FCM HTTP v1 (`service-account.json` in assets)
- **App lock** — 3-minute background timeout → redirects to `LockScreenActivity` (handled in `BaseActivity.onStart()`)
- **Auto sign-out** — Configurable inactivity timer (1 h / 8 h / 24 h / 7 days); kills Firebase session entirely when triggered; checked before PIN lock in `BaseActivity`
- **Biometric** — Optional fingerprint/face; falls back gracefully to PIN
- **App PIN** — 4-digit in-app PIN
- **Duress PIN** — Silent auto-logout (Firebase signOut) on duress code; section hidden behind a toggle that only appears when an app PIN is set
- **Self-destruct timer** — WorkManager job for scheduled deletion
- **Wipe & Exit** — Clears Room DB, SharedPreferences, Firebase tokens, exits
- **Key fingerprint** — Out-of-band ECDH public key verification
- **Session log** — Local-only Room log of every sign-in, auto sign-out, and duress logout; viewable from Settings → Security Log; never transmitted
- **Fake Chats** — Decoy screen for plausible deniability
- **Message search** — Full-text search over local Room DB
- **Firebase Cloud Functions** — In `functions/` (TypeScript); scheduled cleanup

---

## Room DB

Current schema version: **8**.

Any column addition requires:
1. Increment `version` in `AppDatabase.java` → 9
2. Write `MIGRATION_8_9` and add it to `addMigrations()`
3. **Never use `fallbackToDestructiveMigration()`** on a shipped build

---

## Firebase Project

- Project ID: `duoshield-8caf1`
- Firestore collections: `rooms` (pairing), `conversations`, `users`
- Storage bucket: `duoshield-8caf1.appspot.com`
- `google-services.json` → `app/` (not in git)
- `service-account.json` → `app/src/main/assets/` (not in git — required for FCM sends)

---

## Architecture Decisions

- **No biometric prompt in ChatMediaActivity.onCreate()** — The earlier design called `BiometricHelper.authenticate()` there and `finish()`-ed on failure, causing a silent Activity death on devices without enrolled biometrics. Lock is now handled exclusively by `BaseActivity.onResume()` → `AppLockManager.shouldLock()`.
- **Firestore listener in onStart/onStop only** — `ConversationListActivity` attaches its listener in `onStart()` (null-guarded) and detaches in `onStop()`. Calling `listenForConversation()` in both `onCreate()` and `onStart()` leaks a second registration.
- **Link preview on main thread via callback** — `LinkPreviewFetcher` uses a fixed 3-thread pool; the callback is posted to the main `Handler`. A `setTag(msg.getId())` guard prevents stale results from polluting recycled ViewHolders.
- **Signal Protocol encryption** — All messages encrypted with `SignalCipherHelper` (X3DH key agreement + Double Ratchet). Session is established on first `PreKeySignalMessage`; `keyPending` flag prevents `listenForMessages()` from starting before the session exists.
- **1-to-1 only** — The current Firestore schema and UI are designed for exactly two participants per conversation. Group chat would require a schema redesign.

---

## Gotchas

1. `ChatMediaActivity` **must not** re-add a `BiometricHelper` call to `onCreate()` — it silently kills the Activity on unenrolled devices.
2. Always null-guard the Firestore listener before calling `listenForConversation()` in `onStart()`.
3. `service-account.json` is required in `app/src/main/assets/` for FCM sends; without it every message send logs an IOException.
4. Link previews require the `INTERNET` permission (already in `AndroidManifest.xml`). HTTPS URLs work out of the box. For plain HTTP preview URLs, add a `<domain-config>` entry to `res/xml/network_security_config.xml`.
5. Room migrations must be explicit — never destructive on shipped builds.
6. `google-services.json` must match `applicationId "com.duoshield.app"` exactly or Firebase init crashes at launch.

---

## Outstanding / TODO

- [x] Image/video **encryption before upload** — AES-256-GCM per-file key; `mediaKey` stored in Firestore doc and `messages.mediaKey` Room column (v11). See HANDOFF §5.
- [x] Disappearing messages **UI toggle** — timer button in chat header + Settings picker; both write `disappear_ms`; `SelfDestructWorker` drives on `expiresAt`. See HANDOFF §4.
- [x] Message **forwarding** — `ForwardMessageHelper.forward()` wired in the message action sheet. See HANDOFF §5.
- [x] **Unread badge** on launcher icon — `NotificationHelper` tracks `badge_count` in SharedPrefs; cleared in `ChatMediaActivity.clearBadge()` and `MarkReadReceiver`. See HANDOFF §5.
- [x] Play Store hardening — `shrinkResources true`, `debuggable false`, `versionCode 2` / `versionName "1.1"`.
- [x] `network_security_config.xml` — created; wired via `android:networkSecurityConfig` in manifest; add HTTP domains inside `<domain-config>` as needed.
- [ ] Group conversations — requires Firestore schema redesign (currently 1-to-1 only)

---

## User Preferences

- **Git push after every change**: After completing any code change, push to `origin/main` using the `GIT` secret (PAT). Commit author must be `kumarclaude4-hash <kumarclaude4@gmail.com>`. Use `gitPush({})` via the git-remote skill.
- **Push server**: Do not attempt to set up or fix the Push Server workflow — user does not require it on Replit.
