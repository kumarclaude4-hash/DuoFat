<img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" alt="Android"> <img src="https://img.shields.io/badge/Min%20SDK-26-blue" alt="Min SDK 26"> <img src="https://img.shields.io/badge/Target%20SDK-35-blue" alt="Target SDK 35"> <img src="https://img.shields.io/badge/Version-1.3-purple" alt="Version 1.3"> <img src="https://img.shields.io/badge/License-Proprietary-red" alt="Proprietary">

# DuoShield

A **privacy-first, end-to-end encrypted** Android messaging app built on the Signal Protocol. Every message, voice note, and media file is encrypted on-device before transmission — the server never sees plaintext.

---

## Features

- **Signal Protocol E2EE** — Double Ratchet with X3DH key agreement (via `libsignal-android 0.54.1`)
- **Group chats** — AES-256-GCM shared group key, Signal-encrypted key distribution per member
- **Voice messages** — AES-256-GCM encrypted, streamed from Backblaze B2 with waveform scrubbing
- **Media sharing** — photos and videos encrypted before upload, decrypted client-side
- **Disappearing messages** — per-conversation timer, enforced via scheduled Cloud Function sweep
- **Duress PIN** — secondary PIN triggers silent wipe of all local data and keys
- **App lock** — biometric / PIN lock with configurable auto-lock timeout
- **Self-destruct** — remote wipe of messages, media, and Firestore data via scheduled sweep
- **Safety numbers** — Signal-style identity verification with change banner
- **Delivery & read receipts** — FCM-backed, with race-condition guard against downgrading status
- **Swipe-to-archive** — conversation archiving with undo Snackbar
- **Message reactions** — emoji quick-reactions with MODIFIED-event sync
- **Forward, edit, star, pin** — full message action sheet
- **Search** — real-time conversation and message search with executor debounce
- **QR code contact add** — ZXing gallery scan, deep-link, clipboard paste
- **Backup & restore** — PBKDF2-protected encrypted backup, seed-phrase account restore

---

## Architecture

```
app/
├── crypto/
│   ├── signal/          # SignalCipherHelper, SignalKeyManager, SignalPreKeyRefresher
│   ├── GroupCipherHelper.java
│   └── BackupCryptoHelper.java
├── db/
│   └── AppDatabase.java # Room v12, SQLCipher
├── models/              # Contact, Group, GroupMember, Message, Conversation
├── ui/                  # AddContactActivity, CreateGroupActivity, SettingsActivity, …
├── util/
│   ├── FirebaseCostGuard.java   # singleton — all Firestore reads/writes go through here
│   ├── B2StorageHelper.java     # SigV4 PUT/DELETE/GET, AES-256-GCM, in-flight dedup
│   ├── AppLockManager.java
│   └── NotificationStyler.java
├── BaseActivity.java    # all sensitive screens extend this
├── ChatMediaActivity.java
├── GroupChatActivity.java
├── ConversationListActivity.java
└── MainActivity.java
```

Full architecture notes → [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java (Android) |
| Encryption | libsignal-android 0.54.1, AES-256-GCM |
| Database | Room 2.6 + SQLCipher 4.5 |
| Cloud | Firebase Firestore + Firebase Auth |
| Media storage | Backblaze B2 (SigV4 signed requests) |
| Push | Firebase Cloud Messaging + OneSignal 5.x |
| Image loading | Glide 4.16 |
| QR codes | ZXing |
| Build | Gradle 8, JDK 17 |
| CI/CD | GitHub Actions |

---

## Building

### Prerequisites

- JDK 17
- Android SDK (API 35, build-tools 35.0.0)
- A `google-services.json` from your Firebase project (see template at `app/google-services.json.template`)

### Local build

```bash
# Copy and fill in the config files
cp app/google-services.json.template app/google-services.json
# edit app/google-services.json with your Firebase project values

# local.properties (SDK path + secrets)
echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "b2.key.id=YOUR_KEY"   >> local.properties
echo "b2.application.key=YOUR_SECRET" >> local.properties
echo "b2.bucket=your-bucket" >> local.properties
echo "b2.region=eu-central-003" >> local.properties
echo "push.server.url=https://your-push-server.com" >> local.properties

# Debug APK
./gradlew :app:assembleDebug

# Release APK (requires signing config in local.properties or env vars)
./gradlew :app:assembleRelease
```

### CI / GitHub Actions

| Workflow | Trigger | Output |
|---|---|---|
| `ci.yml` | Push to `main`, PRs | Lint report + debug APK |
| `release.yml` | Manual (`workflow_dispatch`) | Signed release APKs + GitHub Release |
| `firestore.yml` | Changes to `firestore.rules` | Firestore security rules test |

**Required repository secrets:**

| Secret | Description |
|---|---|
| `GOOGLE_SERVICES_JSON` | Full contents of `google-services.json` |
| `KEYSTORE_BASE64` | Release keystore, base64-encoded |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias (`duoshield`) |
| `KEY_PASSWORD` | Key password |
| `B2_KEY_ID` | Backblaze B2 key ID |
| `B2_APPLICATION_KEY` | Backblaze B2 application key |
| `B2_BUCKET` | B2 bucket name |
| `B2_REGION` | B2 region (e.g. `eu-central-003`) |
| `PUSH_SERVER_URL` | Base URL of the FCM push server |

---

## Push notification server

The FCM dispatch server lives in [`server/`](server/). It is a Node.js service deployed on Render.

```bash
cd server
npm install
node index.js
```

Set `GOOGLE_APPLICATION_CREDENTIALS_JSON` (Firebase service-account JSON) in the hosting environment.

---

## Firestore rules & indexes

```bash
# Deploy rules
firebase deploy --only firestore:rules

# Deploy indexes
firebase deploy --only firestore:indexes

# Run rules unit tests locally
cd firestore-tests
npm install
firebase emulators:exec --only firestore "npm test"
```

---

## Security

Vulnerability disclosures → see [`SECURITY.md`](SECURITY.md).

Key design decisions:

- All encryption happens **on-device** before any network call
- The server is a stateless FCM relay — it never touches message content
- SQLCipher database key derived from UID via HKDF-SHA256
- Room DB schema migrations are enforced (no destructive fallback)
- `FirebaseCostGuard` singleton prevents runaway Firestore listeners
- `AppLockManager` ref-count lifecycle prevents lock-bypass on rapid resume

---

## Changelog

See [`CHANGELOG.md`](CHANGELOG.md).

---

## License

Proprietary — all rights reserved. No part of this codebase may be reproduced, distributed, or used without explicit written permission.
